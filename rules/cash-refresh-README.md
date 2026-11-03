# CFS cash-refresh: find the two missing rows

These rules trace the existing data and report output. They never call the refresh endpoint or rebuild balances. Ordinary rules contain SELECT/WITH only. The optional report rule calls the temp-table report function through the tool's non-production rollback mode. No production datasource is used.

## Run in stages

Use JDK 21 and run from `D:\taomish\compare-db-tool`:

```powershell
$env:JAVA_HOME = 'D:\software\jdk-21.0.7'
.\cash-refresh.ps1 -Step preflight
# Optional, before any legacy comparison:
.\cash-refresh.ps1 -Step legacy-preflight
```

Read the exported date/column probes before proceeding. Establish the exact failed request's valuation date, COB/settlement date, and previous settlement date. Do not use today's date automatically. `preflight` needs neither a date nor a MySQL connection. `legacy-preflight` needs MySQL only. Query failures are listed in MANUAL_ACTIONS.md; a missing table is not an empty table.

The following dates are EXAMPLES from the existing migration-comparison fixtures, not confirmed incident dates:

```powershell
.\cash-refresh.ps1 -Step inputs -ValueDate 2026-10-02 -Cob 2026-10-01 -PrevCob 2026-09-30
# If PostgreSQL evidence does not explain the difference:
.\cash-refresh.ps1 -Step legacy -ValueDate 2026-10-02 -Cob 2026-10-01 -PrevCob 2026-09-30
# Optional direct legacy-view versus deployed PG-function evidence:
.\cash-refresh.ps1 -Step report -ValueDate 2026-10-02 -Cob 2026-10-01 -PrevCob 2026-09-30
```

Add `-ValidateOnly` to check XML/configuration without a database connection, or `-Export full` when samples are insufficient. The wrapper rejects absent/malformed dates and enforces PrevCob < Cob <= ValueDate. XML must have valid example date defaults because the current RuleLoader validates defaults before applying overrides: use the wrapper so those examples cannot silently become incident dates.

## Fixed environment assumptions

- CFS only; legacy physical names have `_cfs`. Do not change only the BU filter to reuse for another BU.
- PostgreSQL datasource `postgres-dev`: current schema `opera`; migration schema `opera_backup_04_10`.
- Optional legacy datasource `mysql-oprdb02`, schema `dbsystemuat`.
- Tenant defaults to the supplied request's tenant; override `-Tenant` if the incident differs. PostgreSQL selection is tenant-specific, with exact `business_unit='CFS'` to mirror the service. Preflight also reports case/whitespace variants of the clone's BU.
- These are the conventions in the tool's existing rules, not freshly verified remote inventory. Change static schema/table names in the XML if the preflight shows a different backup or legacy schema. JDBC parameters cannot replace identifiers. Never point these rules at production.
- Compare corresponding snapshots. A backup from early October versus later November trades naturally differs. Legacy today versus an October backup cannot by itself prove migration loss. Use snapshot provenance and per-row evidence.

## What each file establishes

| Rule | Evidence |
|---|---|
| 01 preflight | PG object columns, current/backup valuation dates, previous-date mappings, available balance dates, BU spelling and installed function definition |
| 01b legacy-preflight | Legacy columns, legacy configured dates and helper-view definition |
| 02 pg-inputs | 21 input/output table comparisons, plus S0/S1/S2/S3/S4/client-validity trade membership and row multiplicity for backup versus current |
| 03 legacy-3way | Ten key datasets compared legacy-to-backup, backup-to-current and legacy-to-current (30 comparisons) |
| 04 report-rows | Raw legacy view versus PG function, legacy view versus service-filtered PG rows, and raw per-trade multiplicity |

Rule 02 covers versioned/main and cloned trades; main/cloned security info; FY premium; premium-paid flags; active-lots history; fixing; netting; security settle/actual prices; underlying settle/actual values; futures; client master/threshold; current/previous cash; cash fixing; products; underlying master; financial years.

Stage membership uses the CFS predicates from transaction-core `10bf06a`:

1. S0: tenant/BU clone rows.
2. S1: latest version, RMS subportfolio, excludes OID suffix and JPM counterparty.
3. S2: transaction before valuation date and qualifying premium/expiry/netting event; FX vanilla branch is separate.
4. S3: an active-lots record on/before valuation date exists. Zero lots is retained.
5. S4: financial year covering the requested COB and a matching FY premium; fixing LEFT JOIN preserves multiplication of rows.
6. S8: valid client code including the SIP exception, checked before later enrichment. The S8 label corresponds to service filtering, not an intervening S5-S7 count.

The intermediate stage rule is an **eligibility diagnostic at explicit dates**, not a historical execution of the installed report function. It does not perform S5/S6 FX, S7 settlement-price, multiplier or netting enrichment. Those are inspected through input comparisons and the optional direct report rule. In particular, a zero-result `rms_premium_paid_table` join or SQL NULL predicate must not be guessed to be equivalent to a zero flag.

## Read the results in this order

1. **Dates/availability:** configured current and legacy dates must agree with the incident for rule 04. Inspect both `*-date-guard` exports; anything other than one correctly scoped ALIGNED valuation row invalidates a parity conclusion. Date filtering can otherwise return two empty reports. The PG function uses configured dates internally; passing dates to the rule does not rewind it.
2. **Which row type?** `cash-*` compares final client/currency/COB rows; `cash-fixing-*` compares client/derivative/product/currency groups; report rules compare trade rows. Workbook client tabs require nonzero persisted fixing totals; RawData and client-sheet rows are different populations. The reported difference of two is not assumed to be two missing trades.
3. **Find exact keys:** only-source in legacy-current identifies legacy-only keys; inspect only-target too. A net difference of two can hide larger differences in both directions. Duplicate key files are first-class evidence; resolve them before trusting a per-trade value comparison. Trade-multiplicity comparison retains exact counts even when row comparison excludes duplicate keys.
4. **Locate the first changed stage:** a trade in backup S1 but absent in current S1 points at source/BU/latest-version fields; S2 at security/premium/event dates or netting state; S3 at active lots; S4 at FY/premium. More rows without more distinct trade keys suggests join fanout.
5. **Trace the writer:** clone/FY/paid/lots/fixing are populated by booking and supported bulk import; netting changes deal versions, clone, lots and netting history; fixing changes fixing/clone; pricing engine writes settle/actual values. Consult `D:\oga\knowledge-base\cash-balance-refresh-lineage.md` for source anchors. Main-vs-clone comparisons across snapshots detect drift separately; this is not a repair tool.
6. **Trace final balances:** absent previous closing balance, invalid new client (`client_status` must be `completed`), zero movement groups, differing fixing rates and threshold currency consolidation can change output row counts even when report trades agree. Today's manual values/today-only rows and the empty-report rollover branch also matter.
7. **Attribute carefully:** legacy-only versus backup may be a migration gap only if snapshots correspond. Backup-only versus current may be a later removal or scope change. Matching backup/current with changed legacy may mean legacy evolved later. None alone proves which process ran or when.

All selected input values compare exactly by default, including nulls and case. Small numeric drift is deliberately visible; determine materiality after finding keys. The tool reports duplicates rather than silently DISTINCT-ing them. Bounded sample exports do not sample comparison counts. Do not use `--limit` for final parity: capped queries are partial.

## Execution bounds and limitations

- 60-second timeout per query; wrapper uses one datasource lane to reduce load and date-race exposure. This does not make a multi-query or cross-database run one consistent snapshot. Run against stable snapshots/quiescent data and retain provenance.
- Backup views/functions are not called: a function in a backup schema may still read current-schema tables. Backup eligibility is rebuilt from physical backup tables.
- Report function is invoked separately for raw/API/count evidence, creates temporary tables, and rolls back. It must be trusted and allowed for non-production temp-table mode. Function results can vary between calls if inputs change.
- Legacy table/column compatibility, backup completeness, remote results, and the actual two missing keys remain unverified until execution. Preflight exposes columns/definitions; it does not automatically rewrite incompatible SQL or prove deployed Java version.
- Deeper pricing-model ancestry and an exact replay of Java/MATLAB monetary calculations are not supplied by these rules. Identical source rows with different fixing totals require a calculation-level investigation next.
- Exit 0 = complete equal comparison/export; 1 = differences/duplicates; 2 = query/configuration error or partial. Never interpret exit 2 or a failed query as equality.

## Validation performed

Five files passed the existing packaged tool's offline `--validate` with isolated synthetic datasource definitions; no remote connections or credentials were used. All 26 current-schema rule-02/rule-04 queries passed EXPLAIN (without ANALYZE) on local `localhost/xceler_dev`, schema `opera` (22 input/funnel queries, four report/date queries). Launcher parsing, missing-date rejection and explicit-date offline validation passed. This verifies local SQL/table/column compatibility, not remote data or MySQL syntax. See `D:\oga\analysis\result-cash-refresh-rules.md` for the delivery checkpoint.
