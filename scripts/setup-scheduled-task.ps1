$TaskName = "Java Web App"
$ScriptPath = Join-Path $PSScriptRoot "run-java-app.ps1"
$WorkingDirectory = "C:\Users\Oprita\Desktop\JavaServer"

# Let's Encrypt / win-acme setup.
# Public 443 must forward to local 8443.
# Public 80 must forward to local 8080 for HTTP-01 validation.
$EnableLetsEncrypt = $true
$LetsEncryptHostName = "server.sellfusion.ro"
$LetsEncryptEmail = "office@sellfusion.ro"
$LocalHttpPort = 8080
$LetsEncryptCertificateDirectory = "C:\Users\Oprita\Desktop\JavaServer\certs"
$LetsEncryptChallengeWebRoot = "C:\Users\Oprita\Desktop\JavaServer\acme-challenge"
$LetsEncryptCertificatePath = Join-Path $LetsEncryptCertificateDirectory "$LetsEncryptHostName.pfx"
$LetsEncryptPasswordFile = Join-Path $LetsEncryptCertificateDirectory "$LetsEncryptHostName.pw"
$WinAcmeExecutable = ""
$WinAcmeFriendlyName = "sellfusion-java-server"
$ForceLetsEncryptSetup = $false
$WaitForAppTimeoutSeconds = 120
$RestartScriptPath = Join-Path $PSScriptRoot "restart-java-task.ps1"

$ErrorActionPreference = "Stop"

function Ensure-Directory {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function New-PfxPasswordFileIfMissing {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        return
    }

    Ensure-Directory (Split-Path -Parent $Path)
    $bytes = New-Object byte[] 24
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    [Convert]::ToBase64String($bytes) | Set-Content -LiteralPath $Path -Encoding ASCII
}

function Resolve-WinAcmeExecutable {
    if (-not [string]::IsNullOrWhiteSpace($WinAcmeExecutable)) {
        if (Test-Path -LiteralPath $WinAcmeExecutable -PathType Leaf) {
            return $WinAcmeExecutable
        }
        throw "Configured WinAcmeExecutable does not exist: $WinAcmeExecutable"
    }

    $command = Get-Command "wacs.exe" -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $candidates = @(
        "C:\Program Files\win-acme\wacs.exe",
        "C:\ProgramData\win-acme\wacs.exe",
        "$env:ProgramFiles\win-acme\wacs.exe"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }

    throw "win-acme was not found. Install win-acme, or set `$WinAcmeExecutable to the full path of wacs.exe."
}

function Wait-ApplicationHealth {
    $url = "http://localhost:$LocalHttpPort/health"
    $deadline = (Get-Date).AddSeconds($WaitForAppTimeoutSeconds)

    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                Write-Host "Application health check is available at $url."
                return
            }
        }
        catch {
            Start-Sleep -Seconds 3
        }
    }

    throw "Application did not answer $url within $WaitForAppTimeoutSeconds seconds."
}

function Invoke-LetsEncryptSetup {
    if (-not $EnableLetsEncrypt) {
        return
    }

    if ([string]::IsNullOrWhiteSpace($LetsEncryptEmail)) {
        throw "Set `$LetsEncryptEmail before enabling Let's Encrypt setup."
    }

    Ensure-Directory $LetsEncryptCertificateDirectory
    Ensure-Directory $LetsEncryptChallengeWebRoot
    New-PfxPasswordFileIfMissing $LetsEncryptPasswordFile

    if ((Test-Path -LiteralPath $LetsEncryptCertificatePath -PathType Leaf) -and -not $ForceLetsEncryptSetup) {
        Write-Host "Certificate already exists at $LetsEncryptCertificatePath. Skipping win-acme creation."
        Write-Host "Set `$ForceLetsEncryptSetup = `$true if you need to recreate the win-acme renewal."
        return
    }

    $wacs = Resolve-WinAcmeExecutable
    $pfxPassword = (Get-Content -LiteralPath $LetsEncryptPasswordFile -Raw).Trim()

    Write-Host "Requesting Let's Encrypt certificate for $LetsEncryptHostName."
    Write-Host "Router requirement: public port 80 must reach this machine on local port $LocalHttpPort."

    $arguments = @(
        "--source", "manual",
        "--host", $LetsEncryptHostName,
        "--commonname", $LetsEncryptHostName,
        "--friendlyname", $WinAcmeFriendlyName,
        "--validation", "filesystem",
        "--validationmode", "http-01",
        "--webroot", $LetsEncryptChallengeWebRoot,
        "--store", "pfxfile",
        "--pfxfilepath", $LetsEncryptCertificateDirectory,
        "--pfxfilename", $LetsEncryptHostName,
        "--pfxpassword", $pfxPassword,
        "--installation", "script",
        "--script", $RestartScriptPath,
        "--scriptparameters", "-TaskName '$TaskName'",
        "--accepttos",
        "--emailaddress", $LetsEncryptEmail,
        "--setuptaskscheduler"
    )

    & $wacs @arguments
    $exitCode = $LASTEXITCODE
    if ($null -eq $exitCode) {
        $exitCode = 0
    }
    if ($exitCode -ne 0) {
        throw "win-acme failed with exit code $exitCode"
    }
}

Ensure-Directory $WorkingDirectory

$Action = New-ScheduledTaskAction `
    -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$ScriptPath`"" `
    -WorkingDirectory $WorkingDirectory

$Trigger = New-ScheduledTaskTrigger -AtStartup

$Settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Seconds 0) `
    -MultipleInstances IgnoreNew

$Principal = New-ScheduledTaskPrincipal `
    -UserId "NT AUTHORITY\SYSTEM" `
    -LogonType ServiceAccount `
    -RunLevel Highest

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $Action `
    -Trigger $Trigger `
    -Settings $Settings `
    -Principal $Principal `
    -Description "Starts and supervises Java Maven application from GitHub" `
    -Force

Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
Start-ScheduledTask -TaskName $TaskName

if ($EnableLetsEncrypt) {
    Wait-ApplicationHealth
    Invoke-LetsEncryptSetup
}
