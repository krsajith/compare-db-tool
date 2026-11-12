# compare-db-tool backlog

Improvements requested while using the tool. Newest first.

## DONE 07-Nov-2026: `--change` (guarded UPDATE / DELETE / free SQL) and the database changelog (`--changelog`) (see README and HOW-TO-USE.md 6.9c)

Requested while fixing OUAT-178 test state by hand: backup, change, undo and "what ran where" were separate manual steps. Typed confirmation (generated longer phrase when risky, or the rule's `confirm`),
changelog table with backup links, backfill of older backup tables, `allow-write` datasource flag. **Not done:** the lock and the rerun guard are tested on H2 only (the PostgreSQL `FOR UPDATE NOWAIT` path needs a manual test with two sessions);
an undo that removes rows a `sql` INSERT added; undo of a change that touched only audit columns (restore compares content columns only); dropping old backup tables (the changelog lists them, the tool never drops).

## DONE 05-Nov-2026: `--load`: copy rows from any database into a table (see README, "Load", and HOW-TO-USE.md 6.9b)

Requested while fixing Combined VAR (XOGA-713): the data was in legacy MySQL but never migrated, and every copy had to be done by hand in DBeaver or psql.
Insert only, preview by default, backup per step, undo rule, `expect` counts, `verify` rules, duplicate-key and scope checks. **Not done:** upsert/update mode;
loading from a comparison's only-source rows; a datasource flag that must be set before a datasource may be written (today the database account is the guard, as for `--restore`).

## DONE 04-Nov-2026: Findings for stream comparisons (see README, "Findings")

Re-keyed rows, near-miss keys, snapshot timing, concentration by value (who/which/when), constant ratio/offset/sign flip, few rows driving a gap,
one-column differences, empty side. **Not done:** findings for `engine="db"` comparisons; baseline/drift fingerprints; `--catalog` (read-only schema/function listing for building funnels); column profiling. Not wanted: cross-database
checksums. Depersonalisation is parked (depends on the project).

## DONE 04-Nov-2026: `<suggest>`: tell me when a restore rule is the answer (see README, "Restore advice")

**Not done:** generating a restore rule from a comparison (needs table names, keys and scope that the SQL does not give); restoring directly from another database (MySQL to PostgreSQL).

## DONE 12-Nov-2026: `<explain>`: EXPLAIN (ANALYZE, BUFFERS) of one statement in a read-only, rolled back transaction, or plan only (`analyze="false"`); `max-ms`, `no-seq-scan`, `max-estimate-error` expectations; plan.txt, plan.json, SUMMARY.md (see README and HOW-TO-USE 6.5b)

## DONE 04-Nov-2026: `--check`: EXPLAIN and column description of every query against the live databases (see README)

**Not done:** checking `engine="db"` types; resolving the values of chained queries for a more exact check.

## DONE 04-Nov-2026: Presence matrix (`<presence>`: which of N sources has each key; see README)

**Not done:** value differences between the sources for keys they share (use `<compare>` for the pair); a graphical matrix.

## DONE 04-Nov-2026: Restricted SpEL expressions (computed parameters, `<check>`, `when=`, `<expect expr=>`; see README, "Expressions")

**Done later:** `when=`/`after=` on queries with skipping, result variables (`#query1.first.first_name`), list/map/text functions, `rows()`/`values()`. **Not done:** `when=` on comparisons and funnels; a holiday calendar for business days; expressions that read a funnel's per-stage counts.

## DONE 04-Nov-2026: One query's result as input of another (`uses=`, `:alias.col`, `:alias.col[]`, batching; see README)

**Not done:** inputs from a funnel's diverging keys; temp-table based filtering for lists above 100,000. (A comparison's unmatched and differing rows are done: `uses="m=cmp:only-source"`.)

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
