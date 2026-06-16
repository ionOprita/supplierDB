# =====================================================================
# User configuration
# =====================================================================

# The exact local directory where the repository should live.
# The script clones directly into this directory.
$AppDirectory = "C:\Users\Oprita\Desktop\JavaServer\app"

# Your GitHub repository URL.
# Example: https://github.com/my-org/my-java-app.git
$RepositoryUrl = "https://github.com/ionOprita/supplierDB.git"

# Optional branch. Leave empty to use the default/current branch.
# Example: "main"
$Branch = ""

# Log files will be written here.
$LogDirectory = "C:\Users\Oprita\Desktop\JavaServer\logs"

# Delay before restarting the app after it exits or crashes.
$RestartDelaySeconds = 15

# Keep Maven's downloaded dependencies in a predictable location.
# This is especially useful if the task runs as SYSTEM.
$MavenLocalRepository = "C:\Users\Oprita\Desktop\JavaServer\.m2\repository"

# Maven command. Use "mvn" if Maven is installed system-wide.
# If your repo has Maven Wrapper, you may use ".\mvnw.cmd" instead.
$MavenCommand = "mvn"

# The Maven command line to run your app.
$MavenArguments = @(
    "-Dmaven.repo.local=$MavenLocalRepository",
    "compile",
    "exec:java"
)

# =====================================================================
# Do not normally edit below this line
# =====================================================================

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

function Get-CurrentLogFile {
    return Join-Path $LogDirectory ("java-app-{0}.log" -f (Get-Date -Format "yyyy-MM-dd"))
}

function Write-Log {
    param(
        [AllowEmptyString()]
        [object] $Message
    )

    Ensure-Directory $LogDirectory

    $text = ""
    if ($null -ne $Message) {
        $text = $Message.ToString()
    }

    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $text
    Add-Content -LiteralPath (Get-CurrentLogFile) -Value $line -Encoding UTF8

    # Useful when running manually. Does not pollute function return values.
    Write-Host $line
}

function Test-DirectoryEmpty {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $firstItem = Get-ChildItem -LiteralPath $Path -Force -ErrorAction SilentlyContinue | Select-Object -First 1
    return ($null -eq $firstItem)
}

function Invoke-LoggedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string] $FilePath,

        [string[]] $Arguments = @(),

        [Parameter(Mandatory = $true)]
        [string] $WorkingDirectory
    )

    Write-Log "> cd `"$WorkingDirectory`""
    Write-Log ("> {0} {1}" -f $FilePath, ($Arguments -join " "))

    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $FilePath @Arguments 2>&1 | ForEach-Object {
            Write-Log $_.ToString()
        }

        $exitCode = $LASTEXITCODE
        if ($null -eq $exitCode) {
            $exitCode = 0
        }

        Write-Log ("Command exited with code {0}: {1}" -f $exitCode, $FilePath)
        return [int]$exitCode
    }
    finally {
        Pop-Location
    }
}

function Sync-Repository {
    Ensure-Directory (Split-Path -Parent $AppDirectory)

    if (-not (Test-Path -LiteralPath $AppDirectory -PathType Container)) {
        Write-Log "Application directory does not exist. Creating: $AppDirectory"
        Ensure-Directory $AppDirectory
    }

    $gitDirectory = Join-Path $AppDirectory ".git"

    if (Test-Path -LiteralPath $gitDirectory -PathType Container) {
        Write-Log "Application directory exists and is a Git repository. Pulling latest changes."

        $exitCode = Invoke-LoggedCommand -FilePath "git" -Arguments @("fetch", "--all", "--prune") -WorkingDirectory $AppDirectory
        if ($exitCode -ne 0) {
            throw "git fetch failed with exit code $exitCode"
        }

        if (-not [string]::IsNullOrWhiteSpace($Branch)) {
            $exitCode = Invoke-LoggedCommand -FilePath "git" -Arguments @("checkout", $Branch) -WorkingDirectory $AppDirectory
            if ($exitCode -ne 0) {
                throw "git checkout $Branch failed with exit code $exitCode"
            }
        }

        $exitCode = Invoke-LoggedCommand -FilePath "git" -Arguments @("pull", "--ff-only") -WorkingDirectory $AppDirectory
        if ($exitCode -ne 0) {
            throw "git pull failed with exit code $exitCode"
        }

        return
    }

    if (Test-DirectoryEmpty $AppDirectory) {
        Write-Log "Application directory exists but is empty. Cloning repository."

        $cloneArguments = @("clone")

        if (-not [string]::IsNullOrWhiteSpace($Branch)) {
            $cloneArguments += @("--branch", $Branch)
        }

        $cloneArguments += @($RepositoryUrl, ".")

        $exitCode = Invoke-LoggedCommand -FilePath "git" -Arguments $cloneArguments -WorkingDirectory $AppDirectory
        if ($exitCode -ne 0) {
            throw "git clone failed with exit code $exitCode"
        }

        return
    }

    throw "Directory '$AppDirectory' exists, is not empty, and is not a Git repository. Move it away, delete it, or choose another AppDirectory."
}

function Invoke-ForegroundCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string] $FilePath,

        [string[]] $Arguments = @(),

        [Parameter(Mandatory = $true)]
        [string] $WorkingDirectory
    )

    Write-Log "> cd `"$WorkingDirectory`""
    Write-Log ("> {0} {1}" -f $FilePath, ($Arguments -join " "))

    Push-Location -LiteralPath $WorkingDirectory
    try {
        # Important:
        # No PowerShell pipeline here.
        # The Java app inherits the real console.
        & $FilePath @Arguments

        $exitCode = $LASTEXITCODE
        if ($null -eq $exitCode) {
            $exitCode = 0
        }

        Write-Log ("Command exited with code {0}: {1}" -f $exitCode, $FilePath)
        return [int]$exitCode
    }
    finally {
        Pop-Location
    }
}

Ensure-Directory $LogDirectory
Ensure-Directory $MavenLocalRepository

$TranscriptLogFile = Join-Path $LogDirectory ("java-app-console-{0}.log" -f (Get-Date -Format "yyyy-MM-dd"))

try {
    Start-Transcript -Path $TranscriptLogFile -Append | Out-Null
}
catch {
    Write-Host "Could not start transcript logging: $($_.Exception.Message)"
}

Write-Log "============================================================"
Write-Log "Java application supervisor started."
Write-Log "AppDirectory: $AppDirectory"
Write-Log "RepositoryUrl: $RepositoryUrl"
Write-Log "Branch: $(if ([string]::IsNullOrWhiteSpace($Branch)) { '<default/current>' } else { $Branch })"
Write-Log "LogDirectory: $LogDirectory"
Write-Log "MavenCommand: $MavenCommand"
Write-Log "MavenArguments: $($MavenArguments -join ' ')"
Write-Log "============================================================"

while ($true) {
    try {
        Sync-Repository

        Write-Log "Starting Java application via Maven."
        $exitCode = Invoke-ForegroundCommand -FilePath $MavenCommand -Arguments $MavenArguments -WorkingDirectory $AppDirectory

        Write-Log "Java application / Maven process terminated with exit code $exitCode."
    }
    catch {
        Write-Log "ERROR: $($_.Exception.Message)"
        Write-Log "ERROR DETAILS:"
        Write-Log ($_ | Out-String)
    }

    Write-Log "Restarting in $RestartDelaySeconds seconds."
    Start-Sleep -Seconds $RestartDelaySeconds
}