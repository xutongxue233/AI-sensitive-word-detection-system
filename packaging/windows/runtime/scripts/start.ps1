. "$PSScriptRoot\lib.ps1"

$Root = Get-PackageRoot
Ensure-Directories -Root $Root
Import-LocalEnv -Root $Root

$backendPort = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { "8090" }
$asrPort = if ($env:ASR_PORT) { $env:ASR_PORT } else { "9000" }
$ocrPort = if ($env:OCR_SERVICE_PORT) { $env:OCR_SERVICE_PORT } else { "9001" }
$env:APP_ASR_BASE_URL = if ($env:APP_ASR_BASE_URL) { $env:APP_ASR_BASE_URL } else { "http://localhost:$asrPort" }
$env:APP_SUBTITLE_OCR_BASE_URL = if ($env:APP_SUBTITLE_OCR_BASE_URL) { $env:APP_SUBTITLE_OCR_BASE_URL } else { "http://localhost:$ocrPort" }

$ffmpegDir = Join-Path $Root "tools\ffmpeg\bin"
if (Test-Path $ffmpegDir) {
    $env:FFMPEG_BIN_DIR = $ffmpegDir
    $env:FFMPEG_PATH = Join-Path $ffmpegDir "ffmpeg.exe"
    $env:FFPROBE_PATH = Join-Path $ffmpegDir "ffprobe.exe"
    $env:Path = "$ffmpegDir;$env:Path"
}

if (-not $env:WHISPER_DEVICE) { $env:WHISPER_DEVICE = "cpu" }
if (-not $env:WHISPER_FP16) { $env:WHISPER_FP16 = "false" }
if (-not $env:PADDLE_OCR_USE_GPU) { $env:PADDLE_OCR_USE_GPU = "false" }

$java = Resolve-Executable `
    -Preferred (Join-Path $Root "runtime\jdk\bin\java.exe") `
    -FallbackCommand "java.exe" `
    -DisplayName "Java 21"
$backendJar = Join-Path $Root "backend\video-moderation.jar"
if (-not (Test-Path $backendJar)) {
    throw "Backend jar not found: $backendJar"
}

$embeddedPython = Join-Path $Root "runtime\python\python.exe"
$asrSitePackages = Join-Path $Root "asr-service\.venv\Lib\site-packages"
$ocrSitePackages = Join-Path $Root "ocr-service\.venv\Lib\site-packages"
if (Test-Path $embeddedPython) {
    $asrPython = $embeddedPython
    $ocrPython = $embeddedPython
    if (-not (Test-Path $asrSitePackages)) {
        throw "ASR site-packages not found: $asrSitePackages"
    }
    if (-not (Test-Path $ocrSitePackages)) {
        throw "OCR site-packages not found: $ocrSitePackages"
    }
} else {
    $asrPython = Join-Path $Root "asr-service\.venv\Scripts\python.exe"
    $ocrPython = Join-Path $Root "ocr-service\.venv\Scripts\python.exe"
    if (-not (Test-Path $asrPython)) {
        throw "ASR Python venv not found: $asrPython. Rebuild the package with bundled venvs or include runtime\python."
    }
    if (-not (Test-Path $ocrPython)) {
        throw "OCR Python venv not found: $ocrPython. Rebuild the package with bundled venvs or include runtime\python."
    }
}

Write-Host "Package root: $Root"
Write-Host "Backend: http://127.0.0.1:$backendPort/"
Write-Host "ASR:     http://127.0.0.1:$asrPort/health"
Write-Host "OCR:     http://127.0.0.1:$ocrPort/health"

$previousPythonPath = $env:PYTHONPATH
if (Test-Path $embeddedPython) {
    $env:PYTHONNOUSERSITE = "1"
    $env:PYTHONPATH = $asrSitePackages
}
Start-ManagedProcess `
    -Root $Root `
    -Name "asr" `
    -FilePath $asrPython `
    -Arguments @("app.py") `
    -WorkingDirectory (Join-Path $Root "asr-service")

if (Test-Path $embeddedPython) {
    $env:PYTHONPATH = $ocrSitePackages
}
Start-ManagedProcess `
    -Root $Root `
    -Name "ocr" `
    -FilePath $ocrPython `
    -Arguments @("ocr_app.py") `
    -WorkingDirectory (Join-Path $Root "ocr-service")

if (Test-Path $embeddedPython) {
    $env:PYTHONPATH = $previousPythonPath
}
Start-ManagedProcess `
    -Root $Root `
    -Name "backend" `
    -FilePath $java `
    -Arguments @("-jar", $backendJar) `
    -WorkingDirectory $Root

Write-Host "Waiting for backend..."
if (Wait-HttpOk -Url "http://127.0.0.1:$backendPort/" -TimeoutSeconds 80) {
    Start-Process "http://127.0.0.1:$backendPort/"
    Write-Host "Started. Browser opened."
} else {
    Write-Host "Backend did not become ready in time. Check logs\backend.err.log and logs\backend.out.log."
}
