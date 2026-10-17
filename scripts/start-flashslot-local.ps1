param([switch]$SkipBuild, [int]$FrontendPort=5173, [int]$BackendPort=18080, [string]$DemoPassword)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskRun=Join-Path $taskRoot '.run/flashslot-local'
$taskData=Join-Path $taskRun 'mysql'
$taskStateFile=Join-Path $taskRun 'processes.json'
if(Test-Path -LiteralPath $taskStateFile) { throw 'Local state exists. Use scripts/stop-flashslot-local.ps1 before starting again.' }
foreach($taskPort in @($FrontendPort,$BackendPort)) { if(Get-NetTCPConnection -State Listen -LocalPort $taskPort -ErrorAction SilentlyContinue) { throw "Local port $taskPort is occupied. Choose another -FrontendPort/-BackendPort." } }
New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
if(-not $DemoPassword) { $DemoPassword='Fs!'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(9)) }
if($DemoPassword.Length -lt 12) { throw 'DemoPassword must have at least 12 characters.' }
$taskMysql=(Get-Command mysql.exe -ErrorAction Stop).Source
$taskMysqld=(Get-Command mysqld.exe -ErrorAction Stop).Source
$taskJava=if($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {Join-Path $env:JAVA_HOME 'bin/java.exe'} else {(Get-Command java.exe -ErrorAction Stop).Source}
$taskNode=(Get-Command node.exe -ErrorAction Stop).Source
$taskMysqlBase=Split-Path -Parent (Split-Path -Parent $taskMysqld)
$taskJar=Join-Path $taskRoot 'flashslot/target/flashslot-0.0.1-SNAPSHOT.jar'
if(-not $SkipBuild) {
    & mvn -f (Join-Path $taskRoot 'flashslot/pom.xml') package -DskipTests -B -ntp
    if($LASTEXITCODE -ne 0) {throw 'Backend build failed.'}
    Push-Location (Join-Path $taskRoot 'flashslot-web')
    try { & npm ci --no-audit --no-fund; if($LASTEXITCODE -ne 0) {throw 'Frontend install failed.'}; & npm run build; if($LASTEXITCODE -ne 0) {throw 'Frontend build failed.'} } finally {Pop-Location}
}
if(-not (Test-Path -LiteralPath $taskJar)) {throw 'Build backend first.'}
$taskRuntimeJar=Join-Path $taskRun 'flashslot.jar'
Copy-Item -LiteralPath $taskJar -Destination $taskRuntimeJar
$taskState=@{Processes=@();Rabbit=$null;RedisDirectory=$null;FrontendPort=$FrontendPort;BackendPort=$BackendPort;MysqlPort=$null}
function Save-LocalState { $taskState | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $taskStateFile -Encoding utf8 }
function Start-LocalProcess($Name,$File,$Arguments,$Directory,$ChildEnvironment) {
    $taskStartParams=@{FilePath=$File;ArgumentList=$Arguments;WorkingDirectory=$Directory;WindowStyle='Hidden';PassThru=$true;RedirectStandardOutput=(Join-Path $taskRun "$Name.out.log");RedirectStandardError=(Join-Path $taskRun "$Name.err.log")}
    if($ChildEnvironment) {$taskStartParams.Environment=$ChildEnvironment}
    $taskProcess=Start-Process @taskStartParams
    # 启动瞬间 Process.Path 可能仍为空；使用本次明确传入的可执行文件路径记录身份。
    $taskState.Processes+=@{Name=$Name;Id=$taskProcess.Id;Ticks=$taskProcess.StartTime.ToUniversalTime().Ticks;Path=[IO.Path]::GetFullPath($File)}
    Save-LocalState
    return $taskProcess
}
try {
    if(-not (Test-Path -LiteralPath $taskData)) {
        $taskInit=Start-Process -FilePath $taskMysqld -ArgumentList @('--no-defaults','--initialize-insecure',"--basedir=`"$taskMysqlBase`"","--datadir=`"$taskData`"") -WindowStyle Hidden -Wait -PassThru -RedirectStandardError (Join-Path $taskRun 'mysql-init.log')
        if($taskInit.ExitCode -ne 0) {throw 'Private MySQL initialization failed; inspect mysql-init.log.'}
    }
    $taskSocket=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$taskSocket.Start();$taskMysqlPort=$taskSocket.LocalEndpoint.Port;$taskSocket.Stop()
    $taskState.MysqlPort=$taskMysqlPort
    $taskMysqlProcess=Start-LocalProcess 'mysql' $taskMysqld @('--no-defaults',"--basedir=`"$taskMysqlBase`"","--datadir=`"$taskData`"","--port=$taskMysqlPort",'--bind-address=127.0.0.1','--mysqlx=0','--skip-log-bin','--innodb-buffer-pool-size=64M','--console') $taskRoot @{}
    $taskSqlArgs=@('--no-defaults','--protocol=TCP','--host=127.0.0.1',"--port=$taskMysqlPort",'--user=root','--ssl-mode=DISABLED','--batch','--skip-column-names')
    $taskReady=$false
    for($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++) {
        $taskActual=& $taskMysql @taskSqlArgs '--execute=SELECT @@datadir;' 2>$null
        if($LASTEXITCODE -eq 0) {
            if([IO.Path]::GetFullPath($taskActual.Replace('/','\')).TrimEnd('\') -ne [IO.Path]::GetFullPath($taskData).TrimEnd('\')) {throw 'Private MySQL ownership mismatch.'}
            $taskReady=$true;break
        }
        if($taskMysqlProcess.HasExited) {throw 'Private MySQL exited; inspect mysql.err.log.'}
        Start-Sleep -Milliseconds 250
    }
    if(-not $taskReady) {throw 'Private MySQL startup timed out.'}
    & $taskMysql @taskSqlArgs '--execute=CREATE DATABASE IF NOT EXISTS flashslot_local CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;'
    if($LASTEXITCODE -ne 0) {throw 'Private database creation failed.'}
    . (Join-Path $PSScriptRoot 'Private-Redis.ps1')
    $taskRedis=Start-FlashSlotPrivateRedis
    $taskState.Processes+=@{Name='redis';Id=$taskRedis.Process.Id;Ticks=$taskRedis.Process.StartTime.ToUniversalTime().Ticks;Path=$taskRedis.Process.Path}
    $taskState.RedisDirectory=$taskRedis.Directory;Save-LocalState
    . (Join-Path $PSScriptRoot 'Private-RabbitMQ.ps1')
    $taskErlang=Split-Path -Parent (Split-Path -Parent (Get-Command erl.exe -ErrorAction Stop).Source)
    $taskState.Rabbit=Start-FlashSlotPrivateRabbit -ErlangHome $taskErlang;Save-LocalState
    $taskChildEnv=@{APP_PROFILE='local,demo';SERVER_PORT="$BackendPort";DB_URL="jdbc:mysql://127.0.0.1:$taskMysqlPort/flashslot_local?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8";DB_USERNAME='root';DB_PASSWORD='';JWT_SECRET=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));FLASHSLOT_DEMO_PASSWORD=$DemoPassword;REDIS_HOST='127.0.0.1';REDIS_PORT="$($taskRedis.Port)";REDIS_PASSWORD='';RABBIT_HOST='127.0.0.1';RABBIT_PORT="$($taskState.Rabbit.Port)";RABBIT_USERNAME='guest';RABBIT_PASSWORD='guest';SCHEDULE_CACHE_ENABLED='true';MESSAGING_ENABLED='true';MESSAGING_SCHEDULING_ENABLED='true';RELEASE_SCHEDULING_ENABLED='true';BOOKING_EXPIRY_SCHEDULING_ENABLED='true'}
    $taskBackend=Start-LocalProcess 'backend' $taskJava @('-Xms96m','-Xmx384m','-jar',"`"$taskRuntimeJar`"",'--server.address=127.0.0.1') $taskRoot $taskChildEnv
    $taskReady=$false
    for($taskAttempt=0;$taskAttempt -lt 80;$taskAttempt++) {
        try { $taskHealth=Invoke-RestMethod "http://127.0.0.1:$BackendPort/actuator/health/readiness" -TimeoutSec 2; if($taskHealth.status -eq 'UP') {$taskReady=$true;break} } catch {}
        if($taskBackend.HasExited) {throw 'Backend exited; inspect backend.out.log/backend.err.log.'}
        Start-Sleep -Milliseconds 500
    }
    if(-not $taskReady) {throw 'Backend startup timed out.'}
    $taskFrontend=Start-LocalProcess 'frontend' $taskNode @('node_modules/vite/bin/vite.js','--host','127.0.0.1','--port',"$FrontendPort",'--strictPort') (Join-Path $taskRoot 'flashslot-web') @{API_TARGET="http://127.0.0.1:$BackendPort"}
    $taskFrontendReady=$false
    for($taskAttempt=0;$taskAttempt -lt 40;$taskAttempt++) {
        if($taskFrontend.HasExited) {throw 'Frontend exited; inspect frontend.err.log.'}
        try { $taskPage=Invoke-WebRequest "http://127.0.0.1:$FrontendPort" -TimeoutSec 2; if($taskPage.StatusCode -eq 200 -and $taskPage.Content.Contains('id="app"')) {$taskFrontendReady=$true;break} } catch {}
        Start-Sleep -Milliseconds 250
    }
    if(-not $taskFrontendReady) {throw 'Frontend startup timed out.'}
    Write-Output "FlashSlot local: http://127.0.0.1:$FrontendPort"
    Write-Output "Accounts: demo-user / demo-admin; local demo password: $DemoPassword"
    Write-Output 'Independent local MySQL/Redis/RabbitMQ started; existing services untouched. Stop with scripts/stop-flashslot-local.ps1. MySQL demo data is retained.'
} catch {
    $taskFailure=$_
    if(Test-Path -LiteralPath $taskStateFile) { & (Join-Path $PSScriptRoot 'stop-flashslot-local.ps1') }
    throw $taskFailure
}
