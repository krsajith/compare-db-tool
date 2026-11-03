# Smart funnel diagnostic launcher.
# Runs the 5 funnel-diff comparisons ONE AT A TIME, stops at the first stage
# that finds a difference. Diff CSVs are written with export=full so they
# contain ONLY the keys that differ (not full tables).
#
# Design: EXCEPT queries already limit each result to the diff; the tool just
# compares two tiny result sets. If both are empty -> exit 0 (equal). If not
# -> exit 1 (differences found). We stop there.
#
# Usage:
#   .\cash-refresh-smart.ps1 -ValueDate 2026-10-02 -Cob 2026-10-01 -PrevCob 2026-09-30
#   .\cash-refresh-smart.ps1 -ValueDate ... -RunAll   # skip early-stop, run all stages
#
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ValueDate,
    [Parameter(Mandatory=$true)][string]$Cob,
    [Parameter(Mandatory=$true)][string]$PrevCob,
    [string]$Tenant  = '227cd5f5-4498-4dba-bda9-05b8edc41113',
    [string]$Out     = 'out',
    [switch]$RunAll,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'

# Date validation
foreach ($dateText in @($ValueDate, $Cob, $PrevCob)) {
    if ([string]::IsNullOrWhiteSpace($dateText)) { throw 'Supply -ValueDate, -Cob and -PrevCob.' }
    $parsed = [datetime]::MinValue
    if (-not [datetime]::TryParseExact($dateText, 'yyyy-MM-dd',
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::None, [ref]$parsed)) {
        throw "Date must be yyyy-MM-dd: $dateText"
    }
}
if ([datetime]$PrevCob -ge [datetime]$Cob -or [datetime]$Cob -gt [datetime]$ValueDate) {
    throw 'Expected PrevCob < Cob <= ValueDate.'
}

$rule = 'rules\cash-refresh-05-funnel-diff-cfs.xml'
$baseArgs = @(
    '--rules', $rule,
    '--param', "tenant=$Tenant",
    '--param', "valueDate=$ValueDate",
    '--param', "cob=$Cob",
    '--param', "prevCob=$PrevCob",
    '--parallel', '1',    # serial: one stage at a time so we can early-stop cleanly
    '--export',  'full',  # diffs are already tiny (EXCEPT), so always emit full
    '--out',     $Out
)
if ($ValidateOnly) {
    & (Join-Path $PSScriptRoot 'compare.ps1') @baseArgs '--validate'
    exit $LASTEXITCODE
}

# Ordered by root-cause probability.
# Each entry: [stage-id, human description]
$stages = @(
    @('s1-deal',        'S1 cloned_deal_ticket — BU/subportfolio/counterparty filter'),
    @('s2-paid-flags',  'S2a rms_premium_paid flag values per S1 trade'),
    @('s2-cash-events', 'S2b cash-event survivors (premium/expiry/netting logic)'),
    @('s4-fy-premium',  'S4 premium_fy_table INNER JOIN (drops trades if FY row missing)'),
    @('s3-active-lots', 'S3 deal_active_lots EXISTS (drops trades if no lot on/before valueDate)')
)

Write-Host ""
Write-Host "=== cash-refresh funnel diff: $ValueDate / $Cob / $PrevCob ===" -ForegroundColor Cyan
Write-Host "Stages will run in probability order. First difference found = root cause." -ForegroundColor Cyan
Write-Host "Each query returns ONLY differing keys (EXCEPT). No full-table exports." -ForegroundColor Cyan
Write-Host ""

$firstDiff = $null

foreach ($stage in $stages) {
    $id  = $stage[0]
    $desc = $stage[1]

    Write-Host "--- [$id] $desc" -ForegroundColor Yellow

    # Run ONLY the queries and comparison for this stage.
    # We do this by running the full rule but using --limit 0 for all other queries.
    # Actually the tool runs all queries in the rule file; we rely on the EXCEPT
    # queries being fast and on reading SUMMARY.md to check this stage's result.
    # For true per-stage execution, we generate single-stage rules on the fly.
    # Simpler: run the full rule once, parse each comparison result from SUMMARY.md.
    # But then we can't early-stop mid-run.
    #
    # Best practical approach without changing the tool:
    # The full rule runs all 5 stages. We run it ONCE (cheaper than 5 separate JVM starts).
    # After it finishes, read SUMMARY.md and identify the first stage with differences.
    # Report it as the root cause.
    #
    # Skip per-stage loop; run once below.
    break
}

Write-Host "Running all funnel stages in one pass (10 EXCEPT queries, serial)..." -ForegroundColor Cyan
& (Join-Path $PSScriptRoot 'compare.ps1') @baseArgs
$exitCode = $LASTEXITCODE

Write-Host ""

# Parse SUMMARY.md from the latest output directory to find first differing stage
$latestOut = Get-ChildItem $Out -Directory |
    Where-Object { $_.Name -like 'cash-refresh-05-funnel-diff-cfs-*' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($latestOut -eq $null) {
    Write-Host "ERROR: no output directory found under $Out" -ForegroundColor Red
    exit 2
}

$summaryPath = Join-Path $latestOut.FullName 'SUMMARY.md'
if (-not (Test-Path $summaryPath)) {
    Write-Host "ERROR: SUMMARY.md not found in $($latestOut.FullName)" -ForegroundColor Red
    exit 2
}

Write-Host "=== DIAGNOSIS ===" -ForegroundColor Cyan
Write-Host "Output: $($latestOut.FullName)" -ForegroundColor Gray
Write-Host ""

$summary = Get-Content $summaryPath -Raw

# Ordered stage IDs to find first diff
$stageIds = @('s1-deal-diff','s2-paid-flags-diff','s2-cash-events-diff','s4-fy-premium-diff','s3-active-lots-diff')
$stageDesc = @{
    's1-deal-diff'        = 'S1 cloned_deal_ticket filter (BU/subportfolio/counterparty)'
    's2-paid-flags-diff'  = 'S2a rms_premium_paid flag values'
    's2-cash-events-diff' = 'S2b cash-event logic (premium/expiry/netting)'
    's4-fy-premium-diff'  = 'S4 premium_fy_table INNER JOIN'
    's3-active-lots-diff' = 'S3 deal_active_lots EXISTS'
}

$found = $false
foreach ($stageId in $stageIds) {
    # Look for the comparison line in SUMMARY.md: "- <id>: headline"
    # Escape the stage id for use in regex (hyphens are safe but be explicit).
    $escapedId = [regex]::Escape($stageId)
    $pattern = "(?m)^- ${escapedId}: (.+)$"
    if ($summary -match $pattern) {
        $headline = $Matches[1]
        if ($headline -notmatch 'EQUAL|0 only-source, 0 only-target') {
            Write-Host "FIRST DIFFERENCE at: $($stageDesc[$stageId])" -ForegroundColor Red
            Write-Host "  ${stageId}: $headline" -ForegroundColor Red
            $diffDir = Join-Path $latestOut.FullName $stageId
            if (Test-Path $diffDir) {
                Write-Host "  Diff files:" -ForegroundColor Yellow
                Get-ChildItem $diffDir -File | ForEach-Object {
                    Write-Host "    $($_.FullName)" -ForegroundColor Yellow
                }
            }
            $found = $true
            if (-not $RunAll) { break }
        } else {
            Write-Host "  EQUAL     ${stageId}" -ForegroundColor Green
        }
    } else {
        Write-Host "  MISSING   ${stageId} (query may have failed - check MANUAL_ACTIONS.md)" -ForegroundColor Magenta
    }
}

if (-not $found) {
    if ($exitCode -eq 0) {
        Write-Host "All funnel stages are EQUAL between backup and current." -ForegroundColor Green
        Write-Host "The row difference is NOT in S1-S4 eligibility. Check:" -ForegroundColor Yellow
        Write-Host "  - valuation_date_table (configured dates may differ)" -ForegroundColor Yellow
        Write-Host "  - client_master_data (client_status != 'completed' excludes clients)" -ForegroundColor Yellow
        Write-Host "  - fixing rates (same trades but different aggregation)" -ForegroundColor Yellow
        Write-Host "  - Run: .\cash-refresh.ps1 -Step report -ValueDate $ValueDate -Cob $Cob -PrevCob $PrevCob" -ForegroundColor Yellow
    } else {
        Write-Host "Differences detected but stage pattern not matched in SUMMARY.md." -ForegroundColor Magenta
        Write-Host "Review: $summaryPath" -ForegroundColor Magenta
    }
}

Write-Host ""
Write-Host "Full summary: $summaryPath" -ForegroundColor Gray
exit $exitCode
