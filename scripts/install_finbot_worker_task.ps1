[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$TaskName = "FinBot-Worker",
    [string]$ProjectRoot = "",
    [string]$PythonExecutable = (Get-Command python -ErrorAction Stop).Source,
    [string]$DataDir = "data"
)

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
}
$resolvedRoot = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
$resolvedPython = (Resolve-Path -LiteralPath $PythonExecutable -ErrorAction Stop).Path
$resolvedPowerShell = (Get-Command powershell.exe -ErrorAction Stop).Source
$modulePath = Join-Path $resolvedRoot "finbot\cli\serve_worker.py"
$runnerPath = Join-Path $resolvedRoot "scripts\run_finbot_service.ps1"
if (-not (Test-Path -LiteralPath $modulePath -PathType Leaf)) {
    throw "FinBot Worker entrypoint not found: $modulePath"
}
if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) {
    throw "FinBot service supervisor not found: $runnerPath"
}

$argument = "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$runnerPath`" -Service worker -ProjectRoot `"$resolvedRoot`" -PythonExecutable `"$resolvedPython`" -DataDir `"$DataDir`""
$action = New-ScheduledTaskAction `
    -Execute $resolvedPowerShell `
    -Argument $argument `
    -WorkingDirectory $resolvedRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew `
    -Hidden `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal `
    -UserId "$env:USERDOMAIN\$env:USERNAME" `
    -LogonType Interactive `
    -RunLevel Limited

if ($PSCmdlet.ShouldProcess($TaskName, "Register and start FinBot Worker scheduled task")) {
    Register-ScheduledTask `
        -TaskName $TaskName `
        -Action $action `
        -Trigger $trigger `
        -Settings $settings `
        -Principal $principal `
        -Description "FinBot autonomous research worker with SQLite queue, lease, and heartbeat." `
        -Force | Out-Null
    Start-ScheduledTask -TaskName $TaskName
    Get-ScheduledTask -TaskName $TaskName | Select-Object TaskName, State
}
