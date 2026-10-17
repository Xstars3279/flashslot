# 只执行启动脚本中的状态记录函数；不启动真实进程或中间件。
$ErrorActionPreference='Stop'
$taskScript=Join-Path (Split-Path -Parent $PSScriptRoot) 'start-flashslot-local.ps1'
$taskAst=[Management.Automation.Language.Parser]::ParseFile($taskScript,[ref]$null,[ref]$null)
$taskFunction=$taskAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Start-LocalProcess'},$true)
Invoke-Expression $taskFunction.Extent.Text
$taskRun=[IO.Path]::GetTempPath()
$taskRoot=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$taskState=@{Processes=@()}
function Save-LocalState {}
function Start-Process {
    param($FilePath,$ArgumentList,$WorkingDirectory,$WindowStyle,[switch]$PassThru,$RedirectStandardOutput,$RedirectStandardError,$Environment)
    [pscustomobject]@{Id=123;StartTime=[datetime]::UtcNow;Path=$null}
}
$taskExpected=(Get-Command java.exe -ErrorAction Stop).Source
$null=Start-LocalProcess 'backend' $taskExpected @('-version') $taskRoot @{}
if($taskState.Processes[0].Path -ne $taskExpected) {throw 'Startup state must record the known executable path even before Process.Path is available.'}
Write-Output 'PASS: startup records executable identity without waiting for Process.Path.'
