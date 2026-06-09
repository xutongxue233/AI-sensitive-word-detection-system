param(
    [switch]$SkipPython,
    [switch]$SkipNodeInstall,
    [switch]$SkipBuild,
    [switch]$RunTests
)

. "$PSScriptRoot\lib.ps1"

$Root = Get-RepoRoot
Ensure-LocalDirectories -Root $Root
Import-LocalEnv -Root $Root
Initialize-LocalEnvironment -Root $Root

Write-Host "Repo root: $Root"
Write-Host "Preparing local source runtime..."

$java = Resolve-LocalJava -Root $Root
Assert-Java21 -JavaExe $java
$npm = Resolve-LocalNpm -Root $Root
$maven = Resolve-LocalMaven -Root $Root

if (-not $SkipPython) {
    $python = Resolve-LocalPython -Root $Root
    Ensure-PythonVenv -Root $Root -ServiceName "asr-service" -PythonExe $python
    $ocrExtraPackages = @("paddlepaddle==3.2.1", "paddleocr>=3.0,<4.0")
    if ($env:PADDLE_OCR_USE_GPU -and ($env:PADDLE_OCR_USE_GPU).ToLower() -in @("1", "true", "yes", "on")) {
        $ocrExtraPackages = @()
        Write-Host "PADDLE_OCR_USE_GPU=true, skipping CPU paddle packages. Run ocr-service\install-ocr-gpu.bat for GPU OCR."
    }
    Ensure-PythonVenv `
        -Root $Root `
        -ServiceName "ocr-service" `
        -PythonExe $python `
        -ExtraPackages $ocrExtraPackages
}

if (-not $SkipNodeInstall) {
    $frontendDir = Join-Path $Root "frontend"
    if (Test-Path (Join-Path $frontendDir "package-lock.json")) {
        Invoke-Checked -FilePath $npm -Arguments @("ci") -WorkingDirectory $frontendDir
    } else {
        Invoke-Checked -FilePath $npm -Arguments @("install") -WorkingDirectory $frontendDir
    }
}

if (-not $SkipBuild) {
    Build-LocalBackendJar -Root $Root -Npm $npm -Maven $maven -RunTests ([bool]$RunTests)
}

Write-Host "Local setup completed."
Write-Host "Run: $Root\start-local.bat"
