# YupiSaver Windows 版

這是 `CatchTranslator` Android 版的 Windows 桌面移植，專案位置是：

`D:\Project Program\CatchTranslatorWin`

Windows 版保留主要功能：

- DeepSeek / OpenAI 相容 API 對話與情緒回應
- 動態 AI 按鈕（4、8、10 個）
- Edge 粵語/普通話語音、MiniMax Speech 2.8 情緒語音、MiniMax Voice Design、系統 TTS 備援
- 不重用錄音快取；每次線上語音都重新產生暫存音訊，播放後刪除
- 感恩模式，直接使用輸入框內容
- 推動力模式：AI 拆步驟、可移動提醒視窗、完成/稍後/取消
- 定時提醒：支援「30 分鐘後提醒我出去吃飯」等自然語句，任務會保存並在下次啟動時恢復
- 主動安慰：間距可輸入 `0–300` 分鐘；`0` 代表使用安全的 1 分鐘間隔
- 播放完主動安慰後，可選擇開啟瀏覽器的 ChatGPT 對話頁面
- 定時抬頭顯示：參考 WakeMyheadUp 的透明全屏大字 HUD，只從內置 1000 句抬頭話術隨機抽一句；支援隨機間隔 `0–300` 分鐘、浮動時間、顯示秒數、字號及可選語音
- 懸浮按鈕與可移動視窗
- 主窗口可最小化；最小化後懸浮按鈕、定時提醒、主動安慰和抬頭顯示仍會繼續運作
- 每日/每週摘要、統計、記錄、Telegram Log
- API Key、Telegram、語音等設定預設折疊，節省主畫面空間

藍牙保活、藍牙音箱相關功能沒有移植。

## 直接執行

先在 PowerShell 進入專案目錄：

```powershell
cd D:\Project Program\CatchTranslatorWin
py -3 -m pip install -r requirements.txt
py -3 main.py
```

## 建立 EXE

```powershell
cd D:\Project Program\CatchTranslatorWin
Set-ExecutionPolicy -Scope Process Bypass
.\build_windows.ps1
```

完成後執行：

`D:\Project Program\CatchTranslatorWin\dist\YupiSaver\YupiSaver.exe`

這是 portable onedir 版本，整個 `dist\YupiSaver` 資料夾一起保留即可。設定、記錄和待辦任務會寫入 EXE 同層的 `data` 資料夾。

## 語音注意事項

- Edge TTS 和 MiniMax 需要網路。
- Windows 版只保留文字輸入；AI 回覆、安慰語音和提醒語音仍可正常播放。
- Edge/MiniMax 失敗時會自動退回 Windows 系統語音。
- Windows 版的「播放後開啟 ChatGPT」是開啟 `https://chatgpt.com/`，實際語音模式由 ChatGPT 網頁/桌面程式決定。

請把 API Key 填入程式內的折疊設定，不要寫入原始碼或提交到 GitHub。
