param()

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
$taskPrivateVerified = $false
$taskChecks = [Collections.Generic.List[string]]::new()
$taskReport = Join-Path $taskRoot '.run/flashslot-schema-verification.json'
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
    foreach ($taskFile in (Get-ChildItem -LiteralPath (Join-Path $taskRoot 'db') -Filter '*.sql' -File | Sort-Object Name)) {
        $null = Invoke-RequiredSql (Get-Content -LiteralPath $taskFile.FullName -Raw -Encoding utf8)
        $taskChecks.Add('migration: ' + $taskFile.Name)
    }
    foreach ($taskTable in @('resource','resource_day','app_user','booking_order','waitlist_record','release_job','slot_occupation','user_slot_occupation','credit_ledger','api_idempotency','outbox_event','consumer_inbox','notification','admin_audit','revoked_token','dead_letter')) {
        Assert-Query "table exists: $taskTable" "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='flashslot_test' AND table_name='$taskTable';" '1'
    }
    $null = Invoke-RequiredSql @'
INSERT INTO app_user(id,username,password_hash) VALUES(1,'schema_test_a','test-only-hash'),(2,'schema_test_b','test-only-hash');
INSERT INTO resource(id,name,location) VALUES(101,'test device A','test lab'),(102,'test device B','test lab');
INSERT INTO resource_day(resource_id,book_date) VALUES(101,'2026-10-15'),(102,'2026-10-15');
INSERT INTO booking_order(id,order_no,user_id,resource_id,book_date,start_slot,end_slot,charged_slots,purpose,start_at,check_in_open_at,check_in_deadline,end_at)
VALUES(9001,'BK_TEST_A',1,101,'2026-10-15',18,22,4,'EXPERIMENT','2026-10-15 01:00:00','2026-10-15 00:50:00','2026-10-15 01:15:00','2026-10-15 03:00:00'),
(9002,'BK_TEST_B',2,101,'2026-10-15',20,24,4,'DEVICE_TEST','2026-10-15 02:00:00','2026-10-15 01:50:00','2026-10-15 02:15:00','2026-10-15 04:00:00'),
(9003,'BK_TEST_C',1,102,'2026-10-15',20,24,4,'STUDY','2026-10-15 02:00:00','2026-10-15 01:50:00','2026-10-15 02:15:00','2026-10-15 04:00:00');
INSERT INTO slot_occupation(resource_id,book_date,slot_index,state,booking_id) VALUES
(101,'2026-10-15',18,'BOOKED',9001),(101,'2026-10-15',19,'BOOKED',9001),(101,'2026-10-15',20,'BOOKED',9001),(101,'2026-10-15',21,'BOOKED',9001);
INSERT INTO user_slot_occupation(user_id,book_date,slot_index,booking_id) VALUES(1,'2026-10-15',20,9001);
'@
    Assert-Query 'four-slot booking stored' 'SELECT COUNT(*) FROM slot_occupation WHERE booking_id=9001;' '4'
    Assert-Rejected 'same device/date/slot rejects a second order' "INSERT INTO slot_occupation VALUES(101,'2026-10-15',20,'BOOKED',9002,NULL);" 'ERROR 1062'
    Assert-Rejected 'multi-row conflicting insert leaves no partial slots' "INSERT INTO slot_occupation VALUES(101,'2026-10-15',16,'BOOKED',9002,NULL),(101,'2026-10-15',18,'BOOKED',9002,NULL);" 'ERROR 1062'
    Assert-Query 'failed multi-row insert is atomic' "SELECT COUNT(*) FROM slot_occupation WHERE resource_id=101 AND slot_index=16;" '0'
    $null = Invoke-RequiredSql "INSERT INTO slot_occupation VALUES(102,'2026-10-15',20,'BOOKED',9003,NULL);"
    Assert-Query 'different devices can share the same slot' "SELECT COUNT(*) FROM slot_occupation WHERE slot_index=20;" '2'
    Assert-Rejected 'same user/time rejects overlapping device bookings' "INSERT INTO user_slot_occupation VALUES(1,'2026-10-15',20,9003);" 'ERROR 1062'
    Assert-Rejected 'daily calendar cannot be duplicated' "INSERT INTO resource_day(resource_id,book_date) VALUES(101,'2026-10-15');" 'ERROR 1062'
    Assert-Rejected 'invalid slot index is rejected' "INSERT INTO slot_occupation VALUES(101,'2026-10-15',48,'BOOKED',9001,NULL);" 'ERROR 3819'
    Assert-Rejected 'booked slot without owner is rejected' "INSERT INTO slot_occupation VALUES(101,'2026-10-15',22,'BOOKED',NULL,NULL);" 'ERROR 3819'
    Assert-Rejected 'invalid order range is rejected' 'UPDATE booking_order SET end_slot=18 WHERE id=9002;' 'ERROR (3819|1690)'
    Assert-Rejected 'checked-in order requires check-in timestamp' "UPDATE booking_order SET status='CHECKED_IN' WHERE id=9002;" 'ERROR 3819'
    Assert-Rejected 'unknown user cannot own an order' 'UPDATE booking_order SET user_id=99999 WHERE id=9002;' 'ERROR 1452'
    $null = Invoke-RequiredSql "INSERT INTO waitlist_record(id,waitlist_no,user_id,resource_id,book_date,start_slot,end_slot,purpose,expire_at) VALUES(1001,'WL_TEST_A',2,101,'2026-10-15',18,22,'STUDY','2026-10-15 00:50:00');"
    Assert-Rejected 'duplicate active waitlist is rejected' "INSERT INTO waitlist_record(waitlist_no,user_id,resource_id,book_date,start_slot,end_slot,purpose,expire_at) VALUES('WL_TEST_B',2,101,'2026-10-15',18,22,'STUDY','2026-10-15 00:50:00');" 'ERROR 1062'
    $null = Invoke-RequiredSql "UPDATE waitlist_record SET status='CANCELED' WHERE id=1001; INSERT INTO waitlist_record(waitlist_no,user_id,resource_id,book_date,start_slot,end_slot,purpose,expire_at) VALUES('WL_TEST_C',2,101,'2026-10-15',18,22,'STUDY','2026-10-15 00:50:00');"
    Assert-Query 'canceled waitlist allows a new application' "SELECT COUNT(*) FROM waitlist_record WHERE user_id=2 AND status='WAITING';" '1'
    $null = Invoke-RequiredSql "INSERT INTO credit_ledger(user_id,event_type,business_id,delta,score_before,score_after,reason) VALUES(1,'NO_SHOW','9001',-10,100,90,'test');"
    Assert-Rejected 'same no-show cannot be charged twice' "INSERT INTO credit_ledger(user_id,event_type,business_id,delta,score_before,score_after,reason) VALUES(1,'NO_SHOW','9001',-10,90,80,'test');" 'ERROR 1062'
    Assert-Rejected 'inconsistent credit delta is rejected' "INSERT INTO credit_ledger(user_id,event_type,business_id,delta,score_before,score_after,reason) VALUES(1,'ADMIN_ADJUST','ADJ_TEST',-10,90,85,'test');" 'ERROR 3819'
    $null = Invoke-RequiredSql "INSERT INTO consumer_inbox VALUES('notification','00000000-0000-0000-0000-000000000001',UTC_TIMESTAMP(3));"
    Assert-Rejected 'same consumer/event cannot complete twice' "INSERT INTO consumer_inbox VALUES('notification','00000000-0000-0000-0000-000000000001',UTC_TIMESTAMP(3));" 'ERROR 1062'
    $null = Invoke-RequiredSql "INSERT INTO notification(user_id,event_id,type,payload) VALUES(1,'00000000-0000-0000-0000-000000000001','BOOKING_CREATED','{}');"
    Assert-Rejected 'same user/event notification cannot duplicate' "INSERT INTO notification(user_id,event_id,type,payload) VALUES(1,'00000000-0000-0000-0000-000000000001','BOOKING_CREATED','{}');" 'ERROR 1062'
    Assert-Query 'explicit transaction rollback removes tentative slot' "START TRANSACTION; INSERT INTO slot_occupation VALUES(101,'2026-10-15',23,'BOOKED',9002,NULL); ROLLBACK; SELECT COUNT(*) FROM slot_occupation WHERE resource_id=101 AND slot_index=23;" '0'
    $null = Invoke-RequiredSql "INSERT INTO api_idempotency(user_id,operation,request_key,payload_hash,expires_at) VALUES(1,'CREATE_BOOKING','KEY_TEST',UNHEX(REPEAT('00',32)),'2026-10-22 00:00:00');"
    Assert-Rejected 'same user operation request key cannot duplicate' "INSERT INTO api_idempotency(user_id,operation,request_key,payload_hash,expires_at) VALUES(1,'CREATE_BOOKING','KEY_TEST',UNHEX(REPEAT('00',32)),'2026-10-22 00:00:00');" 'ERROR 1062'
    $null = Invoke-RequiredSql "INSERT INTO api_idempotency(user_id,operation,request_key,payload_hash,expires_at) VALUES(2,'CREATE_BOOKING','KEY_TEST',UNHEX(REPEAT('00',32)),'2026-10-22 00:00:00');"
    Assert-Query 'different users can reuse request key independently' "SELECT COUNT(*) FROM api_idempotency WHERE request_key='KEY_TEST';" '2'
    Assert-Rejected 'outbox rejects unknown publishing state' "INSERT INTO outbox_event(event_id,event_type,aggregate_id,payload,status,next_run_at) VALUES('00000000-0000-0000-0000-000000000002','BOOKING_CREATED','BK_TEST_A','{}','INVALID',UTC_TIMESTAMP(3));" 'ERROR 3819'
    Assert-Query 'release compensation lookup has covering prefix index' "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='outbox_event' AND index_name='idx_outbox_aggregate';" 'event_type,aggregate_id,created_at'
    $null = Invoke-RequiredSql @'
START TRANSACTION;
SELECT revision INTO @rev FROM resource_day WHERE resource_id=101 AND book_date='2026-10-15' FOR UPDATE;
UPDATE booking_order SET status='CANCELED',charged_slots=0,termination_reason='USER_CANCEL',terminated_at='2026-10-14 13:00:00' WHERE id=9001;
INSERT INTO release_job(id,job_no,source_booking_id,resource_id,book_date,start_slot,end_slot,effective_start_slot,next_run_at)
VALUES(7001,'RJ_TEST_A',9001,101,'2026-10-15',18,22,18,UTC_TIMESTAMP(3));
UPDATE slot_occupation SET state='HANDOFF',booking_id=NULL,release_job_id=7001 WHERE booking_id=9001;
DELETE FROM user_slot_occupation WHERE booking_id=9001;
COMMIT;
'@
    Assert-Query 'cancellation holds all four slots for handoff' "SELECT COUNT(*) FROM slot_occupation WHERE release_job_id=7001 AND state='HANDOFF';" '4'
    Assert-Rejected 'handoff slots cannot be booked by another order' "INSERT INTO slot_occupation VALUES(101,'2026-10-15',20,'BOOKED',9002,NULL);" 'ERROR 1062'
    Assert-Rejected 'source order cannot create two release jobs' "INSERT INTO release_job(job_no,source_booking_id,resource_id,book_date,start_slot,end_slot,effective_start_slot,next_run_at) VALUES('RJ_TEST_DUP',9001,101,'2026-10-15',18,22,18,UTC_TIMESTAMP(3));" 'ERROR 1062'
    $null = Invoke-RequiredSql @'
START TRANSACTION;
SELECT revision INTO @rev FROM resource_day WHERE resource_id=101 AND book_date='2026-10-15' FOR UPDATE;
SELECT id INTO @waiting FROM waitlist_record WHERE user_id=2 AND status='WAITING' ORDER BY id LIMIT 1 FOR UPDATE;
INSERT INTO booking_order(id,order_no,user_id,resource_id,book_date,start_slot,end_slot,charged_slots,purpose,source,waitlist_id,release_job_id,start_at,check_in_open_at,check_in_deadline,end_at)
VALUES(9004,'BK_TEST_MATCHED',2,101,'2026-10-15',18,22,4,'STUDY','WAITLIST',@waiting,7001,'2026-10-15 01:00:00','2026-10-15 00:50:00','2026-10-15 01:15:00','2026-10-15 03:00:00');
UPDATE slot_occupation SET state='BOOKED',booking_id=9004,release_job_id=NULL WHERE release_job_id=7001;
INSERT INTO user_slot_occupation VALUES(2,'2026-10-15',18,9004),(2,'2026-10-15',19,9004),(2,'2026-10-15',20,9004),(2,'2026-10-15',21,9004);
UPDATE waitlist_record SET status='MATCHED',matched_booking_id=9004 WHERE id=@waiting;
UPDATE release_job SET status='MATCHED' WHERE id=7001;
COMMIT;
'@
    Assert-Query 'handoff transfers all four slots to new booking' "SELECT COUNT(*) FROM slot_occupation WHERE booking_id=9004 AND state='BOOKED';" '4'
    Assert-Query 'matched waitlist links to exactly one booking' "SELECT COUNT(*) FROM waitlist_record WHERE status='MATCHED' AND matched_booking_id=9004;" '1'
    Assert-Query 'release job reaches matched state' 'SELECT status FROM release_job WHERE id=7001;' 'MATCHED'
    Assert-Query 'old ownership cannot delete new booking slots' "DELETE FROM slot_occupation WHERE booking_id=9001; SELECT COUNT(*) FROM slot_occupation WHERE booking_id=9004;" '4'
    $taskStatus='PASS'
    Write-Output "PASS: $($taskChecks.Count) migrations/assertions against private MySQL."
}
catch {
    $taskStatus='FAIL'
    $taskFailure=$_.Exception.Message
    throw
}
finally {
    if ($null -ne $taskServer -and -not $taskServer.HasExited) {
        if ($taskPrivateVerified) {
            $null = Invoke-PrivateSql 'SHUTDOWN;' -NoDatabase
            if (-not $taskServer.WaitForExit(15000)) { Stop-Process -Id $taskServer.Id -Force }
        } else {
            Stop-Process -Id $taskServer.Id -Force
        }
    }
    [pscustomobject]@{status=$taskStatus; checks=$taskChecks.ToArray(); error=$taskFailure; elapsedSeconds=[math]::Round($taskTimer.Elapsed.TotalSeconds,2); environment='private temporary MySQL, loopback random port'; database='flashslot_test'; existingDatabasesTouched=$false} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $taskReport -Encoding utf8
    $taskResolvedTemp = [IO.Path]::GetFullPath($taskTemp)
    if ($taskResolvedTemp.StartsWith($taskTempRoot, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $taskResolvedTemp) -match '^FlashSlotSchema-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $taskResolvedTemp -Recurse -Force
    }
}
