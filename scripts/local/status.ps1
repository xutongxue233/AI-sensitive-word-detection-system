. "$PSScriptRoot\lib.ps1"

$Root = Get-RepoRoot
Ensure-LocalDirectories -Root $Root
Import-LocalEnv -Root $Root
Initialize-LocalEnvironment -Root $Root

$backendPort = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { "8090" }
$asrPort = if ($env:ASR_PORT) { $env:ASR_PORT } else { "9000" }
$ocrPort = if ($env:OCR_SERVICE_PORT) { $env:OCR_SERVICE_PORT } else { "9001" }

function Write-ServiceStatus {
    param([string]$Name, [string]$Url)

    $running = Test-ManagedProcess -Root $Root -Name $Name
    $httpOk = Test-HttpOk -Url $Url -TimeoutSeconds 2
    $processText = if ($running) { "running" } else { "stopped" }
    $httpText = if ($httpOk) { "http ok" } else { "http down" }
    Write-Host ("{0,-8} {1,-8} {2,-9} {3}" -f $Name, $processText, $httpText, $Url)
}

Write-Host "Repo root: $Root"
Write-ServiceStatus -Name "backend" -Url "http://127.0.0.1:$backendPort/"
Write-ServiceStatus -Name "asr" -Url "http://127.0.0.1:$asrPort/health"
Write-ServiceStatus -Name "ocr" -Url "http://127.0.0.1:$ocrPort/health"
Write-Host ""
Write-Host "Logs: $Root\logs"
