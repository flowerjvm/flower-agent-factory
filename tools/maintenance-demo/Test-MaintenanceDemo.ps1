#Requires -Version 7.2
[CmdletBinding()]
param([switch] $Docker, [string] $HandoffPath, [string] $MavenRepository)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:TestRunDocker = [bool] $Docker
$script:TestHandoffArgument = $HandoffPath
$script:TestMavenArgument = $MavenRepository
$script:TestToolRoot = $PSScriptRoot
. (Join-Path $PSScriptRoot 'Invoke-MaintenanceDemo.ps1')
if (!$IsWindows) { throw 'WINDOWS_TEST_HOST_REQUIRED' }
if (!('Flower.MaintenanceDemo.DemoProcess' -as [type])) {
    Add-Type -Path (Join-Path $script:TestToolRoot 'DemoProcess.cs')
}
$script:TestCount = 0
$script:TestDockerCount = 0
$script:TestPwsh = (Get-Command pwsh -CommandType Application | Select-Object -First 1).Source
$script:TestHandoff = Get-DemoSafePath $(if ($script:TestHandoffArgument) {
    $script:TestHandoffArgument
} else { Join-Path $script:DemoRepoRoot 'demo-handoff-20260908-repair-ra001' })
$script:TestMaven = Get-DemoSafePath $(if ($script:TestMavenArgument) {
    $script:TestMavenArgument
} else { Join-Path $env:USERPROFILE '.m2/repository' })
$script:TestOwner = [Guid]::NewGuid().ToString('N')
$script:TestTempRoot = Get-DemoSafePath ([IO.Path]::GetTempPath().TrimEnd('\', '/'))
$script:TestRoot = Join-Path $script:TestTempRoot ('flower-maintenance-demo-' + $script:TestOwner)

function Assert-Test([bool] $Condition, [string] $Code) {
    if (!$Condition) { throw [InvalidOperationException]::new($Code) }
}

function Invoke-TestCase([string] $Name, [scriptblock] $Body) {
    try {
        & $Body
        $script:TestCount++
        Write-Host ('PASS HOST_' + $Name)
    } catch {
        Write-Host ('FAIL HOST_' + $Name)
        throw
    }
}

function Assert-TestThrows([scriptblock] $Body, [string] $ExpectedCode) {
    $caught = $false
    try { $null = & $Body } catch {
        $caught = $true
        if ($ExpectedCode) {
            $match = $false
            $errorObject = $_.Exception
            while ($errorObject) {
                if ($errorObject.Message -ceq $ExpectedCode) { $match = $true }
                $errorObject = $errorObject.InnerException
            }
            Assert-Test $match 'UNEXPECTED_FAILURE_CODE'
        }
    }
    Assert-Test $caught 'EXPECTED_REJECTION_NOT_OBSERVED'
}

function Invoke-TestMain([hashtable] $Options) {
    $arguments = @{
        InputPath = $script:TestValidFile
        OutputDirectory = (Join-Path $script:TestRoot 'not-created-output')
        HandoffPath = $script:TestHandoff
        MavenRepository = $script:TestMaven
    }
    foreach ($key in $Options.Keys) { $arguments[$key] = $Options[$key] }
    # Main's messages are bounded public status text. Suppress them in the harness;
    # only the numeric return code is asserted. These cases stop BEFORE Docker.
    $observed = @(Invoke-MaintenanceDemoMain @arguments 6>&1)
    $codes = @($observed | Where-Object { $_ -is [int] })
    Assert-Test ($codes.Count -eq 1) 'SINGLE_MAIN_EXIT_CODE_REQUIRED'
    return $codes[0]
}

function Invoke-TestChild([string] $Command, [byte[]] $InputBytes = [byte[]]::new(0),
    [int] $Seconds = 10, [int] $StdoutLimit = 65536, [int] $StderrLimit = 65536) {
    return Invoke-DemoProcess $script:TestPwsh @('-NoLogo', '-NoProfile', '-NonInteractive', '-Command', $Command) `
        $InputBytes $Seconds $StdoutLimit $StderrLimit
}

function Invoke-HostTests {
    $script:TestValidFile = Join-Path $script:TestRoot 'valid.json'
    $script:TestMalformedFile = Join-Path $script:TestRoot 'malformed.json'
    $script:TestOversizeFile = Join-Path $script:TestRoot 'oversize.json'
    $script:TestExisting = Join-Path $script:TestRoot 'existing-output'
    Write-DemoNewFile $script:TestValidFile $script:DemoUtf8.GetBytes(
        '{"incidentId":"INC-HOST","service":"api","summary":"Synthetic","observations":[]}')
    Write-DemoNewFile $script:TestMalformedFile $script:DemoUtf8.GetBytes('{')
    Write-DemoNewFile $script:TestOversizeFile ([byte[]]::new(262145))
    New-DemoPrivateDirectory $script:TestExisting
    $preserved = Join-Path $script:TestExisting 'preserved.txt'
    Write-DemoNewFile $preserved $script:DemoUtf8.GetBytes('owned-test-output-must-not-change')
    $script:TestPreservedHash = Get-DemoHash (Read-DemoFile $preserved 1024)
    $script:TestIndexBefore = Get-DemoHash (Read-DemoFile (Join-Path $script:TestHandoff 'handoff-index.json') 1048576)

    Invoke-TestCase 'private_test_directory_acl' {
        Assert-Test ((Get-Acl -LiteralPath $script:TestRoot).AreAccessRulesProtected) 'TEST_ACL_NOT_PRIVATE'
    }
    Invoke-TestCase 'help_needs_no_docker_or_input' {
        Assert-Test ((Invoke-TestMain @{ Help = $true; InputPath = 'missing' }) -eq 0) 'HELP_EXIT'
    }
    Invoke-TestCase 'missing_input_rejected_before_docker' {
        Assert-Test ((Invoke-TestMain @{ InputPath = (Join-Path $script:TestRoot 'missing.json') }) -eq 10) 'MISSING_INPUT_EXIT'
    }
    Invoke-TestCase 'oversize_input_rejected_before_docker' {
        Assert-Test ((Invoke-TestMain @{ InputPath = $script:TestOversizeFile }) -eq 2) 'OVERSIZE_INPUT_EXIT'
    }
    Invoke-TestCase 'invalid_json_rejected_before_docker' {
        Assert-Test ((Invoke-TestMain @{ InputPath = $script:TestMalformedFile }) -eq 2) 'INVALID_JSON_EXIT'
    }
    Invoke-TestCase 'existing_output_preserved_before_docker' {
        Assert-Test ((Invoke-TestMain @{ OutputDirectory = $script:TestExisting }) -eq 10) 'EXISTING_OUTPUT_EXIT'
        Assert-Test ((Get-DemoHash (Read-DemoFile $preserved 1024)) -ceq $script:TestPreservedHash) 'EXISTING_OUTPUT_CHANGED'
        Assert-Test ((Get-DemoFiles $script:TestExisting).Count -eq 1) 'EXISTING_OUTPUT_FILES_CHANGED'
    }
    Invoke-TestCase 'protected_tool_output_rejected_before_docker' {
        Assert-Test ((Invoke-TestMain @{ OutputDirectory = (Join-Path $script:TestToolRoot ('test-forbidden-' + $script:TestOwner)) }) -eq 10) 'PROTECTED_OUTPUT_EXIT'
    }
    Invoke-TestCase 'nonexistent_output_parent_rejected_before_docker' {
        Assert-Test ((Invoke-TestMain @{ OutputDirectory = (Join-Path $script:TestRoot 'missing-parent/result') }) -eq 10) 'MISSING_PARENT_EXIT'
    }
    Invoke-TestCase 'timeout_below_range_rejected_before_docker' {
        Assert-Test ((Invoke-TestMain @{ TimeoutSeconds = 4 }) -eq 10) 'TIMEOUT_RANGE_EXIT'
    }
    Invoke-TestCase 'timeout_above_range_rejected_before_docker' {
        Assert-Test ((Invoke-TestMain @{ TimeoutSeconds = 181 }) -eq 10) 'TIMEOUT_RANGE_EXIT'
    }
    Invoke-TestCase 'path_traversal_rejected' {
        Assert-TestThrows { Get-DemoSafePath (Join-Path $script:TestRoot '../escape') } 'UNSAFE_PATH'
    }
    Invoke-TestCase 'dot_relative_path_respects_powershell_location' {
        $previousDirectory = [Environment]::CurrentDirectory
        Push-Location -LiteralPath $script:TestRoot
        try {
            [Environment]::CurrentDirectory = $script:DemoRepoRoot
            Assert-Test ((Get-DemoSafePath '.\valid.json') -ceq $script:TestValidFile) 'RELATIVE_PATH_USED_PROCESS_DIRECTORY'
            Assert-Test ((Get-DemoSafePath './valid.json') -ceq $script:TestValidFile) 'RELATIVE_FORWARD_PATH_USED_PROCESS_DIRECTORY'
        } finally {
            [Environment]::CurrentDirectory = $previousDirectory
            Pop-Location
        }
    }
    Invoke-TestCase 'drive_relative_path_rejected' {
        Assert-TestThrows { Get-DemoSafePath 'C:input.json' } 'DRIVE_RELATIVE_PATH_NOT_ALLOWED'
    }
    Invoke-TestCase 'alternate_data_stream_rejected' {
        Assert-TestThrows { Get-DemoSafePath ($script:TestValidFile + ':hidden') } 'LOCAL_WINDOWS_PATH_REQUIRED'
    }
    Invoke-TestCase 'unc_path_rejected' {
        Assert-TestThrows { Get-DemoSafePath '\\localhost\share\input.json' } 'UNSAFE_PATH'
    }
    Invoke-TestCase 'docker_mount_comma_rejected' {
        Assert-TestThrows { Get-DemoSafePath (Join-Path $script:TestRoot 'a,b.json') } 'UNSAFE_PATH'
    }
    Invoke-TestCase 'credential_path_rejected_without_reading' {
        Assert-TestThrows { Get-DemoSafePath (Join-Path $script:TestRoot '.codex/auth.json') } 'PROTECTED_OR_NONPORTABLE_PATH'
    }
    Invoke-TestCase 'environment_file_path_rejected_without_reading' {
        Assert-TestThrows { Get-DemoSafePath (Join-Path $script:TestRoot '.env.local') } 'PROTECTED_OR_NONPORTABLE_PATH'
    }
    Invoke-TestCase 'reserved_windows_name_rejected' {
        Assert-TestThrows { Get-DemoSafePath (Join-Path $script:TestRoot 'NUL.txt') } 'PROTECTED_OR_NONPORTABLE_PATH'
    }
    Invoke-TestCase 'trailing_dot_rejected' {
        Assert-TestThrows { Get-DemoSafePath (Join-Path $script:TestRoot 'example.') } 'PROTECTED_OR_NONPORTABLE_PATH'
    }
    Invoke-TestCase 'member_traversal_rejected' {
        Assert-TestThrows { Assert-DemoMemberPath '../escape.java' } 'INVALID_HANDOFF_MEMBER'
    }
    Invoke-TestCase 'member_absolute_path_rejected' {
        Assert-TestThrows { Assert-DemoMemberPath '/absolute.java' } 'INVALID_HANDOFF_MEMBER'
    }
    Invoke-TestCase 'member_backslash_rejected' {
        Assert-TestThrows { Assert-DemoMemberPath 'src\Example.java' } 'INVALID_HANDOFF_MEMBER'
    }
    Invoke-TestCase 'member_duplicate_separator_rejected' {
        Assert-TestThrows { Assert-DemoMemberPath 'src//Example.java' } 'INVALID_HANDOFF_MEMBER'
    }
    Invoke-TestCase 'local_docker_named_pipe_accepted' {
        Assert-DemoLocalDockerEndpoint 'npipe:////./pipe/dockerDesktopLinuxEngine'
    }
    Invoke-TestCase 'remote_named_pipe_rejected_without_connection' {
        Assert-TestThrows { Assert-DemoLocalDockerEndpoint 'npipe:////remote/pipe/docker_engine' } 'LOCAL_DOCKER_NAMED_PIPE_REQUIRED'
    }
    Invoke-TestCase 'tcp_docker_endpoint_rejected_without_connection' {
        Assert-TestThrows { Assert-DemoLocalDockerEndpoint 'tcp://127.0.0.1:2375' } 'LOCAL_DOCKER_NAMED_PIPE_REQUIRED'
    }
    Invoke-TestCase 'bounded_file_reader' {
        Assert-TestThrows { Read-DemoFile $script:TestOversizeFile 262144 } 'FILE_SIZE_LIMIT'
    }
    Invoke-TestCase 'missing_regular_file_rejected' {
        Assert-TestThrows { Read-DemoFile (Join-Path $script:TestRoot 'absent.json') 1024 } 'REQUIRED_FILE_MISSING'
    }
    Invoke-TestCase 'new_file_writer_preserves_existing_bytes' {
        Assert-TestThrows { Write-DemoNewFile $preserved $script:DemoUtf8.GetBytes('replacement') } ''
        Assert-Test ((Get-DemoHash (Read-DemoFile $preserved 1024)) -ceq $script:TestPreservedHash) 'NEW_FILE_OVERWROTE_EXISTING'
    }
    Invoke-TestCase 'new_file_creation_reference_marks_only_owned_write' {
        $created = $false
        $fresh = Join-Path $script:TestRoot 'creation-reference.txt'
        Write-DemoNewFile $fresh $script:DemoUtf8.GetBytes('owned-write') ([ref] $created)
        Assert-Test $created 'CREATE_REFERENCE_NOT_SET'
        $created = $false
        Assert-TestThrows { Write-DemoNewFile $fresh $script:DemoUtf8.GetBytes('replacement') ([ref] $created) } ''
        Assert-Test (!$created) 'REJECTED_CREATE_CLAIMED_OWNERSHIP'
        Assert-Test ($script:DemoUtf8.GetString((Read-DemoFile $fresh 1024)) -ceq 'owned-write') 'CREATE_REFERENCE_OVERWROTE_FILE'
    }
    Invoke-TestCase 'hardlinked_file_rejected' {
        $link = Join-Path $script:TestRoot 'hardlink.json'
        $null = New-Item -ItemType HardLink -Path $link -Target $script:TestValidFile -ErrorAction Stop
        try {
            Assert-TestThrows { Read-DemoFile $script:TestValidFile 1024 } 'UNSAFE_LINK_PATH'
            Assert-TestThrows { [Flower.MaintenanceDemo.DemoProcess]::ReadRegularFile($script:TestValidFile, 1024) } 'REGULAR_SINGLE_LINK_FILE_REQUIRED'
        } finally {
            # Exact owned hardlink only; no recursion and never a handoff target.
            Assert-Test (Test-DemoWithin $link $script:TestRoot) 'LINK_CLEANUP_SCOPE'
            Remove-Item -LiteralPath $link -Force -ErrorAction Stop
        }
    }
    Invoke-TestCase 'json_root_object_required' {
        Assert-TestThrows { ConvertFrom-DemoJson $script:DemoUtf8.GetBytes('[]') } 'JSON_OBJECT_REQUIRED'
    }
    Invoke-TestCase 'malformed_utf8_rejected' {
        Assert-TestThrows { ConvertFrom-DemoJson ([byte[]] @(0xc3, 0x28)) } ''
    }
    Invoke-TestCase 'utf8_bom_json_accepted' {
        $withBom = $script:DemoUtf8.GetBytes(([string][char]0xFEFF) + '{"name":"Unicode 왕복 🚢"}')
        $parsed = ConvertFrom-DemoJson $withBom
        Assert-Test ($parsed.name -ceq 'Unicode 왕복 🚢') 'UTF8_BOM_NOT_ACCEPTED'
    }
    Invoke-TestCase 'host_json_depth_limit' {
        Assert-TestThrows { ConvertFrom-DemoJson $script:DemoUtf8.GetBytes('{"a":' + ('[' * 33) + '0' + (']' * 33) + '}') } ''
    }
    Invoke-TestCase 'exact_handoff_28_payloads_and_source_tree' {
        $script:TestMembers = Read-DemoHandoff $script:TestHandoff
        Assert-Test ($script:TestMembers.Count -eq 28) 'HANDOFF_PAYLOAD_COUNT'
        Assert-Test ($script:TestMembers.ContainsKey('provenance/acceptance-case-suite.json')) 'CASE_SUITE_REQUIRED'
    }
    Invoke-TestCase 'tampered_index_rejected' {
        $fake = Join-Path $script:TestRoot 'tampered-handoff'
        New-DemoPrivateDirectory $fake
        Write-DemoNewFile (Join-Path $fake 'handoff-index.json') $script:DemoUtf8.GetBytes('{}')
        Assert-TestThrows { Read-DemoHandoff $fake } 'HANDOFF_INDEX_HASH_MISMATCH'
    }
    Invoke-TestCase 'unmodified_index_without_members_rejected' {
        $fake = Join-Path $script:TestRoot 'incomplete-handoff'
        New-DemoPrivateDirectory $fake
        Write-DemoNewFile (Join-Path $fake 'handoff-index.json') (Read-DemoFile (Join-Path $script:TestHandoff 'handoff-index.json') 1048576)
        Assert-TestThrows { Read-DemoHandoff $fake } 'REQUIRED_FILE_MISSING'
    }
    Invoke-TestCase 'helper_utf8_stdin_stdout_exact_bytes' {
        $bytes = $script:DemoUtf8.GetBytes('UTF-8 왕복 🚢 Unicode')
        $result = Invoke-TestChild '[Console]::OpenStandardInput().CopyTo([Console]::OpenStandardOutput())' $bytes
        Assert-DemoProcessOk $result 'UTF8_CHILD_FAILED'
        Assert-Test ((Get-DemoHash $result.Stdout) -ceq (Get-DemoHash $bytes)) 'UTF8_BYTES_CHANGED'
        Assert-Test ($result.Stderr.Length -eq 0) 'UNEXPECTED_STDERR'
    }
    Invoke-TestCase 'helper_child_exit_code_preserved' {
        $result = Invoke-TestChild 'exit 7'
        Assert-Test ($result.ExitCode -eq 7 -and !$result.TimedOut -and !$result.OutputLimitExceeded) 'CHILD_EXIT_CODE_CHANGED'
    }
    Invoke-TestCase 'helper_timeout_is_bounded' {
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $result = Invoke-TestChild 'Start-Sleep -Seconds 15' -Seconds 2
        $clock.Stop()
        Assert-Test ($result.TimedOut -and !$result.OutputLimitExceeded) 'CHILD_TIMEOUT_NOT_OBSERVED'
        Assert-Test ($clock.Elapsed.TotalSeconds -lt 10) 'CHILD_TIMEOUT_UNBOUNDED'
    }
    Invoke-TestCase 'helper_stdout_limit_is_bounded' {
        $result = Invoke-TestChild '[Console]::OpenStandardOutput().Write([byte[]]::new(8192))' -StdoutLimit 1024
        Assert-Test ($result.OutputLimitExceeded -and $result.Stdout.Length -le 1024) 'STDOUT_LIMIT_NOT_ENFORCED'
    }
    Invoke-TestCase 'helper_stderr_limit_is_bounded' {
        $result = Invoke-TestChild '[Console]::OpenStandardError().Write([byte[]]::new(8192))' -StderrLimit 1024
        Assert-Test ($result.OutputLimitExceeded -and $result.Stderr.Length -le 1024) 'STDERR_LIMIT_NOT_ENFORCED'
    }
    Invoke-TestCase 'helper_input_bound_rejected_before_child_start' {
        Assert-TestThrows { Invoke-TestChild 'exit 0' ([byte[]]::new(262145)) } 'PROCESS_BOUNDS'
    }
    Invoke-TestCase 'original_handoff_unchanged_after_host_tests' {
        Assert-Test ((Get-DemoHash (Read-DemoFile (Join-Path $script:TestHandoff 'handoff-index.json') 1048576)) -ceq
            $script:TestIndexBefore) 'HANDOFF_INDEX_CHANGED'
        $again = Read-DemoHandoff $script:TestHandoff
        Assert-Test ($again.Count -eq 28) 'HANDOFF_RECHECK_FAILED'
        Assert-Test (!(Test-Path -LiteralPath (Join-Path $script:TestRoot 'not-created-output'))) 'FAILED_RUN_CREATED_OUTPUT'
    }
}

function Invoke-DockerTests {
    Write-Host 'DOCKER_PHASE: native execution is NOT_RUN until the explicit sandbox test succeeds.'
    $dockerExecutable = (Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    if ($env:DOCKER_HOST) { Assert-DemoLocalDockerEndpoint $env:DOCKER_HOST }
    $context = Invoke-DemoProcess $dockerExecutable @('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}')
    Assert-DemoProcessOk $context 'DOCKER_CONTEXT_UNAVAILABLE'
    Assert-DemoLocalDockerEndpoint $script:DemoUtf8.GetString($context.Stdout).Trim()
    $info = Invoke-DemoProcess $dockerExecutable @('info', '--format', '{{.OSType}}')
    Assert-DemoProcessOk $info 'DOCKER_ENGINE_NOT_RUNNING'
    Assert-Test ($script:DemoUtf8.GetString($info.Stdout).Trim() -ceq 'linux') 'DOCKER_LINUX_ENGINE_REQUIRED'
    $image = Invoke-DemoProcess $dockerExecutable @('image', 'inspect', '--format', '{{.Os}}', $script:DemoImage)
    Assert-DemoProcessOk $image 'PINNED_DOCKER_IMAGE_MISSING'
    Assert-Test ($script:DemoUtf8.GetString($image.Stdout).Trim() -ceq 'linux') 'DOCKER_LINUX_IMAGE_REQUIRED'
    $owner = [Guid]::NewGuid().ToString('N')
    $stage = Join-Path $script:TestTempRoot ('flower-maintenance-demo-' + $owner)
    $name = 'flower-maintenance-demo-' + $owner
    $attempted = $false
    $cleanupConfirmed = $true
    try {
        New-DemoPrivateDirectory $stage
        Write-DemoNewFile (Join-Path $stage '.demo-owner') $script:DemoUtf8.GetBytes($owner)
        New-DemoPrivateDirectory (Join-Path $stage 'lib')
        $prefix = 'product-source/src/main/java/io/github/flowerjvm/pack/maintenance/'
        foreach ($javaName in @('IncidentInvestigator.java', 'InvestigationAcceptanceApi.java')) {
            Write-DemoNewFile (Join-Path $stage $javaName) $script:TestMembers[$prefix + $javaName]
        }
        foreach ($javaName in @('MaintenanceDemoCli.java', 'MaintenanceDemoCliTest.java')) {
            Write-DemoNewFile (Join-Path $stage $javaName) (Read-DemoFile (Join-Path $script:TestToolRoot $javaName) 131072)
        }
        Write-DemoNewFile (Join-Path $stage 'case-suite.json') $script:TestMembers['provenance/acceptance-case-suite.json']
        $dependencies = @(
            @{ path = 'com/fasterxml/jackson/core/jackson-databind/2.21.4/jackson-databind-2.21.4.jar'; hash = '3888e9e69ab66fbacaacc9aea0e9ffbf15368288e4aca468b024dba11c09fbf9' },
            @{ path = 'com/fasterxml/jackson/core/jackson-core/2.21.4/jackson-core-2.21.4.jar'; hash = '4b40a06396f239f8de2da57419adde6e94e5edc18a2171d471ea05eeed4e5c2d' },
            @{ path = 'com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar'; hash = '53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b' }
        )
        foreach ($dependency in $dependencies) {
            $dependencyPath = Get-DemoSafePath (Join-Path $script:TestMaven $dependency.path)
            $bytes = Read-DemoFile $dependencyPath 8388608
            Assert-Test ((Get-DemoHash $bytes) -ceq $dependency.hash) 'PINNED_JACKSON_JAR_HASH_MISMATCH'
            Write-DemoNewFile (Join-Path (Join-Path $stage 'lib') ([IO.Path]::GetFileName($dependencyPath))) $bytes
        }
        # Constant shell only; no user-controlled values are interpolated or executed.
        $command = 'mkdir /tmp/classes && javac -J-Xmx128m -proc:none -encoding UTF-8 --release 21 -cp "/input/lib/*" -d /tmp/classes /input/IncidentInvestigator.java /input/InvestigationAcceptanceApi.java /input/MaintenanceDemoCli.java /input/MaintenanceDemoCliTest.java && exec java -Xmx128m -Duser.home=/tmp -Djava.io.tmpdir=/tmp -cp "/tmp/classes:/input/lib/*" MaintenanceDemoCliTest /input/case-suite.json'
        $arguments = @('run', '--name', $name, '--label', ($script:DemoLabel + '=' + $owner),
            '--pull', 'never', '--network', 'none', '--read-only', '--user', '65534:65534',
            '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--memory', '512m',
            '--cpus', '1', '--pids-limit', '128', '--log-driver', 'none',
            '--entrypoint', 'timeout',
            '--tmpfs', '/tmp:rw,nosuid,nodev,size=128m,mode=1777',
            '--mount', ('type=bind,source=' + $stage + ',target=/input,readonly'),
            $script:DemoImage, '--signal=KILL', '90s', 'sh', '-c', $command)
        $attempted = $true
        $cleanupConfirmed = $false
        $run = Invoke-DemoProcess $dockerExecutable $arguments ([byte[]]::new(0)) 95 65536 65536
        Assert-DemoProcessOk $run 'DOCKER_CLI_TESTS_FAILED'
        $lines = $script:DemoUtf8.GetString($run.Stdout) -split '\r?\n'
        foreach ($line in $lines) {
            if ($line -match '^PASS (ADAPTER_[a-z0-9_]+|FACTORY_[A-Z_]+ invocations=[1-3])$') { Write-Host $line }
        }
        Assert-Test ($lines -contains 'PASS SUMMARY adapterTests=30 factoryCases=16 factoryInvocations=18') 'DOCKER_TEST_COUNTS'
        Remove-DemoOwnedContainer $dockerExecutable $name $owner
        $attempted = $false
        $cleanupConfirmed = $true
        $script:TestDockerCount++
        Write-Host 'PASS DOCKER_java_cli_30_and_factory_16_cases_18_invocations'
    } finally {
        if ($attempted) {
            Remove-DemoOwnedContainer $dockerExecutable $name $owner
            $cleanupConfirmed = $true
        }
        if ($cleanupConfirmed -and (Test-Path -LiteralPath $stage)) {
            Remove-DemoOwnedStage $stage $script:TestTempRoot $owner
        }
    }
    $successPath = Join-Path $script:TestRoot 'native-result'
    $resultCode = Invoke-TestMain @{ InputPath = (Join-Path $script:TestToolRoot 'examples/incident.json'); OutputDirectory = $successPath }
    Assert-Test ($resultCode -eq 0) 'NATIVE_LAUNCHER_SUCCESS_FAILED'
    $runInfo = ConvertFrom-DemoJson (Read-DemoFile (Join-Path $successPath 'run-info.json') 1048576)
    Assert-Test ($runInfo.status -ceq 'SMOKE_RUN_PASSED' -and $runInfo.containerRemovalConfirmed -and
        !$runInfo.liveCertificationStatusChecked -and $runInfo.sourceTreeSha256 -ceq $script:DemoSourceHash) 'NATIVE_RUN_INFO'
    foreach ($file in $runInfo.files) {
        Assert-DemoMemberPath $file.path
        $bytes = Read-DemoFile (Join-Path $successPath $file.path) 2097152
        Assert-Test ($bytes.Length -eq $file.sizeBytes -and (Get-DemoHash $bytes) -ceq $file.sha256) 'NATIVE_OUTPUT_HASH'
    }
    $script:TestDockerCount++
    Write-Host 'PASS DOCKER_launcher_actual_success_and_output_hashes'
    $invalidPath = Join-Path $script:TestRoot 'native-invalid-output'
    $invalidCode = Invoke-TestMain @{ InputPath = (Join-Path $script:TestToolRoot 'examples/invalid-incident.json'); OutputDirectory = $invalidPath }
    Assert-Test ($invalidCode -eq 3 -and !(Test-Path -LiteralPath $invalidPath)) 'NATIVE_INVALID_DOMAIN_BOUNDARY'
    $script:TestDockerCount++
    Write-Host 'PASS DOCKER_launcher_actual_invalid_domain_no_output'
    $again = Read-DemoHandoff $script:TestHandoff
    Assert-Test ($again.Count -eq 28 -and (Get-DemoHash (Read-DemoFile (Join-Path $script:TestHandoff 'handoff-index.json') 1048576)) -ceq
        $script:TestIndexBefore) 'NATIVE_HANDOFF_CHANGED'
}

$passed = $false
try {
    New-DemoPrivateDirectory $script:TestRoot
    Write-DemoNewFile (Join-Path $script:TestRoot '.demo-owner') $script:DemoUtf8.GetBytes($script:TestOwner)
    Invoke-HostTests
    if ($script:TestRunDocker) { Invoke-DockerTests }
    $passed = $true
} catch {
    # No raw stderr, exception messages or private paths in test diagnostics.
    Write-Host ('FAIL TEST_SUITE type=' + $_.Exception.GetType().Name + ' line=' + $_.InvocationInfo.ScriptLineNumber)
    if ($script:TestRunDocker -and $script:TestDockerCount -eq 0) {
        $safeCode = $_.Exception.Message
        if ($safeCode -cnotmatch '^[A-Z][A-Z0-9_]{2,80}$') { $safeCode = 'PREREQUISITE_OR_NATIVE_TEST_FAILURE' }
        Write-Host ('DOCKER_NATIVE: NOT_RUN_OR_NOT_PASSED code=' + $safeCode)
    }
} finally {
    if (Test-Path -LiteralPath $script:TestRoot) {
        try { Remove-DemoOwnedStage $script:TestRoot $script:TestTempRoot $script:TestOwner } catch {
            $passed = $false
            Write-Host 'FAIL TEST_OWNED_TEMP_CLEANUP (preserved; no broad cleanup attempted)'
        }
    }
}
if ($passed) {
    $dockerStatus = if ($script:TestRunDocker) { 'PASS' } else { 'NOT_RUN' }
    Write-Host ('PASS SUMMARY hostTests=' + $script:TestCount + ' dockerGroups=' + $script:TestDockerCount + ' docker=' + $dockerStatus)
    exit 0
}
exit 1
