# 一键停止:按端口定位并结束 backend/frontend/asr/ocr 四个服务进程(连同子进程树)。
# 无论服务由 dev.bat、start-local.bat 还是手动启动(含遗留孤儿进程),占着服务端口即被停止。
# 同时清理 start-local.bat 留下的 .runtime\*.pid。用法:双击 stop.bat。

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

# 只读解析 config/local.env 取端口配置(不存在则全用默认,不产生副作用)
function Read-LocalEnvValue {
    param([string]$Root, [string]$Key, [string]$Default)

    $envFile = Join-Path $Root "config\local.env"
    if (Test-Path $envFile) {
        foreach ($line in Get-Content -LiteralPath $envFile) {
            $trimmed = $line.Trim()
            if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
            $index = $trimmed.IndexOf("=")
            if ($index -le 0) { continue }
            if ($trimmed.Substring(0, $index).Trim() -eq $Key) {
                $value = $trimmed.Substring($index + 1).Trim().Trim('"')
                if ($value) { return $value }
            }
        }
    }
    return $Default
}

function Get-ListeningPids {
    param([int]$Port)

    $pids = @()
    try {
        $pids = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique)
    } catch { }
    return $pids | Where-Object { $_ -and $_ -gt 0 }
}

# 结束进程树;返回是否实际杀掉了进程
function Stop-ProcessTree {
    param([int]$ProcessId, [string]$Label)

    $proc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $proc) { return $false }
    $name = $proc.ProcessName
    & taskkill /F /T /PID $ProcessId 2>$null | Out-Null
    Write-Host ("已停止 {0,-9} pid={1,-6} ({2})" -f $Label, $ProcessId, $name) -ForegroundColor Yellow
    return $true
}

$Root = Get-RepoRoot
$services = @(
    @{ Name = "backend";  Port = [int](Read-LocalEnvValue -Root $Root -Key "SERVER_PORT" -Default "8090") },
    @{ Name = "frontend"; Port = 5174 },  # 由 frontend/vite.config.ts 固定(strictPort)
    @{ Name = "asr";      Port = [int](Read-LocalEnvValue -Root $Root -Key "ASR_PORT" -Default "9000") },
    @{ Name = "ocr";      Port = [int](Read-LocalEnvValue -Root $Root -Key "OCR_SERVICE_PORT" -Default "9001") }
)

Write-Host "== 停止项目服务 =="
$stopped = 0
foreach ($service in $services) {
    $found = $false
    foreach ($processId in (Get-ListeningPids -Port $service.Port)) {
        if (Stop-ProcessTree -ProcessId $processId -Label "$($service.Name):$($service.Port)") {
            $stopped++
            $found = $true
        }
    }
    if (-not $found) {
        Write-Host ("{0,-9} :{1,-5} 未在运行" -f $service.Name, $service.Port) -ForegroundColor DarkGray
    }
}

# 清理 start-local.bat 的受管 PID 文件(进程若仍存活则一并结束)
foreach ($pidFile in (Get-ChildItem -Path (Join-Path $Root ".runtime") -Filter "*.pid" -ErrorAction SilentlyContinue)) {
    $processId = Get-Content -LiteralPath $pidFile.FullName -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($processId -and (Stop-ProcessTree -ProcessId ([int]$processId) -Label $pidFile.BaseName)) {
        $stopped++
    }
    Remove-Item -LiteralPath $pidFile.FullName -Force -ErrorAction SilentlyContinue
}

# 复核端口已释放
Start-Sleep -Milliseconds 600
$busy = @()
foreach ($service in $services) {
    if (Get-ListeningPids -Port $service.Port) { $busy += "$($service.Name):$($service.Port)" }
}
if ($busy.Count -gt 0) {
    Write-Host ("警告: 以下端口仍被占用,可能有进程未被识别: {0}" -f ($busy -join ", ")) -ForegroundColor Red
    exit 1
}
Write-Host ("完成: 共停止 {0} 个进程,所有服务端口已释放。" -f $stopped) -ForegroundColor Green
