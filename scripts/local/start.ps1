param(
    [ValidateSet("start", "stop", "status", "setup", "rebuild")]
    [string]$Action = "start",
    [switch]$NoBrowser,
    [switch]$RunTests
)

$ErrorActionPreference = "Stop"

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "== $Message =="
}

function Ensure-Directories {
    param([string]$Root)
    "logs", ".runtime", "storage", "config" | ForEach-Object {
        $path = Join-Path $Root $_
        if (-not (Test-Path $path)) {
            New-Item -ItemType Directory -Path $path | Out-Null
        }
    }
}

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

function Initialize-LocalEnvironment {
    param([string]$Root)

    $runtimeRoot = Join-Path $Root ".runtime"
    $runtimeJdkBin = Join-Path $runtimeRoot "jdk\bin"
    $runtimeNode = Join-Path $runtimeRoot "node"
    $runtimeMavenBin = Join-Path $runtimeRoot "maven\bin"
    $runtimePython = Join-Path $runtimeRoot "python"
    $runtimeFfmpegDir = Join-Path $runtimeRoot "ffmpeg\bin"
    $sourceFfmpegDir = Join-Path $Root "backend\tools\ffmpeg\bin"
    $ffmpegDir = if (Test-Path $runtimeFfmpegDir) { $runtimeFfmpegDir } else { $sourceFfmpegDir }

    Add-PathIfExists -Path $runtimeJdkBin
    Add-PathIfExists -Path $runtimeNode
    Add-PathIfExists -Path $runtimeMavenBin
    Add-PathIfExists -Path $runtimePython

    if (Test-Path $ffmpegDir) {
        $env:FFMPEG_BIN_DIR = $ffmpegDir
        $env:FFMPEG_PATH = Join-Path $ffmpegDir "ffmpeg.exe"
        $env:FFPROBE_PATH = Join-Path $ffmpegDir "ffprobe.exe"
        Add-PathIfExists -Path $ffmpegDir
    }

    if (-not $env:WHISPER_DEVICE) { $env:WHISPER_DEVICE = "cpu" }
    if (-not $env:WHISPER_FP16) { $env:WHISPER_FP16 = "false" }
    if (-not $env:PADDLE_OCR_USE_GPU) { $env:PADDLE_OCR_USE_GPU = "false" }
    if (-not $env:PADDLE_OCR_MIN_TEXT_LENGTH) { $env:PADDLE_OCR_MIN_TEXT_LENGTH = "2" }
    if (-not $env:PADDLE_OCR_DROP_SHORT_LATIN) { $env:PADDLE_OCR_DROP_SHORT_LATIN = "true" }
    if (-not $env:PADDLE_OCR_MIN_REPEAT_FRAMES) { $env:PADDLE_OCR_MIN_REPEAT_FRAMES = "2" }
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

    if (-not (Test-Path $JavaExe)) {
        return $null
    }
    $output = Get-CommandText -FilePath $JavaExe -Arguments @("-version")
    if ($output -match 'version "([0-9]+)(?:\.|")') {
        return [int]$Matches[1]
    }
    return $null
}

function Get-JavaDescription {
    param([string]$JavaExe)

    if (-not $JavaExe -or -not (Test-Path $JavaExe)) {
        return ""
    }
    return (Get-CommandText -FilePath $JavaExe -Arguments @("-version")).Trim()
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

function Resolve-Java21 {
    param([string]$Root)

    $candidates = New-Object System.Collections.Generic.List[string]
    $runtimeJava = Join-Path $Root ".runtime\jdk\bin\java.exe"
    $candidates.Add($runtimeJava)
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
        if (-not $candidate -or $seen.ContainsKey($candidate)) {
            continue
        }
        $seen[$candidate] = $true
        $major = Get-JavaMajor -JavaExe $candidate
        if ($major -ge 21) {
            $javaHome = Split-Path -Parent (Split-Path -Parent $candidate)
            $env:JAVA_HOME = $javaHome
            Add-PathIfExists -Path (Join-Path $javaHome "bin")
            return (Resolve-Path $candidate).Path
        }
    }

    $currentJava = if ($pathJava) { $pathJava.Source } else { "" }
    $currentText = Get-JavaDescription -JavaExe $currentJava
    $message = @(
        "Java 21+ was not found.",
        "",
        "The current java.exe on PATH is not Java 21:",
        $currentText,
        "",
        "Fix it with one of these options:",
        "1. Install JDK 21 and set JAVA_HOME to that JDK.",
        "2. Copy JDK 21 to .runtime\jdk, so .runtime\jdk\bin\java.exe exists.",
        "",
        "Java 8 cannot run this backend."
    ) -join [Environment]::NewLine
    throw $message
}

function Resolve-Executable {
    param(
        [string]$Root,
        [string]$RuntimePath,
        [string]$CommandName,
        [string]$DisplayName
    )

    $localPath = Join-Path $Root $RuntimePath
    if (Test-Path $localPath) {
        return (Resolve-Path $localPath).Path
    }
    $command = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "$DisplayName was not found. Install it on PATH, or put it under $RuntimePath."
}

function Resolve-Python310 {
    param([string]$Root)

    $python = Resolve-Executable `
        -Root $Root `
        -RuntimePath ".runtime\python\python.exe" `
        -CommandName "python.exe" `
        -DisplayName "Python 3.10"
    $output = Get-CommandText -FilePath $python -Arguments @("--version")
    if ($output -notmatch "Python 3\.10\.") {
        throw "Python 3.10.x is required. Current version: $output"
    }
    return $python
}

function Invoke-Checked {
    param([string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory)

    Write-Host ">> $FilePath $($Arguments -join ' ')"
    $process = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory `
        -NoNewWindow `
        -Wait `
        -PassThru
    if ($process.ExitCode -ne 0) {
        throw "Command failed with exit code $($process.ExitCode): $FilePath"
    }
}

function Copy-DirectoryClean {
    param([string]$Source, [string]$Destination)

    if (-not (Test-Path $Source)) {
        throw "Directory not found: $Source"
    }
    if (Test-Path $Destination) {
        Remove-Item -LiteralPath $Destination -Recurse -Force
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
    Copy-Item -LiteralPath $Source -Destination $Destination -Recurse -Force
}

function Get-PythonRuntimeMode {
    param([string]$PythonExe)

    # Keep this probe deliberately small: importing onnxruntime is enough to
    # tell whether the OCR venv has the CPU or DirectML provider installed.
    # A failed import is reported as "missing" so the caller repairs the venv
    # instead of treating a half-installed environment as ready.
    $probe = "import onnxruntime; print('dml' if 'DmlExecutionProvider' in onnxruntime.get_available_providers() else 'cpu')"
    try {
        $output = & $PythonExe -c $probe 2>$null
        if ($LASTEXITCODE -ne 0) {
            return "missing"
        }
        $mode = ($output | Select-Object -Last 1).ToString().Trim().ToLowerInvariant()
        if ($mode -eq "dml" -or $mode -eq "cpu") {
            return $mode
        }
    } catch {
        return "missing"
    }
    return "missing"
}

function Test-PythonPip {
    param([string]$PythonExe)

    try {
        $null = & $PythonExe -m pip --version 2>$null
        return $LASTEXITCODE -eq 0
    } catch {
        return $false
    }
}

function Get-PythonDependencyState {
    param(
        [string]$RequirementsPath,
        [string[]]$ExtraPackages,
        [switch]$UseDirectML
    )

    if (-not (Test-Path $RequirementsPath)) {
        throw "Python requirements file was not found: $RequirementsPath"
    }
    $hash = (Get-FileHash -LiteralPath $RequirementsPath -Algorithm SHA256).Hash
    $extras = if ($ExtraPackages) { $ExtraPackages -join ";" } else { "" }
    return "$hash|$extras|$([bool]$UseDirectML)"
}

function Install-PythonRequirements {
    param(
        [string]$PythonExe,
        [string]$ServiceDir,
        [string[]]$ExtraPackages
    )

    Invoke-Checked -FilePath $PythonExe -Arguments @("-m", "pip", "install", "--upgrade", "pip", "setuptools", "wheel") -WorkingDirectory $ServiceDir
    Invoke-Checked -FilePath $PythonExe -Arguments @("-m", "pip", "install", "-r", "requirements.txt") -WorkingDirectory $ServiceDir
    foreach ($package in $ExtraPackages) {
        Invoke-Checked -FilePath $PythonExe -Arguments @("-m", "pip", "install", $package) -WorkingDirectory $ServiceDir
    }
}

function Ensure-OcrRuntime {
    param(
        [string]$PythonExe,
        [string]$ServiceDir,
        [switch]$UseDirectML
    )

    $mode = Get-PythonRuntimeMode -PythonExe $PythonExe
    $desired = if ($UseDirectML) { "dml" } else { "cpu" }
    if ($mode -eq $desired) {
        return
    }

    if ($UseDirectML) {
        Write-Host "Installing DirectML onnxruntime for ocr-service..."
        try {
            # The two distributions expose the same import name and cannot be
            # installed side by side. Remove both names before migration.
            Invoke-Checked -FilePath $PythonExe -Arguments @("-m", "pip", "uninstall", "-y", "onnxruntime", "onnxruntime-directml") -WorkingDirectory $ServiceDir
            Invoke-Checked -FilePath $PythonExe -Arguments @("-m", "pip", "install", "onnxruntime-directml==1.23.0") -WorkingDirectory $ServiceDir
        } catch {
            # Do not leave a venv without an onnxruntime implementation. The
            # CPU package is a safe recovery path; the state marker is written
            # only after this function returns, so a later start retries DML.
            Write-Warning "DirectML installation failed; restoring CPU onnxruntime before retrying next start."
            try {
                Install-PythonRequirements -PythonExe $PythonExe -ServiceDir $ServiceDir -ExtraPackages @()
            } catch {
                Write-Warning "CPU onnxruntime recovery also failed: $($_.Exception.Message)"
            }
            throw
        }
    } else {
        Write-Host "Switching ocr-service to CPU onnxruntime..."
        try {
            Invoke-Checked -FilePath $PythonExe -Arguments @("-m", "pip", "uninstall", "-y", "onnxruntime-directml") -WorkingDirectory $ServiceDir
            Invoke-Checked -FilePath $PythonExe -Arguments @("-m", "pip", "install", "onnxruntime") -WorkingDirectory $ServiceDir
        } catch {
            # A failed migration must be visible and must not be marked ready.
            # The next invocation probes the provider again and retries.
            throw
        }
    }

    $actual = Get-PythonRuntimeMode -PythonExe $PythonExe
    if ($actual -ne $desired) {
        throw "onnxruntime migration did not provide the requested '$desired' provider (detected '$actual')."
    }
}

function Ensure-PythonVenv {
    param(
        [string]$Root,
        [string]$ServiceName,
        [string]$PythonExe,
        [string[]]$ExtraPackages = @(),
        [switch]$UseDirectML
    )

    $serviceDir = Join-Path $Root $ServiceName
    $venvDir = Join-Path $serviceDir ".venv"
    $venvPython = Join-Path $venvDir "Scripts\python.exe"
    $requirementsPath = Join-Path $serviceDir "requirements.txt"
    $statePath = Join-Path $venvDir ".codex-dependencies.state"

    $created = $false
    if (-not (Test-Path $venvPython)) {
        if (Test-Path $venvDir) {
            Write-Warning "$ServiceName\.venv is incomplete; removing it so the next setup starts clean."
            Remove-Item -LiteralPath $venvDir -Recurse -Force
        }
        if (-not $PythonExe -or -not (Test-Path $PythonExe)) {
            throw "Python 3.10 is required to create $ServiceName\.venv."
        }
        Invoke-Checked -FilePath $PythonExe -Arguments @("-m", "venv", ".venv") -WorkingDirectory $serviceDir
        $created = $true
    }

    if (-not (Test-PythonPip -PythonExe $venvPython)) {
        if (-not $PythonExe -or -not (Test-Path $PythonExe)) {
            throw "$ServiceName\.venv is missing a working pip and Python 3.10 is unavailable to rebuild it."
        }
        Write-Warning "$ServiceName\.venv has no working pip; removing it so setup can recreate it."
        Remove-Item -LiteralPath $venvDir -Recurse -Force
        Invoke-Checked -FilePath $PythonExe -Arguments @("-m", "venv", ".venv") -WorkingDirectory $serviceDir
        $created = $true
    }

    $desiredState = Get-PythonDependencyState -RequirementsPath $requirementsPath -ExtraPackages $ExtraPackages -UseDirectML:$UseDirectML
    $recordedState = if (Test-Path $statePath) { (Get-Content -LiteralPath $statePath -Raw).Trim() } else { "" }
    $needsRequirements = $created -or ($recordedState -ne $desiredState)

    if ($needsRequirements) {
        Write-Host "Installing or repairing $ServiceName Python dependencies..."
        Install-PythonRequirements -PythonExe $venvPython -ServiceDir $serviceDir -ExtraPackages $ExtraPackages
    } else {
        Write-Host "$ServiceName\.venv dependencies are up to date; checking runtime provider."
    }

    if ($ServiceName -eq "ocr-service") {
        Ensure-OcrRuntime -PythonExe $venvPython -ServiceDir $serviceDir -UseDirectML:$UseDirectML
    }

    # Write the marker only after every install/migration and provider probe
    # succeeds. A failed setup therefore remains retryable on the next start.
    Set-Content -LiteralPath $statePath -Value $desiredState -Encoding UTF8
}

function Build-BackendJar {
    param(
        [string]$Root,
        [string]$Npm,
        [string]$Maven,
        [bool]$RunTests
    )

    $frontendDir = Join-Path $Root "frontend"
    $backendDir = Join-Path $Root "backend"
    $staticDir = Join-Path $backendDir "src\main\resources\static"

    Invoke-Checked -FilePath $Npm -Arguments @("run", "build") -WorkingDirectory $frontendDir
    try {
        if (Test-Path $staticDir) {
            Remove-Item -LiteralPath $staticDir -Recurse -Force
        }
        Copy-DirectoryClean -Source (Join-Path $frontendDir "dist") -Destination $staticDir

        $mavenArgs = @("-q")
        if (-not $RunTests) {
            $mavenArgs += "-DskipTests"
        }
        $mavenArgs += "package"
        Invoke-Checked -FilePath $Maven -Arguments $mavenArgs -WorkingDirectory $backendDir
    } finally {
        if (Test-Path $staticDir) {
            Remove-Item -LiteralPath $staticDir -Recurse -Force
        }
    }
}

function Get-BackendJar {
    param([string]$Root)

    $targetDir = Join-Path $Root "backend\target"
    if (-not (Test-Path $targetDir)) {
        return $null
    }
    $jar = Get-ChildItem -Path $targetDir -Filter "*.jar" |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($jar) {
        return $jar.FullName
    }
    return $null
}

function Get-PidPath {
    param([string]$Root, [string]$Name)
    return Join-Path $Root ".runtime\$Name.pid"
}

function Test-ManagedProcess {
    param([string]$Root, [string]$Name)

    $pidPath = Get-PidPath -Root $Root -Name $Name
    if (-not (Test-Path $pidPath)) {
        return $false
    }
    $processId = Get-Content -LiteralPath $pidPath -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $processId) {
        return $false
    }
    return [bool](Get-Process -Id ([int]$processId) -ErrorAction SilentlyContinue)
}

function Start-ManagedProcess {
    param(
        [string]$Root,
        [string]$Name,
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    if (Test-ManagedProcess -Root $Root -Name $Name) {
        Write-Host "$Name is already running."
        return
    }

    $stdout = Join-Path $Root "logs\$Name.out.log"
    $stderr = Join-Path $Root "logs\$Name.err.log"
    $process = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru
    Set-Content -LiteralPath (Get-PidPath -Root $Root -Name $Name) -Value $process.Id
    Write-Host "Started $Name, pid=$($process.Id)"
}

function Stop-ManagedProcess {
    param([string]$Root, [string]$Name)

    $pidPath = Get-PidPath -Root $Root -Name $Name
    if (-not (Test-Path $pidPath)) {
        Write-Host "$Name was not started by this script."
        return
    }
    $processId = Get-Content -LiteralPath $pidPath -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($processId) {
        $process = Get-Process -Id ([int]$processId) -ErrorAction SilentlyContinue
        if ($process) {
            Stop-Process -Id $process.Id -Force
            Write-Host "Stopped $Name, pid=$processId"
        }
    }
    Remove-Item -LiteralPath $pidPath -Force -ErrorAction SilentlyContinue
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

function Wait-HttpOk {
    param([string]$Url, [int]$TimeoutSeconds = 80)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-HttpOk -Url $Url -TimeoutSeconds 2) {
            return $true
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Ensure-Ready {
    param([string]$Root, [bool]$ForceBuild)

    $java = Resolve-Java21 -Root $Root

    $backendJar = Get-BackendJar -Root $Root
    $needsBuild = $ForceBuild -or -not $backendJar

    $asrVenvPython = Join-Path $Root "asr-service\.venv\Scripts\python.exe"
    $ocrVenvPython = Join-Path $Root "ocr-service\.venv\Scripts\python.exe"
    $needsPython = `
        -not (Test-Path $asrVenvPython) -or `
        -not (Test-Path $ocrVenvPython) -or `
        -not (Test-PythonPip -PythonExe $asrVenvPython) -or `
        -not (Test-PythonPip -PythonExe $ocrVenvPython)
    $python = if ($needsPython) { Resolve-Python310 -Root $Root } else { "" }

    Write-Step "Checking Python service dependencies"
    Ensure-PythonVenv -Root $Root -ServiceName "asr-service" -PythonExe $python
    Ensure-PythonVenv -Root $Root -ServiceName "ocr-service" -PythonExe $python -UseDirectML

    if ($needsBuild) {
        $npm = Resolve-Executable -Root $Root -RuntimePath ".runtime\node\npm.cmd" -CommandName "npm.cmd" -DisplayName "Node.js/npm"
        $maven = Resolve-Executable -Root $Root -RuntimePath ".runtime\maven\bin\mvn.cmd" -CommandName "mvn.cmd" -DisplayName "Maven"

        Write-Step "Checking frontend dependencies"
        $frontendDir = Join-Path $Root "frontend"
        if (-not (Test-Path (Join-Path $frontendDir "node_modules"))) {
            if (Test-Path (Join-Path $frontendDir "package-lock.json")) {
                Invoke-Checked -FilePath $npm -Arguments @("ci") -WorkingDirectory $frontendDir
            } else {
                Invoke-Checked -FilePath $npm -Arguments @("install") -WorkingDirectory $frontendDir
            }
        } else {
            Write-Host "frontend\node_modules exists; skipping npm install."
        }

        Write-Step "Building backend Jar with frontend assets"
        Build-BackendJar -Root $Root -Npm $npm -Maven $maven -RunTests $RunTests
        $backendJar = Get-BackendJar -Root $Root
    } else {
        Write-Host "Backend Jar exists; skipping frontend/backend build."
    }

    if (-not $backendJar) {
        throw "Backend Jar was not found under backend\target."
    }

    return @{
        Java = $java
        BackendJar = $backendJar
    }
}

function Write-Status {
    param([string]$Root, [string]$BackendPort, [string]$AsrPort, [string]$OcrPort)

    function Write-ServiceStatus {
        param([string]$Name, [string]$Url)

        $running = Test-ManagedProcess -Root $Root -Name $Name
        $httpOk = Test-HttpOk -Url $Url -TimeoutSeconds 2
        $processText = if ($running) { "running" } else { "stopped" }
        $httpText = if ($httpOk) { "http ok" } else { "http down" }
        Write-Host ("{0,-8} {1,-8} {2,-9} {3}" -f $Name, $processText, $httpText, $Url)
    }

    Write-Host "Project root: $Root"
    Write-ServiceStatus -Name "backend" -Url "http://127.0.0.1:$BackendPort/"
    Write-ServiceStatus -Name "asr" -Url "http://127.0.0.1:$AsrPort/health"
    Write-ServiceStatus -Name "ocr" -Url "http://127.0.0.1:$OcrPort/health"
    Write-Host "Logs: $Root\logs"
}

$Root = Get-RepoRoot
Ensure-Directories -Root $Root
Import-LocalEnv -Root $Root
Initialize-LocalEnvironment -Root $Root

$backendPort = if ($env:SERVER_PORT) { $env:SERVER_PORT } else { "8090" }
$asrPort = if ($env:ASR_PORT) { $env:ASR_PORT } else { "9000" }
$ocrPort = if ($env:OCR_SERVICE_PORT) { $env:OCR_SERVICE_PORT } else { "9001" }
$env:APP_ASR_BASE_URL = if ($env:APP_ASR_BASE_URL) { $env:APP_ASR_BASE_URL } else { "http://localhost:$asrPort" }
$env:APP_SUBTITLE_OCR_BASE_URL = if ($env:APP_SUBTITLE_OCR_BASE_URL) { $env:APP_SUBTITLE_OCR_BASE_URL } else { "http://localhost:$ocrPort" }

if ($Action -eq "stop") {
    Stop-ManagedProcess -Root $Root -Name "backend"
    Stop-ManagedProcess -Root $Root -Name "ocr"
    Stop-ManagedProcess -Root $Root -Name "asr"
    exit 0
}

if ($Action -eq "status") {
    Write-Status -Root $Root -BackendPort $backendPort -AsrPort $asrPort -OcrPort $ocrPort
    exit 0
}

$forceBuild = $Action -in @("setup", "rebuild")
$ready = Ensure-Ready -Root $Root -ForceBuild $forceBuild
if ($Action -in @("setup", "rebuild")) {
    Write-Host "Setup completed. Double-click start-local.bat to start."
    exit 0
}

$asrPython = Join-Path $Root "asr-service\.venv\Scripts\python.exe"
$ocrPython = Join-Path $Root "ocr-service\.venv\Scripts\python.exe"
if (-not (Test-Path $asrPython)) { throw "ASR Python environment not found: $asrPython" }
if (-not (Test-Path $ocrPython)) { throw "OCR Python environment not found: $ocrPython" }

Write-Step "Starting services"
Write-Host "Project root: $Root"
Write-Host "Backend page: http://127.0.0.1:$backendPort/"
Write-Host "Logs: $Root\logs"

if (Test-HttpOk -Url "http://127.0.0.1:$asrPort/health" -TimeoutSeconds 2) {
    Write-Host "ASR port already responds; reusing http://127.0.0.1:$asrPort/health"
} else {
    Start-ManagedProcess -Root $Root -Name "asr" -FilePath $asrPython -Arguments @("app.py") -WorkingDirectory (Join-Path $Root "asr-service")
}

if (Test-HttpOk -Url "http://127.0.0.1:$ocrPort/health" -TimeoutSeconds 2) {
    Write-Host "OCR port already responds; reusing http://127.0.0.1:$ocrPort/health"
} else {
    Start-ManagedProcess -Root $Root -Name "ocr" -FilePath $ocrPython -Arguments @("ocr_app.py") -WorkingDirectory (Join-Path $Root "ocr-service")
}

if (Test-HttpOk -Url "http://127.0.0.1:$backendPort/" -TimeoutSeconds 2) {
    Write-Host "Backend port already responds; opening the page."
} else {
    Start-ManagedProcess -Root $Root -Name "backend" -FilePath $ready.Java -Arguments @("-jar", $ready.BackendJar) -WorkingDirectory $Root
}

Write-Host "Waiting for backend..."
if (Wait-HttpOk -Url "http://127.0.0.1:$backendPort/" -TimeoutSeconds 80) {
    if (-not $NoBrowser) {
        Start-Process "http://127.0.0.1:$backendPort/"
    }
    Write-Host "Started."
    Write-Host "Stop services: start-local.bat stop"
    Write-Host "Check status:  start-local.bat status"
} else {
    Write-Host "Backend did not become ready in time. Check logs\backend.err.log and logs\backend.out.log."
}
