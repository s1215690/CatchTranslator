import tempfile
import unittest
from pathlib import Path

from core import AIEngine, DebugLog, HeadUpScheduler, SettingsStore, TimedNudgeScheduler, TranslatorDb


class CoreSmokeTests(unittest.TestCase):
    def test_timed_phrase_parser(self):
        self.assertEqual(TimedNudgeScheduler.parse("30分鐘後提醒我出去吃飯"), ("出去吃飯", 30 * 60 * 1000))
        self.assertEqual(TimedNudgeScheduler.parse("半個鐘後叫我休息"), ("休息", 30 * 60 * 1000))
        self.assertIsNone(TimedNudgeScheduler.parse("2分鐘後提醒我"))

    def test_settings_and_database(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            settings = SettingsStore(root / "settings.json")
            settings.update({"button_count": 8})
            self.assertEqual(settings.get("button_count"), 8)

            db = TranslatorDb(root / "records.db")
            db.insert("感恩", "狗狗陪著我", "text")
            db.insert("完成", "整理桌面", "nudge")
            self.assertEqual(db.count_all(), 2)
            self.assertEqual(db.channel_counts(7).get("感恩"), 1)

    def test_fallback_response_has_buttons(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            settings = SettingsStore(root / "settings.json")
            settings.update({"button_count": 4})
            db = TranslatorDb(root / "records.db")
            ai = AIEngine(settings, db, DebugLog(root / "app.log"))
            response = ai.respond("今天有點累")
            self.assertTrue(response.reply)
            self.assertEqual(len(response.buttons), 4)

    def test_head_up_scheduler_limits(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            settings = SettingsStore(root / "settings.json")
            settings.update({"head_up_interval": 999, "head_up_jitter": 999})
            scheduler = HeadUpScheduler(settings, lambda: None, DebugLog(root / "app.log"))
            self.assertEqual(scheduler.interval(), 300)
            self.assertEqual(scheduler.jitter(), 30)
            settings.update({"head_up_interval": 0})
            self.assertIn("安全最短 1 分鐘", scheduler.interval_description())
            scheduler.start()
            scheduler.stop()


if __name__ == "__main__":
    unittest.main()
