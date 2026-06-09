param(
    [switch]$NoBrowser,
    [switch]$NoAutoSetup
)

. "$PSScriptRoot\lib.ps1"

$Root = Get-RepoRoot
Ensure-LocalDirectories -Root $Root
Import-LocalEnv -Root $Root
Initialize-LocalEnvironment -Root $Root

$backendPort = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { "8090" }
$asrPort = if ($env:ASR_PORT) { $env:ASR_PORT } else { "9000" }
$ocrPort = if ($env:OCR_SERVICE_PORT) { $env:OCR_SERVICE_PORT } else { "9001" }
$env:APP_ASR_BASE_URL = if ($env:APP_ASR_BASE_URL) { $env:APP_ASR_BASE_URL } else { "http://localhost:$asrPort" }
$env:APP_SUBTITLE_OCR_BASE_URL = if ($env:APP_SUBTITLE_OCR_BASE_URL) { $env:APP_SUBTITLE_OCR_BASE_URL } else { "http://localhost:$ocrPort" }

$asrPython = Join-Path $Root "asr-service\.venv\Scripts\python.exe"
$ocrPython = Join-Path $Root "ocr-service\.venv\Scripts\python.exe"
$backendJar = Get-BackendJar -Root $Root
$missing = @()
if (-not (Test-Path $asrPython)) { $missing += "asr-service\.venv" }
if (-not (Test-Path $ocrPython)) { $missing += "ocr-service\.venv" }
if (-not $backendJar) { $missing += "backend\target\*.jar" }

if ($missing.Count -gt 0) {
    if ($NoAutoSetup) {
        throw "Missing local runtime artifacts: $($missing -join ', '). Run setup-local.bat first."
    }
    Write-Host "Missing local runtime artifacts: $($missing -join ', ')"
    Write-Host "Running setup-local automatically. This can take a while on first startup..."
    & "$PSScriptRoot\setup.ps1"
    if ($LASTEXITCODE -ne 0) {
        throw "Automatic setup failed."
    }
    $backendJar = Get-BackendJar -Root $Root
}

if (-not (Test-Path $asrPython)) { throw "ASR venv not found: $asrPython" }
if (-not (Test-Path $ocrPython)) { throw "OCR venv not found: $ocrPython" }
if (-not $backendJar) { throw "Backend jar not found under backend\target." }

$java = Resolve-LocalJava -Root $Root
Assert-Java21 -JavaExe $java

Write-Host "Repo root: $Root"
Write-Host "Backend: http://127.0.0.1:$backendPort/"
Write-Host "ASR:     http://127.0.0.1:$asrPort/health"
Write-Host "OCR:     http://127.0.0.1:$ocrPort/health"
Write-Host "Logs:    $Root\logs"

Start-ManagedProcess `
    -Root $Root `
    -Name "asr" `
    -FilePath $asrPython `
    -Arguments @("app.py") `
    -WorkingDirectory (Join-Path $Root "asr-service")

Start-ManagedProcess `
    -Root $Root `
    -Name "ocr" `
    -FilePath $ocrPython `
    -Arguments @("ocr_app.py") `
    -WorkingDirectory (Join-Path $Root "ocr-service")

Start-ManagedProcess `
    -Root $Root `
    -Name "backend" `
    -FilePath $java `
    -Arguments @("-jar", $backendJar) `
    -WorkingDirectory $Root

Write-Host "Waiting for backend..."
if (Wait-HttpOk -Url "http://127.0.0.1:$backendPort/" -TimeoutSeconds 80) {
    if (-not $NoBrowser) {
        Start-Process "http://127.0.0.1:$backendPort/"
    }
    Write-Host "Started. Only managed background services were launched; no extra service windows are opened."
} else {
    Write-Host "Backend did not become ready in time. Check logs\backend.err.log and logs\backend.out.log."
}
