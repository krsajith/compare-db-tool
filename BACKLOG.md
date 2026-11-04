# compare-db-tool backlog

Improvements requested while using the tool. Newest first.

## DONE 04-Nov-2026: Findings for stream comparisons (see README, "Findings")

Re-keyed rows, near-miss keys, snapshot timing, concentration by value (who/which/when), constant ratio/offset/sign flip, few rows driving a gap,
one-column differences, empty side. **Not done:** findings for `engine="db"` comparisons; baseline/drift fingerprints; `--catalog` (read-only schema/function listing for building funnels); column profiling. Not wanted: cross-database
checksums. Depersonalisation is parked (depends on the project).

## DONE 04-Nov-2026: Restricted SpEL expressions (computed parameters, `<check>`, `when=`, `<expect expr=>`; see README, "Expressions")

**Not done:** expressions in `when=` on queries/comparisons (skip a query conditionally); a holiday calendar for business days; expressions that read a funnel's per-stage counts.

## DONE 04-Nov-2026: One query's result as input of another (`uses=`, `:alias.col`, `:alias.col[]`, batching; see README)

**Not done:** inputs from a comparison's only-source/only-target keys (drill-down), from a funnel's diverging keys; temp-table based filtering for lists above 100,000.

## DONE 04-Nov-2026: Console messages while running and when done (see README, "What the console tells you")

Found rows per query, comparison counts and first finding, expectation failures, zip size, heartbeat for slow steps, restore step counts, `--quiet`,
console log level WARN by default (files keep everything). **Not done:** a progress percentage inside one long query (the row count is shown instead).

## DONE 04-Nov-2026: `<funnel>` with first-divergence detection (compare and chain modes; see README, "Funnels")

**Not done:** generating funnels from code; profile of the comparison findings by funnel stage.

## DONE 04-Nov-2026: Interactive menu (arrow keys, "/" commands, history, confirmations; see README)

## DONE 04-Nov-2026: Expected results in a rule: the tool says PASS or FAIL (requested 03-Nov-2026)

**Implemented:** `<expect>` for queries and comparisons, verdicts and exit code, small results in `SUMMARY.md`, restore `expect`
and `verify` (see README, "Expected results"). **Not done:** the accepted list of keys ("these 254 rows may differ") - use the exact
count, e.g. `only-target="254"`; per-key lists need a separate design.

Original request:

**Problem.** After a run the user has to send the zip for someone to read the counts and decide whether they are as expected
(example: `rrr-input-values-20261002` after `rrr-restore-20261002`, 21 comparisons, each with its own expected counts).
In the default `sample` export mode the query rows are not even kept, so only the number of rows per query can be read.

**Wanted.** A rule states what it expects; the tool checks it and prints one verdict per check and an overall PASS or FAIL
in `SUMMARY.md` and the exit code. Examples of expectations:

- a query: expected value of a column in the first row (`<expect column="only_in_reference" equals="0"/>`), a range
  (`max="0"`, `between="73000,74500"`) or a row count (`rows="1"`);
- a comparison: `different="0"`, `only-source="0"`, or an accepted list of keys ("these 254 rows may differ: our deliberate correction");
- a restore: after apply, re-run named checks automatically (`<verify rule="rrr-input-values-20261002.xml"/>`).

Each expectation carries a short reason text, printed with its verdict, so the summary explains itself
("7 client names repaired by script 18: expected").

**Also.** Write small query results (counts, one-row summaries) into `SUMMARY.md` even in `sample` mode, so the numbers are
visible without `--export full`.

## Restore performance note (03-Nov-2026, fixed in c1d6b2e)

Key matching with `IS NOT DISTINCT FROM` prevents hash joins in PostgreSQL (nested loops, hours on 300k rows); the restore
uses `=` on non-null keys and refuses null keys.
