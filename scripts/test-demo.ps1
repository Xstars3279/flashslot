$ErrorActionPreference = 'Stop'
$orderUrl = 'http://127.0.0.1:8082/orders'
$body = @{ userId = 1; productName = 'book'; quantity = 2 } | ConvertTo-Json
$results = @()
# Registration and subscription are asynchronous; allow a short startup window.
$deadline = (Get-Date).AddSeconds(45)
do {
    try {
        $first = Invoke-RestMethod -Method Post -Uri $orderUrl -ContentType 'application/json' -Body $body -TimeoutSec 8
        break
    } catch {
        if ((Get-Date) -ge $deadline) { throw }
        Start-Sleep -Seconds 1
    }
} while ($true)
$results += $first
1..9 | ForEach-Object {
    $results += Invoke-RestMethod -Method Post -Uri $orderUrl -ContentType 'application/json' -Body $body -TimeoutSec 8
}
$results | Select-Object id,productName,quantity,@{Name='userInstance';Expression={$_.user.instance}} | Format-Table
$counts = @($results | Group-Object { $_.user.instance } | Select-Object Name,Count)
$counts | Format-Table
$read = Invoke-RestMethod "$orderUrl/$($first.id)" -TimeoutSec 5
if ($read.id -ne $first.id -or $read.user.instance -ne $first.user.instance) {
    throw 'Order query did not return the saved order.'
}
if ($counts.Name -notcontains 'user:8081' -or $counts.Name -notcontains 'user:8083') {
    throw 'Both user instances were not observed. Check Nacos registration and retry.'
}
$report = @{ checkedAt = (Get-Date).ToString('o'); counts = $counts; orders = $results; queriedOrder = $read }
$runDir = Join-Path (Split-Path $PSScriptRoot -Parent) '.run'
New-Item -ItemType Directory -Force $runDir | Out-Null
$report | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $runDir 'verification.json')
Write-Host 'PASS: orders created and queried; both user instances handled Feign calls.'
