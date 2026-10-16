$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$stateFile = Join-Path $projectRoot '.run/services.json'
if (-not (Test-Path -LiteralPath $stateFile)) { Write-Host 'No demo processes recorded.'; return }
foreach ($entry in @(Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json)) {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($entry.Id)"
    # Check the jar path before stopping a recorded PID, in case Windows reused it.
    if ($processInfo -and $processInfo.Name -eq 'java.exe' -and
        $processInfo.CommandLine.Contains($entry.Jar)) {
        Stop-Process -Id $entry.Id
        Write-Host "Stopped $($entry.Name), PID $($entry.Id)"
    }
}
Remove-Item -LiteralPath $stateFile
Write-Host 'Application services stopped. Nacos remains running.'
