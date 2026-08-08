$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$venvPath = Join-Path $projectDir ".venv"
$pythonPath = Join-Path $venvPath "Scripts\python.exe"

if (-not (Test-Path -LiteralPath $pythonPath)) {
    Write-Host "建立 Windows 版 Python 虛擬環境..."
    & py -3 -m venv $venvPath
}

Write-Host "安裝/更新 Windows 版依賴..."
& $pythonPath -m pip install --upgrade pip
& $pythonPath -m pip install -r (Join-Path $projectDir "requirements.txt")

Write-Host "檢查 Python 原始碼..."
& $pythonPath -m py_compile (Join-Path $projectDir "core.py") (Join-Path $projectDir "main.py")

Write-Host "開始建立 YupiSaver Windows 桌面版..."
$distDir = Join-Path $projectDir "dist"
& $pythonPath -m PyInstaller `
    --noconfirm `
    --clean `
    --windowed `
    --name YupiSaver `
    --distpath $distDir `
    --workpath (Join-Path $projectDir "build") `
    --specpath $projectDir `
    --collect-all edge_tts `
    --collect-all pyttsx3 `
    --hidden-import pyttsx3.drivers `
    (Join-Path $projectDir "main.py")
if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller 打包失败，退出码：$LASTEXITCODE"
}

$exePath = Join-Path $distDir "YupiSaver\YupiSaver.exe"
if (-not (Test-Path -LiteralPath $exePath)) {
    throw "打包完成但找不到 $exePath"
}

$runtimeData = Join-Path (Split-Path -Parent $exePath) "data"
if (-not (Test-Path -LiteralPath $runtimeData)) {
    New-Item -ItemType Directory -Path $runtimeData | Out-Null
}

Write-Host "完成：$exePath"
