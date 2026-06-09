$ErrorActionPreference = "Stop"

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Import-LocalEnv {
    param([string]$Root)

    $configDir = Join-Path $Root "config"
    $envFile = Join-Path $configDir "local.env"
    $example = Join-Path $configDir "local.env.example"
    if (-not (Test-Path $envFile) -and (Test-Path $example)) {
        New-Item -ItemType Directory -Path $configDir -Force | Out-Null
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

function Ensure-LocalDirectories {
    param([string]$Root)

    "logs", ".runtime", "storage", "config" | ForEach-Object {
        $path = Join-Path $Root $_
        if (-not (Test-Path $path)) {
            New-Item -ItemType Directory -Path $path | Out-Null
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
    $jdkHome = Join-Path $runtimeRoot "jdk"
    $nodeHome = Join-Path $runtimeRoot "node"
    $mavenBin = Join-Path $runtimeRoot "maven\bin"
    $runtimeFfmpegDir = Join-Path $runtimeRoot "ffmpeg\bin"
    $sourceFfmpegDir = Join-Path $Root "backend\tools\ffmpeg\bin"
    $ffmpegDir = if (Test-Path $runtimeFfmpegDir) { $runtimeFfmpegDir } else { $sourceFfmpegDir }

    if (Test-Path (Join-Path $jdkHome "bin\java.exe")) {
        $env:JAVA_HOME = $jdkHome
        Add-PathIfExists -Path (Join-Path $jdkHome "bin")
    }
    Add-PathIfExists -Path $nodeHome
    Add-PathIfExists -Path $mavenBin

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

function Resolve-LocalExecutable {
    param(
        [string]$Root,
        [string]$RuntimeRelativePath,
        [string]$CommandName,
        [string]$DisplayName
    )

    $runtimePath = Join-Path $Root $RuntimeRelativePath
    if (Test-Path $runtimePath) {
        return (Resolve-Path $runtimePath).Path
    }
    $command = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "$DisplayName not found. Install it or unzip it into $RuntimeRelativePath."
}

function Resolve-LocalJava {
    param([string]$Root)

    $runtimeJava = Join-Path $Root ".runtime\jdk\bin\java.exe"
    if (Test-Path $runtimeJava) {
        $env:JAVA_HOME = Join-Path $Root ".runtime\jdk"
        return (Resolve-Path $runtimeJava).Path
    }
    if ($env:JAVA_HOME) {
        $javaHomeExe = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $javaHomeExe) {
            return (Resolve-Path $javaHomeExe).Path
        }
    }
    $command = Get-Command "java.exe" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "Java 21 not found. Install Java 21 or unzip it into .runtime\jdk."
}

function Assert-Java21 {
    param([string]$JavaExe)

    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $JavaExe -version 2>&1 | Out-String
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($output -notmatch 'version "([0-9]+)(?:\.|")') {
        throw "Cannot detect Java version from: $JavaExe"
    }
    $major = [int]$Matches[1]
    if ($major -lt 21) {
        throw "Java 21+ is required, current version output: $output"
    }
}

function Resolve-LocalPython {
    param([string]$Root)

    if ($env:PYTHON_EXE -and (Test-Path $env:PYTHON_EXE)) {
        return (Resolve-Path $env:PYTHON_EXE).Path
    }
    return Resolve-LocalExecutable `
        -Root $Root `
        -RuntimeRelativePath ".runtime\python\python.exe" `
        -CommandName "python.exe" `
        -DisplayName "Python 3.10"
}

function Resolve-LocalNpm {
    param([string]$Root)

    return Resolve-LocalExecutable `
        -Root $Root `
        -RuntimeRelativePath ".runtime\node\npm.cmd" `
        -CommandName "npm.cmd" `
        -DisplayName "Node.js/npm"
}

function Resolve-LocalMaven {
    param([string]$Root)

    return Resolve-LocalExecutable `
        -Root $Root `
        -RuntimeRelativePath ".runtime\maven\bin\mvn.cmd" `
        -CommandName "mvn.cmd" `
        -DisplayName "Maven"
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

function Ensure-PythonVenv {
    param(
        [string]$Root,
        [string]$ServiceName,
        [string]$PythonExe,
        [string[]]$ExtraPackages = @()
    )

    $serviceDir = Join-Path $Root $ServiceName
    $venvDir = Join-Path $serviceDir ".venv"
    $venvPython = Join-Path $venvDir "Scripts\python.exe"
    if (-not (Test-Path $venvPython)) {
        Invoke-Checked -FilePath $PythonExe -Arguments @("-m", "venv", ".venv") -WorkingDirectory $serviceDir
    }
    Invoke-Checked -FilePath $venvPython -Arguments @("-m", "pip", "install", "--upgrade", "pip", "setuptools", "wheel") -WorkingDirectory $serviceDir
    Invoke-Checked -FilePath $venvPython -Arguments @("-m", "pip", "install", "-r", "requirements.txt") -WorkingDirectory $serviceDir
    foreach ($package in $ExtraPackages) {
        Invoke-Checked -FilePath $venvPython -Arguments @("-m", "pip", "install", $package) -WorkingDirectory $serviceDir
    }
}

function Copy-DirectoryClean {
    param([string]$Source, [string]$Destination)

    if (-not (Test-Path $Source)) {
        throw "Source directory not found: $Source"
    }
    if (Test-Path $Destination) {
        Remove-Item -LiteralPath $Destination -Recurse -Force
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
    Copy-Item -LiteralPath $Source -Destination $Destination -Recurse -Force
}

function Build-LocalBackendJar {
    param(
        [string]$Root,
        [string]$Npm,
        [string]$Maven,
        [bool]$RunTests = $false
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
    $processId = (Get-Content -LiteralPath $pidPath -ErrorAction SilentlyContinue | Select-Object -First 1)
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
        Write-Host "$Name already running."
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
        Write-Host "$Name not started."
        return
    }
    $processId = (Get-Content -LiteralPath $pidPath -ErrorAction SilentlyContinue | Select-Object -First 1)
    if ($processId) {
        $process = Get-Process -Id ([int]$processId) -ErrorAction SilentlyContinue
        if ($process) {
            Stop-Process -Id $process.Id -Force
            Write-Host "Stopped $Name, pid=$processId"
        } else {
            Write-Host "$Name pid file exists but process is not running."
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
    param([string]$Url, [int]$TimeoutSeconds = 60)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-HttpOk -Url $Url -TimeoutSeconds 2) {
            return $true
        }
        Start-Sleep -Seconds 1
    }
    return $false
}
