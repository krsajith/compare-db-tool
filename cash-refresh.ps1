# Bounded diagnostic launcher. No refresh HTTP call, no restore, no production datasource.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('preflight','legacy-preflight','inputs','legacy','report')]
    [string]$Step,
    [string]$ValueDate,
    [string]$Cob,
    [string]$PrevCob,
    [string]$Tenant = '227cd5f5-4498-4dba-bda9-05b8edc41113',
    [ValidateSet('sample','full','none')][string]$Export = 'sample',
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$ruleNames = @{
    'preflight' = 'cash-refresh-01-preflight-cfs.xml'
    'legacy-preflight' = 'cash-refresh-01b-legacy-preflight-cfs.xml'
    'inputs' = 'cash-refresh-02-pg-inputs-cfs.xml'
    'legacy' = 'cash-refresh-03-legacy-3way-cfs.xml'
    'report' = 'cash-refresh-04-report-rows-cfs.xml'
}
$ruleArguments = @('--rules', ('rules\' + $ruleNames[$Step]), '--param', ('tenant=' + $Tenant), '--parallel', '1', '--export', $Export)
if ($Step -in @('inputs','legacy','report')) {
    foreach ($dateText in @($ValueDate,$Cob,$PrevCob)) {
        if ([string]::IsNullOrWhiteSpace($dateText)) { throw 'Supply -ValueDate, -Cob and -PrevCob from the incident and preflight. No date defaults are used by this launcher.' }
        $parsedDate = [datetime]::MinValue
        if (-not [datetime]::TryParseExact($dateText, 'yyyy-MM-dd', [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::None, [ref]$parsedDate)) {
            throw 'Dates must be yyyy-MM-dd.'
        }
    }
    if ([datetime]$PrevCob -ge [datetime]$Cob -or [datetime]$Cob -gt [datetime]$ValueDate) {
        throw 'Expected PrevCob < Cob <= ValueDate. Check the incident dates.'
    }
    $ruleArguments += @('--param',('valueDate='+$ValueDate),'--param',('cob='+$Cob),'--param',('prevCob='+$PrevCob))
}
if ($ValidateOnly) { $ruleArguments += '--validate' }
& (Join-Path $PSScriptRoot 'compare.ps1') @ruleArguments
exit $LASTEXITCODE
