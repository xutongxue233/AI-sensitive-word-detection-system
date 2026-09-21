# 开发模式一键启动:前台直接跑源码,四个服务输出汇聚到同一个窗口。
#   - backend  : mvn spring-boot:run            (:8090, 改 Java 代码需手动重启本脚本)
#   - frontend : npm run dev (vite)             (:5174, 热更新)
#   - asr      : .venv 下 python -u app.py       (:9000)
#   - ocr      : .venv 下 python -u ocr_app.py   (:9001)
# 每行日志带 [backend]/[frontend]/[asr]/[ocr] 彩色前缀;Ctrl+C 统一停止并杀进程树。
# 环境准备(创建 .venv、装依赖、装 node_modules)请先跑:  start-local.bat setup

param(
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"

# 控制台与管道统一 UTF-8,避免中文日志乱码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "== $Message ==" -ForegroundColor White
}

# 读取 config/local.env(不存在则从 .example 复制),逐行注入到进程环境
function Import-LocalEnv {
    param([string]$Root)

    $configDir = Join-Path $Root "config"
    $envFile = Join-Path $configDir "local.env"
    $example = Join-Path $configDir "local.env.example"
    if (-not (Test-Path $envFile) -and (Test-Path $example)) {
        Copy-Item -LiteralPath $example -Destination $envFile
    }
    if (-not (Test-Path $envFile)) {
        return
    }

    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) {
            return
        }
        $index = $line.IndexOf("=")
        if ($index -le 0) {
            return
        }
        $key = $line.Substring(0, $index).Trim()
        $value = $line.Substring($index + 1).Trim().Trim('"')
        if ($key) {
            [Environment]::SetEnvironmentVariable($key, $value, "Process")
        }
    }
}

function Add-PathIfExists {
    param([string]$Path)
    if ($Path -and (Test-Path $Path)) {
        $env:Path = "$Path;$env:Path"
    }
}

# 把可选的 .runtime 自带运行时与 ffmpeg 注入 PATH,并补齐 ASR/OCR 默认环境变量
function Initialize-LocalEnvironment {
    param([string]$Root)

    $runtimeRoot = Join-Path $Root ".runtime"
    Add-PathIfExists -Path (Join-Path $runtimeRoot "jdk\bin")
    Add-PathIfExists -Path (Join-Path $runtimeRoot "node")
    Add-PathIfExists -Path (Join-Path $runtimeRoot "maven\bin")
    Add-PathIfExists -Path (Join-Path $runtimeRoot "python")

    $runtimeFfmpegDir = Join-Path $runtimeRoot "ffmpeg\bin"
    $sourceFfmpegDir = Join-Path $Root "backend\tools\ffmpeg\bin"
    $ffmpegDir = if (Test-Path $runtimeFfmpegDir) { $runtimeFfmpegDir } else { $sourceFfmpegDir }
    if (Test-Path $ffmpegDir) {
        $env:FFMPEG_BIN_DIR = $ffmpegDir
        $env:FFMPEG_PATH = Join-Path $ffmpegDir "ffmpeg.exe"
        $env:FFPROBE_PATH = Join-Path $ffmpegDir "ffprobe.exe"
        Add-PathIfExists -Path $ffmpegDir
    }

    if (-not $env:WHISPER_DEVICE) { $env:WHISPER_DEVICE = "cpu" }
    if (-not $env:WHISPER_FP16) { $env:WHISPER_FP16 = "false" }
    if (-not $env:OCR_MAX_WIDTH) { $env:OCR_MAX_WIDTH = "1280" }
    if (-not $env:OCR_UPSCALE) { $env:OCR_UPSCALE = "false" }
    if (-not $env:PADDLE_OCR_USE_GPU) { $env:PADDLE_OCR_USE_GPU = "false" }
    if (-not $env:PADDLE_OCR_MIN_TEXT_LENGTH) { $env:PADDLE_OCR_MIN_TEXT_LENGTH = "2" }
    if (-not $env:PADDLE_OCR_DROP_SHORT_LATIN) { $env:PADDLE_OCR_DROP_SHORT_LATIN = "true" }
    if (-not $env:PADDLE_OCR_MIN_REPEAT_FRAMES) { $env:PADDLE_OCR_MIN_REPEAT_FRAMES = "2" }

    # Python 子进程:无缓冲 + UTF-8,保证日志实时且不乱码
    $env:PYTHONUNBUFFERED = "1"
    $env:PYTHONIOENCODING = "utf-8"
}

function Get-CommandText {
    param([string]$FilePath, [string[]]$Arguments)
    $commandLine = "`"$FilePath`""
    if ($Arguments) {
        $commandLine = "$commandLine $($Arguments -join ' ')"
    }
    return (& cmd.exe /d /c "$commandLine 2>&1") -join "`n"
}

function Get-JavaMajor {
    param([string]$JavaExe)
    if (-not (Test-Path $JavaExe)) { return $null }
    $output = Get-CommandText -FilePath $JavaExe -Arguments @("-version")
    if ($output -match 'version "([0-9]+)(?:\.|")') {
        return [int]$Matches[1]
    }
    return $null
}

function Get-CommonJavaCandidates {
    $patterns = @(
        "$env:ProgramFiles\Eclipse Adoptium\jdk-21*",
        "$env:ProgramFiles\Java\jdk-21*",
        "$env:ProgramFiles\Microsoft\jdk-21*",
        "$env:ProgramFiles\Zulu\zulu-21*",
        "${env:ProgramFiles(x86)}\Eclipse Adoptium\jdk-21*",
        "${env:ProgramFiles(x86)}\Java\jdk-21*"
    )
    foreach ($pattern in $patterns) {
        Get-ChildItem -Path $pattern -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            Join-Path $_.FullName "bin\java.exe"
        }
    }
}

# 依次从 .runtime\jdk、JAVA_HOME、常见安装目录、PATH 找 Java 21+,找到则设好 JAVA_HOME
function Resolve-Java21 {
    param([string]$Root)

    $candidates = New-Object System.Collections.Generic.List[string]
    $candidates.Add((Join-Path $Root ".runtime\jdk\bin\java.exe"))
    if ($env:JAVA_HOME) {
        $candidates.Add((Join-Path $env:JAVA_HOME "bin\java.exe"))
    }
    foreach ($candidate in Get-CommonJavaCandidates) {
        $candidates.Add($candidate)
    }
    $pathJava = Get-Command "java.exe" -ErrorAction SilentlyContinue
    if ($pathJava) {
        $candidates.Add($pathJava.Source)
    }

    $seen = @{}
    foreach ($candidate in $candidates) {
        if (-not $candidate -or $seen.ContainsKey($candidate)) { continue }
        $seen[$candidate] = $true
        $major = Get-JavaMajor -JavaExe $candidate
        if ($major -ge 21) {
            $javaHome = Split-Path -Parent (Split-Path -Parent $candidate)
            $env:JAVA_HOME = $javaHome
            Add-PathIfExists -Path (Join-Path $javaHome "bin")
            return (Resolve-Path $candidate).Path
        }
    }

    throw @(
        "未找到 Java 21+。后端 mvn spring-boot:run 需要 JDK 21。",
        "解决办法二选一:",
        "  1. 安装 JDK 21 并把 JAVA_HOME 指向它;",
        "  2. 把 JDK 21 拷到 .runtime\jdk(使 .runtime\jdk\bin\java.exe 存在)。",
        "JDK 8 无法构建/运行本后端。"
    ) -join [Environment]::NewLine
}

# 优先用 .runtime 下自带的可执行文件,否则回退 PATH;都没有则报错
function Resolve-Executable {
    param([string]$Root, [string]$RuntimePath, [string]$CommandName, [string]$DisplayName)

    $localPath = Join-Path $Root $RuntimePath
    if (Test-Path $localPath) {
        return (Resolve-Path $localPath).Path
    }
    $command = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "$DisplayName 未找到。请将其加入 PATH,或放到 $RuntimePath。"
}

function Test-HttpOk {
    param([string]$Url, [int]$TimeoutSeconds = 2)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSeconds
        return [int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 400
    } catch {
        return $false
    }
}

# 各服务前缀颜色
$script:ServiceColors = @{
    backend  = "Green"
    frontend = "Cyan"
    asr      = "Yellow"
    ocr      = "Magenta"
}

# 用 System.Diagnostics.Process 前台启动一个服务,异步事件给每行加前缀输出到本窗口。
# Exe 为真正的 .exe(如 python)直接启动;.cmd/.bat(如 mvn/npm)由调用方用 cmd.exe 包装。
function Start-DevService {
    param(
        [string]$Name,
        [string]$Exe,
        [string]$ArgString,
        [string]$WorkingDirectory
    )

    $color = $script:ServiceColors[$Name]
    if (-not $color) { $color = "Gray" }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = $ArgString
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi

    # stdout/stderr 共用同一处理逻辑:打印 [name] 前缀(着色) + 原始行
    $handler = {
        $line = $EventArgs.Data
        if ($null -ne $line) {
            $meta = $Event.MessageData
            Write-Host ("[{0}] " -f $meta.Name) -ForegroundColor $meta.Color -NoNewline
            Write-Host $line
        }
    }
    $msg = @{ Name = $Name; Color = $color }
    Register-ObjectEvent -InputObject $proc -EventName OutputDataReceived -Action $handler -MessageData $msg -SourceIdentifier "$Name-out" | Out-Null
    Register-ObjectEvent -InputObject $proc -EventName ErrorDataReceived -Action $handler -MessageData $msg -SourceIdentifier "$Name-err" | Out-Null

    [void]$proc.Start()
    $proc.BeginOutputReadLine()
    $proc.BeginErrorReadLine()

    Write-Host ("启动 {0,-8} pid={1}  ({2} {3})" -f $Name, $proc.Id, (Split-Path -Leaf $Exe), $ArgString) -ForegroundColor $color
    return [pscustomobject]@{ Name = $Name; Process = $proc }
}

# 杀掉所有仍存活的服务(含子进程树)并清理事件订阅
function Stop-AllServices {
    param($Services)

    foreach ($s in $Services) {
        if ($s -and $s.Process -and -not $s.Process.HasExited) {
            try {
                & taskkill /F /T /PID $s.Process.Id 2>$null | Out-Null
                Write-Host ("已停止 {0} (pid={1})" -f $s.Name, $s.Process.Id) -ForegroundColor Yellow
            } catch { }
        }
    }
    Get-EventSubscriber -ErrorAction SilentlyContinue | Unregister-Event -ErrorAction SilentlyContinue
}

# ---------------- 主流程 ----------------

$Root = Get-RepoRoot
Import-LocalEnv -Root $Root
Initialize-LocalEnvironment -Root $Root

$backendPort = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { "8090" }
$asrPort = if ($env:ASR_PORT) { $env:ASR_PORT } else { "9000" }
$ocrPort = if ($env:OCR_SERVICE_PORT) { $env:OCR_SERVICE_PORT } else { "9001" }
$frontendPort = "5174"  # 由 frontend/vite.config.ts 固定(strictPort)

# 后端读取的下游地址 + 前端 dev 代理目标
if (-not $env:APP_ASR_BASE_URL) { $env:APP_ASR_BASE_URL = "http://localhost:$asrPort" }
if (-not $env:APP_SUBTITLE_OCR_BASE_URL) { $env:APP_SUBTITLE_OCR_BASE_URL = "http://localhost:$ocrPort" }
if (-not $env:VITE_API_PROXY) { $env:VITE_API_PROXY = "http://localhost:$backendPort" }

Write-Step "检查运行环境"

# 后端:JDK 21 + Maven
$java = Resolve-Java21 -Root $Root
Write-Host "JAVA_HOME = $env:JAVA_HOME"
$maven = Resolve-Executable -Root $Root -RuntimePath ".runtime\maven\bin\mvn.cmd" -CommandName "mvn.cmd" -DisplayName "Maven"
Write-Host "Maven     = $maven"

# 前端:npm
$npm = Resolve-Executable -Root $Root -RuntimePath ".runtime\node\npm.cmd" -CommandName "npm.cmd" -DisplayName "Node.js/npm"
Write-Host "npm       = $npm"

# Python 服务:.venv 必须已就绪(创建虚拟环境/装依赖太重,交给 start-local.bat setup)
$asrPython = Join-Path $Root "asr-service\.venv\Scripts\python.exe"
$ocrPython = Join-Path $Root "ocr-service\.venv\Scripts\python.exe"
foreach ($pair in @(@{ Name = "asr-service"; Path = $asrPython }, @{ Name = "ocr-service"; Path = $ocrPython })) {
    if (-not (Test-Path $pair.Path)) {
        throw @(
            ("未找到 {0} 的 Python 虚拟环境: {1}" -f $pair.Name, $pair.Path),
            "请先准备环境(创建 .venv 并安装依赖):",
            "  start-local.bat setup"
        ) -join [Environment]::NewLine
    }
}
Write-Host "asr venv  = $asrPython"
Write-Host "ocr venv  = $ocrPython"

# 前端依赖:缺失则自动安装一次(轻量,几十秒)
$frontendDir = Join-Path $Root "frontend"
if (-not (Test-Path (Join-Path $frontendDir "node_modules"))) {
    Write-Step "安装前端依赖(首次较慢)"
    $install = Start-Process -FilePath "cmd.exe" -ArgumentList "/c npm install" -WorkingDirectory $frontendDir -NoNewWindow -Wait -PassThru
    if ($install.ExitCode -ne 0) {
        throw "前端依赖安装失败(npm install 退出码 $($install.ExitCode))。"
    }
}

Write-Step "启动服务(输出汇聚到本窗口,Ctrl+C 全部停止)"
Write-Host ("项目根目录 : {0}" -f $Root)
Write-Host ("访问地址   : http://127.0.0.1:{0}/   (前端 dev,/api 代理到后端 :{1})" -f $frontendPort, $backendPort) -ForegroundColor Green
Write-Host ("后端 API   : http://127.0.0.1:{0}/" -f $backendPort)
Write-Host ("ASR / OCR  : :{0} / :{1}" -f $asrPort, $ocrPort)
Write-Host "提示       : 改 Java 后端代码需 Ctrl+C 后重新运行本脚本;前端改动 vite 热更新。" -ForegroundColor DarkGray
Write-Host ""

$services = @()
# Ctrl+C 交由脚本主循环处理,避免直接打断 finally 清理
[Console]::TreatControlCAsInput = $true

try {
    # asr/ocr 是真 .exe,直接启动;-u 再保险一层无缓冲
    $services += Start-DevService -Name "asr" -Exe $asrPython -ArgString "-u app.py" -WorkingDirectory (Join-Path $Root "asr-service")
    $services += Start-DevService -Name "ocr" -Exe $ocrPython -ArgString "-u ocr_app.py" -WorkingDirectory (Join-Path $Root "ocr-service")
    # mvn/npm 是 .cmd,用 cmd.exe /c 包装(短名依赖前面已校验并在 PATH 上)
    $services += Start-DevService -Name "backend" -Exe "cmd.exe" -ArgString "/c mvn spring-boot:run" -WorkingDirectory (Join-Path $Root "backend")
    $services += Start-DevService -Name "frontend" -Exe "cmd.exe" -ArgString "/c npm run dev" -WorkingDirectory $frontendDir

    Write-Host ""
    Write-Host "全部服务已拉起,正在输出日志... (Ctrl+C 停止)" -ForegroundColor White
    Write-Host ""

    $browserOpened = $false
    $lastBrowserCheck = Get-Date
    while ($true) {
        # 1) 捕获 Ctrl+C
        if ([Console]::KeyAvailable) {
            $key = [Console]::ReadKey($true)
            if (($key.Modifiers -band [ConsoleModifiers]::Control) -and ($key.Key -eq [ConsoleKey]::C)) {
                Write-Host ""
                Write-Host "收到 Ctrl+C,正在停止所有服务..." -ForegroundColor Yellow
                break
            }
        }

        # 2) 任一服务退出则整体停止(开发模式下崩溃即暴露,不静默)
        $dead = $services | Where-Object { $_.Process.HasExited }
        if ($dead) {
            foreach ($d in $dead) {
                Write-Host ("[{0}] 进程已退出(退出码 {1}),停止其余服务。" -f $d.Name, $d.Process.ExitCode) -ForegroundColor Red
            }
            break
        }

        # 3) 前端就绪后自动开浏览器(每约 2 秒探测一次)
        if (-not $browserOpened -and -not $NoBrowser -and (((Get-Date) - $lastBrowserCheck).TotalSeconds -ge 2)) {
            $lastBrowserCheck = Get-Date
            if (Test-HttpOk -Url ("http://127.0.0.1:{0}/" -f $frontendPort)) {
                Start-Process ("http://127.0.0.1:{0}/" -f $frontendPort)
                $browserOpened = $true
                Write-Host ("已在浏览器打开 http://127.0.0.1:{0}/" -f $frontendPort) -ForegroundColor Green
            }
        }

        # 让出主线程,使异步输出事件得以处理
        Start-Sleep -Milliseconds 200
    }
} finally {
    Write-Host ""
    Stop-AllServices -Services $services
    [Console]::TreatControlCAsInput = $false
    Write-Host "已退出。" -ForegroundColor White
}
