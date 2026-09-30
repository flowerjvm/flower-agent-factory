#Requires -Version 7.2
<#!
.SYNOPSIS
Run the released Maintenance Investigation Pack in a bounded, offline Docker sandbox.
.EXAMPLE
pwsh -NoProfile -File .\run-demo.ps1
.EXAMPLE
pwsh -NoProfile -File .\run-demo.ps1 -InputPath .\incident.json
.NOTES
Requires Windows, PowerShell 7.2+, a running local Docker Linux engine, the pinned
image and three pinned Jackson jars already present. No downloads or API keys.
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

& (Join-Path $PSScriptRoot 'tools/maintenance-demo/Invoke-MaintenanceDemo.ps1') @PSBoundParameters
exit $LASTEXITCODE
