function Start-FlashSlotPrivateRedis {
    $taskRedisCommand = Get-Command redis-server.exe -ErrorAction SilentlyContinue
    $taskRedisExe = if ($env:REDIS_SERVER_EXE) { $env:REDIS_SERVER_EXE } elseif ($taskRedisCommand) { $taskRedisCommand.Source } else { 'D:\Redis\Redis-7.0.15-Windows-x64-msys2\redis-server.exe' }
    if (-not (Test-Path -LiteralPath $taskRedisExe)) { throw 'Set REDIS_SERVER_EXE to redis-server.exe.' }
    $taskRedisTemp = Join-Path ([IO.Path]::GetTempPath()) ('FlashSlotRedis-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskRedisTemp | Out-Null
    $taskRedisSocket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $taskRedisSocket.Start(); $taskRedisPort = $taskRedisSocket.LocalEndpoint.Port; $taskRedisSocket.Stop()
    $taskRedisInfo = [Diagnostics.ProcessStartInfo]::new($taskRedisExe)
    $taskRedisInfo.UseShellExecute=$false; $taskRedisInfo.CreateNoWindow=$true
    $taskRedisInfo.RedirectStandardOutput=$true; $taskRedisInfo.RedirectStandardError=$true
    foreach($taskRedisArg in @('--bind','127.0.0.1','--port',"$taskRedisPort",'--save','','--appendonly','no','--dir',$taskRedisTemp)) { $taskRedisInfo.ArgumentList.Add($taskRedisArg) }
    $taskRedisProcess=[Diagnostics.Process]::Start($taskRedisInfo)
    $taskRedisOutput=$taskRedisProcess.StandardOutput.ReadToEndAsync()
    $taskRedisError=$taskRedisProcess.StandardError.ReadToEndAsync()
    return @{Process=$taskRedisProcess;Port=$taskRedisPort;Directory=$taskRedisTemp;Output=$taskRedisOutput;Error=$taskRedisError}
}

function Stop-FlashSlotPrivateRedis($Instance) {
    if(-not $Instance) { return }
    if(-not $Instance.Process.HasExited) { $Instance.Process.Kill($true); $Instance.Process.WaitForExit(5000) | Out-Null }
    $taskRedisResolved=[IO.Path]::GetFullPath($Instance.Directory)
    $taskRedisTempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    if(-not $taskRedisResolved.StartsWith($taskRedisTempRoot,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($taskRedisResolved) -notlike 'FlashSlotRedis-*') { throw 'Refusing Redis cleanup outside owned temporary directory.' }
    Remove-Item -LiteralPath $taskRedisResolved -Recurse -Force
    $Instance.Process.Dispose()
}
