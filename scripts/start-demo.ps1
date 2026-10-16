param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { $null }
if (-not $javaExe -or -not (Test-Path -LiteralPath $javaExe)) {
    throw 'Set JAVA_HOME to your JDK 21 directory before starting the demo.'
}
$runDir = Join-Path $projectRoot '.run'
New-Item -ItemType Directory -Force $runDir | Out-Null

# Nacos is a separately managed local component, not part of the application jars.
$nacosAddress = if ($env:NACOS_SERVER_ADDR) { $env:NACOS_SERVER_ADDR } else { '127.0.0.1:8848' }
try {
    Invoke-RestMethod "http://$nacosAddress/nacos/v1/console/health/readiness" -TimeoutSec 5 | Out-Null
} catch {
    throw "Nacos is not ready at $nacosAddress. Start Nacos in standalone mode first."
}

$services = @(
    @{ Name = 'user-8081'; Module = 'user'; Port = 8081 },
    @{ Name = 'user-8083'; Module = 'user'; Port = 8083 },
    @{ Name = 'order-8082'; Module = 'order'; Port = 8082 }
)
foreach ($service in $services) {
    if (Get-NetTCPConnection -State Listen -LocalPort $service.Port -ErrorAction SilentlyContinue) {
        throw "Port $($service.Port) is already in use. Stop the previous demo before starting again."
    }
}
if (-not $SkipBuild) {
    Push-Location $projectRoot
    try {
        & mvn package -B -ntp
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
    } finally { Pop-Location }
}
foreach ($service in $services) {
    $jarPath = Join-Path $projectRoot "$($service.Module)/target/$($service.Module)-1.0-SNAPSHOT.jar"
    if (-not (Test-Path -LiteralPath $jarPath)) { throw "Missing jar: $jarPath" }
}
$stateFile = Join-Path $runDir 'services.json'
$processes = @()
foreach ($service in $services) {
    $jarPath = Join-Path $projectRoot "$($service.Module)/target/$($service.Module)-1.0-SNAPSHOT.jar"
    $javaProcess = Start-Process -FilePath $javaExe `
        -ArgumentList "-Xms64m -Xmx256m -jar `"$jarPath`" --server.port=$($service.Port)" `
        -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runDir "$($service.Name).out.log") `
        -RedirectStandardError (Join-Path $runDir "$($service.Name).err.log")
    $processes += @{ Name = $service.Name; Id = $javaProcess.Id; Jar = $jarPath }
    ConvertTo-Json -InputObject @($processes) | Set-Content -LiteralPath $stateFile
    Write-Host "Started $($service.Name), PID $($javaProcess.Id)"
}
Write-Host 'Waiting for startup and Nacos registration...'
$deadline = (Get-Date).AddSeconds(60)
do {
    $ready = $true
    foreach ($port in 8081,8083,8082) {
        if (-not (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) {
            $ready = $false
        }
    }
    if (-not $ready) { Start-Sleep -Seconds 1 }
} until ($ready -or (Get-Date) -gt $deadline)
if (-not $ready) { throw "Services did not start in time. See $runDir for logs; use stop-demo.ps1 to stop them." }
Write-Host 'Ports are ready. Run ./scripts/test-demo.ps1 to verify the complete discovery and calling chain.'
