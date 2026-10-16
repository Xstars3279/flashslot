# A loopback-only, disposable broker for the FlashSlot integration checks.
# The downloaded server is cached; all broker state belongs to a unique temp root.
function Start-FlashSlotPrivateRabbit {
    [CmdletBinding()]
    param(
        [string]$ErlangHome = 'D:\rabbitmq\Erlang OTP',
        [int]$TimeoutSeconds = 90
    )
    $ErrorActionPreference = 'Stop'
    $erl = Join-Path $ErlangHome 'bin\erl.exe'
    if (-not (Test-Path -LiteralPath $erl)) { throw "Erlang executable not found: $erl" }
    $epmd = Get-ChildItem -LiteralPath $ErlangHome -Directory -Filter 'erts-*' |
        Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin\epmd.exe' } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $epmd) { throw 'Erlang epmd.exe was not found.' }
    $cache = Join-Path ([IO.Path]::GetTempPath()) 'flashslot-rabbitmq-cache'
    $server = Join-Path $cache 'rabbitmq_server-3.13.7'
    if (-not (Test-Path -LiteralPath (Join-Path $server 'sbin\rabbitmq-server.bat'))) {
        New-Item -ItemType Directory -Path $cache -Force | Out-Null
        $zip = Join-Path $cache 'rabbitmq-server-windows-3.13.7.zip'
        if (-not (Test-Path -LiteralPath $zip)) {
            Invoke-WebRequest -Uri 'https://github.com/rabbitmq/rabbitmq-server/releases/download/v3.13.7/rabbitmq-server-windows-3.13.7.zip' -OutFile $zip
        }
        Expand-Archive -LiteralPath $zip -DestinationPath $cache -Force
    }
    # Hold all three reservations together so they cannot receive the same port.
    $reservations = @()
    try {
        1..3 | ForEach-Object {
            $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
            $listener.Start()
            $reservations += $listener
        }
        $ports = @($reservations | ForEach-Object { $_.LocalEndpoint.Port })
    } finally { $reservations | ForEach-Object { $_.Stop() } }
    $id = [Guid]::NewGuid().ToString('N')
    $root = Join-Path ([IO.Path]::GetTempPath()) "flashslot-private-rabbit-$id"
    $node = "flashslot_$id@localhost"
    New-Item -ItemType Directory -Path $root, (Join-Path $root 'home'), (Join-Path $root 'home\erlang'), (Join-Path $root 'data'), (Join-Path $root 'log') | Out-Null
    [IO.File]::WriteAllText((Join-Path $root '.owner'), $id)
    $cookie = [Guid]::NewGuid().ToString('N')
    [IO.File]::WriteAllText((Join-Path $root 'home\.erlang.cookie'), $cookie)
    # OTP 26 on Windows prefers APPDATA/erlang over the Erlang -home argument.
    [IO.File]::WriteAllText((Join-Path $root 'home\erlang\.erlang.cookie'), $cookie)
    [IO.File]::WriteAllText((Join-Path $root 'enabled_plugins'), '[].')
    [IO.File]::WriteAllText((Join-Path $root 'rabbitmq.conf'), @"
listeners.tcp.1 = 127.0.0.1:$($ports[0])
distribution.listener.interface = 127.0.0.1
distribution.listener.port_range.min = $($ports[1])
distribution.listener.port_range.max = $($ports[1])
log.console = false
log.file.level = warning
"@)
    $childEnvironment = @{
        ERL_LIBS = (Join-Path $server 'plugins'); ERL_EPMD_PORT = "$($ports[2])";
        ERL_EPMD_ADDRESS = '127.0.0.1'; ERL_CRASH_DUMP = (Join-Path $root 'erl_crash.dump');
        RABBITMQ_HOME = $server; RABBITMQ_BASE = $root; RABBITMQ_NODENAME = $node;
        RABBITMQ_CONFIG_FILE = (Join-Path $root 'rabbitmq');
        RABBITMQ_ADVANCED_CONFIG_FILE = (Join-Path $root 'advanced.config');
        RABBITMQ_ENABLED_PLUGINS_FILE = (Join-Path $root 'enabled_plugins');
        RABBITMQ_MNESIA_BASE = (Join-Path $root 'data'); RABBITMQ_LOG_BASE = (Join-Path $root 'log');
        RABBITMQ_PLUGINS_EXPAND_DIR = (Join-Path $root 'plugins');
        RABBITMQ_PID_FILE = (Join-Path $root 'rabbit.pid');
        RABBITMQ_DIST_PORT = "$($ports[1])"; HOME = (Join-Path $root 'home');
        APPDATA = (Join-Path $root 'home'); USERPROFILE = (Join-Path $root 'home');
        HOMEDRIVE = [IO.Path]::GetPathRoot($root).TrimEnd('\');
        HOMEPATH = (Join-Path $root 'home').Substring([IO.Path]::GetPathRoot($root).Length - 1)
    }
    $runtime = [pscustomobject]@{
        Port = $ports[0]; DistributionPort = $ports[1]; EpmdPort = $ports[2];
        ProcessId = 0; EpmdProcessId = 0; Root = $root; OwnerId = $id; Node = $node;
        ErlangExecutable = $erl; EpmdExecutable = $epmd;
        ProcessStartedUtcTicks = 0L; EpmdStartedUtcTicks = 0L
    }
    try {
        foreach ($kind in @('epmd', 'rabbit')) {
            $start = [Diagnostics.ProcessStartInfo]::new()
            $start.FileName = if ($kind -eq 'epmd') { $epmd } else { $erl }
            $start.WorkingDirectory = $root
            $start.UseShellExecute = $false
            $start.CreateNoWindow = $true
            $start.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
            # Strip inherited broker and Erlang overrides, then set child-local values.
            @($start.EnvironmentVariables.Keys) | Where-Object { $_ -match '^(RABBITMQ_|ERL_|ERL_AFLAGS$|ERL_ZFLAGS$)' } |
                ForEach-Object { $start.EnvironmentVariables.Remove($_) }
            foreach ($key in $childEnvironment.Keys) { $start.EnvironmentVariables[$key] = $childEnvironment[$key] }
            $start.Arguments = if ($kind -eq 'epmd') {
                "-address 127.0.0.1 -port $($ports[2])"
            } else {
                '-noinput -boot start_sasl +S 2:2 -start_epmd false -s rabbit boot'
            }
            $process = [Diagnostics.Process]::Start($start)
            if ($kind -eq 'epmd') {
                $runtime.EpmdProcessId = $process.Id
                $runtime.EpmdStartedUtcTicks = $process.StartTime.ToUniversalTime().Ticks
            } else {
                $runtime.ProcessId = $process.Id
                $runtime.ProcessStartedUtcTicks = $process.StartTime.ToUniversalTime().Ticks
            }
            if ($kind -eq 'epmd') { Start-Sleep -Milliseconds 300 }
        }
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while ([DateTime]::UtcNow -lt $deadline) {
            $process.Refresh()
            if ($process.HasExited) { throw "Private RabbitMQ exited with code $($process.ExitCode). Logs: $root" }
            $client = [Net.Sockets.TcpClient]::new()
            try {
                $client.Connect('127.0.0.1', $runtime.Port)
                return $runtime
            } catch { Start-Sleep -Milliseconds 250 } finally { $client.Dispose() }
        }
        throw "Private RabbitMQ readiness timed out. Logs: $root"
    } catch {
        # Preserve the error before cleanup, which also handles partially started nodes.
        $failure = $_
        $diagnostic = Get-ChildItem -LiteralPath (Join-Path $root 'log') -File -ErrorAction SilentlyContinue |
            ForEach-Object { (Get-Content -LiteralPath $_.FullName -TotalCount 20) -join [Environment]::NewLine }
        if (-not $diagnostic -and (Test-Path -LiteralPath (Join-Path $root 'erl_crash.dump'))) {
            $diagnostic = (Get-Content -LiteralPath (Join-Path $root 'erl_crash.dump') -TotalCount 3) -join [Environment]::NewLine
        }
        Stop-FlashSlotPrivateRabbit -Runtime $runtime
        if ($diagnostic) { throw "$failure`n$diagnostic" }
        throw $failure
    }
}

function Stop-FlashSlotPrivateRabbit {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Runtime)
    $ErrorActionPreference = 'Stop'
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $root = [IO.Path]::GetFullPath([string]$Runtime.Root).TrimEnd('\', '/')
    if (-not $root.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($root) -ne "flashslot-private-rabbit-$($Runtime.OwnerId)" -or
        $Runtime.OwnerId -notmatch '^[0-9a-f]{32}$' -or
        $Runtime.Node -ne "flashslot_$($Runtime.OwnerId)@localhost") {
        throw 'Refusing cleanup: private RabbitMQ ownership/path validation failed.'
    }
    if (-not (Test-Path -LiteralPath $root)) { return }
    if ((Get-Content -LiteralPath (Join-Path $root '.owner') -Raw) -ne $Runtime.OwnerId) {
        throw 'Refusing cleanup: private RabbitMQ owner marker mismatch.'
    }
    foreach ($entry in @(
        @{ Id = $Runtime.ProcessId; Ticks = $Runtime.ProcessStartedUtcTicks; Executable = $Runtime.ErlangExecutable },
        @{ Id = $Runtime.EpmdProcessId; Ticks = $Runtime.EpmdStartedUtcTicks; Executable = $Runtime.EpmdExecutable }
    )) {
        if ($entry.Id -le 0) { continue }
        $owned = Get-Process -Id $entry.Id -ErrorAction SilentlyContinue
        if (-not $owned) { continue }
        if ($owned.StartTime.ToUniversalTime().Ticks -ne $entry.Ticks -or
            -not [string]::Equals($owned.Path, $entry.Executable, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing cleanup: process identity no longer matches the private broker.'
        }
        $owned.Kill()
        if (-not $owned.WaitForExit(10000)) { throw 'Private RabbitMQ process did not exit; keeping its data.' }
    }
    Remove-Item -LiteralPath $root -Recurse -Force
}
