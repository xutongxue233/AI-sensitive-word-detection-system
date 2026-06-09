$ErrorActionPreference = "Stop"

function Get-PackageRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

function Import-LocalEnv {
    param([string]$Root)

    $envFile = Join-Path $Root "config\local.env"
    $example = Join-Path $Root "config\local.env.example"
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

function Ensure-Directories {
    param([string]$Root)

    "logs", ".runtime", "storage", "config" | ForEach-Object {
        $path = Join-Path $Root $_
        if (-not (Test-Path $path)) {
            New-Item -ItemType Directory -Path $path | Out-Null
        }
    }
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

function Resolve-Executable {
    param([string]$Preferred, [string]$FallbackCommand, [string]$DisplayName)

    if ($Preferred -and (Test-Path $Preferred)) {
        return (Resolve-Path $Preferred).Path
    }
    $command = Get-Command $FallbackCommand -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "$DisplayName not found. Put it into the package or install it on this machine."
}
