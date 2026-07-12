[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$TaskName = "FinBot-Web"
)

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($null -eq $task) {
    Write-Output "Scheduled task does not exist: $TaskName"
    return
}

if ($PSCmdlet.ShouldProcess($TaskName, "Stop and unregister FinBot Web scheduled task")) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Output "Removed scheduled task: $TaskName"
}

