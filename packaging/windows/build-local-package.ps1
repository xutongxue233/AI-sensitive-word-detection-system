param(
    [string]$OutputDir,
    [string]$PackageName = "AI-sensitive-word-detection-system-local",
    [string]$JdkHome,
    [string]$PythonHome,
    [bool]$IncludePythonVenv = $true,
    [bool]$IncludePythonRuntime = $false,
    [bool]$IncludeJdk = $false,
    [bool]$RunTests = $false,
    [switch]$NoZip
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir "..\..")).Path
if (-not $OutputDir) {
    $OutputDir = Join-Path $RepoRoot "release"
}
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)
$PackageRoot = Join-Path $OutputDir $PackageName
$FrontendDir = Join-Path $RepoRoot "frontend"
$BackendDir = Join-Path $RepoRoot "backend"
$StaticDir = Join-Path $BackendDir "src\main\resources\static"

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
        throw "Source directory not found: $Source"
    }
    if (Test-Path $Destination) {
        Remove-Item -LiteralPath $Destination -Recurse -Force
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
    Copy-Item -LiteralPath $Source -Destination $Destination -Recurse -Force
}

function Copy-FileIfExists {
    param([string]$Source, [string]$Destination)
    if (Test-Path $Source) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
        Copy-Item -LiteralPath $Source -Destination $Destination -Force
    }
}

function Copy-DirectoryContents {
    param([string]$Source, [string]$Destination)

    if (-not (Test-Path $Source)) {
        throw "Source directory not found: $Source"
    }
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $Destination $_.Name) -Recurse -Force
    }
}

function Get-VenvHome {
    param([string]$VenvPath)

    $cfg = Join-Path $VenvPath "pyvenv.cfg"
    if (-not (Test-Path $cfg)) {
        return $null
    }
    foreach ($line in Get-Content -LiteralPath $cfg) {
        if ($line -match "^home\s*=\s*(.+)$") {
            return $Matches[1].Trim()
        }
    }
    return $null
}

$javaHomeForBuild = if ($JdkHome) { $JdkHome } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else { "" }
if ($javaHomeForBuild) {
    $javaExe = Join-Path $javaHomeForBuild "bin\java.exe"
    if (-not (Test-Path $javaExe)) {
        throw "JDK not found: $javaExe"
    }
    $env:JAVA_HOME = $javaHomeForBuild
    $env:Path = "$javaHomeForBuild\bin;$env:Path"
}

Write-Host "Repo root: $RepoRoot"
Write-Host "Package:   $PackageRoot"

if (Test-Path $PackageRoot) {
    Remove-Item -LiteralPath $PackageRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $PackageRoot -Force | Out-Null

try {
    Invoke-Checked -FilePath "npm.cmd" -Arguments @("run", "build") -WorkingDirectory $FrontendDir

    if (Test-Path $StaticDir) {
        Remove-Item -LiteralPath $StaticDir -Recurse -Force
    }
    Copy-DirectoryClean -Source (Join-Path $FrontendDir "dist") -Destination $StaticDir

    $mavenArgs = @("-q")
    if (-not $RunTests) {
        $mavenArgs += "-DskipTests"
    }
    $mavenArgs += "package"
    Invoke-Checked -FilePath "mvn.cmd" -Arguments $mavenArgs -WorkingDirectory $BackendDir
} finally {
    if (Test-Path $StaticDir) {
        Remove-Item -LiteralPath $StaticDir -Recurse -Force
    }
}

$jar = Get-ChildItem -Path (Join-Path $BackendDir "target") -Filter "*.jar" |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "Backend jar not found under backend\target"
}

New-Item -ItemType Directory -Path (Join-Path $PackageRoot "backend") -Force | Out-Null
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $PackageRoot "backend\video-moderation.jar") -Force

Copy-DirectoryContents -Source (Join-Path $ScriptDir "runtime") -Destination $PackageRoot

New-Item -ItemType Directory -Path (Join-Path $PackageRoot "logs") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $PackageRoot "storage") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $PackageRoot ".runtime") -Force | Out-Null

$ffmpegSource = Join-Path $BackendDir "tools\ffmpeg"
if (Test-Path $ffmpegSource) {
    Copy-DirectoryClean -Source $ffmpegSource -Destination (Join-Path $PackageRoot "tools\ffmpeg")
} else {
    Write-Warning "FFmpeg directory not found: $ffmpegSource"
}

$asrTarget = Join-Path $PackageRoot "asr-service"
New-Item -ItemType Directory -Path $asrTarget -Force | Out-Null
"app.py", "requirements.txt", "pyproject.toml", "uv.lock", "install-gpu.bat" | ForEach-Object {
    Copy-FileIfExists -Source (Join-Path $RepoRoot "asr-service\$_") -Destination (Join-Path $asrTarget $_)
}

$ocrTarget = Join-Path $PackageRoot "ocr-service"
New-Item -ItemType Directory -Path $ocrTarget -Force | Out-Null
"ocr_app.py", "ocr_core.py", "requirements.txt", "install-ocr-gpu.bat" | ForEach-Object {
    Copy-FileIfExists -Source (Join-Path $RepoRoot "ocr-service\$_") -Destination (Join-Path $ocrTarget $_)
}

if ($IncludePythonVenv) {
    $asrVenv = Join-Path $RepoRoot "asr-service\.venv"
    $ocrVenv = Join-Path $RepoRoot "ocr-service\.venv"
    if (Test-Path $asrVenv) {
        Copy-DirectoryClean -Source $asrVenv -Destination (Join-Path $asrTarget ".venv")
    } else {
        Write-Warning "ASR venv not found, package will not be unzip-and-run for ASR: $asrVenv"
    }
    if (Test-Path $ocrVenv) {
        Copy-DirectoryClean -Source $ocrVenv -Destination (Join-Path $ocrTarget ".venv")
    } else {
        Write-Warning "OCR venv not found, package will not be unzip-and-run for OCR: $ocrVenv"
    }
}

if ($IncludePythonRuntime) {
    $pythonHomeForPackage = $PythonHome
    if (-not $pythonHomeForPackage) {
        $pythonHomeForPackage = Get-VenvHome -VenvPath (Join-Path $RepoRoot "asr-service\.venv")
    }
    if (-not $pythonHomeForPackage) {
        throw "Use -PythonHome when -IncludePythonRuntime is true."
    }
    $pythonExe = Join-Path $pythonHomeForPackage "python.exe"
    if (-not (Test-Path $pythonExe)) {
        throw "Python runtime not found: $pythonExe"
    }
    Copy-DirectoryClean -Source $pythonHomeForPackage -Destination (Join-Path $PackageRoot "runtime\python")
}

if ($IncludeJdk) {
    if (-not $javaHomeForBuild) {
        throw "Use -JdkHome when -IncludeJdk is true."
    }
    Copy-DirectoryClean -Source $javaHomeForBuild -Destination (Join-Path $PackageRoot "runtime\jdk")
}

if (-not $NoZip) {
    $zip = Join-Path $OutputDir "$PackageName.zip"
    if (Test-Path $zip) {
        Remove-Item -LiteralPath $zip -Force
    }
    Compress-Archive -Path (Join-Path $PackageRoot "*") -DestinationPath $zip -Force
    Write-Host "Zip created: $zip"
}

Write-Host "Package created: $PackageRoot"
Write-Host "Run: $PackageRoot\start.bat"
