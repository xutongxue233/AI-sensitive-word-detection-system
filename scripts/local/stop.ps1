. "$PSScriptRoot\lib.ps1"

$Root = Get-RepoRoot
Ensure-LocalDirectories -Root $Root

Stop-ManagedProcess -Root $Root -Name "backend"
Stop-ManagedProcess -Root $Root -Name "ocr"
Stop-ManagedProcess -Root $Root -Name "asr"

Write-Host "All managed services stopped."
