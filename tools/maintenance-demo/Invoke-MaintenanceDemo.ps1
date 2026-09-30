#Requires -Version 7.2
<#!
.SYNOPSIS
Offline, one-shot execution of an exact historical Maintenance Pack release snapshot.
.DESCRIPTION
Checks the sealed handoff before executing only its two pinned Java source files in
a local Docker Linux sandbox. This is a consumer smoke run, not Factory certification,
a new release, a live certificate-status lookup, deployment, or a persistent service.
#>
[CmdletBinding()]
param(
    [string] $InputPath,
    [string] $OutputDirectory,
    [string] $HandoffPath,
    [string] $MavenRepository,
    [ValidateRange(5, 180)] [int] $TimeoutSeconds = 60,
    [Alias('h')] [switch] $Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:DemoToolRoot = $PSScriptRoot
$script:DemoLauncherPath = $PSCommandPath
$script:DemoRepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$script:DemoUtf8 = [Text.UTF8Encoding]::new($false, $true)
$script:DemoIndexHash = 'af8acb8f8f1d2091e97c326b2d54dcbc05988f7eac52bfe59a4ed4456924739c'
$script:DemoSourceHash = 'b070bb863765fc35757ac0c90d7401229c14479ff0239141aeb5fffbfbd58232'
$script:DemoImage = 'maven@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e'
$script:DemoLabel = 'io.github.flowerjvm.maintenance-demo.owner'

function Stop-Demo([string] $Code) { throw [InvalidOperationException]::new($Code) }

function Get-DemoHash([byte[]] $Bytes) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant()
}

function Test-DemoWithin([string] $Path, [string] $Parent) {
    return $Path.Equals($Parent, [StringComparison]::OrdinalIgnoreCase) -or
        $Path.StartsWith($Parent.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)
}

function Assert-DemoNoLinks([string] $Path) {
    $cursor = $Path
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $item.LinkType) {
                Stop-Demo 'UNSAFE_LINK_PATH'
            }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if (!$parent -or $parent -eq $cursor) { break }
        $cursor = $parent
    }
}

function Get-DemoSafePath([string] $Path) {
    if ($Path -and ($Path.StartsWith('.\') -or $Path.StartsWith('./'))) { $Path = $Path.Substring(2) }
    if ([string]::IsNullOrWhiteSpace($Path) -or $Path.Length -gt 1024 -or
        $Path -match '[\x00-\x1f\x7f]' -or $Path -match '(^|[\\/])\.{1,2}([\\/]|$)' -or
        $Path.StartsWith('\\') -or $Path.Contains('::') -or $Path.Contains(',')) {
        Stop-Demo 'UNSAFE_PATH'
    }
    # Windows GetFullPath may erase a trailing dot/space. Reject those aliases
    # in the raw spelling before canonicalization can conceal them.
    foreach ($rawPart in ($Path -split '[\\/]')) {
        if ($rawPart.EndsWith('.') -or $rawPart.EndsWith(' ')) {
            Stop-Demo 'PROTECTED_OR_NONPORTABLE_PATH'
        }
    }
    if ($Path -match '^[A-Za-z]:[^\\/]') { Stop-Demo 'DRIVE_RELATIVE_PATH_NOT_ALLOWED' }
    if ([IO.Path]::IsPathFullyQualified($Path)) {
        $full = [IO.Path]::GetFullPath($Path)
    } else {
        $location = Get-Location
        if ($location.Provider.Name -cne 'FileSystem') { Stop-Demo 'FILESYSTEM_LOCATION_REQUIRED' }
        # Set-Location does not always update Environment.CurrentDirectory.
        $full = [IO.Path]::GetFullPath($Path, $location.ProviderPath)
    }
    if ($full -notmatch '^[A-Za-z]:\\' -or $full.Substring(2).Contains(':')) {
        Stop-Demo 'LOCAL_WINDOWS_PATH_REQUIRED'
    }
    foreach ($part in ($full.Substring(3) -split '[\\/]')) {
        if (!$part -or $part.EndsWith('.') -or $part.EndsWith(' ') -or
            $part -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$' -or
            $part -match '^(?i:\.git|\.codex|\.codex-factory|\.ssh|\.aws|\.azure|\.kube|auth\.json|\.env(?:\..*)?)$') {
            Stop-Demo 'PROTECTED_OR_NONPORTABLE_PATH'
        }
    }
    Assert-DemoNoLinks $full
    return $full
}

function Assert-DemoMemberPath([string] $Relative) {
    if (!$Relative -or $Relative.Length -gt 240 -or $Relative -notmatch '^[A-Za-z0-9._/-]+$' -or
        $Relative.StartsWith('/') -or $Relative.EndsWith('/') -or $Relative.Contains('//')) {
        Stop-Demo 'INVALID_HANDOFF_MEMBER'
    }
    foreach ($part in ($Relative -split '/')) {
        if ($part -in '.', '..' -or $part.EndsWith('.') -or
            $part -match '^(?i:\.git|\.codex|\.ssh|CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$') {
            Stop-Demo 'INVALID_HANDOFF_MEMBER'
        }
    }
}

function Read-DemoFile([string] $Path, [int] $Maximum) {
    Assert-DemoNoLinks $Path
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { Stop-Demo 'REQUIRED_FILE_MISSING' }
    $bytes = [Flower.MaintenanceDemo.DemoProcess]::ReadRegularFile($Path, $Maximum)
    Assert-DemoNoLinks $Path
    return ,$bytes
}

function ConvertFrom-DemoJson([byte[]] $Bytes, [int] $Depth = 32) {
    $text = $script:DemoUtf8.GetString($Bytes)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
    # JsonDocument rejects comments/trailing commas and caps nesting. The Java CLI
    # additionally rejects duplicate object keys before product code can run.
    $options = [Text.Json.JsonDocumentOptions]::new()
    $options.MaxDepth = $Depth
    $document = [Text.Json.JsonDocument]::Parse($text, $options)
    try {
        if ($document.RootElement.ValueKind -ne [Text.Json.JsonValueKind]::Object) {
            Stop-Demo 'JSON_OBJECT_REQUIRED'
        }
    } finally { $document.Dispose() }
    return ConvertFrom-Json -InputObject $text -AsHashtable -Depth $Depth -ErrorAction Stop
}

function Get-DemoFiles([string] $Root, [int] $Maximum = 128) {
    # Manual bounded traversal: never ask recursive enumeration to walk a link first.
    $pending = [Collections.Generic.Queue[string]]::new()
    $pending.Enqueue($Root)
    $files = [Collections.Generic.List[string]]::new()
    $visited = 0
    while ($pending.Count -gt 0) {
        $directory = $pending.Dequeue()
        Assert-DemoNoLinks $directory
        foreach ($item in [IO.Directory]::EnumerateFileSystemEntries($directory)) {
            $visited++
            if ($visited -gt $Maximum) { Stop-Demo 'DIRECTORY_MEMBER_LIMIT' }
            Assert-DemoNoLinks $item
            $info = Get-Item -LiteralPath $item -Force
            if ($info.PSIsContainer) { $pending.Enqueue($item) } else { $files.Add($item) }
        }
    }
    return ,$files.ToArray()
}

function Read-DemoHandoff([string] $Root) {
    $indexBytes = Read-DemoFile (Join-Path $Root 'handoff-index.json') 1048576
    if ((Get-DemoHash $indexBytes) -cne $script:DemoIndexHash) { Stop-Demo 'HANDOFF_INDEX_HASH_MISMATCH' }
    $index = ConvertFrom-DemoJson $indexBytes
    if ($index.schemaVersion -cne 'factory.demo-handoff-index.v1' -or
        $index.scope -cne 'released-snapshot-not-new-certified-product' -or
        $index.fileCount -ne 28 -or $index.files.Count -ne 28 -or $index.totalBytes -ne 93891) {
        Stop-Demo 'HANDOFF_INDEX_CONTRACT'
    }
    $members = [Collections.Generic.Dictionary[string, byte[]]]::new([StringComparer]::OrdinalIgnoreCase)
    [long] $total = 0
    foreach ($entry in $index.files) {
        Assert-DemoMemberPath $entry.path
        if ($members.ContainsKey($entry.path) -or $entry.path -ieq 'handoff-index.json' -or
            $entry.sha256 -cnotmatch '^[a-f0-9]{64}$' -or $entry.sizeBytes -lt 0 -or
            $entry.sizeBytes -gt 8388608) { Stop-Demo 'HANDOFF_MEMBER_CONTRACT' }
        $path = Join-Path $Root $entry.path
        if (!(Test-DemoWithin $path $Root)) { Stop-Demo 'HANDOFF_MEMBER_ESCAPE' }
        $bytes = Read-DemoFile $path 8388608
        if ($bytes.Length -ne $entry.sizeBytes -or (Get-DemoHash $bytes) -cne $entry.sha256) {
            Stop-Demo 'HANDOFF_MEMBER_HASH_MISMATCH'
        }
        $members.Add($entry.path, $bytes)
        $total += $bytes.Length
        if ($total -gt 67108864) { Stop-Demo 'HANDOFF_SIZE_LIMIT' }
    }
    $actual = Get-DemoFiles $Root
    if ($actual.Count -ne 29 -or $total -ne $index.totalBytes) { Stop-Demo 'HANDOFF_UNEXPECTED_FILES' }
    foreach ($path in $actual) {
        $relative = [IO.Path]::GetRelativePath($Root, $path).Replace('\', '/')
        if ($relative -cne 'handoff-index.json' -and !$members.ContainsKey($relative)) {
            Stop-Demo 'HANDOFF_UNEXPECTED_FILES'
        }
    }
    $manifest = ConvertFrom-DemoJson $members['provenance/source-manifest.json']
    if ($manifest.schemaVersion -cne 'factory.candidate-source-manifest.v1' -or
        $manifest.sourceLockAlgorithmId -cne 'factory.ordinal-sha256.v1' -or
        $manifest.candidateHash -cne $script:DemoSourceHash -or
        $manifest.fileCount -ne 4 -or $manifest.files.Count -ne 4 -or $manifest.totalBytes -ne 16661) {
        Stop-Demo 'SOURCE_MANIFEST_CONTRACT'
    }
    $lines = [Collections.Generic.List[string]]::new()
    $sourceNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    [long] $sourceTotal = 0
    foreach ($entry in $manifest.files) {
        Assert-DemoMemberPath $entry.path
        if (!$sourceNames.Add($entry.path) -or !$members.ContainsKey('product-source/' + $entry.path)) {
            Stop-Demo 'SOURCE_MEMBER_MISMATCH'
        }
        $bytes = $members['product-source/' + $entry.path]
        if ($bytes.Length -ne $entry.sizeBytes -or (Get-DemoHash $bytes) -cne $entry.contentHash) {
            Stop-Demo 'SOURCE_MEMBER_MISMATCH'
        }
        $sourceTotal += $bytes.Length
        $lines.Add($entry.path + "`t" + $entry.contentHash)
    }
    $lines.Sort([StringComparer]::Ordinal)
    $treeHash = Get-DemoHash $script:DemoUtf8.GetBytes([string]::Join("`n", $lines))
    if ($treeHash -cne $script:DemoSourceHash -or $sourceTotal -ne 16661) { Stop-Demo 'SOURCE_TREE_HASH_MISMATCH' }
    $javaPrefix = 'product-source/src/main/java/io/github/flowerjvm/pack/maintenance/'
    if ((Get-DemoHash $members[$javaPrefix + 'IncidentInvestigator.java']) -cne
            'e256a77e59f721419f1ca31151d4503424e7fcffdc58b8039045ac60fe7455a9' -or
        (Get-DemoHash $members[$javaPrefix + 'InvestigationAcceptanceApi.java']) -cne
            'cf193ad9d0d10171a124007bf1da102f8e5483126183da4c3d736226c83c85b4') {
        Stop-Demo 'EXECUTION_SOURCE_HASH_MISMATCH'
    }
    # Recheck the pinned index after reading. Only the bytes held above are staged;
    # later edits to the handoff cannot alter execution inputs.
    if ((Get-DemoHash (Read-DemoFile (Join-Path $Root 'handoff-index.json') 1048576)) -cne
        $script:DemoIndexHash) { Stop-Demo 'HANDOFF_CHANGED_DURING_READ' }
    return $members
}

function New-DemoPrivateDirectory([string] $Path) {
    Assert-DemoNoLinks ([IO.Path]::GetDirectoryName($Path))
    if (Test-Path -LiteralPath $Path) { Stop-Demo 'DIRECTORY_ALREADY_EXISTS' }
    # The directory is empty until ACL protection is complete. New-Item does not
    # overwrite an existing path; never use -Force here.
    $created = New-Item -ItemType Directory -Path $Path -ErrorAction Stop
    Assert-DemoNoLinks $Path
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($sid)
    foreach ($identity in @($sid, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
            [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity,
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
    $actual = Get-Acl -LiteralPath $Path
    if (!$actual.AreAccessRulesProtected) { Stop-Demo 'PRIVATE_ACL_REQUIRED' }
}

function Write-DemoNewFile([string] $Path, [byte[]] $Bytes, [ref] $Created = ([ref]::new($false))) {
    Assert-DemoNoLinks ([IO.Path]::GetDirectoryName($Path))
    $stream = [IO.FileStream]::new($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    if ($null -ne $Created) { $Created.Value = $true }
    try { $stream.Write($Bytes, 0, $Bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
    if ((Get-DemoHash (Read-DemoFile $Path 8388608)) -cne (Get-DemoHash $Bytes)) {
        Stop-Demo 'WRITE_READBACK_MISMATCH'
    }
}

function Invoke-DemoProcess([string] $Executable, [string[]] $Arguments,
    [byte[]] $InputBytes = [byte[]]::new(0), [int] $Seconds = 10,
    [int] $StdoutLimit = 65536, [int] $StderrLimit = 65536) {
    return [Flower.MaintenanceDemo.DemoProcess]::Run($Executable, $Arguments, $InputBytes,
        $Seconds, $StdoutLimit, $StderrLimit)
}

function Assert-DemoProcessOk($Result, [string] $Code) {
    if ($Result.TimedOut -or $Result.OutputLimitExceeded -or $Result.ExitCode -ne 0) { Stop-Demo $Code }
}

function Assert-DemoLocalDockerEndpoint([string] $Endpoint) {
    # Windows named pipes also have remote-host forms. Accept only the literal
    # local '.' pipe namespace; a npipe:// prefix alone is not a locality check.
    if ($Endpoint -cnotmatch '^npipe:/{2,4}\./pipe/[A-Za-z0-9_.-]{1,128}$') {
        Stop-Demo 'LOCAL_DOCKER_NAMED_PIPE_REQUIRED'
    }
}

function Remove-DemoOwnedContainer([string] $Docker, [string] $Name, [string] $Owner) {
    # Never use docker prune, name prefixes, or an unverified container ID.
    $inspection = Invoke-DemoProcess $Docker @('container', 'inspect', '--format',
        ('{{.Id}}|{{.Name}}|{{index .Config.Labels "' + $script:DemoLabel + '"}}'), $Name)
    if ($inspection.ExitCode -ne 0 -and !$inspection.TimedOut -and !$inspection.OutputLimitExceeded) {
        # Absence is confirmed by a separate exact-name inventory, not by assuming
        # every inspect failure means the container disappeared.
        $inventory = Invoke-DemoProcess $Docker @('container', 'ls', '--all', '--filter',
            ('name=^/' + $Name + '$'), '--format', '{{.Names}}')
        Assert-DemoProcessOk $inventory 'CONTAINER_CLEANUP_UNCONFIRMED'
        if ($script:DemoUtf8.GetString($inventory.Stdout).Trim().Length -eq 0) { return }
        Stop-Demo 'CONTAINER_CLEANUP_UNCONFIRMED'
    }
    Assert-DemoProcessOk $inspection 'CONTAINER_CLEANUP_UNCONFIRMED'
    $parts = $script:DemoUtf8.GetString($inspection.Stdout).Trim() -split '\|'
    if ($parts.Count -ne 3 -or $parts[0] -cnotmatch '^[a-f0-9]{64}$' -or
        $parts[1] -cne '/' + $Name -or $parts[2] -cne $Owner) { Stop-Demo 'CONTAINER_OWNERSHIP_MISMATCH' }
    $removed = Invoke-DemoProcess $Docker @('container', 'rm', '--force', $parts[0])
    Assert-DemoProcessOk $removed 'CONTAINER_CLEANUP_UNCONFIRMED'
    $remaining = Invoke-DemoProcess $Docker @('container', 'ls', '--all', '--filter',
        ('id=' + $parts[0]), '--format', '{{.ID}}')
    Assert-DemoProcessOk $remaining 'CONTAINER_CLEANUP_UNCONFIRMED'
    if ($script:DemoUtf8.GetString($remaining.Stdout).Trim().Length -ne 0) { Stop-Demo 'CONTAINER_CLEANUP_UNCONFIRMED' }
}

function Remove-DemoOwnedStage([string] $Stage, [string] $TempRoot, [string] $Owner) {
    $full = [IO.Path]::GetFullPath($Stage)
    if (!(Test-DemoWithin $full $TempRoot) -or [IO.Path]::GetDirectoryName($full) -ine $TempRoot -or
        [IO.Path]::GetFileName($full) -cne 'flower-maintenance-demo-' + $Owner) {
        Stop-Demo 'STAGING_CLEANUP_SCOPE'
    }
    Assert-DemoNoLinks $full
    $marker = Read-DemoFile (Join-Path $full '.demo-owner') 64
    if ($script:DemoUtf8.GetString($marker) -cne $Owner) { Stop-Demo 'STAGING_CLEANUP_OWNER' }
    $null = Get-DemoFiles $full 32
    # Explicit resolved, unique owned temp directory only. Material source/output
    # directories and the handoff are never deletion targets.
    Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop
}

function Invoke-MaintenanceDemoMain {
    [CmdletBinding()]
    param([string] $InputPath, [string] $OutputDirectory, [string] $HandoffPath,
        [string] $MavenRepository, [int] $TimeoutSeconds = 60, [switch] $Help)
    if ($Help) {
        Write-Host @'
Maintenance Investigation Pack — released-snapshot demo (Windows)

  pwsh -NoProfile -File .\run-demo.ps1
  pwsh -NoProfile -File .\run-demo.ps1 -InputPath C:\demo\incident.json
  pwsh -NoProfile -File .\run-demo.ps1 -InputPath C:\demo\incident.json -OutputDirectory C:\demo\new-result

Options:
  -InputPath         UTF-8 incident JSON, <= 256 KiB; default: bundled example.
  -OutputDirectory   NEW directory only; default: .demo-runs/<time>-<random>.
  -HandoffPath       Copy of the exact sealed 2026-09-08 repair RA001 handoff.
  -MavenRepository   Existing Maven cache containing the pinned Jackson jars.
  -TimeoutSeconds   Compile + run limit, 5..180 seconds (default 60).
  -Help             This help; no Docker calls, files, or downloads.

Requires PowerShell 7.2+, a running LOCAL Docker Linux engine, the pinned Maven
image and Jackson 2.21.4/annotations 2.21 cache. Nothing is installed or pulled.
No API key, Factory Host, DB, or model is used. Original release files remain
unchanged. Successful runs create result.json, report.md and run-info.json.
run-info.json is written LAST and records smoke-run evidence, NOT certification.
Input/results can contain incident information: use sanitized data only.
Exit codes: 0 success, 2 invalid JSON, 3 domain validation, 4 product execution,
10 prerequisite/integrity/timeout/output/cleanup failure. No success files on
input/domain failure. Do not treat a partial output directory as success.
'@
        return 0
    }

    $stage = $null
    $tempRoot = $null
    $owner = [Guid]::NewGuid().ToString('N')
    $containerName = 'flower-maintenance-demo-' + $owner
    $containerAttempted = $false
    $docker = $null
    $exitCode = 10
    $successOutput = $null
    $ownedOutput = $null
    $completionOwned = $false
    $containerCleanupConfirmed = $true
    try {
        if (!$IsWindows) { Stop-Demo 'WINDOWS_REQUIRED_FOR_PRIVATE_ACL' }
        if ($TimeoutSeconds -lt 5 -or $TimeoutSeconds -gt 180) { Stop-Demo 'TIMEOUT_RANGE' }
        if (!('Flower.MaintenanceDemo.DemoProcess' -as [type])) {
            Add-Type -Path (Join-Path $script:DemoToolRoot 'DemoProcess.cs')
        }
        if (!$InputPath) { $InputPath = Join-Path $script:DemoToolRoot 'examples/incident.json' }
        if (!$HandoffPath) { $HandoffPath = Join-Path $script:DemoRepoRoot 'demo-handoff-20260908-repair-ra001' }
        if (!$MavenRepository) { $MavenRepository = Join-Path $env:USERPROFILE '.m2/repository' }
        $inputFile = Get-DemoSafePath $InputPath
        $handoff = Get-DemoSafePath $HandoffPath
        $repository = Get-DemoSafePath $MavenRepository
        $usingDefaultOutput = !$OutputDirectory
        $defaultOutputRoot = Join-Path $script:DemoRepoRoot '.demo-runs'
        if ($usingDefaultOutput) {
            $OutputDirectory = Join-Path $defaultOutputRoot ((Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' + $owner)
        }
        $output = Get-DemoSafePath $OutputDirectory
        if (Test-Path -LiteralPath $output) { Stop-Demo 'OUTPUT_DIRECTORY_ALREADY_EXISTS' }
        if ((Test-DemoWithin $output $handoff) -or (Test-DemoWithin $output $script:DemoToolRoot) -or
            (Test-DemoWithin $output $repository) -or (Test-DemoWithin $inputFile $output) -or
            $output -ieq $script:DemoRepoRoot -or
            (Test-DemoWithin $output (Join-Path $script:DemoRepoRoot 'tools')) -or
            $output -match '(^|[\\/])demo-handoff[^\\/]*([\\/]|$)') {
            Stop-Demo 'PROTECTED_OUTPUT_PATH'
        }
        $outputParent = [IO.Path]::GetDirectoryName($output)
        if (!(Test-Path -LiteralPath $outputParent -PathType Container) -and
            !($usingDefaultOutput -and $outputParent -ieq $defaultOutputRoot)) {
            Stop-Demo 'OUTPUT_PARENT_MUST_EXIST'
        }
        try { $inputBytes = Read-DemoFile $inputFile 262144 } catch {
            $cause = $_.Exception
            while ($cause.InnerException) { $cause = $cause.InnerException }
            if ($cause.Message -ceq 'FILE_SIZE_LIMIT') { Stop-Demo 'INPUT_TOO_LARGE' }
            throw
        }
        try { $null = ConvertFrom-DemoJson $inputBytes 16 } catch { Stop-Demo 'INVALID_JSON' }
        $members = Read-DemoHandoff $handoff
        $cliPath = Get-DemoSafePath (Join-Path $script:DemoToolRoot 'MaintenanceDemoCli.java')
        $cliBytes = Read-DemoFile $cliPath 131072
        $launcherBytes = Read-DemoFile $script:DemoLauncherPath 262144
        $helperBytes = Read-DemoFile (Join-Path $script:DemoToolRoot 'DemoProcess.cs') 131072
        $dependencies = @(
            @{ path = 'com/fasterxml/jackson/core/jackson-databind/2.21.4/jackson-databind-2.21.4.jar'; hash = '3888e9e69ab66fbacaacc9aea0e9ffbf15368288e4aca468b024dba11c09fbf9' },
            @{ path = 'com/fasterxml/jackson/core/jackson-core/2.21.4/jackson-core-2.21.4.jar'; hash = '4b40a06396f239f8de2da57419adde6e94e5edc18a2171d471ea05eeed4e5c2d' },
            @{ path = 'com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar'; hash = '53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b' }
        )
        $dependencyBytes = [Collections.Generic.Dictionary[string, byte[]]]::new([StringComparer]::Ordinal)
        foreach ($dependency in $dependencies) {
            $path = Get-DemoSafePath (Join-Path $repository $dependency.path)
            if (!(Test-Path -LiteralPath $path -PathType Leaf)) { Stop-Demo 'PINNED_JACKSON_JAR_MISSING' }
            $bytes = Read-DemoFile $path 8388608
            if ((Get-DemoHash $bytes) -cne $dependency.hash) { Stop-Demo 'PINNED_JACKSON_JAR_HASH_MISMATCH' }
            $dependencyBytes.Add([IO.Path]::GetFileName($path), $bytes)
        }
        $dockerCommand = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if (!$dockerCommand) { Stop-Demo 'DOCKER_NOT_INSTALLED' }
        $docker = $dockerCommand.Source
        if ($env:DOCKER_HOST) { Assert-DemoLocalDockerEndpoint $env:DOCKER_HOST }
        $context = Invoke-DemoProcess $docker @('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}')
        Assert-DemoProcessOk $context 'DOCKER_CONTEXT_UNAVAILABLE'
        Assert-DemoLocalDockerEndpoint $script:DemoUtf8.GetString($context.Stdout).Trim()
        $info = Invoke-DemoProcess $docker @('info', '--format', '{{.OSType}}')
        Assert-DemoProcessOk $info 'DOCKER_ENGINE_NOT_RUNNING'
        if ($script:DemoUtf8.GetString($info.Stdout).Trim() -cne 'linux') { Stop-Demo 'DOCKER_LINUX_ENGINE_REQUIRED' }
        $image = Invoke-DemoProcess $docker @('image', 'inspect', '--format', '{{.Os}}', $script:DemoImage)
        Assert-DemoProcessOk $image 'PINNED_DOCKER_IMAGE_MISSING'
        if ($script:DemoUtf8.GetString($image.Stdout).Trim() -cne 'linux') { Stop-Demo 'DOCKER_LINUX_IMAGE_REQUIRED' }

        $tempRoot = Get-DemoSafePath ([IO.Path]::GetTempPath().TrimEnd('\', '/'))
        $stage = Join-Path $tempRoot ('flower-maintenance-demo-' + $owner)
        New-DemoPrivateDirectory $stage
        Write-DemoNewFile (Join-Path $stage '.demo-owner') $script:DemoUtf8.GetBytes($owner)
        New-DemoPrivateDirectory (Join-Path $stage 'lib')
        $javaPrefix = 'product-source/src/main/java/io/github/flowerjvm/pack/maintenance/'
        Write-DemoNewFile (Join-Path $stage 'IncidentInvestigator.java') $members[$javaPrefix + 'IncidentInvestigator.java']
        Write-DemoNewFile (Join-Path $stage 'InvestigationAcceptanceApi.java') $members[$javaPrefix + 'InvestigationAcceptanceApi.java']
        Write-DemoNewFile (Join-Path $stage 'MaintenanceDemoCli.java') $cliBytes
        foreach ($entry in $dependencyBytes.GetEnumerator()) {
            Write-DemoNewFile (Join-Path (Join-Path $stage 'lib') $entry.Key) $entry.Value
        }
        # Constant shell command. No input paths, JSON values or user arguments are
        # interpolated. Candidate pom.xml/tests never run; only explicit javac inputs.
        $command = 'mkdir /tmp/classes && javac -J-Xmx128m -proc:none -encoding UTF-8 --release 21 -cp "/input/lib/*" -d /tmp/classes /input/IncidentInvestigator.java /input/InvestigationAcceptanceApi.java /input/MaintenanceDemoCli.java && exec java -Xmx128m -Duser.home=/tmp -Djava.io.tmpdir=/tmp -cp "/tmp/classes:/input/lib/*" MaintenanceDemoCli'
        $arguments = @('run', '--name', $containerName, '--label', ($script:DemoLabel + '=' + $owner),
            '--pull', 'never', '--network', 'none', '--read-only', '--user', '65534:65534',
            '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--memory', '512m',
            '--cpus', '1', '--pids-limit', '128', '--log-driver', 'none',
            '--entrypoint', 'timeout',
            '--tmpfs', '/tmp:rw,nosuid,nodev,size=128m,mode=1777',
            '--mount', ('type=bind,source=' + $stage + ',target=/input,readonly'),
            '--interactive', $script:DemoImage, '--signal=KILL', ($TimeoutSeconds.ToString() + 's'),
            'sh', '-c', $command)
        Write-Host 'Verified released snapshot. Running the offline demo (no API key / DB / Factory Host).'
        $startedAt = [DateTimeOffset]::UtcNow
        $containerAttempted = $true
        $containerCleanupConfirmed = $false
        $run = Invoke-DemoProcess $docker $arguments $inputBytes $TimeoutSeconds 2097152 65536
        if ($run.TimedOut) { Stop-Demo 'EXECUTION_TIMEOUT' }
        if ($run.OutputLimitExceeded) { Stop-Demo 'EXECUTION_OUTPUT_LIMIT' }
        # Cleanup must be confirmed before recording a successful run.
        Remove-DemoOwnedContainer $docker $containerName $owner
        $containerAttempted = $false
        $containerCleanupConfirmed = $true
        if ($run.ExitCode -ne 0) {
            if ($run.ExitCode -in 2, 3, 4) {
                Stop-Demo ('PRODUCT_EXIT_' + $run.ExitCode)
            }
            if ($run.ExitCode -in 124, 137) { Stop-Demo 'EXECUTION_TIMEOUT' }
            Stop-Demo 'COMPILE_OR_CONTAINER_FAILED'
        }
        $result = ConvertFrom-DemoJson $run.Stdout
        if (!$result.ContainsKey('reportMarkdown') -or $result.reportMarkdown -isnot [string]) {
            Stop-Demo 'PRODUCT_OUTPUT_CONTRACT'
        }
        $reportBytes = $script:DemoUtf8.GetBytes($result.reportMarkdown)
        if ($reportBytes.Length -gt 1048576) { Stop-Demo 'REPORT_SIZE_LIMIT' }
        if ($usingDefaultOutput -and !(Test-Path -LiteralPath $defaultOutputRoot)) {
            New-DemoPrivateDirectory $defaultOutputRoot
        }
        # A failed input/product creates no output directory. A later I/O failure
        # can leave a partial NEW directory, but never overwrites previous runs.
        New-DemoPrivateDirectory $output
        $ownedOutput = $output
        Write-DemoNewFile (Join-Path $output 'result.json') $run.Stdout
        Write-DemoNewFile (Join-Path $output 'report.md') $reportBytes
        $runInfo = [ordered]@{
            schemaVersion = 'factory.maintenance-demo-run.v1'
            status = 'SMOKE_RUN_PASSED'
            scope = 'historical-released-snapshot-consumer-smoke-not-certification-or-deployment'
            liveCertificationStatusChecked = $false
            runId = $owner
            startedAt = $startedAt.ToString('o')
            completedAt = [DateTimeOffset]::UtcNow.ToString('o')
            handoffIndexSha256 = $script:DemoIndexHash
            sourceTreeSha256 = $script:DemoSourceHash
            image = $script:DemoImage
            inputSha256 = Get-DemoHash $inputBytes
            inputSizeBytes = $inputBytes.Length
            cliSha256 = Get-DemoHash $cliBytes
            launcherSha256 = Get-DemoHash $launcherBytes
            processHelperSha256 = Get-DemoHash $helperBytes
            dependencies = @($dependencies | ForEach-Object { [ordered]@{ path = $_.path; sha256 = $_.hash } })
            files = @(
                [ordered]@{ path = 'result.json'; sizeBytes = $run.Stdout.Length; sha256 = Get-DemoHash $run.Stdout },
                [ordered]@{ path = 'report.md'; sizeBytes = $reportBytes.Length; sha256 = Get-DemoHash $reportBytes }
            )
            containerRemovalConfirmed = $true
        }
        # Verify the payload set before writing the sole completion marker LAST.
        if ((Get-DemoFiles $output 4).Count -ne 2) { Stop-Demo 'OUTPUT_UNEXPECTED_FILES' }
        $infoBytes = $script:DemoUtf8.GetBytes(($runInfo | ConvertTo-Json -Depth 16))
        Write-DemoNewFile (Join-Path $output 'run-info.json') $infoBytes ([ref] $completionOwned)
        if ((Get-DemoFiles $output 4).Count -ne 3) { Stop-Demo 'OUTPUT_UNEXPECTED_FILES' }
        foreach ($entry in $runInfo.files) {
            $actual = Read-DemoFile (Join-Path $output $entry.path) 2097152
            if ($actual.Length -ne $entry.sizeBytes -or (Get-DemoHash $actual) -cne $entry.sha256) {
                Stop-Demo 'OUTPUT_FINAL_READBACK_MISMATCH'
            }
        }
        $successOutput = $output
        $exitCode = 0
    } catch {
        # Do not print exception stacks, raw stderr, input JSON, or private paths.
        $code = $_.Exception.Message
        if ($code -cnotmatch '^[A-Z][A-Z0-9_]{2,80}$') { $code = 'LAUNCHER_VALIDATION_OR_IO_FAILURE' }
        Write-Host ('DEMO_FAILED: ' + $code)
        switch ($code) {
            { $_ -in 'INVALID_JSON', 'INPUT_TOO_LARGE', 'PRODUCT_EXIT_2' } {
                Write-Host 'Supply one valid UTF-8 JSON object (optional BOM), at most 256 KiB and depth 16. No success artifacts were created.'
            }
            'PRODUCT_EXIT_3' {
                Write-Host 'The incident did not satisfy the product input contract. Check the documented fields and examples; no success artifacts were created.'
            }
            'PRODUCT_EXIT_4' {
                Write-Host 'The product could not complete this input; no success artifacts were created. Raw input/stack traces are not logged.'
            }
            'DOCKER_NOT_INSTALLED' {
                Write-Host 'Install/start Docker Desktop with its Linux engine, then retry. This script does not install software.'
            }
            { $_ -in 'DOCKER_ENGINE_NOT_RUNNING', 'DOCKER_CONTEXT_UNAVAILABLE' } {
                Write-Host 'Start Docker Desktop and wait for the Linux engine, then retry. If Desktop itself cannot start, recover that environment first; this script does not reset Docker or change its settings.'
            }
            { $_ -in 'DOCKER_LINUX_ENGINE_REQUIRED', 'DOCKER_LINUX_IMAGE_REQUIRED', 'LOCAL_DOCKER_NAMED_PIPE_REQUIRED' } {
                Write-Host 'Select a local Docker Desktop Linux-engine context. Remote engines and Windows containers are not supported.'
            }
            'PINNED_DOCKER_IMAGE_MISSING' {
                Write-Host ('Pre-provision the exact trusted image: ' + $script:DemoImage)
                Write-Host 'This offline launcher never pulls an image or substitutes a different tag.'
            }
            { $_ -in 'PINNED_JACKSON_JAR_MISSING', 'PINNED_JACKSON_JAR_HASH_MISMATCH' } {
                Write-Host 'Pre-provision the documented exact Jackson jars/hashes in a Maven cache, or pass -MavenRepository. No download or hash substitution is performed.'
            }
            'OUTPUT_DIRECTORY_ALREADY_EXISTS' {
                Write-Host 'Choose a NEW -OutputDirectory, or omit it for an automatically unique .demo-runs directory.'
            }
        }
        $exitCode = switch ($code) {
            { $_ -in 'INVALID_JSON', 'INPUT_TOO_LARGE', 'PRODUCT_EXIT_2' } { 2; break }
            'PRODUCT_EXIT_3' { 3; break }
            'PRODUCT_EXIT_4' { 4; break }
            default { 10 }
        }
    } finally {
        if ($containerAttempted -and $docker) {
            try {
                Remove-DemoOwnedContainer $docker $containerName $owner
                $containerCleanupConfirmed = $true
            } catch {
                Write-Host ('DEMO_FAILED: CONTAINER_CLEANUP_UNCONFIRMED. Inspect only container ' + $containerName + '.')
                $exitCode = 10
            }
        }
        if ($stage -and (Test-Path -LiteralPath $stage) -and $containerCleanupConfirmed) {
            try { Remove-DemoOwnedStage $stage $tempRoot $owner } catch {
                Write-Host 'DEMO_FAILED: PRIVATE_STAGING_CLEANUP_UNCONFIRMED (preserved; no broad cleanup attempted).'
                $exitCode = 10
            }
        }
        if ($exitCode -ne 0 -and $ownedOutput -and $completionOwned) {
            # Never leave a success marker if the final cleanup failed. The new
            # payload directory is retained for diagnosis, not treated as a run.
            $marker = Join-Path $ownedOutput 'run-info.json'
            try { Assert-DemoNoLinks $marker; Remove-Item -LiteralPath $marker -ErrorAction Stop } catch {
                Write-Host 'DEMO_FAILED: COMPLETION_MARKER_CLEANUP_UNCONFIRMED; this run is NOT successful.'
            }
        }
    }
    if ($exitCode -eq 0) {
        Write-Host 'DEMO_SUCCEEDED: fresh execution of the unchanged released Pack.'
        Write-Host ('Report: ' + (Join-Path $successOutput 'report.md'))
        Write-Host ('JSON:   ' + (Join-Path $successOutput 'result.json'))
        Write-Host ('Run:    ' + (Join-Path $successOutput 'run-info.json'))
    }
    return $exitCode
}

# Dot-sourcing exposes validation helpers for deterministic tests without executing
# a product or contacting Docker. The ordinary script entry always runs the checks.
if ($MyInvocation.InvocationName -ne '.') {
    $code = Invoke-MaintenanceDemoMain @PSBoundParameters
    exit $code
}
