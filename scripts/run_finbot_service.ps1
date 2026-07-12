[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet("web", "worker")]
    [string]$Service,
    [string]$ProjectRoot = "",
    [string]$PythonExecutable = (Get-Command python -ErrorAction Stop).Source,
    [string]$DataDir = "data",
    [string]$HostAddress = "127.0.0.1",
    [int]$Port = 8780,
    [string]$FrontendDist = "web-ui/dist",
    [string]$LogDir = "data/runtime/services",
    [long]$MaxLogBytes = 10485760,
    [int]$RetainedLogFiles = 5
)

$ErrorActionPreference = "Stop"
$script:childPid = $null

if (-not ("FinBot.ServiceRuntime.ChildJob" -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace FinBot.ServiceRuntime
{
    public sealed class ChildJob : IDisposable
    {
        private const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
        private IntPtr handle;

        public ChildJob()
        {
            handle = CreateJobObject(IntPtr.Zero, null);
            if (handle == IntPtr.Zero)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Unable to create service job object.");
            }
            var information = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            information.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            int length = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
            if (!SetInformationJobObject(handle, 9, ref information, (uint)length))
            {
                int error = Marshal.GetLastWin32Error();
                CloseHandle(handle);
                handle = IntPtr.Zero;
                throw new Win32Exception(error, "Unable to configure service job object.");
            }
        }

        public void Assign(Process process)
        {
            if (!AssignProcessToJobObject(handle, process.Handle))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Unable to attach child process to service job object.");
            }
        }

        public void Dispose()
        {
            if (handle != IntPtr.Zero)
            {
                CloseHandle(handle);
                handle = IntPtr.Zero;
            }
            GC.SuppressFinalize(this);
        }

        ~ChildJob()
        {
            Dispose();
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct JOBOBJECT_BASIC_LIMIT_INFORMATION
        {
            public long PerProcessUserTimeLimit;
            public long PerJobUserTimeLimit;
            public uint LimitFlags;
            public UIntPtr MinimumWorkingSetSize;
            public UIntPtr MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass;
            public uint SchedulingClass;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct IO_COUNTERS
        {
            public ulong ReadOperationCount;
            public ulong WriteOperationCount;
            public ulong OtherOperationCount;
            public ulong ReadTransferCount;
            public ulong WriteTransferCount;
            public ulong OtherTransferCount;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
        {
            public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
            public IO_COUNTERS IoInfo;
            public UIntPtr ProcessMemoryLimit;
            public UIntPtr JobMemoryLimit;
            public UIntPtr PeakProcessMemoryUsed;
            public UIntPtr PeakJobMemoryUsed;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateJobObject(IntPtr securityAttributes, string name);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetInformationJobObject(
            IntPtr job,
            int informationClass,
            ref JOBOBJECT_EXTENDED_LIMIT_INFORMATION information,
            uint informationLength);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr handle);
    }
}
"@
}

function Rotate-ServiceLog {
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [long]$MaximumBytes,
        [int]$RetainedFiles
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return
    }
    if ((Get-Item -LiteralPath $Path).Length -lt $MaximumBytes) {
        return
    }
    for ($index = $RetainedFiles - 1; $index -ge 1; $index--) {
        $source = "$Path.$index"
        $target = "$Path.$($index + 1)"
        if (Test-Path -LiteralPath $source) {
            Move-Item -LiteralPath $source -Destination $target -Force
        }
    }
    Move-Item -LiteralPath $Path -Destination "$Path.1" -Force
}

function Write-SupervisorState {
    param(
        [Parameter(Mandatory)]
        [string]$Status,
        [int]$ExitCode = 0,
        [string]$ErrorMessage = ""
    )

    $state = [ordered]@{
        service = $Service
        status = $Status
        supervisor_pid = $PID
        child_pid = $script:childPid
        python_executable = $resolvedPython
        project_root = $resolvedRoot
        started_at = $script:startedAt
        updated_at = [DateTimeOffset]::UtcNow.ToString("o")
        exit_code = $ExitCode
        error = if ([string]::IsNullOrWhiteSpace($ErrorMessage)) { $null } else { $ErrorMessage }
    }
    $state | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $statePath -Encoding UTF8
}

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
}
$resolvedRoot = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
$resolvedPython = (Resolve-Path -LiteralPath $PythonExecutable -ErrorAction Stop).Path
$resolvedLogDir = if ([IO.Path]::IsPathRooted($LogDir)) {
    [IO.Path]::GetFullPath($LogDir)
} else {
    [IO.Path]::GetFullPath((Join-Path $resolvedRoot $LogDir))
}
New-Item -ItemType Directory -Path $resolvedLogDir -Force | Out-Null

$entrypoint = Join-Path $resolvedRoot "finbot\cli\serve_$Service.py"
if (-not (Test-Path -LiteralPath $entrypoint -PathType Leaf)) {
    throw "FinBot $Service entrypoint not found: $entrypoint"
}

$stdoutPath = Join-Path $resolvedLogDir "finbot-$Service.stdout.log"
$stderrPath = Join-Path $resolvedLogDir "finbot-$Service.stderr.log"
$supervisorLogPath = Join-Path $resolvedLogDir "finbot-$Service.supervisor.log"
$statePath = Join-Path $resolvedLogDir "finbot-$Service.state.json"
$instanceLockPath = Join-Path $resolvedLogDir "finbot-$Service.instance.lock"
$instanceLock = $null
try {
    $instanceLock = [IO.File]::Open(
        $instanceLockPath,
        [IO.FileMode]::OpenOrCreate,
        [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::None
    )
} catch [IO.IOException] {
    Write-Error "FinBot $Service supervisor is already running for $resolvedRoot"
    exit 10
}
foreach ($logPath in @($stdoutPath, $stderrPath, $supervisorLogPath)) {
    Rotate-ServiceLog -Path $logPath -MaximumBytes $MaxLogBytes -RetainedFiles $RetainedLogFiles
}

$script:startedAt = [DateTimeOffset]::UtcNow.ToString("o")
$arguments = if ($Service -eq "web") {
    @(
        "-m", "finbot.cli.serve_web",
        "--data-dir", $DataDir,
        "--host", $HostAddress,
        "--port", [string]$Port,
        "--frontend-dist", $FrontendDist
    )
} else {
    @("-m", "finbot.cli.serve_worker", "--data-dir", $DataDir)
}

$previousLocation = Get-Location
$previousPythonPath = $env:PYTHONPATH
$previousUnbuffered = $env:PYTHONUNBUFFERED
$childProcess = $null
$childJob = $null
$stdoutStream = $null
$stderrStream = $null
$finalExitCode = 0
try {
    Set-Location -LiteralPath $resolvedRoot
    $env:PYTHONPATH = $resolvedRoot
    $env:PYTHONUNBUFFERED = "1"
    Write-SupervisorState -Status "starting"
    Add-Content -LiteralPath $supervisorLogPath -Encoding UTF8 -Value "$script:startedAt START service=$Service supervisor_pid=$PID"
    $processInfo = [Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = $resolvedPython
    $processInfo.Arguments = ($arguments | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }) -join " "
    $processInfo.WorkingDirectory = $resolvedRoot
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true

    $childProcess = [Diagnostics.Process]::new()
    $childProcess.StartInfo = $processInfo
    $childJob = [FinBot.ServiceRuntime.ChildJob]::new()
    if (-not $childProcess.Start()) {
        throw "Unable to start FinBot $Service child process."
    }
    $script:childPid = $childProcess.Id
    $childJob.Assign($childProcess)
    Write-SupervisorState -Status "running"

    $stdoutStream = [IO.File]::Open($stdoutPath, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
    $stderrStream = [IO.File]::Open($stderrPath, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
    $stdoutCopy = $childProcess.StandardOutput.BaseStream.CopyToAsync($stdoutStream)
    $stderrCopy = $childProcess.StandardError.BaseStream.CopyToAsync($stderrStream)
    $childProcess.WaitForExit()
    $stdoutCopy.GetAwaiter().GetResult()
    $stderrCopy.GetAwaiter().GetResult()
    $stdoutStream.Flush()
    $stderrStream.Flush()
    $exitCode = [int]$childProcess.ExitCode
    $finalExitCode = $exitCode
    $finishedAt = [DateTimeOffset]::UtcNow.ToString("o")
    Add-Content -LiteralPath $supervisorLogPath -Encoding UTF8 -Value "$finishedAt EXIT service=$Service exit_code=$exitCode"
    Write-SupervisorState -Status "exited" -ExitCode $exitCode
} catch {
    $finishedAt = [DateTimeOffset]::UtcNow.ToString("o")
    $message = $_.Exception.Message
    Add-Content -LiteralPath $supervisorLogPath -Encoding UTF8 -Value "$finishedAt ERROR service=$Service message=$message"
    Write-SupervisorState -Status "failed" -ExitCode 1 -ErrorMessage $message
    $finalExitCode = 1
} finally {
    if ($null -ne $stdoutStream) {
        $stdoutStream.Dispose()
    }
    if ($null -ne $stderrStream) {
        $stderrStream.Dispose()
    }
    if ($null -ne $childProcess) {
        if (-not $childProcess.HasExited) {
            $childProcess.Kill()
            $childProcess.WaitForExit()
        }
        $childProcess.Dispose()
    }
    if ($null -ne $childJob) {
        $childJob.Dispose()
    }
    if ($null -ne $instanceLock) {
        $instanceLock.Dispose()
    }
    Set-Location -LiteralPath $previousLocation
    $env:PYTHONPATH = $previousPythonPath
    $env:PYTHONUNBUFFERED = $previousUnbuffered
}
exit $finalExitCode
