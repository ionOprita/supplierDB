param(
    [string] $TaskName = "Java Web App",
    [int] $DelaySeconds = 2
)

$ErrorActionPreference = "Stop"

if ($DelaySeconds -gt 0) {
    Start-Sleep -Seconds $DelaySeconds
}

try {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
}
catch {
    Write-Host "Could not stop scheduled task '$TaskName': $($_.Exception.Message)"
}

Start-ScheduledTask -TaskName $TaskName
