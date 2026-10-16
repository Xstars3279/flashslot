param([switch]$WithRabbit, [switch]$WithRedis, [string]$Test)
# 验证基础设施、登录、预约、候补撮合、签到及自动到期，复用私有 MySQL 隔离方式。
$taskOldEnv = @{}
$taskOldEnv['RESOURCE_CALENDAR_SCHEDULING_ENABLED'] = [Environment]::GetEnvironmentVariable('RESOURCE_CALENDAR_SCHEDULING_ENABLED')
foreach ($name in @('DB_URL','DB_USERNAME','DB_PASSWORD','APP_PROFILE','FLASHSLOT_PRIVATE_TEST','JWT_SECRET','RELEASE_SCHEDULING_ENABLED','BOOKING_EXPIRY_SCHEDULING_ENABLED','MESSAGING_ENABLED','MESSAGING_SCHEDULING_ENABLED','FLASHSLOT_PRIVATE_RABBIT_TEST','RABBIT_HOST','RABBIT_PORT','RABBIT_USERNAME','RABBIT_PASSWORD')) { $taskOldEnv[$name] = [Environment]::GetEnvironmentVariable($name) }

# Runs db/*.sql against a private, temporary local MySQL instance.
# Never connects to an existing database server. Requires local mysql/mysqld.
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskMysql = (Get-Command mysql.exe -ErrorAction Stop).Source
$taskMysqld = (Get-Command mysqld.exe -ErrorAction Stop).Source
$taskBase = Split-Path -Parent (Split-Path -Parent $taskMysqld)
$taskTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$taskTemp = Join-Path $taskTempRoot ('FlashSlotSchema-' + [guid]::NewGuid().ToString('N'))
$taskData = Join-Path $taskTemp 'data'
$taskServer = $null
$taskRabbit = $null
$taskRedis = $null
foreach ($name in @('SCHEDULE_CACHE_ENABLED','FLASHSLOT_PRIVATE_REDIS_TEST','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD')) { $taskOldEnv[$name] = [Environment]::GetEnvironmentVariable($name) }
$taskPrivateVerified = $false
$taskChecks = [Collections.Generic.List[string]]::new()
$taskReport = Join-Path $taskRoot '.run/flashslot-foundation-verification.json'
$taskTimer = [Diagnostics.Stopwatch]::StartNew()
New-Item -ItemType Directory -Path $taskTemp -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $taskReport) -Force | Out-Null
$taskListener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$taskListener.Start()
$taskPort = $taskListener.LocalEndpoint.Port
$taskListener.Stop()

function Invoke-PrivateSql([string]$Sql, [switch]$NoDatabase) {
    $taskInfo = [Diagnostics.ProcessStartInfo]::new()
    $taskInfo.FileName = $taskMysql
    $taskInfo.UseShellExecute = $false
    $taskInfo.CreateNoWindow = $true
    $taskInfo.RedirectStandardInput = $true
    $taskInfo.RedirectStandardOutput = $true
    $taskInfo.RedirectStandardError = $true
    $taskInfo.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($taskArgument in @('--no-defaults', '--protocol=TCP', '--host=127.0.0.1', "--port=$taskPort", '--user=root', '--ssl-mode=DISABLED', '--default-character-set=utf8mb4', '--batch', '--skip-column-names', '--raw')) {
        $taskInfo.ArgumentList.Add($taskArgument)
    }
    if (-not $NoDatabase) { $taskInfo.ArgumentList.Add('--database=flashslot_test') }
    $taskClient = [Diagnostics.Process]::Start($taskInfo)
    $taskOutputRead = $taskClient.StandardOutput.ReadToEndAsync()
    $taskErrorRead = $taskClient.StandardError.ReadToEndAsync()
    $taskClient.StandardInput.Write($Sql)
    $taskClient.StandardInput.Close()
    if (-not $taskClient.WaitForExit(30000)) {
        $taskClient.Kill()
        throw 'Private MySQL client timed out.'
    }
    $taskResult = [pscustomobject]@{ ExitCode=$taskClient.ExitCode; Output=$taskOutputRead.GetAwaiter().GetResult().Trim(); Error=$taskErrorRead.GetAwaiter().GetResult().Trim() }
    $taskClient.Dispose()
    return $taskResult
}

function Invoke-RequiredSql([string]$Sql) {
    $taskResult = Invoke-PrivateSql $Sql
    if ($taskResult.ExitCode -ne 0) { throw $taskResult.Error }
    return $taskResult.Output
}

function Assert-Query([string]$Name, [string]$Sql, [string]$Expected) {
    $taskActual = Invoke-RequiredSql $Sql
    if ($taskActual -ne $Expected) { throw "$Name : expected [$Expected], got [$taskActual]" }
    $taskChecks.Add($Name)
}

function Assert-Rejected([string]$Name, [string]$Sql, [string]$ExpectedError) {
    $taskResult = Invoke-PrivateSql $Sql
    if ($taskResult.ExitCode -eq 0 -or $taskResult.Error -notmatch $ExpectedError) { throw "$Name : expected error [$ExpectedError], got [$($taskResult.Error)]" }
    $taskChecks.Add($Name)
}

try {
    $taskInit = Start-Process -FilePath $taskMysqld -ArgumentList @('--no-defaults', '--initialize-insecure', "--basedir=`"$taskBase`"", "--datadir=`"$taskData`"") -WindowStyle Hidden -PassThru -Wait -RedirectStandardError (Join-Path $taskTemp 'init.stderr') -RedirectStandardOutput (Join-Path $taskTemp 'init.stdout')
    if ($taskInit.ExitCode -ne 0) { throw (Get-Content -LiteralPath (Join-Path $taskTemp 'init.stderr') -Raw) }
    $taskServer = Start-Process -FilePath $taskMysqld -ArgumentList @('--no-defaults', "--basedir=`"$taskBase`"", "--datadir=`"$taskData`"", "--port=$taskPort", '--bind-address=127.0.0.1', '--mysqlx=0', '--skip-log-bin', '--innodb-buffer-pool-size=64M', '--console') -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $taskTemp 'server.stderr') -RedirectStandardOutput (Join-Path $taskTemp 'server.stdout')
    $taskReady = $false
    for ($taskAttempt=0; $taskAttempt -lt 60; $taskAttempt++) {
        if ($taskServer.HasExited) { throw (Get-Content -LiteralPath (Join-Path $taskTemp 'server.stderr') -Raw) }
        $taskProbe = Invoke-PrivateSql 'SELECT @@datadir;' -NoDatabase
        if ($taskProbe.ExitCode -eq 0) {
            $taskActualData = [IO.Path]::GetFullPath($taskProbe.Output.Replace('/', '\')).TrimEnd([char]'\')
            $taskExpectedData = [IO.Path]::GetFullPath($taskData).TrimEnd([char]'\')
            if (-not $taskActualData.Equals($taskExpectedData, [StringComparison]::OrdinalIgnoreCase)) { throw 'Port is not owned by the private test data directory; refusing SQL execution.' }
            $taskPrivateVerified=$true
            $taskReady=$true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $taskReady) { throw 'Private MySQL instance did not become ready.' }
    $taskCreate = Invoke-PrivateSql 'CREATE DATABASE flashslot_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;' -NoDatabase
    if ($taskCreate.ExitCode -ne 0) { throw $taskCreate.Error }
    $env:DB_URL = "jdbc:mysql://127.0.0.1:$taskPort/flashslot_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false"
    $env:DB_USERNAME = 'root'
    $env:DB_PASSWORD = ''
    $env:APP_PROFILE = 'local'
    $env:FLASHSLOT_PRIVATE_TEST = 'true'
    # 测试显式调用扫描业务，避免真实时钟的半小时触发器改变其他用例的夹具。
    $env:RELEASE_SCHEDULING_ENABLED = 'false'
    $env:RESOURCE_CALENDAR_SCHEDULING_ENABLED = 'false'
    $env:BOOKING_EXPIRY_SCHEDULING_ENABLED = 'false'
    $env:MESSAGING_ENABLED = 'false'
    $env:MESSAGING_SCHEDULING_ENABLED = 'false'
    $env:FLASHSLOT_PRIVATE_RABBIT_TEST = 'false'
    $env:SCHEDULE_CACHE_ENABLED = 'false'
    $env:FLASHSLOT_PRIVATE_REDIS_TEST = 'false'
    if ($WithRedis) {
        . (Join-Path $PSScriptRoot 'Private-Redis.ps1')
        $taskRedis = Start-FlashSlotPrivateRedis
        $env:REDIS_HOST = '127.0.0.1'
        $env:REDIS_PORT = "$($taskRedis.Port)"
        $env:REDIS_PASSWORD = ''
        $env:SCHEDULE_CACHE_ENABLED = 'true'
        $env:FLASHSLOT_PRIVATE_REDIS_TEST = 'true'
    }
    if ($WithRabbit) {
        . (Join-Path $PSScriptRoot 'Private-RabbitMQ.ps1')
        $taskErlangHome = Split-Path -Parent (Split-Path -Parent (Get-Command erl.exe -ErrorAction Stop).Source)
        $taskRabbit = Start-FlashSlotPrivateRabbit -ErlangHome $taskErlangHome
        $env:RABBIT_HOST = '127.0.0.1'
        $env:RABBIT_PORT = "$($taskRabbit.Port)"
        $env:RABBIT_USERNAME = 'guest'
        $env:RABBIT_PASSWORD = 'guest'
        $env:FLASHSLOT_PRIVATE_RABBIT_TEST = 'true'
    }
    # 每次隔离测试生成私有签名密钥，不输出或写入仓库；退出时恢复调用者环境。
    $env:JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $taskMavenArgs = @('-f', (Join-Path $taskRoot 'flashslot/pom.xml'), 'clean', 'verify', '-B', '-ntp')
    if ($Test) { $taskMavenArgs += "-Dtest=$Test" }
    & mvn @taskMavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven foundation verification failed: $LASTEXITCODE" }
    $taskChecks.Add('Spring Boot/Flyway, entity mapping, OpenAPI, readiness, UTC/JSON; BCrypt login, stateless Bearer JWT, expiry/signature/issuer/audience checks, account and IP throttling, canonical account aliases, real-token business flow; resource/calendar/schedule APIs; booking eligibility, cutoff after lock wait, idempotency replay and 20 concurrent retries, resource/user slot conflicts, unique constraint and outbox/idempotency failure rollback; order queries, cursor pagination, ownership/admin authorization and release progress; cancellation/HANDOFF, no-show/completion normalization, credit rollback, waitlist admission, concurrency limits and private queue pagination')
    $taskChecks.Add('Half-hour release matching, eligibility/FIFO, suffix/cutoff boundaries, transaction retries and manual review; signed device check-in codes, ownership, window boundaries and concurrent check-in/expiry; five-second expiry registration, atomic no-show/credit/release and completion, failure isolation, 100-order batch continuation and concurrent per-user credit updates')
    $taskChecks.Add('Admin audit filters/cursor and current-role authorization; private notification pagination/unread count, idempotent and concurrent first read, account/privacy validation')
    if ($WithRabbit) { $taskChecks.Add('Private RabbitMQ: business Outbox -> publisher confirm/mandatory return -> transactional notification/Inbox; repeated/concurrent delivery, post-insert rollback and dead-letter redelivery, schema rejection, expired/stale leases, NACK/timeout, 12-failure limit and waitlist notification deduplication') }
    $taskStatus = 'PASS'
    Write-Output 'PASS: isolated FlashSlot foundation integration verification.'
}
catch {
    $taskStatus='FAIL'
    $taskFailure=$_.Exception.Message
    throw
}
finally {
    $taskRedisCleanupFailure = $null
    if ($null -ne $taskRedis) {
        try { Stop-FlashSlotPrivateRedis $taskRedis }
        catch { $taskRedisCleanupFailure=$_.Exception; $taskStatus='FAIL'; $taskFailure="Private Redis cleanup failed: $($_.Exception.Message)"; Write-Warning $taskFailure }
    }
    $taskRabbitCleanupFailure = $null
    if ($null -ne $taskRabbit) {
        try { Stop-FlashSlotPrivateRabbit -Runtime $taskRabbit }
        catch {
            $taskRabbitCleanupFailure = $_.Exception
            $taskStatus = 'FAIL'
            $taskFailure = "Private RabbitMQ cleanup failed: $($_.Exception.Message)"
            Write-Warning $taskFailure
        }
    }
    foreach ($name in $taskOldEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $taskOldEnv[$name]) }
    if ($null -ne $taskServer -and -not $taskServer.HasExited) {
        if ($taskPrivateVerified) {
            $null = Invoke-PrivateSql 'SHUTDOWN;' -NoDatabase
            if (-not $taskServer.WaitForExit(15000)) { Stop-Process -Id $taskServer.Id -Force }
        } else {
            Stop-Process -Id $taskServer.Id -Force
        }
    }
    $taskTestEnvironment = 'private temporary MySQL, loopback random port'
    if ($WithRabbit) { $taskTestEnvironment += '; private RabbitMQ' }
    if ($WithRedis) { $taskTestEnvironment += '; private Redis' }
    [pscustomobject]@{status=$taskStatus; checks=$taskChecks.ToArray(); error=$taskFailure; elapsedSeconds=[math]::Round($taskTimer.Elapsed.TotalSeconds,2); environment=$taskTestEnvironment; database='flashslot_test'; existingDatabasesTouched=$false} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $taskReport -Encoding utf8
    $taskResolvedTemp = [IO.Path]::GetFullPath($taskTemp)
    if ($taskResolvedTemp.StartsWith($taskTempRoot, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $taskResolvedTemp) -match '^FlashSlotSchema-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $taskResolvedTemp -Recurse -Force
    }
    if ($null -ne $taskRabbitCleanupFailure) { throw $taskRabbitCleanupFailure }
    if ($null -ne $taskRedisCleanupFailure) { throw $taskRedisCleanupFailure }
}
