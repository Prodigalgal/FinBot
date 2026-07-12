[CmdletBinding()]
param(
    [string]$BaseUri = "http://127.0.0.1:8780",
    [string[]]$TaskNames = @("FinBot-Web", "FinBot-Worker"),
    [int]$TimeoutSeconds = 10
)

$ErrorActionPreference = "Stop"
$checks = [System.Collections.Generic.List[object]]::new()

function Add-Check {
    param(
        [Parameter(Mandatory)]
        [string]$Name,
        [Parameter(Mandatory)]
        [bool]$Passed,
        [Parameter(Mandatory)]
        [string]$Detail
    )
    $checks.Add([ordered]@{ name = $Name; passed = $Passed; detail = $Detail })
}

foreach ($taskName in $TaskNames) {
    try {
        $task = Get-ScheduledTask -TaskName $taskName -ErrorAction Stop
        $taskInfo = $task | Get-ScheduledTaskInfo
        $isRunning = [string]$task.State -eq "Running"
        Add-Check -Name "scheduled_task:$taskName" -Passed $isRunning -Detail "state=$($task.State), last_result=$($taskInfo.LastTaskResult)"
    } catch {
        Add-Check -Name "scheduled_task:$taskName" -Passed $false -Detail $_.Exception.Message
    }
}

try {
    $health = Invoke-RestMethod -Uri "$BaseUri/health" -TimeoutSec $TimeoutSeconds
    Add-Check -Name "web_health" -Passed ($health.status -eq "ok") -Detail "status=$($health.status)"
} catch {
    Add-Check -Name "web_health" -Passed $false -Detail $_.Exception.Message
}

try {
    $autonomous = Invoke-RestMethod -Uri "$BaseUri/api/v1/autonomous/status" -TimeoutSec $TimeoutSeconds
    $reportedActiveWorkers = @($autonomous.worker.workers | Where-Object { $_.active })
    $liveActiveWorkers = @(
        $reportedActiveWorkers | Where-Object {
            $workerProcessId = [int]($_.process_id)
            $workerProcessId -gt 0 -and $null -ne (Get-Process -Id $workerProcessId -ErrorAction SilentlyContinue)
        }
    )
    Add-Check -Name "worker_heartbeat" -Passed ($liveActiveWorkers.Count -eq 1) -Detail "live_workers=$($liveActiveWorkers.Count), reported_active=$($reportedActiveWorkers.Count)"

    $resultLoopId = [string]$autonomous.latest_result_loop_run_id
    $foreignDebates = @($autonomous.latest_ai_debates | Where-Object { $_.loop_run_id -ne $resultLoopId }).Count
    $foreignDecisions = @($autonomous.latest_ai_decisions | Where-Object { $_.loop_run_id -ne $resultLoopId }).Count
    $snapshotConsistent = (-not [string]::IsNullOrWhiteSpace($resultLoopId)) -and $foreignDebates -eq 0 -and $foreignDecisions -eq 0
    Add-Check -Name "result_snapshot" -Passed $snapshotConsistent -Detail "loop_run_id=$resultLoopId, foreign_debates=$foreignDebates, foreign_decisions=$foreignDecisions"

    $staleRuns = @($autonomous.recent_runs | Where-Object { $_.status -eq "running" -and $_.started_at -lt [DateTimeOffset]::UtcNow.AddHours(-1).ToString("o") }).Count
    Add-Check -Name "stale_run_recovery" -Passed ($staleRuns -eq 0) -Detail "stale_running_runs=$staleRuns"
} catch {
    Add-Check -Name "autonomous_status" -Passed $false -Detail $_.Exception.Message
}

$failed = @($checks | Where-Object { -not $_.passed })
$result = [ordered]@{
    status = if ($failed.Count -eq 0) { "passed" } else { "failed" }
    generated_at = [DateTimeOffset]::UtcNow.ToString("o")
    base_uri = $BaseUri
    checks = $checks
    failed_count = $failed.Count
}
$result | ConvertTo-Json -Depth 6
if ($failed.Count -gt 0) {
    exit 1
}
