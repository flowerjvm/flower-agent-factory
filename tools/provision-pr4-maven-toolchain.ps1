param(
    [Parameter(Mandatory = $true)]
    [string]$Destination
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$factoryRoot = [System.IO.Path]::GetFullPath((Join-Path $scriptRoot '..'))
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $destinationPath) {
    Write-Host "PR4 curated Maven repository already exists; the verifier will enforce the compiled-in manifest hash: $destinationPath"
    exit 0
}

$parent = Split-Path -Parent $destinationPath
[System.IO.Directory]::CreateDirectory($parent) | Out-Null
$staging = Join-Path $parent ('.pr4-toolchain-staging-' + [guid]::NewGuid().ToString('N'))
[System.IO.Directory]::CreateDirectory($staging) | Out-Null
$download = Join-Path $staging 'maven-download'
$curated = Join-Path $staging 'curated-pom-jar-only'
[System.IO.Directory]::CreateDirectory($download) | Out-Null
[System.IO.Directory]::CreateDirectory($curated) | Out-Null
$probeRoot = Join-Path $factoryRoot 'factory-infrastructure\src\test\resources\factory-verification\pr4\toolchain-probe'
$wrapper = Join-Path $factoryRoot 'mvnw.cmd'
$previousOptions = $env:MAVEN_OPTS
try {
    $env:MAVEN_OPTS = (($previousOptions + ' -Dmaven.repo.local=' + $download).Trim())
    & $wrapper -B -ntp -s (Join-Path $probeRoot 'settings.xml') -f (Join-Path $probeRoot 'pom.xml') `
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:go-offline
    if ($LASTEXITCODE -ne 0) { throw 'Maven Central toolchain provisioning failed' }
    # Surefire 3.5.2 selects its JUnit Platform provider dynamically. Maven's
    # normal graph mediation keeps the candidate's newer Platform commons and
    # otherwise omits this provider-private exact runtime file.
    foreach ($coordinate in @(
        'org.junit.platform:junit-platform-commons:1.9.3:jar',
        'org.junit.platform:junit-platform-launcher:1.12.2:jar'
    )) {
        & $wrapper -B -ntp -s (Join-Path $probeRoot 'settings.xml') -f (Join-Path $probeRoot 'pom.xml') `
            org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get `
            ("-Dartifact=" + $coordinate) '-Dtransitive=false'
        if ($LASTEXITCODE -ne 0) { throw "Pinned Surefire provider closure provisioning failed: $coordinate" }
    }
    Get-ChildItem -LiteralPath $download -Recurse -File | Where-Object {
        $_.Extension -eq '.pom' -or $_.Extension -eq '.jar'
    } | ForEach-Object {
        $source = [System.IO.Path]::GetFullPath($_.FullName)
        if (-not $source.StartsWith($download + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'Maven toolchain source escaped the random staging directory'
        }
        $relative = $source.Substring($download.Length + 1)
        $output = [System.IO.Path]::GetFullPath((Join-Path $curated $relative))
        if (-not $output.StartsWith($curated + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'Maven toolchain destination escaped the curated staging directory'
        }
        [System.IO.Directory]::CreateDirectory((Split-Path -Parent $output)) | Out-Null
        Copy-Item -LiteralPath $source -Destination $output
    }
    try {
        [System.IO.Directory]::Move($curated, $destinationPath)
        Write-Host "Provisioned bounded PR4 Maven toolchain repository: $destinationPath"
    } catch [System.IO.IOException] {
        if (-not (Test-Path -LiteralPath $destinationPath)) { throw }
        # A concurrent exact provisioner won the atomic directory claim. The
        # verifier hashes its full pom/jar manifest before any byte is trusted.
        Write-Host "Concurrent PR4 provisioner won; verifier will validate its compiled-in manifest hash."
    }
} finally {
    $env:MAVEN_OPTS = $previousOptions
    if (Test-Path -LiteralPath $staging) {
        # This target is the random staging child created above, never a caller-provided broad path.
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
}
