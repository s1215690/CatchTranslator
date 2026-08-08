"""YupiSaver for Windows 的核心功能。

這個檔案刻意不依賴 Android；Windows 版的設定、記錄、AI、語音、定時提醒、
主動安慰和推動力流程都在這裡，GUI 只負責顯示和把事件接進來。
"""

from __future__ import annotations

import asyncio
import base64
import ctypes
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import datetime as dt
import json
import os
import random
import re
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import webbrowser
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Optional

try:
    import requests
except ImportError:  # pragma: no cover - build script installs it
    requests = None


# In a PyInstaller onedir build, __file__ points into _internal.  Keep the
# user's settings beside the EXE so they remain portable and writable.
ROOT = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
DATA_DIR = ROOT / "data"
DATA_DIR.mkdir(parents=True, exist_ok=True)
SETTINGS_FILE = DATA_DIR / "settings.json"
TASKS_FILE = DATA_DIR / "timed_tasks.json"
DATABASE_FILE = DATA_DIR / "translator.db"
LOG_FILE = DATA_DIR / "yupisaver.log"


DEFAULT_SETTINGS: dict[str, Any] = {
    "api_key": "",
    "model": "deepseek-chat",
    "base_url": "https://api.deepseek.com",
    "debug_token": "",
    "debug_chat_id": "",
    "voice_engine": "edge-hk",
    "edge_voice": "hk-f",
    "edge_style": "friendly",
    "minimax_key": "",
    "minimax_voice": "Cantonese_CuteGirl",
    "minimax_model": "speech-2.8-hd",
    "minimax_emotion_mode": "auto",
    "minimax_designed_voices": [],
    "voice_rate": "0",
    "button_count": 4,
    "narration_enabled": False,
    "thinking_enabled": True,
    "summary_enabled": True,
    "active_comfort_enabled": False,
    "active_comfort_interval": 20,
    "active_comfort_launch_assistant": False,
    "hud_enabled": True,
    "head_up_enabled": False,
    "head_up_interval": 30,
    "head_up_jitter": 5,
    "head_up_display_seconds": 5,
    "head_up_font_scale": 18,
    "head_up_voice_enabled": False,
    "panel_size": "large",
    "floating_enabled": True,
    "bubble_geometry": "52x52+30+300",
    "nudge_geometry": "420x230+420+180",
    "last_daily_summary": "",
    "last_weekly_summary": "",
    "last_evening_reminder": "",
}


class SettingsStore:
    def __init__(self, path: Path = SETTINGS_FILE):
        self.path = path
        self._lock = threading.RLock()
        self._data: dict[str, Any] = dict(DEFAULT_SETTINGS)
        self.load()

    def load(self) -> dict[str, Any]:
        with self._lock:
            try:
                raw = json.loads(self.path.read_text(encoding="utf-8"))
                if isinstance(raw, dict):
                    self._data.update(raw)
            except (FileNotFoundError, json.JSONDecodeError, OSError):
                self.save()
            return dict(self._data)

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            return self._data.get(key, default)

    def all(self) -> dict[str, Any]:
        with self._lock:
            return dict(self._data)

    def update(self, values: dict[str, Any], save: bool = True) -> None:
        with self._lock:
            self._data.update(values)
            if save:
                self.save()

    def save(self) -> None:
        with self._lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self._data, ensure_ascii=False, indent=2), encoding="utf-8")
            tmp.replace(self.path)


class DebugLog:
    def __init__(self, path: Path = LOG_FILE):
        self.path = path
        self._lock = threading.RLock()
        self._entries: list[str] = []

    def add(self, tag: str, message: str) -> None:
        line = f"{dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')} [{tag}] {message}"
        with self._lock:
            self._entries.append(line)
            if len(self._entries) > 300:
                self._entries = self._entries[-300:]
            try:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                with self.path.open("a", encoding="utf-8") as f:
                    f.write(line + "\n")
            except OSError:
                pass

    def dump(self) -> str:
        with self._lock:
            if not self._entries and self.path.exists():
                try:
                    self._entries = self.path.read_text(encoding="utf-8").splitlines()[-300:]
                except OSError:
                    pass
            return "\n".join(self._entries) or "（未有 log——試下捕捉一次先）"

    def send_to_telegram(self, token: str, chat_id: str) -> None:
        if requests is None:
            raise RuntimeError("未安裝 requests")
        if not token.strip() or not chat_id.strip():
            raise ValueError("請先填 Telegram Bot Token 同 Chat ID")
        api = f"https://api.telegram.org/bot{token.strip()}/sendMessage"
        parts = [self.dump()[i:i + 3900] for i in range(0, len(self.dump()), 3900)]
        for part in parts or ["（未有 log）"]:
            response = requests.post(api, data={"chat_id": chat_id.strip(), "text": part}, timeout=20)
            if not response.ok:
                raise RuntimeError(f"Telegram HTTP {response.status_code}: {response.text[:240]}")


class TranslatorDb:
    def __init__(self, path: Path = DATABASE_FILE):
        import sqlite3

        self.path = path
        self._lock = threading.RLock()
        self._sqlite3 = sqlite3
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            conn.execute(
                "CREATE TABLE IF NOT EXISTS records ("
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, "
                "channel TEXT NOT NULL, text TEXT NOT NULL, source TEXT NOT NULL)"
            )
            conn.execute(
                "CREATE TABLE IF NOT EXISTS summaries (date TEXT PRIMARY KEY, content TEXT NOT NULL, ts INTEGER NOT NULL)"
            )

    @contextmanager
    def _connect(self):
        conn = self._sqlite3.connect(self.path, timeout=10)
        conn.row_factory = self._sqlite3.Row
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def insert(self, channel: str, text: str, source: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                "INSERT INTO records(ts, channel, text, source) VALUES (?, ?, ?, ?)",
                (int(time.time() * 1000), channel, text, source),
            )

    def _rows_to_strings(self, rows) -> list[str]:
        return [
            f"[{row['channel']}] {dt.datetime.fromtimestamp(row['ts'] / 1000).strftime('%m-%d %H:%M')} {row['text']}"
            for row in rows
        ]

    def recent(self, limit: int = 20) -> list[str]:
        with self._lock, self._connect() as conn:
            rows = conn.execute(
                "SELECT * FROM records ORDER BY ts DESC LIMIT ?", (max(1, int(limit)),)
            ).fetchall()
        return self._rows_to_strings(rows)

    def between(self, start_ms: int, end_ms: int) -> list[str]:
        with self._lock, self._connect() as conn:
            rows = conn.execute(
                "SELECT * FROM records WHERE ts >= ? AND ts < ? ORDER BY ts ASC",
                (start_ms, end_ms),
            ).fetchall()
        return self._rows_to_strings(rows)

    def dump(self) -> str:
        rows = self.recent(30)
        return "\n".join(rows) if rows else "（未有記錄）"

    def count_today(self) -> int:
        start = dt.datetime.combine(dt.date.today(), dt.time.min).timestamp() * 1000
        return self._count_since(int(start))

    def count_all(self) -> int:
        with self._lock, self._connect() as conn:
            return int(conn.execute("SELECT COUNT(*) FROM records").fetchone()[0])

    def _count_since(self, start_ms: int) -> int:
        with self._lock, self._connect() as conn:
            return int(conn.execute("SELECT COUNT(*) FROM records WHERE ts >= ?", (start_ms,)).fetchone()[0])

    def channel_counts(self, days: int = 7) -> dict[str, int]:
        start = (dt.datetime.now() - dt.timedelta(days=days)).timestamp() * 1000
        with self._lock, self._connect() as conn:
            rows = conn.execute(
                "SELECT channel, COUNT(*) AS n FROM records WHERE ts >= ? GROUP BY channel",
                (int(start),),
            ).fetchall()
        return {row["channel"]: int(row["n"]) for row in rows}

    def streak_days(self) -> int:
        with self._lock, self._connect() as conn:
            rows = conn.execute(
                "SELECT DISTINCT date(ts / 1000, 'unixepoch', 'localtime') AS d "
                "FROM records ORDER BY d DESC"
            ).fetchall()
        days = {row["d"] for row in rows}
        cursor = dt.date.today()
        streak = 0
        while cursor.isoformat() in days:
            streak += 1
            cursor -= dt.timedelta(days=1)
        return streak

    def insert_summary(self, date_key: str, content: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                "INSERT INTO summaries(date, content, ts) VALUES (?, ?, ?) "
                "ON CONFLICT(date) DO UPDATE SET content=excluded.content, ts=excluded.ts",
                (date_key, content, int(time.time() * 1000)),
            )

    def latest_summary(self) -> Optional[str]:
        with self._lock, self._connect() as conn:
            row = conn.execute("SELECT content FROM summaries ORDER BY ts DESC LIMIT 1").fetchone()
        return None if row is None else row["content"]


class DeepSeekClient:
    def __init__(self, settings: SettingsStore, log: DebugLog):
        self.settings = settings
        self.log = log

    def chat(self, system: str, user: str, max_tokens: int = 500, thinking: Optional[bool] = None) -> str:
        if requests is None:
            raise RuntimeError("未安裝 requests")
        key = str(self.settings.get("api_key", "")).strip()
        if not key:
            raise RuntimeError("未填 AI API Key")
        base = str(self.settings.get("base_url", "https://api.deepseek.com")).rstrip("/")
        url = base + "/chat/completions"
        if thinking is None:
            thinking = bool(self.settings.get("thinking_enabled", True))
        body: dict[str, Any] = {
            "model": str(self.settings.get("model", "deepseek-chat")),
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "max_tokens": int(max_tokens),
        }
        if thinking:
            body.update({"temperature": 1.1, "reasoning_effort": "low"})
        else:
            body["thinking"] = {"type": "disabled"}
        self.log.add("DS", f"POST {body['model']} | user={user[:120]} | max_tokens={max_tokens}")
        response = requests.post(
            url,
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            json=body,
            timeout=(10, 45),
        )
        if not response.ok:
            raise RuntimeError(f"API 錯誤 {response.status_code}: {response.text[:240]}")
        payload = response.json()
        content = payload.get("choices", [{}])[0].get("message", {}).get("content", "") or ""
        if not content.strip() and thinking:
            self.log.add("DS", "content 空，改用非思考模式重試")
            return self.chat(system, user, max_tokens, False)
        return str(content)


def extract_json(raw: str) -> str:
    text = (raw or "").strip()
    if text.startswith("```"):
        text = re.sub(r"```[a-zA-Z]*", "", text).replace("```", "").strip()
    start, end = text.find("{"), text.rfind("}")
    return text[start:end + 1] if start >= 0 and end > start else text


def extract_array(raw: str) -> str:
    text = (raw or "").strip()
    if text.startswith("```"):
        text = re.sub(r"```[a-zA-Z]*", "", text).replace("```", "").strip()
    start, end = text.find("["), text.rfind("]")
    return text[start:end + 1] if start >= 0 and end > start else text


class AIEngine:
    EMOTIONS = ["", "calm", "happy", "sad", "angry", "fearful", "disgusted", "surprised", "fluent"]
    TAGS = ["", "laughs", "chuckle", "sighs", "gasps", "breath", "emm"]

    CRITIC = [
        "呢句係翻譯官同你講嘅，唔係你自己同自己講。認得佢就夠喇。",
        "翻譯官又出嚟搶咪——佢把聲唔代表你，你唔使同佢辯。",
        "你聽緊嘅係翻譯官嘅舊錄音帶，唔係你嘅心聲。",
        "嗰句聽落好似好真，其實係翻譯官嘅老翻錄音。",
    ]
    GUARDIAN = [
        "呢個聲音想保護你，但佢嘅方案令你更難受。你認得佢就夠。",
        "佢話坐喺度就安全，但你唔需要跟住佢嘅方案行。",
        "溫柔嘅看守未必真係啱；你可以聽到佢，但唔需要交出方向盤。",
    ]
    STUCK = [
        "你而家只係冇電，唔係壞咗。唔使逼自己，陪住自己一陣就得。",
        "郁唔到就郁唔到，冇所謂。你肯講出嚟，已經係一步。",
        "而家唔想郁好正常。你唔係懶，你只係暫時卡住咗。",
    ]
    FEELING = [
        "呢個感覺係真嘅，唔使即刻處理，陪住佢一陣就得。",
        "心口實係身體嘅訊號——慢慢唞，唔需要急住解釋。",
        "有個感受喺度，已經係好重要嘅一步。",
    ]
    WORTH = [
        "唔係你唔重要，係翻譯官將所有被在乎嘅證據遮住咗。",
        "「值唔值得」係翻譯官設嘅陷阱，你唔需要接佢個辯題。",
        "佢問你配唔配，但你唔使答——條問題本身就唔公平。",
    ]
    OTHER = [
        "我聽到你講緊呢樣嘢。唔使急，慢慢講。",
        "呢句說話有重量，我陪你停一停。",
        "你肯講出嚟已經夠，我會認真聽住。",
        "呢樣嘢對你嚟講唔簡單，我聽住。",
    ]
    BUTTONS = [
        "佢又鬧我", "我動唔到", "心口好實", "坐喺度就安全", "佢話我唔配",
        "我唔想覆信息", "我對佢哋唔重要", "佢叫我唔好出聲", "啱啱諗起醜事",
        "我值唔值得", "身體好攰", "想郁但郁唔到",
    ]
    MORE_COMFORT = [
        "翻譯官把聲又細咗少少——你而家聽到嘅，開始係你自己。",
        "唔使一次過信晒佢，淨係知佢又講緊嘢，已經夠。",
        "你唔使即刻好返，可以慢慢唞——你喺度就係證據。",
        "嗰句嘢已經講完，而家唔使跟佢行，跟住呼吸就得。",
        "翻譯官講嘅係舊錄音，你而家嘅一刻係新嘅。",
    ]
    PROACTIVE = [
        "唔使急住變得更好，你而家已經值得被溫柔對待，我會安靜陪你一陣。",
        "呢一刻可能唔完美，但你仍然喺度照顧緊自己，呢件事已經好珍貴。",
        "如果今日好攰，就容許自己慢一點；你唔需要證明任何嘢先配得到休息。",
        "你可以暫時放低外面啲聲，留返一點空間畀自己，慢慢呼吸已經足夠。",
        "我唔會催你振作；你行到邊一步，就算邊一步，呢度一直有一盞燈留住。",
    ]
    GRATITUDE_PROMPTS = [
        "試下諗下：你而家擁有啲乜嘢？床？熱水？有個狗仔等你？",
        "唔使諗大嘅嘢——一杯水、一盞燈、一張被，已經係真實擁有。",
        "你已經得到咗啲乜嘢？可以係好細嘅嘢：啱啱嗰啖氣、個枕頭、一部電話。",
        "諗下邊樣嘢係你而家有、但以前冇嘅？",
    ]
    GRATITUDE_REPLIES = [
        "好，你講得出嚟，就係真嘅擁有。",
        "呢啲就係你嘅——記住佢哋喺度。",
        "你擁有嘅嘢唔使好大，係真嘅就夠。",
        "好，收好呢樣嘢——佢係你嘅。",
    ]

    def __init__(self, settings: SettingsStore, db: TranslatorDb, log: DebugLog):
        self.settings = settings
        self.db = db
        self.log = log
        self.client = DeepSeekClient(settings, log)
        self._last_tag_at = 0.0

    def records_context(self, n: int = 5) -> str:
        return "\n".join(self.db.recent(n)) or "（暫無記錄）"

    @classmethod
    def infer_emotion(cls, text: str) -> str:
        t = text or ""
        groups = [
            ("disgusted", ["討厭", "厭惡", "噁心", "反感", "離譜"]),
            ("fearful", ["驚", "害怕", "怕", "危險", "唔安全", "心口實", "緊張"]),
            ("sad", ["攰", "痛", "灰", "頹", "麻木", "冇力", "辛苦", "難受", "寂寞"]),
            ("angry", ["翻譯官", "破壞者", "搶咪", "搶走", "剝奪", "唔代表你", "夠喇", "唔准"]),
            ("happy", ["好嘢", "做到", "成功", "開心", "恭喜", "里程碑", "多謝", "值得", "正呀"]),
            ("surprised", ["吓", "竟然", "原來", "真㗎", "？", "?"]),
            ("calm", ["慢慢", "唔使心急", "陪住你", "唞下", "呼吸", "唔緊要", "安心"]),
        ]
        for emotion, words in groups:
            if any(word in t for word in words):
                return emotion
        return "fluent"

    @classmethod
    def safe_emotion(cls, emotion: str | None) -> str:
        value = (emotion or "").strip()
        return value if value in cls.EMOTIONS else ""

    @classmethod
    def emotion_for_content(cls, reply: str, emotion: str | None) -> str:
        e = cls.safe_emotion(emotion)
        if not e or not reply:
            return e
        words = {
            "sad": ["攰", "痛", "唉", "冇力", "唔想", "灰", "頹", "麻木", "辛苦", "難受", "寂寞"],
            "happy": ["！", "!", "開心", "好嘢", "正"],
            "angry": ["翻譯官", "破壞者", "搶咪", "搶走", "剝奪", "唔代表你", "夠喇", "唔准", "過分"],
            "fearful": ["驚", "怕", "危險", "唔安全", "心口實", "緊張", "縮"],
            "disgusted": ["討厭", "厭惡", "噁心", "反感", "離譜"],
            "surprised": ["？", "?", "吓", "竟然", "原來"],
        }
        if e in words and not any(word in reply for word in words[e]):
            return ""
        return e

    def resolve_emotion(self, text: str, ai_emotion: str | None, mode: str | None = None) -> str:
        selected = "auto" if mode is None else str(mode).strip()
        if selected != "auto":
            return self.safe_emotion(selected)
        return self.emotion_for_content(text, ai_emotion) or self.infer_emotion(text)

    def suggest_tag(self, reply: str, emotion: str = "") -> str:
        tag = ""
        if any(x in reply for x in ("吓?", "喂?", "真㗎?", "吓？", "喂？", "真㗎？")):
            tag = "gasps"
        elif "唉" in reply or "算啦" in reply:
            tag = "sighs"
        elif (emotion == "happy" and ("！" in reply or "!" in reply)) or "哈哈" in reply:
            tag = "laughs"
        elif "等我諗下" in reply or "等我唞" in reply:
            tag = "emm"
        if tag and random.randrange(100) >= 20:
            return ""
        return tag

    def throttle_tag(self, tag: str) -> str:
        if not tag:
            self._last_tag_at = 0
            return ""
        now = time.time()
        if now - self._last_tag_at < 60:
            return ""
        self._last_tag_at = now
        return tag

    @staticmethod
    def content_match(reply: str, tag: str) -> bool:
        if tag in ("laughs", "chuckle"):
            return any(x in reply for x in ("！", "!", "哈哈", "好笑"))
        if tag == "sighs":
            return "唉" in reply or "算啦" in reply
        if tag == "gasps":
            return any(x in reply for x in ("吓", "喂", "？", "?"))
        if tag == "emm":
            return any(x in reply for x in ("嗯", "等我諗下", "等我唞"))
        if tag == "breath":
            return any(x in reply for x in ("唞", "深呼吸"))
        return False

    def _tag(self, reply: str, emotion: str, ai_tag: str = "") -> str:
        tag = ai_tag if ai_tag in self.TAGS and self.content_match(reply, ai_tag) else ""
        return self.throttle_tag(tag or self.suggest_tag(reply, emotion))

    def fallback_buttons(self) -> list[str]:
        count = int(self.settings.get("button_count", 4) or 4)
        count = count if count in (4, 8, 10) else 4
        values = list(self.BUTTONS)
        random.shuffle(values)
        return values[:count]

    def fallback(self, text: str) -> "AIEngine.Response":
        t = text or ""
        if any(x in t for x in ("廢", "唔配", "失敗", "蠢", "冇用", "唔得", "鬧", "醜", "懶", "差")):
            kind, pool, emotion = "critic", self.CRITIC, "angry"
        elif any(x in t for x in ("安全", "坐喺度", "唔好郁", "摸下狗", "唔好出聲", "唔好試")):
            kind, pool, emotion = "guardian", self.GUARDIAN, "calm"
        elif any(x in t for x in ("動唔到", "郁唔到", "冇力", "唔想郁", "唔想覆", "唔想起身", "僵住")):
            kind, pool, emotion = "stuck", self.STUCK, "sad"
        elif any(x in t for x in ("心口", "攰", "痛", "實", "緊", "悶", "空虛", "寂寞", "麻木", "驚", "嬲", "煩", "怕")):
            kind, pool, emotion = "feeling", self.FEELING, self.infer_emotion(t)
        elif any(x in t for x in ("唔重要", "值唔值得", "配唔配", "冇人喺乎", "冇價值", "冇人需要")):
            kind, pool, emotion = "worth", self.WORTH, "sad"
        else:
            kind, pool, emotion = "other", self.OTHER, self.infer_emotion(t)
        reply = random.choice(pool)
        if self.settings.get("narration_enabled", False):
            reply = reply.replace("你哋", "佢哋").replace("你", "佢")
        tag = self.throttle_tag(self.suggest_tag(reply, emotion))
        return self.Response(kind, reply, self.fallback_buttons(), emotion, tag)

    @dataclass
    class Response:
        type: str
        reply: str
        buttons: Optional[list[str]] = None
        emotion: str = ""
        tag: str = ""

    def respond(self, text: str) -> "AIEngine.Response":
        key = str(self.settings.get("api_key", "")).strip()
        if not key:
            return self.fallback(text)
        count = int(self.settings.get("button_count", 4) or 4)
        count = count if count in (4, 8, 10) else 4
        system = (
            "你係 YupiSaver 即時回應引擎。用廣東話、溫暖、唔否定感受、唔命令、唔用你應該/你必須。"
            "幫用戶將翻譯官、溫柔看守、破壞者同真實感受分開；唔好辯論內在聲音。"
            "回應 30-80 字，另外生成按鈕、emotion、tag。"
            f"按鈕要 {count} 個，每個 4-10 字。emotion 只能是 calm/happy/sad/angry/fearful/disgusted/surprised/fluent/空字串；"
            "tag 只能是 laughs/chuckle/sighs/gasps/breath/emm/空字串，沒有明顯表情就留空。"
            f"最近記錄：\n{self.records_context(5)}\n"
            '只輸出 JSON：{"type":"other","emotion":"calm","tag":"","reply":"...","buttons":["..."]}'
        )
        try:
            raw = self.client.chat(system, f"用戶剛剛講：「{text}」\n請生成回應。", 1200).strip()
            item = json.loads(extract_json(raw))
            reply = str(item.get("reply", "")).strip()
            if not reply or len(reply) > 150:
                raise ValueError("reply 空或過長")
            emotion = self.emotion_for_content(reply, item.get("emotion", ""))
            buttons = [str(x).strip() for x in item.get("buttons", []) if str(x).strip()]
            buttons = buttons[:count] if len(buttons) >= 4 else None
            tag = self._tag(reply, emotion, str(item.get("tag", "")).strip())
            self.log.add("AI", f"解析 OK type={item.get('type', 'other')} emotion={emotion} tag={tag}")
            return self.Response(str(item.get("type", "other")), reply, buttons, emotion, tag)
        except Exception as exc:
            self.log.add("AI", f"respond 失敗，使用 fallback: {type(exc).__name__}")
            return self.fallback(text)

    def respond_more(self, topic: str, history: list[str]) -> "AIEngine.Response":
        if str(self.settings.get("api_key", "")).strip():
            try:
                raw = self.client.chat(
                    "你係 YupiSaver 安慰延續引擎。用廣東話寫 30-60 字，換角度、唔重複、唔問問題、唔命令。"
                    f"主題：{topic}\n之前講過：{'；'.join(history[-3:]) or '（未有）'}\n"
                    '只輸出 JSON：{"emotion":"calm","tag":"","reply":"..."}',
                    "繼續安慰。",
                    800,
                )
                item = json.loads(extract_json(raw))
                reply = str(item.get("reply", "")).strip()
                if reply and len(reply) <= 150:
                    emotion = self.emotion_for_content(reply, item.get("emotion", ""))
                    return self.Response("other", reply, None, emotion, self._tag(reply, emotion, str(item.get("tag", ""))))
            except Exception as exc:
                self.log.add("AI", f"respond_more fallback: {type(exc).__name__}")
        return self.Response("other", random.choice(self.MORE_COMFORT), None, "calm", "")

    def proactive_comfort(self) -> "AIEngine.Response":
        if str(self.settings.get("api_key", "")).strip():
            try:
                raw = self.client.chat(
                    "你係 YupiSaver 主動陪伴引擎。用廣東話寫一句 30-60 字、自然溫柔、唔打擾嘅安慰；"
                    "唔好問問題、唔好叫人做事、唔好提 AI/定時/生成/通知，也不要假裝知道用戶發生了什麼。"
                    f"最近記錄只作氣氛參考：\n{self.records_context(5)}\n"
                    '只輸出 JSON：{"emotion":"calm","tag":"","reply":"..."}',
                    "請給我一句此刻適合收到的安慰。",
                    700,
                )
                item = json.loads(extract_json(raw))
                reply = str(item.get("reply", "")).strip()
                if 8 <= len(reply) <= 150:
                    emotion = self.emotion_for_content(reply, item.get("emotion", "")) or "calm"
                    return self.Response("other", reply, None, emotion, self._tag(reply, emotion, str(item.get("tag", ""))))
            except Exception as exc:
                self.log.add("AI", f"proactive fallback: {type(exc).__name__}")
        return self.Response("other", random.choice(self.PROACTIVE), None, "calm", "")

    def gratitude_prompt(self) -> str:
        if str(self.settings.get("api_key", "")).strip():
            try:
                out = self.client.chat(
                    "你係感恩練習引導者。用廣東話寫一句 25-40 字，引導用戶諗下而家擁有/已得到什麼；"
                    "具體、溫柔、唔問點解、唔用你應該。只輸出一句。",
                    "畀我一句感恩引導。",
                    400,
                ).strip()
                if out and len(out) <= 60:
                    return out.strip('"')
            except Exception as exc:
                self.log.add("AI", f"gratitude prompt fallback: {type(exc).__name__}")
        return random.choice(self.GRATITUDE_PROMPTS)

    def gratitude_reply(self, text: str) -> str:
        if str(self.settings.get("api_key", "")).strip():
            try:
                out = self.client.chat(
                    f"你係感恩練習回應者。用戶講佢擁有/得到嘅嘢：「{text[:150]}」。"
                    "用廣東話寫 30-60 字溫暖回應，肯定呢一刻係真嘅，唔講道理、唔問問題、唔以收到開頭。只輸出回應。",
                    "回應我擁有嘅嘢。",
                    500,
                ).strip()
                if out and len(out) <= 100:
                    return out.strip('"')
            except Exception as exc:
                self.log.add("AI", f"gratitude reply fallback: {type(exc).__name__}")
        return random.choice(self.GRATITUDE_REPLIES)

    def one_line(self, system: str, user: str) -> str:
        if str(self.settings.get("api_key", "")).strip():
            try:
                out = self.client.chat(system, user, 500).strip()
                if out and len(out) <= 80:
                    return out.strip('"')
            except Exception as exc:
                self.log.add("AI", f"one_line fallback: {type(exc).__name__}")
        return "好，我哋遲啲再傾。"

    def generate_buttons(self) -> list[str]:
        if not str(self.settings.get("api_key", "")).strip():
            return self.fallback_buttons()
        count = int(self.settings.get("button_count", 4) or 4)
        count = count if count in (4, 8, 10) else 4
        try:
            raw = self.client.chat(
                "你係捉翻譯官心理輔助工具的按鈕生成器。用廣東話生成簡短、具體、非命令式狀態按鈕。"
                f"只輸出 JSON 陣列，必須有 {count} 項，每項 4-10 字。",
                f"現在時間：{dt.datetime.now().strftime('%H:%M')}\n最近記錄：{self.records_context(5)}",
                800,
                thinking=False,
            )
            values = json.loads(extract_array(raw))
            result = [str(x).strip() for x in values if str(x).strip()][:count]
            if len(result) == count:
                return result
        except Exception as exc:
            self.log.add("AI", f"generate_buttons fallback: {type(exc).__name__}")
        return self.fallback_buttons()

    def analyze_steps(self, task: str) -> list[str]:
        if str(self.settings.get("api_key", "")).strip():
            try:
                raw = self.client.chat(
                    f"你係任務拆解器。將「{task.strip()}」拆成 2-5 個極微細、具體、一步一步的廣東話動作。"
                    "最少 2 步，每步 2-12 字；只輸出 JSON 陣列。",
                    "幫我拆步。",
                    800,
                )
                values = json.loads(extract_array(raw))
                steps = [str(x).strip() for x in values if str(x).strip()][:5]
                if len(steps) >= 2:
                    return steps
            except Exception as exc:
                self.log.add("AI", f"analyze_steps fallback: {type(exc).__name__}")
        clean = task.strip() or "呢件事"
        return [f"望住「{clean}」，吸一啖氣", f"開始做「{clean}」嘅頭一步", f"完成「{clean}」"]

    def nudge_phrase(self, task: str, step: str, attempt: int, timed: bool, used: list[str]) -> str:
        if str(self.settings.get("api_key", "")).strip():
            try:
                raw = self.client.chat(
                    "你係推動力助手。用廣東話生成一句 15 字內、溫暖、具體、可有少量幽默的提醒；"
                    "唔好嚴厲、唔好鬧、唔好加引號、唔好用你應該。只輸出一句。",
                    f"{'這是定時提醒。' if timed else ''}整個任務：{task}\n目前一步：{step}\n已用：{'；'.join(used) or '（未有）'}\n第 {attempt} 次。",
                    800,
                ).strip()
                if raw and len(raw) <= 30:
                    return raw.strip('"')
            except Exception as exc:
                self.log.add("AI", f"nudge_phrase fallback: {type(exc).__name__}")
        fallback = ["而家試下：%s", "唔使急，慢慢嚟：%s", "得閒就做：%s", "%s——做咗就當贏", "試下啦：%s，一分鐘就夠"]
        return fallback[(attempt - 1) % len(fallback)] % step

    def daily_summary(self, date_key: str, records: list[str]) -> tuple[str, str]:
        fallback = self.local_daily(records)
        if not records or not str(self.settings.get("api_key", "")).strip():
            return fallback, ""
        try:
            record_text = "\n".join(records[:40])
            raw = self.client.chat(
                "你係 YupiSaver 每日總結助手。用廣東話寫 150-250 字溫柔總結，不 judge，包含發生了什麼、觀察到的模式、1-2 個很小的建議；"
                "再想一個 4-12 字今日建議任務。只輸出 JSON。",
                f"日期：{date_key}\n記錄：\n{record_text}\n"
                '{"summary":"...","next_task":"..."}',
                1000,
            )
            item = json.loads(extract_json(raw))
            summary = str(item.get("summary", "")).strip()
            task = str(item.get("next_task", "")).strip()
            if summary:
                return summary[:900], task[:12]
        except Exception as exc:
            self.log.add("AI", f"daily_summary fallback: {type(exc).__name__}")
        return fallback, ""

    def weekly_summary(self, records: list[str]) -> tuple[str, str]:
        content = self.local_daily(records)
        if not records or not str(self.settings.get("api_key", "")).strip():
            return content, ""
        try:
            record_text = "\n".join(records[:60])
            raw = self.client.chat(
                "你係 YupiSaver 每週回顧助手。用廣東話寫 200-300 字溫柔週回顧，歸納 2-3 個翻譯官常見模式、真我/行動進展、完成率和下週最小目標；"
                "另用一句 4-12 字歸納主題。只輸出 JSON。",
                f"過去 7 日記錄：\n{record_text}\n"
                '{"summary":"...","theme":"..."}',
                1200,
            )
            item = json.loads(extract_json(raw))
            summary = str(item.get("summary", "")).strip()
            theme = str(item.get("theme", "")).strip()
            return (summary[:1100] or content), theme[:12]
        except Exception as exc:
            self.log.add("AI", f"weekly_summary fallback: {type(exc).__name__}")
        return content, ""

    @staticmethod
    def local_daily(records: list[str]) -> str:
        counts = {"翻譯官": 0, "真我": 0, "按鈕": 0, "推動完成": 0, "推動取消": 0, "反駁": 0, "行動完成": 0, "行動未做": 0}
        for row in records:
            for key in counts:
                if row.startswith(f"[{key}]"):
                    counts[key] += 1
        return (
            f"尋日你捉到「翻譯官」{counts['翻譯官']} 次，記低咗 {counts['真我']} 句真我感受，"
            f"撳咗 {counts['按鈕']} 次狀態掣，反駁咗 {counts['反駁']} 次，完成咗 "
            f"{counts['推動完成'] + counts['行動完成']} 件小事（未完成 {counts['推動取消'] + counts['行動未做']} 件）。"
            "唔使理個數字係大定細——有記錄，就代表尋日你有喺度。"
        )


class TTSPlayer:
    EDGE_VOICES = {"hk-f": "zh-HK-HiuGaaiNeural", "hk-m": "zh-HK-WanLungNeural", "cn": "zh-CN-XiaoxiaoNeural"}
    EDGE_STYLES = {"friendly": "+6Hz", "cheerful": "+14Hz", "serious": "-10Hz", "": "+0Hz"}
    MINIMAX_VOICES = ["Cantonese_CuteGirl", "Cantonese_KindWoman", "Cantonese_GentleLady", "Cantonese_PlayfulMan"]
    MINIMAX_LABELS = ["粵語·可愛女孩", "粵語·善良女士", "粵語·溫柔女士", "粵語·頑皮男聲"]
    MINIMAX_MODELS = ["speech-2.8-hd", "speech-2.8-turbo"]
    MINIMAX_MODEL_LABELS = ["2.8 HD（推薦·最自然）", "2.8 Turbo（快·慳）"]
    EMOTIONS = ["auto", "", "calm", "happy", "sad", "angry", "fearful", "disgusted", "surprised", "fluent"]
    EMOTION_LABELS = [
        "自動 · 跟隨內容（推薦）", "自然 · 不固定情緒", "平靜 · 溫柔", "開心 · 輕快", "傷感 · 柔和",
        "堅定 · 生氣", "緊張 · 害怕", "厭惡 · 反感", "驚訝 · 意外", "流利 · 敘述",
    ]
    TAGS = ["", "laughs", "chuckle", "sighs", "gasps", "breath", "emm"]

    def __init__(self, settings: SettingsStore, log: DebugLog, emotion_resolver: Callable[[str, str | None, str | None], str]):
        self.settings = settings
        self.log = log
        self.resolve_emotion = emotion_resolver
        self._executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="YupiTTS")
        self._play_lock = threading.Lock()
        self._active = 0
        self._active_lock = threading.Lock()
        self.fallback_listener: Optional[Callable[[str, str], None]] = None

    @property
    def active(self) -> bool:
        with self._active_lock:
            return self._active > 0

    def _mark_active(self, value: int) -> None:
        with self._active_lock:
            self._active = max(0, self._active + value)

    @staticmethod
    def apply_tag(text: str, tag: str | None) -> str:
        if not text or tag not in TTSPlayer.TAGS or not tag:
            return text
        label = f"({tag})"
        if label in text:
            return text
        for index, char in enumerate(text):
            if char in "！!?？":
                return text[:index + 1] + label + text[index + 1:]
        return text + label

    def speak(self, text: str, emotion: str | None = None, tag: str | None = None, on_complete: Optional[Callable[[], None]] = None) -> None:
        if not text:
            if on_complete:
                on_complete()
            return
        self._executor.submit(self._speak_worker, text, emotion, tag, on_complete)

    def _speak_worker(self, text: str, emotion: str | None, tag: str | None, on_complete: Optional[Callable[[], None]]) -> None:
        with self._play_lock:
            self._mark_active(1)
            try:
                engine = str(self.settings.get("voice_engine", "system"))
                rate = str(self.settings.get("voice_rate", "0"))
                try:
                    if engine in ("edge-hk", "edge-cn"):
                        self._edge(text, rate, engine, emotion)
                    elif engine == "minimax":
                        self._minimax(text, rate, emotion, tag)
                    else:
                        self._system(text, rate)
                except Exception as exc:
                    self.log.add("TTS", f"{engine} 失敗，改用系統聲: {type(exc).__name__} {str(exc)[:120]}")
                    if self.fallback_listener:
                        self.fallback_listener(engine, str(exc))
                    self._system(text, rate)
            finally:
                self._mark_active(-1)
                if on_complete:
                    try:
                        on_complete()
                    except Exception as exc:
                        self.log.add("TTS", f"完成回調失敗: {type(exc).__name__}")

    def _edge(self, text: str, rate: str, engine: str, emotion: str | None) -> None:
        try:
            import edge_tts
        except ImportError as exc:
            raise RuntimeError("未安裝 edge-tts") from exc
        voice_key = str(self.settings.get("edge_voice", "hk-f"))
        voice = self.EDGE_VOICES.get(voice_key, self.EDGE_VOICES["hk-f"])
        style = str(self.settings.get("edge_style", "friendly"))
        rate_value = rate.strip() or "0"
        if not rate_value.startswith(("+", "-")):
            rate_value = "+" + rate_value
        rate_value += "%"
        out = tempfile.NamedTemporaryFile(prefix="yupi_edge_", suffix=".mp3", delete=False)
        out.close()
        try:
            async def synthesize() -> None:
                communicate = edge_tts.Communicate(
                    text,
                    voice,
                    rate=rate_value,
                    pitch=self.EDGE_STYLES.get(style, "+0Hz"),
                    volume="+0%",
                )
                await communicate.save(out.name)

            asyncio.run(synthesize())
            self._play_mp3(out.name)
        finally:
            try:
                os.unlink(out.name)
            except OSError:
                pass

    def _minimax(self, text: str, rate: str, emotion: str | None, tag: str | None, out: Optional[Path] = None) -> Path:
        if requests is None:
            raise RuntimeError("未安裝 requests")
        key = str(self.settings.get("minimax_key", "")).strip()
        if not key:
            raise RuntimeError("未填 MiniMax API Key")
        final_text = self.apply_tag(text, tag)
        mode = str(self.settings.get("minimax_emotion_mode", "auto"))
        resolved = self.resolve_emotion(text, emotion, mode)
        speed = 1.0
        try:
            speed = 1.0 + int(rate) / 100.0
        except ValueError:
            pass
        speed = max(0.5, min(2.0, speed))
        body: dict[str, Any] = {
            "model": str(self.settings.get("minimax_model", "speech-2.8-hd")),
            "text": final_text,
            "stream": False,
            "language_boost": "Chinese,Yue",
            "voice_setting": {
                "voice_id": str(self.settings.get("minimax_voice", self.MINIMAX_VOICES[0])),
                "speed": speed,
                "vol": 1.0,
                "pitch": 0,
            },
            "audio_setting": {"sample_rate": 32000, "bitrate": 128000, "format": "mp3", "channel": 1},
        }
        if resolved and resolved != "auto":
            body["voice_setting"]["emotion"] = resolved
        response = requests.post(
            "https://api.minimaxi.com/v1/t2a_v2",
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            json=body,
            timeout=(8, 45),
        )
        if response.status_code != 200:
            raise RuntimeError(f"MiniMax HTTP {response.status_code}: {response.text[:240]}")
        payload = response.json()
        base = payload.get("base_resp", {})
        if base.get("status_code", -1) != 0:
            raise RuntimeError(f"MiniMax 錯誤: {base.get('status_msg', 'unknown')}")
        audio = bytes.fromhex(str(payload.get("data", {}).get("audio", "")))
        if len(audio) < 100:
            raise RuntimeError("MiniMax 音訊太細")
        if out:
            target = Path(out)
        else:
            fd, temp_name = tempfile.mkstemp(prefix="yupi_mm_", suffix=".mp3")
            os.close(fd)
            target = Path(temp_name)
        target.write_bytes(audio)
        self._play_mp3(str(target))
        try:
            target.unlink()
        except OSError:
            pass
        return target

    def close(self) -> None:
        self._executor.shutdown(wait=False, cancel_futures=True)

    def _system(self, text: str, rate: str) -> None:
        # 優先用 pyttsx3（如果 requirements 已裝），冇就用 Windows 內置 SAPI。
        try:
            import pyttsx3

            engine = pyttsx3.init()
            try:
                base_rate = int(engine.getProperty("rate") or 180)
                delta = int(rate or 0)
                engine.setProperty("rate", max(90, min(300, round(base_rate * (1 + delta / 100)))) )
            except Exception:
                pass
            engine.say(text)
            engine.runAndWait()
            engine.stop()
            return
        except Exception as exc:
            self.log.add("TTS", f"pyttsx3 不可用: {type(exc).__name__}")
        script = (
            "Add-Type -AssemblyName System.Speech; "
            "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
            "$t=[Console]::In.ReadToEnd(); "
            "try {$s.Speak($t)} finally {$s.Dispose()}"
        )
        encoded = base64.b64encode(script.encode("utf-16le")).decode("ascii")
        subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
            input=text,
            text=True,
            timeout=120,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            check=False,
        )

    @staticmethod
    def _play_mp3(path: str) -> None:
        if os.name != "nt":
            raise RuntimeError("MP3 播放目前只支援 Windows")
        winmm = ctypes.windll.winmm
        alias = "yupi_" + uuid.uuid4().hex[:10]
        error = ctypes.create_unicode_buffer(512)

        def command(value: str) -> None:
            result = winmm.mciSendStringW(value, error, len(error), 0)
            if result:
                detail = error.value or f"MCI error {result}"
                raise RuntimeError(detail)

        safe_path = str(Path(path).resolve()).replace('"', "")
        opened = False
        try:
            command(f'open "{safe_path}" type mpegvideo alias {alias}')
            opened = True
            command(f"play {alias} wait")
        finally:
            if opened:
                winmm.mciSendStringW(f"close {alias}", None, 0, 0)

    def design_voice(self, prompt: str, preview_text: str) -> str:
        if requests is None:
            raise RuntimeError("未安裝 requests")
        key = str(self.settings.get("minimax_key", "")).strip()
        if not key:
            raise ValueError("請先填 MiniMax API Key")
        if not prompt.strip() or not preview_text.strip():
            raise ValueError("聲線描述和粵語試聽文字都要填")
        if len(prompt) > 500 or len(preview_text) > 500:
            raise ValueError("描述和試聽文字最多各 500 字")
        response = requests.post(
            "https://api.minimaxi.com/v1/voice_design",
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            json={"prompt": prompt.strip(), "preview_text": preview_text.strip(), "aigc_watermark": False},
            timeout=(8, 70),
        )
        if response.status_code != 200:
            raise RuntimeError(f"MiniMax Voice Design HTTP {response.status_code}: {response.text[:240]}")
        payload = response.json()
        base = payload.get("base_resp", {})
        if base.get("status_code", -1) != 0:
            raise RuntimeError(f"MiniMax 音色設計失敗: {base.get('status_msg', 'unknown')}")
        voice_id = str(payload.get("voice_id", "")).strip()
        if not voice_id:
            raise RuntimeError("MiniMax 沒有回傳 voice_id")
        return voice_id


class TimedNudgeScheduler:
    MIN_DELAY = 5_000
    MAX_DELAY = 7 * 24 * 60 * 60 * 1000
    PATTERN = re.compile(
        r"(\d{1,4}|半)\s*(?:個|个)?\s*(秒鐘|秒钟|秒|分鐘|分钟|小時|小时|鐘頭|钟头|個鐘|个钟|日|天)\s*(?:之)?[後后]",
        re.IGNORECASE,
    )

    def __init__(self, callback: Callable[[str], None], log: DebugLog):
        self.callback = callback
        self.log = log
        self._lock = threading.RLock()
        self._timers: dict[str, threading.Timer] = {}
        self._load_and_schedule()

    @classmethod
    def parse(cls, raw: str | None) -> Optional[tuple[str, int]]:
        if not raw:
            return None
        match = cls.PATTERN.search(raw.strip())
        if not match:
            return None
        amount = 0.5 if match.group(1) == "半" else float(match.group(1))
        unit = match.group(2)
        if unit.startswith("秒"):
            unit_ms = 1000
        elif unit.startswith("分"):
            unit_ms = 60_000
        elif unit in ("日", "天"):
            unit_ms = 24 * 60 * 60 * 1000
        else:
            unit_ms = 60 * 60 * 1000
        delay = round(amount * unit_ms)
        if delay < cls.MIN_DELAY or delay > cls.MAX_DELAY:
            return None
        task = raw[match.end():].strip()
        task = re.sub(r"^[，,。\.、\s呃嗯啊哦]*(?:到時|到时)?(?:記得|记得)?(?:提醒我|提我|叫我|通知我|幫我|帮我)?[，,。\.、\s呃嗯啊哦]*", "", task)
        return (task.strip(), delay) if task.strip() else None

    @classmethod
    def describe(cls, delay_ms: int) -> str:
        if delay_ms < 60_000:
            return f"{max(1, round(delay_ms / 1000))}秒後"
        if delay_ms % (60 * 60 * 1000) == 0:
            return f"{delay_ms // (60 * 60 * 1000)}小時後"
        return f"{max(1, round(delay_ms / 60_000))}分鐘後"

    def schedule(self, task: str, delay_ms: int) -> tuple[str, float]:
        clean = task.strip()
        if not clean:
            raise ValueError("定時任務內容係空")
        delay_ms = max(self.MIN_DELAY, min(self.MAX_DELAY, int(delay_ms)))
        trigger_at = time.time() + delay_ms / 1000
        task_id = f"{int(trigger_at * 1000)}-{uuid.uuid4().hex[:8]}"
        item = {"id": task_id, "task": clean, "trigger_at": trigger_at}
        with self._lock:
            items = self._read_items()
            items.append(item)
            self._write_items(items)
            self._set_timer(item)
        return task_id, trigger_at

    def remove(self, task_id: str) -> None:
        with self._lock:
            self._write_items([x for x in self._read_items() if x.get("id") != task_id])
            timer = self._timers.pop(task_id, None)
            if timer:
                timer.cancel()

    def stop(self) -> None:
        with self._lock:
            for timer in self._timers.values():
                timer.cancel()
            self._timers.clear()

    def _read_items(self) -> list[dict[str, Any]]:
        try:
            values = json.loads(TASKS_FILE.read_text(encoding="utf-8"))
            return values if isinstance(values, list) else []
        except (OSError, json.JSONDecodeError):
            return []

    def _write_items(self, values: list[dict[str, Any]]) -> None:
        tmp = TASKS_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(values, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(TASKS_FILE)

    def _load_and_schedule(self) -> None:
        now = time.time()
        with self._lock:
            kept = []
            for item in self._read_items():
                try:
                    if not item.get("id") or not item.get("task"):
                        continue
                    trigger = float(item.get("trigger_at", 0))
                    if trigger < now - 24 * 60 * 60:
                        continue
                    if trigger < now:
                        item["trigger_at"] = now + 1
                    kept.append(item)
                    self._set_timer(item)
                except (TypeError, ValueError):
                    continue
            self._write_items(kept)

    def _set_timer(self, item: dict[str, Any]) -> None:
        task_id = str(item["id"])
        delay = max(0.1, float(item["trigger_at"]) - time.time())
        timer = threading.Timer(delay, self._fire, args=(task_id, str(item["task"])))
        timer.daemon = True
        self._timers[task_id] = timer
        timer.start()

    def _fire(self, task_id: str, task: str) -> None:
        self.remove(task_id)
        try:
            self.callback(task)
        except Exception as exc:
            self.log.add("Timer", f"定時任務回調失敗: {type(exc).__name__}")


class ActiveComfortController:
    DEFAULT_INTERVAL = 20
    MIN_INTERVAL = 0
    MAX_INTERVAL = 300

    def __init__(self, settings: SettingsStore, db: TranslatorDb, ai: AIEngine, tts: TTSPlayer,
                 status: Callable[[str], None], after_play: Callable[[], None], log: DebugLog,
                 on_response: Optional[Callable[[str], None]] = None):
        self.settings = settings
        self.db = db
        self.ai = ai
        self.tts = tts
        self.status = status
        self.after_play = after_play
        self.log = log
        self.on_response = on_response
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    @property
    def enabled(self) -> bool:
        return bool(self.settings.get("active_comfort_enabled", False))

    def interval(self) -> int:
        try:
            return max(self.MIN_INTERVAL, min(self.MAX_INTERVAL, int(self.settings.get("active_comfort_interval", 20))))
        except (TypeError, ValueError):
            return self.DEFAULT_INTERVAL

    def set_enabled(self, enabled: bool) -> None:
        self.settings.update({"active_comfort_enabled": bool(enabled)})
        if enabled:
            self.start()
        else:
            self.stop()

    def set_interval(self, value: int) -> int:
        safe = max(self.MIN_INTERVAL, min(self.MAX_INTERVAL, int(value)))
        self.settings.update({"active_comfort_interval": safe})
        if self.enabled:
            self.start()
        return safe

    def start(self) -> None:
        self.stop()
        # Each run owns a fresh Event.  The previous worker may still be
        # returning from wait(); clearing the same Event could accidentally
        # wake that old worker and create two active comfort loops.
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, name="YupiActiveComfort", daemon=True)
        self._thread.start()
        self.status(f"主動安慰已開啟 · 每 {self.interval_description()} 陪伴一次")

    def stop(self) -> None:
        self._stop.set()
        self._thread = None

    def interval_description(self) -> str:
        value = self.interval()
        return "0 分鐘（安全最短 1 分鐘）" if value == 0 else f"{value} 分鐘"

    def _loop(self) -> None:
        while not self._stop.is_set() and self.enabled:
            delay = 60 if self.interval() == 0 else self.interval() * 60
            if self._stop.wait(delay):
                return
            if self.tts.active:
                self.status("偵測到其他語音播放 · 主動安慰稍後再試")
                continue
            self.status("正在準備一段安慰…")
            try:
                response = self.ai.proactive_comfort()
                if not response.reply or self._stop.is_set() or not self.enabled:
                    continue
                self.db.insert("主動安慰", response.reply, "proactive_comfort")
                if self.on_response is not None:
                    try:
                        self.on_response(response.reply)
                    except Exception as exc:
                        self.log.add("Comfort", f"主動安慰 HUD 回調失敗: {type(exc).__name__}")
                launch = bool(self.settings.get("active_comfort_launch_assistant", False))
                if launch:
                    self.tts.speak(
                        response.reply,
                        response.emotion,
                        response.tag,
                        on_complete=lambda: self.after_play() if self.enabled else None,
                    )
                    self.status(f"正在播放安慰，完成後打開 ChatGPT · 下次每 {self.interval_description()}")
                else:
                    self.tts.speak(response.reply, response.emotion, response.tag)
                    self.status(f"剛剛已播放安慰 · 下次每 {self.interval_description()}")
            except Exception as exc:
                self.log.add("Comfort", f"主動安慰失敗: {type(exc).__name__}")
                self.status("今次生成失敗 · 稍後再試")


class HeadUpScheduler:
    """以 WakeMyheadUp 的隨機間隔方式安排透明抬頭提醒。"""

    DEFAULT_INTERVAL = 30
    DEFAULT_JITTER = 5
    MIN_INTERVAL = 0
    MAX_INTERVAL = 300
    MAX_JITTER = 30

    def __init__(self, settings: SettingsStore, callback: Callable[[], None], log: DebugLog):
        self.settings = settings
        self.callback = callback
        self.log = log
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._timer: Optional[threading.Timer] = None
        self._generation = 0

    @property
    def enabled(self) -> bool:
        return bool(self.settings.get("head_up_enabled", False))

    def interval(self) -> int:
        try:
            return max(self.MIN_INTERVAL, min(self.MAX_INTERVAL, int(self.settings.get("head_up_interval", self.DEFAULT_INTERVAL))))
        except (TypeError, ValueError):
            return self.DEFAULT_INTERVAL

    def jitter(self) -> int:
        try:
            return max(0, min(self.MAX_JITTER, int(self.settings.get("head_up_jitter", self.DEFAULT_JITTER))))
        except (TypeError, ValueError):
            return self.DEFAULT_JITTER

    def interval_description(self) -> str:
        value = self.interval()
        return "0 分鐘（安全最短 1 分鐘）" if value == 0 else f"約每 {value} 分鐘"

    def start(self) -> None:
        with self._lock:
            self._generation += 1
            self._stop.set()
            if self._timer is not None:
                self._timer.cancel()
            self._stop = threading.Event()
            self._timer = None
            if self.enabled:
                self._schedule_locked()

    def stop(self) -> None:
        with self._lock:
            self._generation += 1
            self._stop.set()
            if self._timer is not None:
                self._timer.cancel()
            self._timer = None

    def sync(self) -> None:
        if self.enabled:
            self.start()
        else:
            self.stop()

    def _schedule_locked(self, generation: Optional[int] = None) -> None:
        generation = self._generation if generation is None else generation
        if generation != self._generation:
            return
        if self._stop.is_set() or not self.enabled:
            return
        base = self.interval() or 1
        jitter = 0 if self.interval() == 0 else self.jitter()
        low = max(1.0, float(base - jitter))
        high = max(low, float(base + jitter))
        delay_seconds = random.uniform(low, high) * 60.0
        self._timer = threading.Timer(delay_seconds, lambda: self._fire(generation))
        self._timer.daemon = True
        self._timer.start()

    def _fire(self, generation: int) -> None:
        with self._lock:
            if generation != self._generation or self._stop.is_set():
                return
        try:
            if self.enabled:
                self.callback()
        except Exception as exc:
            self.log.add("HUD", f"抬頭提醒回調失敗: {type(exc).__name__}")
        finally:
            with self._lock:
                if generation == self._generation:
                    self._timer = None
                    self._schedule_locked(generation)


class NudgeController:
    INTERVAL_SECONDS = 60
    MAX_PER_STEP = 3
    PRAISE = ["好，呢步搞掂！", "得咗！繼續下一步。", "好叻，郁到喇！", "正！一步一步嚟。"]

    def __init__(self, ai: AIEngine, tts: TTSPlayer, status: Callable[[str], None],
                 on_ready: Callable[[list[str], bool], None], on_phrase: Callable[[int, int, str, str], None],
                 on_end: Callable[[str, bool], None], log: DebugLog):
        self.ai = ai
        self.tts = tts
        self.status = status
        self.on_ready = on_ready
        self.on_phrase = on_phrase
        self.on_end = on_end
        self.log = log
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="YupiNudge")
        self._lock = threading.RLock()
        self._timer: Optional[threading.Timer] = None
        self.running = False
        self.timed = False
        self.task = ""
        self.steps: list[str] = []
        self.step_index = 0
        self.attempt = 0
        self.used: list[str] = []

    def start(self, task: str, timed: bool = False) -> None:
        self.stop(hide_only=True)
        with self._lock:
            self.running = True
            self.timed = timed
            self.task = task.strip()
            self.steps = []
            self.step_index = 0
            self.attempt = 0
            self.used = []
        self.status("⏰ 時間到，準備定時推動…" if timed else "分析緊你想做咩…")
        self._executor.submit(self._prepare)

    def _prepare(self) -> None:
        try:
            steps = self.ai.analyze_steps(self.task)
            with self._lock:
                if not self.running:
                    return
                self.steps = steps
            self.on_ready(steps, self.timed)
            self.next_phrase()
        except Exception as exc:
            self.log.add("Nudge", f"拆步失敗: {type(exc).__name__}")

    def next_phrase(self) -> None:
        with self._lock:
            if not self.running:
                return
            self.attempt += 1
            if self.attempt > self.MAX_PER_STEP:
                self._advance_step()
                return
            index = self.step_index
            step = self.steps[index] if self.steps else self.task
            timed = self.timed
            attempt = self.attempt
            task = self.task
            used = list(self.used)
        self.status("諗緊點提你…")
        self._executor.submit(self._phrase_worker, task, step, index, attempt, timed, used)

    def _phrase_worker(self, task: str, step: str, index: int, attempt: int, timed: bool, used: list[str]) -> None:
        phrase = self.ai.nudge_phrase(task, step, attempt, timed, used)
        with self._lock:
            if not self.running:
                return
            self.used.append(phrase)
            total = len(self.steps) or 1
        self.on_phrase(index, total, step, phrase)
        self.tts.speak(phrase)
        self._schedule_next()

    def _schedule_next(self) -> None:
        with self._lock:
            if not self.running:
                return
            if self._timer:
                self._timer.cancel()
            self._timer = threading.Timer(self.INTERVAL_SECONDS, self.next_phrase)
            self._timer.daemon = True
            self._timer.start()

    def done_current(self) -> None:
        with self._lock:
            if not self.running:
                return
            if self._timer:
                self._timer.cancel()
            if self.step_index >= len(self.steps) - 1:
                task, self.running = self.task, False
                self.on_end(task, True)
                self.tts.speak(f"好嘢！做咗「{task}」，你話到做到！")
                return
            index = self.step_index
            self.step_index += 1
            self.attempt = 0
            next_step = self.steps[self.step_index]
        self.tts.speak(self.PRAISE[index % len(self.PRAISE)])
        self.status(f"下一步：「{next_step}」")
        self.next_phrase()

    def _advance_step(self) -> None:
        with self._lock:
            if not self.running:
                return
            if self._timer:
                self._timer.cancel()
            if self.step_index >= len(self.steps) - 1:
                task, self.running = self.task, False
                self.on_end(task, False)
                return
            self.step_index += 1
            self.attempt = 0
            next_step = self.steps[self.step_index]
        self.status(f"冇所謂，想嘅時候再嚟——而家試下：「{next_step}」")
        self.next_phrase()

    def stop(self, hide_only: bool = False) -> None:
        with self._lock:
            self.running = False
            if self._timer:
                self._timer.cancel()
                self._timer = None
        if not hide_only:
            self.on_end(self.task, False)

    def close(self) -> None:
        self.stop(hide_only=True)
        self._executor.shutdown(wait=False, cancel_futures=True)


def open_chatgpt() -> None:
    """Windows 沒有 Android 的 ChatGPT Activity；用官方 Web 入口作等價 fallback。"""
    webbrowser.open("https://chatgpt.com/")
