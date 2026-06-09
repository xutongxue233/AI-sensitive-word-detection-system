. "$PSScriptRoot\lib.ps1"

$Root = Get-PackageRoot
Ensure-Directories -Root $Root

Stop-ManagedProcess -Root $Root -Name "backend"
Stop-ManagedProcess -Root $Root -Name "ocr"
Stop-ManagedProcess -Root $Root -Name "asr"

Write-Host "All managed services stopped."
