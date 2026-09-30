#Requires -Version 7.2
[CmdletBinding()]
param(
    [string] $MavenRepository = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository'),
    [string] $ComponentSourceDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$runtimeRepo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$runtimeDocker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
$runtimeImage = 'maven@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e'
$runtimeCore = Join-Path $runtimeRepo 'demo-handoff-20260908-repair-ra001/product-source/src/main/java/io/github/flowerjvm/pack/maintenance'
if ($ComponentSourceDirectory) { $runtimeCore = [IO.Path]::GetFullPath($ComponentSourceDirectory) }
$runtimeDependencies = @(
    @{ Path = (Join-Path $MavenRepository 'com/fasterxml/jackson/core/jackson-databind/2.21.4/jackson-databind-2.21.4.jar'); Hash = '3888e9e69ab66fbacaacc9aea0e9ffbf15368288e4aca468b024dba11c09fbf9' },
    @{ Path = (Join-Path $MavenRepository 'com/fasterxml/jackson/core/jackson-core/2.21.4/jackson-core-2.21.4.jar'); Hash = '4b40a06396f239f8de2da57419adde6e94e5edc18a2171d471ea05eeed4e5c2d' },
    @{ Path = (Join-Path $MavenRepository 'com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar'); Hash = '53ca085f4a150f703f49e1aabd935bd03b43e1ea3d55d135438292af22cef56b' }
)
# Exact historical component bytes for module tests; not a substitute for production current-certification resolution.
$runtimeSourceHashes = @{
    'IncidentInvestigator.java' = 'e256a77e59f721419f1ca31151d4503424e7fcffdc58b8039045ac60fe7455a9'
    'InvestigationAcceptanceApi.java' = 'cf193ad9d0d10171a124007bf1da102f8e5483126183da4c3d736226c83c85b4'
}
foreach ($runtimeEntry in $runtimeSourceHashes.GetEnumerator()) {
    if ((Get-FileHash -LiteralPath (Join-Path $runtimeCore $runtimeEntry.Key) -Algorithm SHA256).Hash.ToLowerInvariant() -cne $runtimeEntry.Value) {
        throw 'RUNTIME_TEST_COMPONENT_HASH_MISMATCH'
    }
}
foreach ($runtimeDependency in $runtimeDependencies) {
    if ((Get-FileHash -LiteralPath $runtimeDependency.Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $runtimeDependency.Hash) {
        throw 'RUNTIME_TEST_DEPENDENCY_HASH_MISMATCH'
    }
}
function Invoke-RuntimeTestProcess([string[]] $Arguments, [int] $TimeoutSeconds = 60) {
    $runtimeStart = [Diagnostics.ProcessStartInfo]::new($runtimeDocker)
    $runtimeStart.UseShellExecute = $false
    $runtimeStart.CreateNoWindow = $true
    $runtimeStart.RedirectStandardOutput = $true
    $runtimeStart.RedirectStandardError = $true
    foreach ($runtimeArgument in $Arguments) { $runtimeStart.ArgumentList.Add($runtimeArgument) }
    $runtimeProcess = [Diagnostics.Process]::new()
    $runtimeProcess.StartInfo = $runtimeStart
    try {
        if (!$runtimeProcess.Start()) { throw 'RUNTIME_TEST_PROCESS_START_FAILED' }
        $runtimeOutput = $runtimeProcess.StandardOutput.ReadToEndAsync()
        $runtimeError = $runtimeProcess.StandardError.ReadToEndAsync()
        if (!$runtimeProcess.WaitForExit($TimeoutSeconds * 1000)) {
            $runtimeProcess.Kill($true)
            throw 'RUNTIME_TEST_PROCESS_TIMEOUT'
        }
        $runtimeText = $runtimeOutput.GetAwaiter().GetResult() + $runtimeError.GetAwaiter().GetResult()
        if ($runtimeText.Length -gt 1048576) { throw 'RUNTIME_TEST_OUTPUT_LIMIT' }
        if ($runtimeProcess.ExitCode -ne 0) { throw ('RUNTIME_TEST_PROCESS_FAILED: ' + $runtimeText) }
        return $runtimeText
    } finally { $runtimeProcess.Dispose() }
}
$null = Invoke-RuntimeTestProcess @('image', 'inspect', $runtimeImage, '--format', '{{.Os}}/{{.Architecture}}') 15
$runtimeOwner = [Guid]::NewGuid().ToString('N')
$runtimeName = 'flower-incident-runtime-test-' + $runtimeOwner
$runtimeArguments = @('run', '--rm', '--pull=never', '--name', $runtimeName,
    '--label', ('flower.incident-runtime-test.owner=' + $runtimeOwner), '--network=none',
    '--user', '65534:65534', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
    '--pids-limit=128', '--cpus=2', '--memory=640m', '--memory-swap=640m',
    '--tmpfs', '/work:rw,nosuid,nodev,size=128m,mode=1777',
    '--tmpfs', '/tmp:rw,nosuid,nodev,size=128m,mode=1777',
    '--mount', ('type=bind,src=' + $PSScriptRoot + ',dst=/templates,readonly'),
    '--mount', ('type=bind,src=' + $runtimeCore + ',dst=/core,readonly'))
foreach ($runtimeDependency in $runtimeDependencies) {
    $runtimeArguments += @('--mount', ('type=bind,src=' + $runtimeDependency.Path + ',dst=/deps/' + [IO.Path]::GetFileName($runtimeDependency.Path) + ',readonly'))
}
$runtimeScript = @'
set -eu
for variant in basic history; do
  mkdir -p "/work/$variant"
  javac -J-Xmx256m -proc:none -encoding UTF-8 --release 21 -cp '/deps/*' -d "/work/$variant" /templates/common/*.java /templates/"$variant"/*.java /templates/tests/"$variant"/RuntimeConfiguration.java /templates/tests/IncidentApplicationRuntimeTest.java /core/IncidentInvestigator.java /core/InvestigationAcceptanceApi.java
  java -Xmx256m -cp "/work/$variant:/deps/*" io.github.flowerjvm.product.incident.IncidentApplicationRuntimeTest
done
'@
$runtimeArguments += @('--entrypoint', '/bin/sh', $runtimeImage, '-c', $runtimeScript.Replace("`r`n", "`n"))
try { Invoke-RuntimeTestProcess $runtimeArguments 60 } finally {
    # Exact generated test container only; never affects production hosts/volumes or other containers.
    $runtimeInspect = & $runtimeDocker ps -a --filter ('label=flower.incident-runtime-test.owner=' + $runtimeOwner) --format '{{.Names}}'
    if ($LASTEXITCODE -eq 0 -and @($runtimeInspect) -contains $runtimeName) {
        $null = & $runtimeDocker rm -f $runtimeName
        if ($LASTEXITCODE -ne 0) { Write-Warning 'RUNTIME_TEST_CONTAINER_CLEANUP_FAILED' }
    }
}
