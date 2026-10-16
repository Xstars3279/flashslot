$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskStateFile=Join-Path $taskRoot '.run/flashslot-local/processes.json'
if(-not (Test-Path -LiteralPath $taskStateFile)) {Write-Output 'No managed FlashSlot local session.';return}
$taskState=Get-Content -LiteralPath $taskStateFile -Raw | ConvertFrom-Json
# 逆序停前后端，再停数据库；PID复用时拒绝误杀其他进程。
$taskProcesses=@($taskState.Processes);[array]::Reverse($taskProcesses)
foreach($taskEntry in $taskProcesses) {
    $taskOwned=Get-Process -Id $taskEntry.Id -ErrorAction SilentlyContinue
    if(-not $taskOwned) {continue}
    if($taskOwned.StartTime.ToUniversalTime().Ticks -ne $taskEntry.Ticks -or -not [string]::Equals($taskOwned.Path,$taskEntry.Path,[StringComparison]::OrdinalIgnoreCase)) {throw "Process identity changed for $($taskEntry.Name); refusing stop."}
    if($taskEntry.Name -eq 'mysql' -and $taskState.MysqlPort) {
        $taskMysqlArgs=@('--no-defaults','--protocol=TCP','--host=127.0.0.1',"--port=$($taskState.MysqlPort)",'--user=root','--ssl-mode=DISABLED','--batch','--skip-column-names')
        $taskActualData=& mysql.exe @taskMysqlArgs '--execute=SELECT @@datadir;' 2>$null
        if($LASTEXITCODE -eq 0 -and [IO.Path]::GetFullPath($taskActualData.Replace('/','\')).TrimEnd('\') -eq [IO.Path]::GetFullPath((Join-Path $taskRoot '.run/flashslot-local/mysql')).TrimEnd('\')) {
            & mysql.exe @taskMysqlArgs '--execute=SHUTDOWN;' 2>$null
            $taskOwned.WaitForExit(10000) | Out-Null
        }
    }
    if(-not $taskOwned.HasExited) { $taskOwned.Kill($true);$taskOwned.WaitForExit(10000) | Out-Null }
}
if($taskState.Rabbit) { . (Join-Path $PSScriptRoot 'Private-RabbitMQ.ps1');Stop-FlashSlotPrivateRabbit -Runtime $taskState.Rabbit }
if($taskState.RedisDirectory) {
    $taskRedisResolved=[IO.Path]::GetFullPath($taskState.RedisDirectory)
    $taskTempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    if(-not $taskRedisResolved.StartsWith($taskTempRoot,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskRedisResolved) -notlike 'FlashSlotRedis-*') {throw 'Refusing cleanup outside owned Redis directory.'}
    Remove-Item -LiteralPath $taskRedisResolved -Recurse -Force
}
Remove-Item -LiteralPath $taskStateFile
Write-Output 'FlashSlot local processes stopped. Demo MySQL data retained.'
