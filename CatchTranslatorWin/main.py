"""YupiSaver for Windows desktop GUI。

功能對應 Android 版：浮動按鈕、AI 捕捉、語音播放/文字輸入、感恩、推動力、
定時推動、主動安慰、三種語音引擎、MiniMax Voice Design、統計、每日/每週總結、
Telegram Log。Windows 版不包含藍牙保活。
"""

from __future__ import annotations

import datetime as dt
import random
import textwrap
import tkinter as tk
from concurrent.futures import ThreadPoolExecutor
from tkinter import messagebox, simpledialog, ttk
from typing import Any, Callable, Optional

from core import (
    AIEngine,
    ActiveComfortController,
    DebugLog,
    HeadUpScheduler,
    NudgeController,
    SettingsStore,
    TTSPlayer,
    TimedNudgeScheduler,
    TranslatorDb,
    open_chatgpt,
)


BG = "#EDF6F2"
CARD = "#F8FCFA"
GREEN = "#2E7D5B"
MINT = "#55B99A"
TEXT = "#274C3B"
MUTED = "#6F9685"
ORANGE = "#D77D32"
HUD_BG = "#0B0F19"
HUD_FG = "#FFE14D"
HUD_SHADOW = "#000000"
HUD_FONT = "Microsoft YaHei UI"

HEAD_UP_MESSAGES = (
    "抬頭一下，望遠處 20 秒。",
    "肩膀放低，慢慢眨幾下眼。",
    "飲一啖水，等身體追返你。",
    "你唔需要一次做晒所有嘢。",
    "停一停，感受一下雙腳踩住地面。",
    "將手機放低一陣，畀自己一口氣。",
)


class Collapsible(ttk.Frame):
    def __init__(self, parent, title: str, open_by_default: bool = False):
        super().__init__(parent)
        self.title = title
        self.open = open_by_default
        self.columnconfigure(0, weight=1)
        self.header = ttk.Button(self, text=self._label(), command=self.toggle)
        self.header.grid(row=0, column=0, sticky="ew", pady=(8, 0))
        self.body = ttk.Frame(self)
        if self.open:
            self.body.grid(row=1, column=0, sticky="ew", padx=8, pady=6)

    def _label(self) -> str:
        return ("▾ " if self.open else "▸ ") + self.title

    def toggle(self) -> None:
        self.open = not self.open
        self.header.configure(text=self._label())
        if self.open:
            self.body.grid(row=1, column=0, sticky="ew", padx=8, pady=6)
        else:
            self.body.grid_remove()


class FloatingBubble:
    def __init__(self, app: "YupiSaverApp"):
        self.app = app
        self.win: Optional[tk.Toplevel] = None
        self._press = (0, 0, 0, 0)
        self._dragging = False
        self._suppress_button_command = False

    def show(self) -> None:
        if self.win is not None and self.win.winfo_exists():
            self.win.deiconify()
            self.win.lift()
            return
        self.win = tk.Toplevel(self.app)
        self.win.overrideredirect(True)
        self.win.attributes("-topmost", True)
        self.win.configure(bg=MINT)
        self.win.geometry(self.app.settings.get("bubble_geometry", "52x52+30+300"))
        self.win.resizable(False, False)
        button = tk.Button(
            self.win,
            text="🎧",
            bg=MINT,
            activebackground=GREEN,
            fg="white",
            font=("Segoe UI Emoji", 20),
            width=2,
            height=1,
            relief="flat",
            bd=0,
            highlightthickness=0,
            cursor="hand2",
            command=self._button_command,
        )
        button.pack(fill="both", expand=True, padx=3, pady=3)
        for widget in (self.win, button):
            widget.bind("<ButtonPress-1>", self._press_start)
            widget.bind("<B1-Motion>", self._drag)
            widget.bind("<ButtonRelease-1>", self._release)
        self.win.bind("<Destroy>", lambda _e: setattr(self, "win", None))

    def hide(self) -> None:
        if self.win is not None:
            try:
                self.app.settings.update({"bubble_geometry": self.win.geometry()})
                self.win.destroy()
            except tk.TclError:
                pass
            self.win = None

    def flash(self, text: str = "✓") -> None:
        if self.win is None or not self.win.winfo_exists():
            return
        children = self.win.winfo_children()
        if not children:
            return
        label = children[0]
        original = label.cget("text")
        label.configure(text=text, bg="#E6B85C")
        self.app.after(650, lambda: label.configure(text=original, bg=MINT) if label.winfo_exists() else None)

    def _press_start(self, event) -> None:
        if self.win is None:
            return
        self._press = (event.x_root, event.y_root, self.win.winfo_x(), self.win.winfo_y())
        self._dragging = False
        self._suppress_button_command = False

    def _drag(self, event) -> None:
        if self.win is None:
            return
        sx, sy, wx, wy = self._press
        dx, dy = event.x_root - sx, event.y_root - sy
        if abs(dx) > 5 or abs(dy) > 5:
            self._dragging = True
            self.win.geometry(f"+{wx + dx}+{wy + dy}")

    def _release(self, _event) -> None:
        if self.win is not None:
            self.app.settings.update({"bubble_geometry": self.win.geometry()})
        if self._dragging:
            self._suppress_button_command = True

    def _button_command(self) -> None:
        if self._suppress_button_command:
            self._suppress_button_command = False
            return
        # The floating button is intentionally a simple mouse control:
        # one click opens/closes the panel.
        self.app.toggle_overlay()


class HeadsUpDisplay:
    """參考 WakeMyheadUp 的透明全屏大字提醒，沿用主 Tk 執行緒。"""

    def __init__(self, app: "YupiSaverApp"):
        self.app = app
        self.win: Optional[tk.Toplevel] = None
        self._hide_job: Optional[str] = None
        self._title_label: Optional[tk.Label] = None
        self._shadow_label: Optional[tk.Label] = None
        self._text_label: Optional[tk.Label] = None
        self._hint_label: Optional[tk.Label] = None

    def show(self, text: str, title: str = "抬頭一下", display_ms: Optional[int] = None) -> None:
        if not self.app.settings.get("hud_enabled", True):
            return
        message = " ".join(str(text or "").split())[:260]
        if not message:
            return
        self.hide()
        root = tk.Toplevel(self.app)
        self.win = root
        root.overrideredirect(True)
        root.attributes("-topmost", True)
        root.configure(bg=HUD_BG)
        try:
            root.attributes("-transparentcolor", HUD_BG)
        except tk.TclError:
            pass
        root.attributes("-alpha", 1.0)
        width, height = root.winfo_screenwidth(), root.winfo_screenheight()
        root.geometry(f"{width}x{height}+0+0")
        root.resizable(False, False)

        try:
            font_scale = float(self.app.settings.get("head_up_font_scale", 18)) / 100.0
        except (TypeError, ValueError):
            font_scale = 0.18
        font_size = max(48, min(220, int(height * max(0.08, min(0.40, font_scale)))))
        if len(message) > 90:
            font_size = int(font_size * 0.82)
        if len(message) > 160:
            font_size = int(font_size * 0.68)
        wrap_chars = max(16, min(42, int(width / max(font_size * 0.62, 1))))
        wrapped = textwrap.fill(message, width=wrap_chars)

        self._title_label = tk.Label(root, text=title, font=(HUD_FONT, 20, "bold"), fg="#C7F4DB", bg=HUD_BG)
        self._title_label.place(relx=0.5, rely=0.30, anchor="center")
        self._shadow_label = tk.Label(
            root, text=wrapped, font=(HUD_FONT, font_size, "bold"), fg=HUD_SHADOW, bg=HUD_BG,
            justify="center", wraplength=int(width * 0.82),
        )
        self._shadow_label.place(relx=0.5, rely=0.51, anchor="center", x=4, y=4)
        self._text_label = tk.Label(
            root, text=wrapped, font=(HUD_FONT, font_size, "bold"), fg=HUD_FG, bg=HUD_BG,
            justify="center", wraplength=int(width * 0.82),
        )
        self._text_label.place(relx=0.5, rely=0.51, anchor="center")
        self._hint_label = tk.Label(root, text="點一下或按 Esc 關閉", font=(HUD_FONT, 12), fg="#B8D8C6", bg=HUD_BG)
        self._hint_label.place(relx=0.5, rely=0.78, anchor="center")

        root.bind("<Button-1>", lambda _event: self.hide())
        root.bind("<Escape>", lambda _event: self.hide())
        for widget in (self._title_label, self._shadow_label, self._text_label, self._hint_label):
            widget.bind("<Button-1>", lambda _event: self.hide())
        root.bind("<Destroy>", self._destroyed, add="+")
        root.update_idletasks()
        root.lift()
        root.focus_force()
        if display_ms is None:
            try:
                display_ms = max(2000, int(self.app.settings.get("head_up_display_seconds", 5)) * 1000)
            except (TypeError, ValueError):
                display_ms = 5000
        self._hide_job = root.after(display_ms, self.hide)

    def _destroyed(self, _event) -> None:
        self.win = None
        self._hide_job = None
        self._title_label = None
        self._shadow_label = None
        self._text_label = None
        self._hint_label = None

    def hide(self) -> None:
        root = self.win
        if root is None:
            return
        try:
            if self._hide_job is not None:
                root.after_cancel(self._hide_job)
        except tk.TclError:
            pass
        self._hide_job = None
        try:
            root.destroy()
        except tk.TclError:
            pass
        self.win = None


class NudgePopup:
    def __init__(self, app: "YupiSaverApp"):
        self.app = app
        self.win: Optional[tk.Toplevel] = None
        self.phrase_var = tk.StringVar(value="")
        self.title_var = tk.StringVar(value="🚀 推動力")

    def show(self, steps: list[str], timed: bool) -> None:
        self.hide(save=False)
        self.win = tk.Toplevel(self.app)
        self.win.title("YupiSaver · 推動力")
        self.win.attributes("-topmost", True)
        self.win.configure(bg=BG)
        self.win.geometry(self.app.settings.get("nudge_geometry", "420x230+420+180"))
        self.win.minsize(360, 190)
        self.win.protocol("WM_DELETE_WINDOW", self.cancel)
        self.title_var.set("⏰ 定時推動" if timed else "🚀 推動力")

        frame = ttk.Frame(self.win, padding=16)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, textvariable=self.title_var, style="Title.TLabel").pack(anchor="w")
        ttk.Label(frame, text="按完成進入下一步；視窗可以直接拖動。", style="Muted.TLabel").pack(anchor="w", pady=(2, 8))
        ttk.Label(frame, textvariable=self.phrase_var, wraplength=380, justify="left", style="Body.TLabel").pack(fill="both", expand=True, anchor="w")
        row = ttk.Frame(frame)
        row.pack(fill="x", pady=(12, 0))
        ttk.Button(row, text="✅ 做咗呢步", command=self.done).pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="取消", command=self.cancel).pack(side="left", fill="x", expand=True, padx=(8, 0))

    def update_phrase(self, index: int, total: int, step: str, phrase: str) -> None:
        if self.win is None or not self.win.winfo_exists():
            return
        self.phrase_var.set(f"第 {index + 1}/{total} 步：「{step}」\n\n{phrase}")
        self.win.lift()

    def done(self) -> None:
        if self.app.nudge:
            self.app.nudge.done_current()

    def cancel(self) -> None:
        if self.app.nudge:
            self.app.nudge.stop()
        self.hide()

    def hide(self, save: bool = True) -> None:
        if self.win is not None:
            try:
                if save:
                    self.app.settings.update({"nudge_geometry": self.win.geometry()})
                self.win.destroy()
            except tk.TclError:
                pass
            self.win = None


class OverlayPanel:
    def __init__(self, app: "YupiSaverApp"):
        self.app = app
        self.win: Optional[tk.Toplevel] = None
        self.buttons_frame: Optional[ttk.Frame] = None
        self.follow_frame: Optional[ttk.Frame] = None
        self.status_var = tk.StringVar(value="")
        self.input_var = tk.StringVar(value="")
        self.active_var = tk.BooleanVar(value=False)
        self.assistant_var = tk.BooleanVar(value=False)
        self.hud_var = tk.BooleanVar(value=False)
        self.head_up_var = tk.BooleanVar(value=False)
        self.more_button: Optional[ttk.Button] = None

    def show(self) -> None:
        if self.win is not None and self.win.winfo_exists():
            self.win.deiconify()
            self.win.lift()
            self.render()
            return
        self.win = tk.Toplevel(self.app)
        self.win.title("YupiSaver")
        self.win.attributes("-topmost", True)
        self.win.configure(bg=BG)
        size = {"small": 310, "medium": 380, "large": 460}.get(self.app.settings.get("panel_size", "large"), 460)
        self.win.geometry(f"{size}x640+{max(0, self.app.winfo_screenwidth() - size - 32)}+80")
        self.win.minsize(300, 500)
        self.win.protocol("WM_DELETE_WINDOW", self.hide)
        frame = ttk.Frame(self.win, padding=12)
        frame.pack(fill="both", expand=True)
        top = ttk.Frame(frame)
        top.pack(fill="x")
        ttk.Label(top, text="🎧 YupiSaver", style="Title.TLabel").pack(side="left")
        ttk.Button(top, text="🔄", width=3, command=self.app.regenerate_buttons).pack(side="right")
        ttk.Button(top, text="✕", width=3, command=self.hide).pack(side="right", padx=(5, 0))
        ttk.Label(frame, text="快速開關（不用打開設定頁）", style="Section.TLabel").pack(anchor="w", pady=(8, 2))
        quick = ttk.Frame(frame)
        quick.pack(fill="x")
        self.active_var.set(bool(self.app.settings.get("active_comfort_enabled", False)))
        self.assistant_var.set(bool(self.app.settings.get("active_comfort_launch_assistant", False)))
        self.hud_var.set(bool(self.app.settings.get("hud_enabled", True)))
        self.head_up_var.set(bool(self.app.settings.get("head_up_enabled", False)))
        ttk.Checkbutton(quick, text="主動安慰", variable=self.active_var, command=self.app.toggle_active_comfort).pack(side="left")
        ttk.Checkbutton(quick, text="安慰後開 ChatGPT", variable=self.assistant_var, command=self.app.toggle_assistant).pack(side="left", padx=(8, 0))
        hud_quick = ttk.Frame(frame)
        hud_quick.pack(fill="x", pady=(4, 0))
        ttk.Checkbutton(hud_quick, text="抬頭顯示", variable=self.hud_var, command=lambda: self.app.set_hud_enabled(self.hud_var.get())).pack(side="left")
        ttk.Checkbutton(hud_quick, text="定時抬頭", variable=self.head_up_var, command=lambda: self.app.set_head_up_enabled(self.head_up_var.get())).pack(side="left", padx=(8, 0))
        ttk.Button(hud_quick, text="立即", width=5, command=self.app.test_head_up).pack(side="right")

        self.buttons_frame = ttk.Frame(frame)
        self.buttons_frame.pack(fill="x", pady=(8, 0))
        input_row = ttk.Frame(frame)
        input_row.pack(fill="x", pady=(10, 0))
        ttk.Entry(input_row, textvariable=self.input_var).pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(input_row, text="送出", command=self.send).pack(side="right")
        action_row = ttk.Frame(frame)
        action_row.pack(fill="x", pady=(8, 0))
        ttk.Button(action_row, text="🙏 感恩", command=self.app.gratitude).pack(side="left", fill="x", expand=True)
        ttk.Button(action_row, text="🚀 推動力", command=self.app.nudge_action).pack(side="left", fill="x", expand=True, padx=5)
        ttk.Button(action_row, text="⏰ 定時推動", command=self.app.timed_action).pack(side="left", fill="x", expand=True)
        self.follow_frame = ttk.Frame(frame)
        self.follow_frame.pack(fill="x", pady=(8, 0))
        self.more_button = ttk.Button(frame, text="💛 再安慰多啲", command=self.app.continue_comfort)
        self.more_button.pack(fill="x", pady=(5, 0))
        self.more_button.pack_forget()
        ttk.Label(frame, textvariable=self.status_var, style="Status.TLabel", wraplength=size - 28, justify="left").pack(fill="x", pady=(8, 0))
        self.render()

    def hide(self) -> None:
        if self.win is not None:
            try:
                self.win.destroy()
            except tk.TclError:
                pass
            self.win = None

    def send(self) -> None:
        text = self.input_var.get().strip()
        if text:
            self.input_var.set("")
            self.app.record(text, "text")

    def set_status(self, text: str) -> None:
        self.status_var.set(text)

    def render(self) -> None:
        if not self.buttons_frame:
            return
        for child in self.buttons_frame.winfo_children():
            child.destroy()
        buttons = self.app.current_buttons or ["…"] * self.app.button_count()
        for row_start in range(0, len(buttons), 2):
            row = ttk.Frame(self.buttons_frame)
            row.pack(fill="x", pady=2)
            for index in range(row_start, min(row_start + 2, len(buttons))):
                label = buttons[index]
                ttk.Button(row, text=label, command=lambda value=label: self.app.record(value, "button")).pack(side="left", fill="x", expand=True, padx=(0 if index == row_start else 5, 0))

    def render_followup(self, type_name: str, button_text: str) -> None:
        if not self.follow_frame:
            return
        for child in self.follow_frame.winfo_children():
            child.destroy()
        if self.more_button:
            self.more_button.pack_forget()
        options: list[tuple[str, Callable[[], None]]] = []
        if type_name == "critic":
            options = [("🔨 駁返佢", lambda: self.app.counter_argument(button_text)), ("📋 記低就算", lambda: self.app.record_only(button_text))]
        elif type_name == "stuck":
            options = [("✅ 郁咗一下", lambda: self.app.action_done(button_text)), ("💤 唔郁都得", lambda: self.app.action_later(button_text))]
        elif type_name in ("feeling", "guardian", "worth"):
            options = [("💬 講多啲", lambda: self.app.explore_more(button_text)), ("📋 記低就算", lambda: self.app.record_only(button_text))]
        if options:
            for label, action in options:
                ttk.Button(self.follow_frame, text=label, command=action).pack(side="left", fill="x", expand=True, padx=(0 if not self.follow_frame.winfo_children() else 5, 0))
            if self.more_button:
                self.more_button.pack(fill="x", pady=(5, 0))

    def clear_followup(self) -> None:
        if self.follow_frame:
            for child in self.follow_frame.winfo_children():
                child.destroy()
        if self.more_button:
            self.more_button.pack_forget()


class YupiSaverApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("YupiSaver for Windows")
        self.geometry("960x900")
        self.minsize(780, 680)
        self.configure(bg=BG)
        self.protocol("WM_DELETE_WINDOW", self.on_close)

        self.settings = SettingsStore()
        self.log = DebugLog()
        self.db = TranslatorDb()
        self.ai = AIEngine(self.settings, self.db, self.log)
        self.tts = TTSPlayer(self.settings, self.log, self.ai.resolve_emotion)
        self.tts.fallback_listener = lambda engine, reason: self.status(f"{engine} 語音連唔到，已用系統聲：{reason[:80]}")
        self.executor = ThreadPoolExecutor(max_workers=5, thread_name_prefix="YupiWin")
        self.current_buttons: list[str] = []
        self.last_topic = ""
        self.last_replies: list[str] = []
        self.pending_nudge = False
        self.pending_timed = False
        self.pending_gratitude = False
        self._automation_running = False

        self.status_var = tk.StringVar(value="準備中…")
        self.stats_var = tk.StringVar(value="")
        self.summary_var = tk.StringVar(value="")
        self.voice_design_var = tk.StringVar(value="")
        self.active_interval_var = tk.StringVar(value=str(self.settings.get("active_comfort_interval", 20)))
        self.active_var = tk.BooleanVar(value=bool(self.settings.get("active_comfort_enabled", False)))
        self.assistant_var = tk.BooleanVar(value=bool(self.settings.get("active_comfort_launch_assistant", False)))
        self.hud_var = tk.BooleanVar(value=bool(self.settings.get("hud_enabled", True)))
        self.head_up_var = tk.BooleanVar(value=bool(self.settings.get("head_up_enabled", False)))
        self.head_up_interval_var = tk.StringVar(value=str(self.settings.get("head_up_interval", 30)))
        self.head_up_jitter_var = tk.StringVar(value=str(self.settings.get("head_up_jitter", 5)))
        self.head_up_display_var = tk.StringVar(value=str(self.settings.get("head_up_display_seconds", 5)))
        self.head_up_font_var = tk.StringVar(value=str(self.settings.get("head_up_font_scale", 18)))
        self.head_up_voice_var = tk.BooleanVar(value=bool(self.settings.get("head_up_voice_enabled", False)))
        self.summary_enabled_var = tk.BooleanVar(value=bool(self.settings.get("summary_enabled", True)))
        self.narration_var = tk.BooleanVar(value=bool(self.settings.get("narration_enabled", False)))
        self.thinking_var = tk.BooleanVar(value=bool(self.settings.get("thinking_enabled", True)))
        self.ai_key_var = tk.StringVar(value=str(self.settings.get("api_key", "")))
        self.model_var = tk.StringVar(value=str(self.settings.get("model", "deepseek-chat")))
        self.base_var = tk.StringVar(value=str(self.settings.get("base_url", "https://api.deepseek.com")))
        self.debug_token_var = tk.StringVar(value=str(self.settings.get("debug_token", "")))
        self.debug_chat_var = tk.StringVar(value=str(self.settings.get("debug_chat_id", "")))
        self.voice_engine_var = tk.StringVar(value=self.settings.get("voice_engine", "edge-hk"))
        self.edge_voice_var = tk.StringVar(value=self.settings.get("edge_voice", "hk-f"))
        self.edge_style_var = tk.StringVar(value=self.settings.get("edge_style", "friendly"))
        self.minimax_key_var = tk.StringVar(value=self.settings.get("minimax_key", ""))
        self.minimax_voice_var = tk.StringVar(value=self.settings.get("minimax_voice", TTSPlayer.MINIMAX_VOICES[0]))
        self.minimax_model_var = tk.StringVar(value=self.settings.get("minimax_model", TTSPlayer.MINIMAX_MODELS[0]))
        self.minimax_emotion_var = tk.StringVar(value=self.settings.get("minimax_emotion_mode", "auto"))
        self.rate_var = tk.StringVar(value=str(self.settings.get("voice_rate", "0")))
        self.button_count_var = tk.IntVar(value=int(self.settings.get("button_count", 4) or 4))

        self._configure_styles()
        self._build_main()
        self.nudge_popup = NudgePopup(self)
        self.overlay = OverlayPanel(self)
        self.bubble = FloatingBubble(self)
        self.hud = HeadsUpDisplay(self)
        self.scheduler = TimedNudgeScheduler(lambda task: self.after(0, lambda: self.timer_due(task)), self.log)
        self.head_up_scheduler = HeadUpScheduler(self.settings, lambda: self.after(0, self.head_up_tick), self.log)
        self.active_controller = ActiveComfortController(
            self.settings,
            self.db,
            self.ai,
            self.tts,
            self.status,
            self.after_comfort,
            self.log,
            on_response=lambda text: self.after(0, lambda: self.show_hud(text, "💛 主動安慰")),
        )
        self.nudge = NudgeController(
            self.ai,
            self.tts,
            self.status,
            lambda steps, timed: self.after(0, lambda: self.nudge_ready(steps, timed)),
            lambda index, total, step, phrase: self.after(0, lambda: self.nudge_phrase(index, total, step, phrase)),
            lambda task, done: self.after(0, lambda: self.nudge_end(task, done)),
            self.log,
        )
        self.refresh_voice_options()
        self.refresh_view()
        if bool(self.settings.get("floating_enabled", True)):
            self.bubble.show()
        self.after(1000, self._start_background_services)
        self.after(30_000, self.check_automations)

    def _configure_styles(self) -> None:
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TFrame", background=BG)
        style.configure("TLabel", background=BG, foreground=TEXT, font=("Segoe UI", 10))
        style.configure("Title.TLabel", background=BG, foreground=GREEN, font=("Segoe UI", 20, "bold"))
        style.configure("Subtitle.TLabel", background=BG, foreground=MUTED, font=("Segoe UI", 10))
        style.configure("Section.TLabel", background=BG, foreground=GREEN, font=("Segoe UI", 11, "bold"))
        style.configure("Body.TLabel", background=BG, foreground=TEXT, font=("Segoe UI", 11))
        style.configure("Muted.TLabel", background=BG, foreground=MUTED, font=("Segoe UI", 9))
        style.configure("Status.TLabel", background=BG, foreground=TEXT, font=("Segoe UI", 10))
        style.configure("TButton", padding=(10, 7), font=("Segoe UI", 10))
        style.configure("Accent.TButton", background=MINT, foreground="white", padding=(12, 8), font=("Segoe UI", 10, "bold"))
        style.map("Accent.TButton", background=[("active", GREEN)])
        style.configure("TCheckbutton", background=BG, foreground=TEXT)
        style.configure("TEntry", padding=5)
        style.configure("TCombobox", padding=4)

    def _build_main(self) -> None:
        outer = ttk.Frame(self)
        outer.pack(fill="both", expand=True)
        canvas = tk.Canvas(outer, background=BG, highlightthickness=0)
        scrollbar = ttk.Scrollbar(outer, orient="vertical", command=canvas.yview)
        body = ttk.Frame(canvas)
        body.columnconfigure(0, weight=1)
        window_id = canvas.create_window((0, 0), window=body, anchor="nw")
        canvas.configure(yscrollcommand=scrollbar.set)
        canvas.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        body.bind("<Configure>", lambda _e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(window_id, width=e.width))
        canvas.bind_all("<MouseWheel>", lambda e: canvas.yview_scroll(int(-e.delta / 120), "units"))

        header = ttk.Frame(body, padding=(24, 20, 24, 8))
        header.grid(row=0, column=0, sticky="ew")
        header.columnconfigure(0, weight=1)
        ttk.Label(header, text="YupiSaver for Windows", style="Title.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(header, text="翻譯官一出聲，就捉住佢。全部記錄只存本機。\nWindows 版不包含藍牙保活。", style="Subtitle.TLabel").grid(row=1, column=0, sticky="w", pady=(4, 0))
        actions = ttk.Frame(header)
        actions.grid(row=0, column=1, rowspan=2, sticky="e")
        ttk.Button(actions, text="— 最小化", command=self.minimize_window).pack(side="left")
        ttk.Button(actions, text="儲存設定", style="Accent.TButton", command=self.save_settings).pack(side="left")
        ttk.Button(actions, text="測試 AI", command=self.test_ai).pack(side="left", padx=6)
        ttk.Button(actions, text="測試語音", command=self.test_voice).pack(side="left")

        status_box = ttk.Frame(body, padding=(24, 0, 24, 8))
        status_box.grid(row=1, column=0, sticky="ew")
        status_box.columnconfigure(0, weight=1)
        ttk.Label(status_box, textvariable=self.status_var, style="Status.TLabel", wraplength=760).grid(row=0, column=0, sticky="w")
        ttk.Button(status_box, text="開啟懸浮按鈕", command=self.show_floating).grid(row=0, column=1, padx=(8, 0))
        ttk.Button(status_box, text="關閉懸浮按鈕", command=self.hide_floating).grid(row=0, column=2, padx=(6, 0))

        content = ttk.Frame(body, padding=(24, 0, 24, 24))
        content.grid(row=2, column=0, sticky="ew")
        content.columnconfigure(0, weight=1)

        self._build_ai_section(content)
        self._build_telegram_section(content)
        self._build_voice_section(content)
        self._build_behavior_section(content)
        self._build_records_section(content)

    def minimize_window(self) -> None:
        """只收起主設定視窗，懸浮按鈕和背景服務繼續工作。"""
        self.iconify()

    @staticmethod
    def _entry(parent, label: str, variable: tk.Variable, row: int, secret: bool = False, width: int = 50) -> ttk.Entry:
        ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", pady=4)
        entry = ttk.Entry(parent, textvariable=variable, width=width, show="•" if secret else "")
        entry.grid(row=row, column=1, sticky="ew", pady=4, padx=(10, 0))
        return entry

    def _build_ai_section(self, parent) -> None:
        box = Collapsible(parent, "⚙ AI 設定（API Key、模型、思考模式）", False)
        box.grid(row=0, column=0, sticky="ew")
        box.body.columnconfigure(1, weight=1)
        self._entry(box.body, "DeepSeek/兼容 API Key", self.ai_key_var, 0, True)
        self._entry(box.body, "模型", self.model_var, 1)
        self._entry(box.body, "Base URL", self.base_var, 2)
        ttk.Checkbutton(box.body, text="思考模式（較穩但較慢）", variable=self.thinking_var).grid(row=3, column=1, sticky="w", pady=4)
        ttk.Label(box.body, text="可以填任何 OpenAI chat/completions 兼容接口。", style="Muted.TLabel").grid(row=4, column=1, sticky="w")

    def _build_telegram_section(self, parent) -> None:
        box = Collapsible(parent, "🛠 Telegram 調試設定", False)
        box.grid(row=1, column=0, sticky="ew")
        box.body.columnconfigure(1, weight=1)
        self._entry(box.body, "Bot Token", self.debug_token_var, 0, True)
        self._entry(box.body, "Chat ID", self.debug_chat_var, 1)
        row = ttk.Frame(box.body)
        row.grid(row=2, column=1, sticky="w", pady=(5, 0))
        ttk.Button(row, text="睇 Log", command=self.show_log).pack(side="left")
        ttk.Button(row, text="發 Log 去 Telegram", command=self.send_log).pack(side="left", padx=6)

    def _build_voice_section(self, parent) -> None:
        box = Collapsible(parent, "🔊 語音設定（Edge / MiniMax / 系統聲）", False)
        box.grid(row=2, column=0, sticky="ew")
        box.body.columnconfigure(1, weight=1)
        ttk.Label(box.body, text="語音引擎").grid(row=0, column=0, sticky="w", pady=4)
        self.voice_engine_combo = ttk.Combobox(box.body, textvariable=self.voice_engine_var, state="readonly", values=("edge-hk", "edge-cn", "minimax", "system"))
        self.voice_engine_combo.grid(row=0, column=1, sticky="ew", pady=4, padx=(10, 0))
        self.voice_engine_combo.bind("<<ComboboxSelected>>", lambda _e: self.refresh_voice_options())
        ttk.Label(box.body, text="Edge 聲線").grid(row=1, column=0, sticky="w", pady=4)
        self.edge_voice_combo = ttk.Combobox(box.body, textvariable=self.edge_voice_var, state="readonly", values=("hk-f", "hk-m", "cn"))
        self.edge_voice_combo.grid(row=1, column=1, sticky="ew", pady=4, padx=(10, 0))
        ttk.Label(box.body, text="Edge 風格").grid(row=2, column=0, sticky="w", pady=4)
        self.edge_style_combo = ttk.Combobox(box.body, textvariable=self.edge_style_var, state="readonly", values=("friendly", "", "cheerful", "serious"))
        self.edge_style_combo.grid(row=2, column=1, sticky="ew", pady=4, padx=(10, 0))
        self._entry(box.body, "MiniMax API Key", self.minimax_key_var, 3, True)
        ttk.Label(box.body, text="MiniMax 聲線").grid(row=4, column=0, sticky="w", pady=4)
        self.minimax_voice_combo = ttk.Combobox(box.body, textvariable=self.minimax_voice_var, state="readonly")
        self.minimax_voice_combo.grid(row=4, column=1, sticky="ew", pady=4, padx=(10, 0))
        ttk.Label(box.body, text="MiniMax 模型").grid(row=5, column=0, sticky="w", pady=4)
        self.minimax_model_combo = ttk.Combobox(box.body, textvariable=self.minimax_model_var, state="readonly", values=TTSPlayer.MINIMAX_MODELS)
        self.minimax_model_combo.grid(row=5, column=1, sticky="ew", pady=4, padx=(10, 0))
        ttk.Label(box.body, text="情感模式").grid(row=6, column=0, sticky="w", pady=4)
        self.minimax_emotion_combo = ttk.Combobox(box.body, textvariable=self.minimax_emotion_var, state="readonly", values=TTSPlayer.EMOTIONS)
        self.minimax_emotion_combo.grid(row=6, column=1, sticky="ew", pady=4, padx=(10, 0))
        self._entry(box.body, "語速（-10 / 0 / 20 / 40）", self.rate_var, 7)
        ttk.Button(box.body, text="設計 MiniMax 粵語聲線", command=self.design_voice).grid(row=8, column=1, sticky="w", pady=(8, 0))
        ttk.Label(box.body, textvariable=self.voice_design_var, style="Muted.TLabel", wraplength=620).grid(row=9, column=1, sticky="w")

    def _build_behavior_section(self, parent) -> None:
        box = Collapsible(parent, "🎛 行為與自動化", True)
        box.grid(row=3, column=0, sticky="ew")
        box.body.columnconfigure(1, weight=1)
        ttk.Label(box.body, text="按鈕數量").grid(row=0, column=0, sticky="w", pady=4)
        count_row = ttk.Frame(box.body)
        count_row.grid(row=0, column=1, sticky="w")
        for value in (4, 8, 10):
            ttk.Radiobutton(count_row, text=f"{value} 個", value=value, variable=self.button_count_var).pack(side="left", padx=(0, 16))
        ttk.Checkbutton(box.body, text="旁白模式（用「佢」描述你嘅一刻）", variable=self.narration_var).grid(row=1, column=1, sticky="w", pady=4)
        ttk.Checkbutton(box.body, text="每日 00:01 總結、每日 20:00 提醒、每週一回顧", variable=self.summary_enabled_var).grid(row=2, column=1, sticky="w", pady=4)
        ttk.Separator(box.body).grid(row=3, column=0, columnspan=2, sticky="ew", pady=8)
        ttk.Checkbutton(box.body, text="開啟主動安慰", variable=self.active_var, command=self.toggle_active_comfort).grid(row=4, column=1, sticky="w", pady=4)
        ttk.Label(box.body, text="間隔（0-300 分鐘）").grid(row=5, column=0, sticky="w", pady=4)
        interval_row = ttk.Frame(box.body)
        interval_row.grid(row=5, column=1, sticky="w")
        ttk.Entry(interval_row, textvariable=self.active_interval_var, width=9).pack(side="left")
        ttk.Button(interval_row, text="套用", command=self.apply_interval).pack(side="left", padx=6)
        ttk.Label(interval_row, text="0 會採用安全最短 1 分鐘", style="Muted.TLabel").pack(side="left")
        ttk.Checkbutton(box.body, text="安慰語音播放完後開啟 ChatGPT 網頁語音", variable=self.assistant_var, command=self.toggle_assistant).grid(row=6, column=1, sticky="w", pady=4)
        ttk.Separator(box.body).grid(row=7, column=0, columnspan=2, sticky="ew", pady=8)
        ttk.Checkbutton(box.body, text="開啟抬頭顯示（透明全屏大字，不會播放語音）", variable=self.hud_var, command=lambda: self.set_hud_enabled(self.hud_var.get())).grid(row=8, column=1, sticky="w", pady=4)
        ttk.Checkbutton(box.body, text="定時抬頭提醒（隨機間隔，避免太機械）", variable=self.head_up_var, command=lambda: self.set_head_up_enabled(self.head_up_var.get())).grid(row=9, column=1, sticky="w", pady=4)
        ttk.Label(box.body, text="抬頭間隔（0-300 分鐘）").grid(row=10, column=0, sticky="w", pady=4)
        head_interval_row = ttk.Frame(box.body)
        head_interval_row.grid(row=10, column=1, sticky="w")
        ttk.Entry(head_interval_row, textvariable=self.head_up_interval_var, width=9).pack(side="left")
        ttk.Label(head_interval_row, text="0 = 安全最短 1 分鐘", style="Muted.TLabel").pack(side="left", padx=(6, 0))
        ttk.Label(box.body, text="隨機浮動（0-30 分鐘）").grid(row=11, column=0, sticky="w", pady=4)
        ttk.Entry(box.body, textvariable=self.head_up_jitter_var, width=9).grid(row=11, column=1, sticky="w", pady=4)
        ttk.Label(box.body, text="顯示秒數（2-30）").grid(row=12, column=0, sticky="w", pady=4)
        ttk.Entry(box.body, textvariable=self.head_up_display_var, width=9).grid(row=12, column=1, sticky="w", pady=4)
        ttk.Label(box.body, text="字號（屏幕高度 %）").grid(row=13, column=0, sticky="w", pady=4)
        ttk.Entry(box.body, textvariable=self.head_up_font_var, width=9).grid(row=13, column=1, sticky="w", pady=4)
        ttk.Checkbutton(box.body, text="定時抬頭時也播放語音", variable=self.head_up_voice_var).grid(row=14, column=1, sticky="w", pady=4)
        ttk.Label(box.body, text="浮窗大小").grid(row=15, column=0, sticky="w", pady=4)
        self.panel_size_var = tk.StringVar(value=self.settings.get("panel_size", "large"))
        ttk.Combobox(box.body, textvariable=self.panel_size_var, state="readonly", values=("small", "medium", "large"), width=15).grid(row=15, column=1, sticky="w", pady=4)
        ttk.Label(box.body, text="抬頭顯示會在 AI 回覆、推動力、定時提醒和主動安慰時出現；按一下或 Esc 可提前關閉。", style="Muted.TLabel", wraplength=680).grid(row=16, column=1, sticky="w", pady=(5, 0))

    def _build_records_section(self, parent) -> None:
        box = Collapsible(parent, "📊 統計、每日總結與最近記錄", True)
        box.grid(row=4, column=0, sticky="ew")
        box.body.columnconfigure(0, weight=1)
        ttk.Label(box.body, textvariable=self.stats_var, style="Body.TLabel", wraplength=700, justify="left").grid(row=0, column=0, sticky="w")
        ttk.Label(box.body, text="最新總結", style="Section.TLabel").grid(row=1, column=0, sticky="w", pady=(12, 3))
        ttk.Label(box.body, textvariable=self.summary_var, style="Body.TLabel", wraplength=700, justify="left").grid(row=2, column=0, sticky="w")
        ttk.Label(box.body, text="最近記錄", style="Section.TLabel").grid(row=3, column=0, sticky="w", pady=(12, 3))
        self.records_text = tk.Text(box.body, height=12, wrap="word", bg=CARD, fg=TEXT, relief="flat", padx=8, pady=8)
        self.records_text.grid(row=4, column=0, sticky="ew")
        self.records_text.configure(state="disabled")

    def _start_background_services(self) -> None:
        if self.active_var.get():
            self.active_controller.start()
        self.head_up_scheduler.sync()
        self.regenerate_buttons()

    def status(self, text: str) -> None:
        def update() -> None:
            try:
                self.status_var.set(text)
                if getattr(self, "overlay", None) and self.overlay.win and self.overlay.win.winfo_exists():
                    self.overlay.set_status(text)
            except tk.TclError:
                pass
        try:
            self.after(0, update)
        except tk.TclError:
            pass

    @staticmethod
    def _safe_int(value: str, fallback: int, low: int, high: int) -> int:
        try:
            return max(low, min(high, int(str(value).strip())))
        except (TypeError, ValueError):
            return fallback

    def save_settings(self) -> None:
        engine = self.voice_engine_var.get().strip() or "system"
        try:
            interval = max(0, min(300, int(self.active_interval_var.get().strip())))
        except ValueError:
            interval = 20
            self.active_interval_var.set("20")
        head_interval = self._safe_int(self.head_up_interval_var.get(), 30, 0, 300)
        head_jitter = self._safe_int(self.head_up_jitter_var.get(), 5, 0, 30)
        head_display = self._safe_int(self.head_up_display_var.get(), 5, 2, 30)
        head_font = self._safe_int(self.head_up_font_var.get(), 18, 8, 40)
        self.head_up_interval_var.set(str(head_interval))
        self.head_up_jitter_var.set(str(head_jitter))
        self.head_up_display_var.set(str(head_display))
        self.head_up_font_var.set(str(head_font))
        count = self.button_count_var.get()
        if count not in (4, 8, 10):
            count = 4
            self.button_count_var.set(4)
        self.settings.update({
            "api_key": self.ai_key_var.get().strip(),
            "model": self.model_var.get().strip() or "deepseek-chat",
            "base_url": self.base_var.get().strip() or "https://api.deepseek.com",
            "debug_token": self.debug_token_var.get().strip(),
            "debug_chat_id": self.debug_chat_var.get().strip(),
            "voice_engine": engine,
            "edge_voice": self.edge_voice_var.get().strip() or "hk-f",
            "edge_style": self.edge_style_var.get(),
            "minimax_key": self.minimax_key_var.get().strip(),
            "minimax_voice": self.minimax_voice_var.get().strip() or TTSPlayer.MINIMAX_VOICES[0],
            "minimax_model": self.minimax_model_var.get().strip() or TTSPlayer.MINIMAX_MODELS[0],
            "minimax_emotion_mode": self.minimax_emotion_var.get().strip() or "auto",
            "voice_rate": self.rate_var.get().strip() or "0",
            "button_count": count,
            "narration_enabled": self.narration_var.get(),
            "thinking_enabled": self.thinking_var.get(),
            "summary_enabled": self.summary_enabled_var.get(),
            "active_comfort_interval": interval,
            "active_comfort_enabled": self.active_var.get(),
            "active_comfort_launch_assistant": self.assistant_var.get(),
            "hud_enabled": self.hud_var.get(),
            "head_up_enabled": self.head_up_var.get(),
            "head_up_interval": head_interval,
            "head_up_jitter": head_jitter,
            "head_up_display_seconds": head_display,
            "head_up_font_scale": head_font,
            "head_up_voice_enabled": self.head_up_voice_var.get(),
            "panel_size": self.panel_size_var.get(),
            "floating_enabled": self.bubble.win is not None,
        })
        self.active_controller.set_interval(interval)
        self.head_up_scheduler.sync()
        self.status("設定已儲存 ✅")
        self.refresh_view()

    def refresh_voice_options(self) -> None:
        voices = list(TTSPlayer.MINIMAX_VOICES)
        labels = list(TTSPlayer.MINIMAX_LABELS)
        for item in self.settings.get("minimax_designed_voices", []) or []:
            if isinstance(item, dict) and item.get("id") and item["id"] not in voices:
                voices.append(item["id"])
                labels.append("自訂 · " + str(item.get("name") or "聲線"))
        if hasattr(self, "minimax_voice_combo"):
            self.minimax_voice_combo.configure(values=voices)
        self._minimax_voice_ids = voices
        self._minimax_voice_labels = labels

    def refresh_view(self) -> None:
        self.refresh_voice_options()
        if hasattr(self, "records_text"):
            self.records_text.configure(state="normal")
            self.records_text.delete("1.0", "end")
            self.records_text.insert("1.0", self.db.dump())
            self.records_text.configure(state="disabled")
        counts = self.db.channel_counts(7)
        done = counts.get("行動完成", 0) + counts.get("推動完成", 0)
        skipped = counts.get("行動未做", 0) + counts.get("推動取消", 0)
        rate = f"\n行動完成率：{round(done * 100 / (done + skipped))}%（完成 {done}/{done + skipped}）" if done + skipped else ""
        self.stats_var.set(
            f"🔥 連續使用 {self.db.streak_days()} 日 · 今日捕捉 {self.db.count_today()} 次 · 總共 {self.db.count_all()} 次\n"
            f"近 7 日：翻譯官 {counts.get('翻譯官', 0)} · 真我 {counts.get('真我', 0)} · 按鈕 {counts.get('按鈕', 0)} · 反駁 {counts.get('反駁', 0)}{rate}"
        )
        self.summary_var.set(self.db.latest_summary() or "（未有總結——開啟每日總結後會在每日 00:01 生成）")

    def button_count(self) -> int:
        value = self.button_count_var.get()
        return value if value in (4, 8, 10) else 4

    def test_ai(self) -> None:
        self.save_settings()
        self.status("測試 AI 連線中…")
        self.run_async(lambda: self.ai.one_line("你係 YupiSaver 測試助手。只用廣東話簡短回應，不超過 30 字。", "請回覆測試成功。"), lambda result: self.status(f"AI 測試：{result}"))

    def test_voice(self) -> None:
        self.save_settings()
        self.tts.speak("你好，我係 YupiSaver for Windows。今日想試下呢把聲得唔得。")
        self.status("正在播放試聽…")

    def run_async(self, work: Callable[[], Any], done: Callable[[Any], None]) -> None:
        future = self.executor.submit(work)

        def finish(fut) -> None:
            try:
                value = fut.result()
                self.after(0, lambda: done(value))
            except Exception as exc:
                error_text = f"操作失敗：{exc}"
                self.after(0, lambda text=error_text: self.status(text))

        future.add_done_callback(finish)

    def show_log(self) -> None:
        win = tk.Toplevel(self)
        win.title("YupiSaver · AI Log")
        win.geometry("760x500")
        text = tk.Text(win, wrap="word", bg="#10231C", fg="#D7F3E6")
        text.pack(fill="both", expand=True)
        text.insert("1.0", self.log.dump())
        text.configure(state="disabled")

    def send_log(self) -> None:
        self.save_settings()
        self.status("發送緊 AI Log 去 Telegram…")
        token, chat = self.settings.get("debug_token", ""), self.settings.get("debug_chat_id", "")
        self.run_async(lambda: self.log.send_to_telegram(token, chat), lambda _result: self.status("AI Log 已發送到 Telegram ✅"))

    def design_voice(self) -> None:
        self.save_settings()
        prompt = simpledialog.askstring("MiniMax Voice Design", "聲線描述（最多 500 字）\n例如：香港年輕女生，甜美自然、有親和力，不要播音腔", parent=self)
        if prompt is None:
            return
        preview = simpledialog.askstring("MiniMax Voice Design", "粵語試聽文字（最多 500 字）", initialvalue="你好呀，今日過得點？唔使心急，慢慢講畀我聽。", parent=self)
        if preview is None:
            return
        self.voice_design_var.set("正在設計聲線，通常需要十幾秒…")
        self.run_async(lambda: self.tts.design_voice(prompt, preview), lambda voice_id: self._voice_design_done(voice_id, preview, prompt))

    def _voice_design_done(self, voice_id: str, preview: str, prompt: str) -> None:
        saved = list(self.settings.get("minimax_designed_voices", []) or [])
        if not any(isinstance(x, dict) and x.get("id") == voice_id for x in saved):
            saved.append({"id": voice_id, "name": prompt.replace("\n", " ").strip()[:18]})
        self.settings.update({"minimax_designed_voices": saved, "minimax_voice": voice_id, "voice_engine": "minimax"})
        self.minimax_voice_var.set(voice_id)
        self.voice_engine_var.set("minimax")
        self.refresh_voice_options()
        self.voice_design_var.set(f"已生成並啟用 ✅ {voice_id}，正在播放試聽")
        self.tts.speak(preview)

    def show_floating(self) -> None:
        self.settings.update({"floating_enabled": True})
        self.bubble.show()
        self.status("懸浮按鈕已開啟")

    def hide_floating(self) -> None:
        self.settings.update({"floating_enabled": False})
        self.overlay.hide()
        self.bubble.hide()
        self.status("懸浮按鈕已關閉")

    def toggle_overlay(self) -> None:
        if self.overlay.win is not None and self.overlay.win.winfo_exists():
            self.overlay.hide()
        else:
            self.overlay.show()

    def record(self, text: str, source: str) -> None:
        text = (text or "").strip()
        if not text:
            return
        self.last_topic = text
        self.last_replies.clear()
        if self.pending_gratitude and source != "button":
            self.pending_gratitude = False
            self.handle_gratitude(text)
            return
        parsed = TimedNudgeScheduler.parse(text) if source != "button" else None
        if parsed:
            self.pending_timed = False
            self.pending_nudge = False
            self.schedule_timed(parsed[0], parsed[1])
            return
        if self.pending_timed and source != "button":
            self.status("⏰ 未聽到延遲時間——例如「30分鐘後提醒我出去食飯」")
            self.tts.speak("請連埋時間一齊講，例如三十分鐘後提醒我出去食飯。")
            return
        if self.pending_nudge and source != "button":
            self.pending_nudge = False
            self.start_nudge(text)
            return
        self.bubble.flash("✓")
        channel = "按鈕" if source == "button" else "文字"
        self.db.insert(channel, text, source)
        if source == "button":
            self.settings.update({"last_button": text}, save=True)
        self.status("諗緊點回應…")
        self.run_async(lambda: self.ai.respond(text), lambda response: self.handle_response(response, text, channel))

    def handle_response(self, response: AIEngine.Response, text: str, channel: str) -> None:
        self.last_replies.append(response.reply)
        self.last_replies = self.last_replies[-6:]
        self.tts.speak(response.reply, response.emotion, response.tag)
        self.show_hud(response.reply, "YupiSaver")
        self.status(f"已記低（{channel}）：「{text}」｜{response.reply}")
        if response.buttons and len(response.buttons) == self.button_count():
            self.current_buttons = response.buttons
            if self.overlay.win and self.overlay.win.winfo_exists():
                self.overlay.render()
        if self.overlay.win and self.overlay.win.winfo_exists():
            self.overlay.render_followup(response.type, text)
        self.refresh_view()

    def regenerate_buttons(self) -> None:
        self.status("諗緊新按鈕…")
        self.run_async(self.ai.generate_buttons, self._buttons_done)

    def _buttons_done(self, values: list[str]) -> None:
        self.current_buttons = values
        if self.overlay.win and self.overlay.win.winfo_exists():
            self.overlay.render()
        self.status("按鈕已更新——翻譯官一出聲就撳")

    def gratitude(self) -> None:
        self.pending_nudge = self.pending_timed = False
        value = self.overlay.input_var.get().strip() if self.overlay.win and self.overlay.win.winfo_exists() else ""
        if value:
            self.overlay.input_var.set("")
            self.handle_gratitude(value)
            return
        self.pending_gratitude = True
        self.status("🙏 諗緊點引導你…")
        self.run_async(self.ai.gratitude_prompt, lambda prompt: (self.status(f"🙏 {prompt}（請在輸入框打字講低你擁有嘅嘢）"), self.tts.speak(prompt)))

    def handle_gratitude(self, text: str) -> None:
        self.status("🙏 諗緊點回應你…")
        self.run_async(lambda: self.ai.gratitude_reply(text), lambda reply: self._gratitude_done(text, reply))

    def _gratitude_done(self, text: str, reply: str) -> None:
        self.db.insert("感恩", text, "gratitude")
        self.tts.speak(reply, "happy", self.ai.throttle_tag(self.ai.suggest_tag(reply, "happy")))
        self.show_hud(reply, "🙏 感恩")
        self.status(f"🙏 已記低：「{text}」｜{reply}")
        self.refresh_view()

    def nudge_action(self) -> None:
        self.pending_gratitude = self.pending_timed = False
        value = self.overlay.input_var.get().strip() if self.overlay.win and self.overlay.win.winfo_exists() else ""
        if value:
            self.overlay.input_var.set("")
            self.start_nudge(value)
        else:
            self.pending_nudge = True
            self.status("🚀 請在輸入框打字講低你想做嘅嘢，然後撳送出")
            self.tts.speak("講出你想做嘅事，我會幫你拆成一步一步。")

    def timed_action(self) -> None:
        self.pending_nudge = self.pending_gratitude = False
        value = self.overlay.input_var.get().strip() if self.overlay.win and self.overlay.win.winfo_exists() else ""
        if value:
            parsed = TimedNudgeScheduler.parse(value)
            if parsed:
                self.overlay.input_var.set("")
                self.schedule_timed(parsed[0], parsed[1])
                return
            minutes = simpledialog.askinteger("定時推動", "幾分鐘後提醒？（1-10080）", initialvalue=30, minvalue=1, maxvalue=10080, parent=self)
            if minutes:
                self.overlay.input_var.set("")
                self.schedule_timed(value, minutes * 60_000)
                return
        self.pending_timed = True
        self.status("⏰ 講出時間同任務，例如「30分鐘後提醒我出去食飯」")
        self.tts.speak("想幾耐之後提醒？例如講，三十分鐘後提醒我出去食飯。")

    def schedule_timed(self, task: str, delay_ms: int) -> None:
        try:
            _id, trigger = self.scheduler.schedule(task, delay_ms)
            self.db.insert("定時推動", f"{task}｜{TimedNudgeScheduler.describe(delay_ms)}", "timer")
            self.status(f"⏰ 已安排：{TimedNudgeScheduler.describe(delay_ms)}提醒「{task}」")
            self.tts.speak(f"好，{TimedNudgeScheduler.describe(delay_ms)}提醒你「{task}」。到時我會一步一步陪你做。")
            self.refresh_view()
        except Exception as exc:
            self.status(f"⏰ 定時提醒設定失敗：{exc}")

    def timer_due(self, task: str) -> None:
        self.show_hud(task, "⏰ 時間到")
        self.status(f"⏰ 時間到：「{task}」——開始逐步確認")
        self.tts.speak(f"時間到喇，依家一步一步陪你做：{task}。")
        self.start_nudge(task, timed=True)

    def start_nudge(self, task: str, timed: bool = False) -> None:
        self.nudge.start(task, timed)

    def nudge_ready(self, steps: list[str], timed: bool) -> None:
        self.nudge_popup.show(steps, timed)

    def nudge_phrase(self, index: int, total: int, step: str, phrase: str) -> None:
        self.nudge_popup.update_phrase(index, total, step, phrase)
        self.show_hud(phrase, "⏰ 定時推動" if self.nudge.timed else "🚀 推動力")

    def nudge_end(self, task: str, done: bool) -> None:
        self.nudge_popup.hide()
        self.db.insert("推動完成" if done else "推動取消", task, "nudge")
        self.show_hud("完成咗，做得好！" if done else "冇所謂，想嘅時候再嚟。", "🚀 推動力")
        self.status((f"好嘢！完成咗「{task}」🎉" if done else f"冇所謂，想嘅時候再嚟：「{task}」"))
        self.refresh_view()

    def continue_comfort(self) -> None:
        if not self.last_topic:
            self.status("未有主題——先捕捉一句先")
            return
        self.status("💛 諗緊再安慰你…")
        self.tts.speak("好，我哋繼續傾。")
        history = list(self.last_replies[-3:])
        self.run_async(lambda: self.ai.respond_more(self.last_topic, history), self._more_done)

    def _more_done(self, response: AIEngine.Response) -> None:
        self.last_replies.append(response.reply)
        self.last_replies = self.last_replies[-6:]
        self.tts.speak(response.reply, response.emotion, response.tag)
        self.show_hud(response.reply, "💛 再安慰多啲")
        self.status(f"💛「{self.last_topic}」｜{response.reply}")

    def counter_argument(self, text: str) -> None:
        self.clear_followup()
        self.status("諗緊點駁…")
        self.run_async(lambda: self.ai.one_line(
            "你係 YupiSaver。用廣東話講一句 15-30 字溫柔但有力的反駁，唔好攻擊自己、唔好用你應該。",
            f"用戶想駁返內在批判聲音：「{text}」。真實記錄：{self.ai.records_context(8)}",
        ), lambda reply: self._followup_done("反駁", text, reply))

    def action_done(self, text: str) -> None:
        self.clear_followup()
        self.db.insert("行動完成", text, "followup")
        reply = "好，郁咗一下，話到做到！"
        self.tts.speak(reply)
        self.status(f"郁咗一下：「{text}」")
        self.refresh_view()

    def action_later(self, text: str) -> None:
        self.clear_followup()
        self.db.insert("行動未做", text, "followup")
        reply = "唔郁都得，想郁先郁。"
        self.tts.speak(reply)
        self.status(f"記低咗：「{text}」——唔郁都得")
        self.refresh_view()

    def explore_more(self, text: str) -> None:
        self.clear_followup()
        self.status("諗緊問題…")
        self.run_async(lambda: self.ai.one_line(
            "你係 YupiSaver。用廣東話問一條溫柔、具體、容易答的問題（15-25 字），關於身體感受或當刻情況，不要問為什麼。",
            f"用戶撳咗「{text}」，想探索呢個感受。",
        ), lambda question: (self.tts.speak(question), self.status(f"{question}（請在輸入框打字回答）")))

    def record_only(self, text: str) -> None:
        self.clear_followup()
        self.tts.speak("好，記低咗。")
        self.status(f"已記低：「{text}」")

    def _followup_done(self, kind: str, source: str, reply: str) -> None:
        self.db.insert(kind, source + " → " + reply, "followup")
        self.tts.speak(reply)
        self.show_hud(reply, kind)
        self.status(f"{kind}：「{reply}」")
        self.refresh_view()

    def clear_followup(self) -> None:
        if self.overlay.win and self.overlay.win.winfo_exists():
            self.overlay.clear_followup()

    def toggle_active_comfort(self) -> None:
        enabled = self.active_var.get()
        self.settings.update({"active_comfort_enabled": enabled})
        if enabled:
            self.active_controller.start()
        else:
            self.active_controller.stop()
            self.status("主動安慰已關閉")
        if self.overlay.win and self.overlay.win.winfo_exists():
            self.overlay.active_var.set(enabled)

    def set_hud_enabled(self, enabled: bool) -> None:
        value = bool(enabled)
        self.hud_var.set(value)
        self.settings.update({"hud_enabled": value})
        if self.overlay.win and self.overlay.win.winfo_exists():
            self.overlay.hud_var.set(value)
        if not value:
            self.hud.hide()
        self.status("抬頭顯示已開啟" if value else "抬頭顯示已關閉")

    def set_head_up_enabled(self, enabled: bool) -> None:
        value = bool(enabled)
        self.head_up_var.set(value)
        self.settings.update({"head_up_enabled": value})
        if self.overlay.win and self.overlay.win.winfo_exists():
            self.overlay.head_up_var.set(value)
        self.head_up_scheduler.sync()
        self.status(f"定時抬頭已開啟 · {self.head_up_scheduler.interval_description()}" if value else "定時抬頭已關閉")

    def show_hud(self, text: str, title: str = "抬頭一下") -> None:
        if not self.settings.get("hud_enabled", True):
            return
        try:
            self.hud.show(text, title)
        except tk.TclError:
            pass

    def test_head_up(self) -> None:
        if not self.hud_var.get():
            self.set_hud_enabled(True)
        self.show_hud("透明大字會浮在畫面上，點一下或按 Esc 就會消失。", "⬆ 抬頭顯示測試")

    def head_up_tick(self) -> None:
        if not self.settings.get("head_up_enabled", False):
            return
        message = random.choice(HEAD_UP_MESSAGES)
        self.show_hud(message, "⬆ 抬頭一下")
        if bool(self.settings.get("head_up_voice_enabled", False)):
            self.tts.speak(message)

    def apply_interval(self) -> None:
        try:
            value = max(0, min(300, int(self.active_interval_var.get().strip())))
        except ValueError:
            value = 20
        self.active_interval_var.set(str(value))
        self.active_controller.set_interval(value)
        self.status(f"主動安慰已設定為每 {self.active_controller.interval_description()} 一次")

    def toggle_assistant(self) -> None:
        value = self.assistant_var.get()
        self.settings.update({"active_comfort_launch_assistant": value})
        if self.overlay.win and self.overlay.win.winfo_exists():
            self.overlay.assistant_var.set(value)
        self.status("安慰播放完後會打開 ChatGPT 網頁" if value else "安慰只播放本機語音")

    def after_comfort(self) -> None:
        if not self.settings.get("active_comfort_launch_assistant", False):
            return
        open_chatgpt()
        self.status("安慰已播放完，已打開 ChatGPT 網頁語音")

    def check_automations(self) -> None:
        if self._automation_running:
            return
        self._automation_running = True
        try:
            if self.summary_enabled_var.get():
                now = dt.datetime.now()
                yesterday = (now.date() - dt.timedelta(days=1)).isoformat()
                if now.hour == 0 and now.minute >= 1 and self.settings.get("last_daily_summary", "") != yesterday:
                    self.settings.update({"last_daily_summary": yesterday})
                    records = self.db.between(
                        int(dt.datetime.combine(now.date() - dt.timedelta(days=1), dt.time.min).timestamp() * 1000),
                        int(dt.datetime.combine(now.date(), dt.time.min).timestamp() * 1000),
                    )
                    if records:
                        self.run_async(lambda: self.ai.daily_summary(yesterday, records), self._daily_done)
                today_key = now.date().isoformat()
                if now.hour >= 20 and self.settings.get("last_evening_reminder", "") != today_key:
                    self.settings.update({"last_evening_reminder": today_key})
                    count = self.db.count_today()
                    self.notice("YupiSaver", "今日仲未捕捉過——翻譯官今日有冇出聲？" if count == 0 else f"今日已經捕捉咗 {count} 次，好犀利。")
                monday_key = (now.date() - dt.timedelta(days=now.weekday())).isoformat()
                if now.weekday() == 0 and now.hour == 0 and now.minute >= 5 and self.settings.get("last_weekly_summary", "") != monday_key:
                    self.settings.update({"last_weekly_summary": monday_key})
                    start = dt.datetime.combine(now.date() - dt.timedelta(days=7), dt.time.min)
                    end = dt.datetime.combine(now.date(), dt.time.min)
                    records = self.db.between(int(start.timestamp() * 1000), int(end.timestamp() * 1000))
                    if records:
                        self.run_async(lambda: self.ai.weekly_summary(records), self._weekly_done)
        finally:
            self._automation_running = False
        self.after(30_000, self.check_automations)

    def _daily_done(self, result: tuple[str, str]) -> None:
        summary, task = result
        yesterday = (dt.date.today() - dt.timedelta(days=1)).isoformat()
        self.db.insert_summary(yesterday, summary)
        if task:
            self.settings.update({"next_task": task, "next_task_date": dt.date.today().isoformat()})
        self.notice("尋日總結", summary)
        self.refresh_view()

    def _weekly_done(self, result: tuple[str, str]) -> None:
        summary, theme = result
        key = "週" + (dt.date.today() - dt.timedelta(days=7)).isoformat()
        self.db.insert_summary(key, summary)
        if theme:
            self.settings.update({"common_theme": theme})
        self.notice("每週回顧", summary)
        self.refresh_view()

    def notice(self, title: str, text: str) -> None:
        win = tk.Toplevel(self)
        win.title(title)
        win.attributes("-topmost", True)
        win.geometry(f"360x170+{max(0, self.winfo_screenwidth() - 400)}+{max(0, self.winfo_screenheight() - 250)}")
        frame = ttk.Frame(win, padding=14)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text=title, style="Section.TLabel").pack(anchor="w")
        ttk.Label(frame, text=text, wraplength=320, justify="left").pack(fill="both", expand=True, pady=8)
        ttk.Button(frame, text="知道了", command=win.destroy).pack(anchor="e")
        win.after(10_000, lambda: win.destroy() if win.winfo_exists() else None)

    def refresh_overlay_status(self) -> None:
        if self.overlay.win and self.overlay.win.winfo_exists():
            self.overlay.set_status(self.status_var.get())

    def on_close(self) -> None:
        if not messagebox.askyesno("關閉 YupiSaver", "關閉後懸浮按鈕、定時提醒和主動安慰都會停止。確定關閉？", parent=self):
            return
        self.settings.update({"floating_enabled": False})
        self.active_controller.stop()
        self.scheduler.stop()
        self.head_up_scheduler.stop()
        self.nudge.close()
        self.overlay.hide()
        self.bubble.hide()
        self.hud.hide()
        self.tts.close()
        self.executor.shutdown(wait=False, cancel_futures=True)
        self.destroy()


if __name__ == "__main__":
    app = YupiSaverApp()
    app.mainloop()
