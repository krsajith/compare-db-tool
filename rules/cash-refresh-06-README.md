# Two-row CFS cash-fixing check: OCF / OGB

This is a focused follow-up to the screenshots. It queries only CFS candidates with client prefixes OCF/OGB, derivative `FX_FORWARD_NDF`, and mapped product `USDBRL`, at COB 2026-10-01 / value date 2026-10-02. It compares only backup and current PostgreSQL. Each result row shows the candidate's source fields and Java core gates (base trade filter, cash event, current-FY premium, active-lots history). It does not scan/export full trade tables, call the refresh endpoint, or update balances.

Run:

```powershell
cd D:\taomish\compare-db-tool
.\compare.ps1 --rules rules\cash-refresh-06-ocf-ogb-cfs.xml --parallel 1 --export full
```

The XML dates match the screenshots. Edit the two date parameter values if investigating a different refresh. Use tenant `227cd5f5-4498-4dba-bda9-05b8edc41113` only if that is the affected tenant.

Expected result: two rows in each snapshot. If a row exists in backup but not current, the issue is migration/current data or lookup mapping. If it exists in current, inspect the boolean gates: `s1_pass`, `s2_cash_event_pass`, `s4_fy_premium_pass`, and `s3_active_lots_pass`; the first false gate identifies an input-side eligibility cause. Review all fields when there are duplicates or NULLs; SQL three-valued predicates can yield NULL, so a NULL gate is not automatically proof of false.

The previous broad S2 EXCEPT query timed out, so this rule filters by the two known client prefixes, derivative and product before evaluating the gates. The failed S2 run is inconclusive. Even if the focused SQL passes, it only establishes source data and the eligibility rules it encodes. It does not prove legacy MATLAB parity for the final cash amount or fixing-table write. If both rows pass all gates in current data, the next targeted check is the legacy report rows for those same trade IDs versus Java's raw report rows, followed by the respective FX forward calculation and output filters.
