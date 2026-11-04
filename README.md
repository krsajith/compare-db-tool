# compare-db-tool

Java 21 / Spring Boot CLI for XML-driven SQL execution, CSV export, key-based comparison and ZIP packaging.
Each `<sql db="...">` references an independently configured JDBC datasource. Queries can use the same database,
two instances of the same database type, or different database types. MySQL and PostgreSQL drivers are included.

The Spring Boot version is 4.1.2. Java source/bytecode targets 21; the launcher uses your JAVA_HOME or PATH and can
run on a newer JDK, including Java 27. The runtime Java and Spring Boot versions are recorded in the log at startup.

## What the console tells you

While a rule runs the tool prints short, plain messages; the log files in `logs/` keep the full detail and the stack traces.

```
compare-db-tool: trade-check - 2 queries on mysql-oprdb02, postgres-dev, 1 comparison; export sample
  output: out\trade-check-20261104-101500-...
  > legacy-trades on mysql-oprdb02 ...
  ... legacy-trades on mysql-oprdb02 still running (2 min 0 s, 340,000 rows so far)
  OK  found 9,251 rows in legacy-trades (mysql-oprdb02) in 2 min 31 s
  OK  found 9,248 rows in new-trades (postgres-dev) in 4.2 s
  compare trades: 9,236 matched (9,000 equal, 236 differ), 15 only in legacy-trades, 12 only in new-trades
    finding: 12 of 12 missing rows exist on the other side under a different key ...
  expectations: FAIL (1 of 3 expectations not met)
    FAIL trades: different expected 0, got 236 (parity after the restore)
  zip: trade-check.zip (84.2 KB)
compare-db-tool: done in 2 min 36 s - exit 1 (differences, or an expectation was not met), expectations FAIL
  summary: out\trade-check-...\SUMMARY.md
```

- A step that takes longer than 30 seconds reports every 30 seconds ("still running", with the rows fetched so far). Change the interval with `-Dcompare.progress.seconds=N` (0 = off).
- A failed query shows its cause on one line (`ConnectException: Connection refused`, the SQL state, a timeout); the details are in `MANUAL_ACTIONS.md`.
- Restore steps print their counts (`245,109 rows in live.table, 314,822 in the reference; replaced 0, inserted 314,822 ...`), then PREVIEW or APPLIED, and the verify verdicts.
- `--quiet` turns these messages off (warnings and errors still show). The console otherwise shows warnings and errors only; to see every log line as before, set
  `logging.console.level=INFO` (or `DEBUG`) in `config/logging.properties`, or pass it on the command line with two leading dashes.

## Checking a rule against the databases before the long run

`--validate` checks the rule without connecting. `--check` goes one step further: it asks each database about every query and then checks the rule against the answers. **Nothing is executed and no data is read.**

```powershell
.\compare.ps1 --rules rules\trade-check.xml --check
```

```
  OK    legacy-trades (mysql-oprdb02): 5 columns
  OK    new-trades (postgres-dev): 5 columns, the planner expects about 9,300 rows
  FAIL  lookup (postgres-dev): relation "opera.tradez" does not exist
  FAIL  compare trades: key column(s) [trade_id] are not in both queries. legacy-trades: [ID, AMOUNT]; new-trades: [TRADE_ID, AMOUNT]
  FAIL  funnel deals, stage s2: query l2 has no key column(s) [deal_id]; its columns: [id, a]
```

- **Per query:** the database prepares the statement and describes its columns, and runs `EXPLAIN` (never `EXPLAIN ANALYZE`). A missing table or column, a syntax error or a type mismatch comes back with the database's own message, exactly as it would at run time.
  On PostgreSQL the planner's row estimate is shown, with a warning above 5 million rows (estimates can be off).
- **Against the real columns:** key columns of comparisons (column mappings honoured), funnels and presence matrices; the column in every `:alias.column` / `:alias.column[]` (including a comparison's `:only-source`, `:only-target`, `:different`, which have the
  source or target query's columns); and columns named in `<expect column="...">`.
- **Reachability:** an unreachable datasource is reported once (the wait is 10 seconds, `-Dcompare.check.connect.ms=N` changes it) and its other queries are skipped; a production datasource still needs `--allow-prod`.
- **Not checked:** temp-table queries (they are checked when they run), `engine="db"` types, and the values that queries get from other queries (a placeholder stands in for them, so a query that uses another query's values can show a WARN when the database needs the type).
- Exit code 2 if anything would fail, 0 otherwise. `CHECK.md` in the output folder has the same list, problems first. In the menu: "Switch to: check against the databases".

## Interactive menu

`.\compare.ps1` with no arguments (or `--menu`) opens a menu. Everything you type on the command line still works unchanged.

- **Main screen:** your last runs (`again: rule (run)`, with age, exit code, verdict and parameters), pinned favourites, then the
  commands. Type to filter; type `/` to list the commands: `/rules`, `/restore`, `/open`, `/history`, `/datasources`, `/help`, `/quit`.
- **Keys:** Up/Down (also Ctrl-P/Ctrl-N), PgUp/PgDn, Home/End, Enter selects, Esc goes back (quits on the main screen),
  Ctrl-U clears the filter.
- **Rule screen:** shows the rule's first comment, its datasources (`PRODUCTION` and `NOT CONFIGURED` marked), query/comparison/expectation
  counts and parameters. Options: parameters (each checked against its type; the last values you used are offered), export mode, row limit,
  parallelism, validate only, pin. The equivalent command line is printed before anything runs.
- **Confirmations:** a production datasource needs its name typed (`--allow-prod` is then added for that run only); an unconfigured
  datasource blocks the run; a row limit asks because the result will be PARTIAL; parameter values that differ from the rule's
  declared ones are listed and confirmed. Restore and template rules start as a preview; **Apply** is offered after a clean
  preview and needs `APPLY` typed. A run taken from the history never re-applies: an earlier APPLY comes back as a preview.
- **Result screen:** exit code, verdict (the `<expect>` PASS/FAIL when the rule has one), output folder and the start of
  `SUMMARY.md` / `RESTORE.md`; then run again, change options, apply, or show the whole summary.
- **State:** `out\.history.json` holds recent runs, favourites and parameter values (200 runs at most; no passwords). A damaged file is
  moved to `.history.json.bad` and a new one started.
- **Without a terminal** (pipe, scheduled task) a bare `compare.ps1` prints the help as before; `--menu` then uses numbered prompts.
- Every `SUMMARY.md` and `RESTORE.md` names the tool build (`Tool: compare-db-tool ... built ...`), so a stale copy of the jar shows up in the
  zip you send.

## Smart exports and mail bundles

The new default is **sample**, changing output size only. Every fetched row is still compared; matched, equal,
different, missing-row, duplicate and classification counts are not sampled. Existing XML rules remain valid.

| Mode | Published query output | Comparison output |
|---|---|---|
| `sample` (default) | SQL + row-count/timing/column metadata; no raw query CSV | At most 200 data records per CSV; exact totals in SUMMARY |
| `none` | Row-count/timing metadata | Counts, breakdowns and summaries; no comparison CSVs |
| `full` | Complete query CSV + SQL + metadata | All missing/duplicate records and all changed cells, including accepted changes |

Use `--export none|sample|full` and `--sample-rows N` (default 200). Per-rule `export="..."` on `<sql>` or `<compare>`
overrides the CLI default. Accepted/tolerated changes remain counted by column/class in every mode, but individual
accepted cells are exported only in full mode. Sample limits are data records, excluding the CSV header. Each summary
reports the true total, for example `showing 200 of 9251`. Zero sample rows produces header-only CSVs.

```powershell
.\compare.ps1 --rules rules\grasa-backup-cfs.xml
.\compare.ps1 --rules rules\grasa-backup-cfs.xml --export none
.\compare.ps1 --rules rules\grasa-backup-cfs.xml --export full
.\compare.ps1 --rules rules\grasa-backup-cfs.xml --sample-rows 50
```

Or run `.\compare.ps1` with no arguments and pick the rule and options from the menu (see "Interactive menu").

Comparison inputs are streamed to private temporary CSVs under the run's `.work/` directory, including in none/sample
mode. These are not published or zipped and are removed when the run finishes. This bounds comparison memory without
dropping source data. `none` still includes run parameters/rules and failure diagnostics needed to interpret counts.
Failed-query SQL remains available for manual follow-up regardless of export mode.

ZIP selection continues to use `<include ref="..."/>`, but only the chosen export artifacts are added. The run
summary/rules/parameters/manual-actions and comparison summaries are included along with the capped samples.
The CLI prints each ZIP's byte size. Under 1 MB is the target for the normal GRASA backup mail bundle; actual size
depends on row width, rules and configured samples and cannot be measured here without the remote data.

### Breakdown tables

Add several breakdowns to a comparison without changing its matching key:

```xml
<compare id="security-check" source="legacy-sec" target="now-sec">
    <key columns="security_id"/>
    <breakdown columns="product_code,contract_month" top="15"/>
    <breakdown columns="product_code" top="10"/>
</compare>
```

Tables group only-source rows, only-target rows and matched rows with unaccepted changes. Different rows use source
values and count once even when several cells differ. Tables show the top N groups plus `other`; their totals cover
all groups. Missing breakdown columns are explicitly reported and grouped as `(missing column)`, not fatal errors.
Column mappings are honored. Counts are accumulated during the existing comparison pass, without rereading result
rows. Breakdown tables appear before classification detail in each comparison's 8 KB summary. Extremely large summaries
are explicitly truncated; reduce the number of breakdowns/top values when needed.

### Findings: what the data shows about the differences

Every stream comparison also writes `<comparison>/FINDINGS.md` and `findings.json`, and the run gets one `FINDINGS.md` plus the top lines in
`SUMMARY.md`. They are written in every export mode, `none` included, are small, and are included in a ZIP with the comparison. They come from the same
single pass as the comparison, with bounded memory (examples are capped, counts are exact), and they never change a result or an exit code.
Most important first:

| Finding | What it says |
|---|---|
| `rekeyed` | N of the missing rows exist on the other side with identical values in every compared column but another key (re-keyed, re-numbered): probably not missing |
| `near-miss-key` | N unmatched rows have a key that matches after ignoring case, punctuation and leading zeros: a key normalisation would match them |
| `timing-*` | the unmatched rows are all newer (or older) than everything on the other side, on an audit/date column: one side is an older snapshot, or has no history before a point |
| `concentration` | most rows of a category share one value of a column (a user, a product, a date, a batch), compared with how common that value is overall |
| `constant-ratio`, `constant-offset`, `sign-flip` | all differing numbers differ by the same factor, the same amount, or only in sign |
| `few-rows-drive-gap`, `numeric-gap` | the net and absolute gap, and the rows that carry it |
| `one-column` | every differing cell is in one column |
| `empty-side` | one query returned no rows: check its filter and parameters before reading anything else |

A finding says what the data shows, not what caused it. Re-key and near-miss detection look at the first 50,000 unmatched rows per side
(the finding says so when it applies); a column with more than 200 distinct values is not used for concentration; timestamp columns are
found by JDBC type or a `_timestamp`/`_at`/`_date`/`_time` name. `engine="db"` comparisons keep their SQL-side counts and samples and
produce no findings.

## Using one query's result in another

A query can take values from an earlier query, even on another database, for example the keys that are missing in one system looked up in another. The values are
always bound parameters, never pasted into the SQL. The consuming `<sql>` declares what it uses; ids with a hyphen need an alias (`alias=query-id`), because a hyphen
ends a parameter name in SQL.

```xml
<sql id="valuation-date" db="postgres-dev">SELECT value_date FROM opera.valuation_date_table WHERE business_unit = :bu</sql>
<sql id="missing-keys"   db="mysql-oprdb02">SELECT trade_id FROM legacy_schema.trades WHERE ...</sql>
<sql id="lookup" db="postgres-dev" uses="vd=valuation-date, mk=missing-keys">
    SELECT trade_id, status FROM opera.trades
    WHERE value_date = :vd.value_date AND trade_id IN (:mk.trade_id[])
</sql>
```

- `:alias.column` is one value: the earlier query must return exactly one row, otherwise the run says so (none, or more than one and "use `[]` for a list").
- `:alias.column[]` is a list: all rows of that column, without NULLs and duplicates, for `IN (...)`.
- Types come from the earlier query's JDBC types (numbers, dates, timestamps, booleans, text); binary columns are refused.
- **Order:** the query waits for the ones it uses and still runs in parallel with everything else. If a query it uses failed, was blocked or is PARTIAL, it is not run and
  `MANUAL_ACTIONS.md` says why. `--validate` checks aliases, unknown ids, unused aliases and circles without connecting.
- **Long lists run in batches:** `listBatch` (default 1000, at most 30000) values per statement; the rows of all batches are joined into one result, in batch order, and the
  progress line and the query's `.md` say how many values and batches. A query that combines rows (`GROUP BY`, `DISTINCT`, an aggregate, `LIMIT`/`OFFSET`, `UNION` ...) is
  never split, because the answer would be wrong; above `listBatch` it fails with a message saying so. At most one list may exceed `listBatch`, and no list may have more than
  100,000 values.
- **Empty list:** `IN (:mk.trade_id[])` with no values matches nothing and the query returns no rows, which is the normal answer to "are there any missing?". `NOT IN` with an
  empty list cannot be expressed safely and is refused.
- **Drill-down from a comparison:** `uses="m=cmp:only-source"` (also `:only-target` and `:different`) takes the **complete** unmatched or differing rows of a stream comparison, not the capped
  sample in `only-source.csv`; `:only-source` and `:different` have the source query's columns, `:only-target` the target's. The query waits for the comparison, then runs with
  `IN (:m.trade_id[])` (batched like any list), so "why are these 12 missing?" is answered in the same run, for example by looking the keys up in a backup schema or in the other system.
  With nothing unmatched the follow-up returns no rows. If the comparison fails, the query is not run and `MANUAL_ACTIONS.md` says so. An `engine="db"` comparison keeps only counts and samples
  and cannot be used this way. The complete rows are written only to the run's temporary work folder, and only for comparisons that something uses; they are removed when the run ends.
- Queries with the same SQL but different `uses` are not merged. An input of an `engine="db"` comparison cannot use or feed another query. The scalar values used are shown in
  the progress line and the metadata; lists show only counts.

## Expressions: computed parameters, checks, conditions and reconciliation

A small, restricted expression language (Spring SpEL) is available in four places. `rules/expressions.example.xml` shows them together.

```xml
<parameters>
    <param name="cob" type="date" value="2026-10-01"/>
    <param name="prevCob"   type="date" expr="#prevBusinessDay(#cob)"/>      <!-- computed from the parameters above it -->
    <param name="valueDate" type="date" expr="#nextBusinessDay(#cob)"/>
</parameters>
<check expr="#prevCob &lt; #cob and #cob &lt;= #valueDate" message="Expected PrevCob &lt; Cob &lt;= ValueDate."/>
<expect expr="#q('legacy').rows - #q('new').rows == #c('trades').onlySource - #c('trades').onlyTarget" judges="trades" reason="row counts reconcile"/>
<expect ref="trades" different="0" when="#c('trades').onlySource &lt; 100"/>
```

- **Computed parameter** (`expr=` instead of `value=`): evaluated in order, so it can use the parameters declared before it; the result is converted to the declared `type`.
  A `--param name=value` replaces it (and the menu does not prompt for it).
- **`<check expr="..." message="..."/>`**: must be true, evaluated right after the parameters and before anything connects, also with `--validate` (with your `--param` overrides).
  A failing check stops the run and prints the message and the parameter values used. This replaces date sanity checks that lived in launcher scripts.
- **`<expect expr="...">`**: after the run, must be true. It can read results: `#q('query-id')` has `rows`, `partial`, `columns` and `first` (the first row, by lower-case column name);
  `#c('comparison-id')` has `matched`, `equal`, `different`, `onlySource`, `onlyTarget`, `duplicates`, `differentCells`; `#f('funnel-id')` has `diverging` and `firstDivergence`.
  A failing verdict shows what the expression read, for example `false [q(legacy): rows=9251; q(new): rows=9248; c(trades): onlySource=15 ...]`.
  `judges="trades,other"` says the expression accounts for those comparisons or funnels, so their differences are not also reported as differences.
- **`when="..."`** on any `<expect>`: the check applies only if this is true. A `when` that cannot be evaluated is a failed check, not a silent skip.
- **Reading results by id:** a query, comparison or funnel whose id is a plain identifier can be written as a variable: `#query1.first.first_name == 'sajith'`, `#query1.rows > 0`,
  `#trades.onlySource == 0`. For ids with a hyphen use `#q('legacy-trades')`, `#c('trades')`, `#f('deals')`. Column names are lower case. A parameter with the same name wins. A result that failed
  or was skipped does not turn into a silent null or false: reading it fails with the reason.
- **Conditions on text, lists and maps** (methods cannot be called on values, so these are functions): `size(x)`, `isEmpty(x)`, `contains(x, v)` (a list holds the value, a map has the key,
  a text contains the text; numbers compare by value, so `1` equals `1.0`), `startsWith`, `endsWith`, `equalsIgnoreCase`, `trim`, `isBlank`, `upper`, `lower`, `sum(list)`, `distinct(list)`.
  `rows('id')` is every row of a query as a map by column name (at most 20,000 rows) and `values('id', 'column')` is one column as a list (at most 100,000). With SpEL selection and projection:
  `#size(#rows('people').?[age > 25 and first_name == 'anna']) == 1`, `#contains(#rows('people').![first_name], 'anna')`, `#sum(#values('orders', 'amount')) == 35.75`,
  `#isEmpty(#rows('exceptions'))`.
- **Run a query only if a condition holds:** `<sql id="detail" after="trades" when="#trades.onlySource &gt; 0 and #trades.onlySource &lt; 500">`. `after="id1, id2"` makes the query wait for those queries or
  comparisons without using their rows (with `uses=` it already waits); `when` may read only what the query waits for, so it can never depend on something that has not run. When the condition is false the query is
  **skipped, which is not a failure**: nothing runs, the exit code is unaffected, and `SUMMARY.md` lists it under "Skipped" with the condition. What depends on a skipped query (a query using it, a comparison or funnel
  with it as input, a drill-down from that comparison) is skipped too; an `<expect ref="...">` on something skipped is listed as "not evaluated" rather than passed or failed, and an expression that reads a skipped result
  fails with "was skipped". A `when` that cannot be evaluated (wrong column, not true/false) blocks the query as a failure, never as a silent skip.
- **Functions** (call as `#name(...)`): `plusDays, minusDays, plusMonths, minusMonths, startOfMonth, endOfMonth, nextBusinessDay, prevBusinessDay, addBusinessDays, isBusinessDay,
  daysBetween, dayOfWeek, date('2026-10-02'), today, abs, min, max, round, upper, lower`. Dates are `LocalDate` and compare with `<`, `==`; business days are Monday to Friday
  (no holiday calendar). Operators: `+ - * / % == != < <= > >= and or not`, `? :`, `?:`, `[index]`, `.property`.
- **What is refused**, when the rule is checked and before anything runs: `T(...)` type references, `new`, `@bean`, calling a method on a value (`x.plusDays(1)`; use the functions),
  assignment, `matches` and `instanceof`, any unknown `#variable` or function (a typo is an error, not a silent null), an expression longer than 500 characters, and `q`/`c`/`f` outside `<expect>`.
  Expressions run in Spring's read-only data-binding context, so they cannot reach files, the network or the JVM. They cannot build SQL either: values still reach queries only as bound parameters.

## Presence matrix: which source has each key

"Is this key in legacy, in the backup, in current?" A `<presence>` answers it for two to thirty queries in one run, instead of one comparison per pair.

```xml
<sql id="legacy"  db="mysql-oprdb02">SELECT trade_id, client_code FROM ...</sql>
<sql id="backup"  db="postgres-dev">SELECT trade_id, client_code FROM ...</sql>
<sql id="current" db="postgres-dev">SELECT trade_id, client_code FROM ...</sql>
<presence id="trade-presence" key="trade_id" sources="legacy, backup, current"/>
<expect ref="trade-presence" mismatched="0" in-all=">=9000"/>
```

`trade-presence/SUMMARY.md` (illustration):

```
13 of 103 keys are not in every source; the biggest group: in `legacy` and `backup`, not in `current` (6 keys).
`legacy` lacks 3; `backup` lacks 7; `current` lacks 10.

| legacy | backup | current | Keys | Share | Examples        |
| yes    | yes    | yes     | 90   | 87.4% |                 |
| yes    | yes    | -       | 6    | 5.8%  | `91`, `92`, `93` |
| yes    | -      | -       | 4    | 3.9%  | `97`, `98`, `99` |
| -      | -      | yes     | 3    | 2.9%  | `101`, `102`, `103` |
```

- Counts are exact. Keys match by value (an `INTEGER` key equals a `DECIMAL(10,2)` one); a source that has the same key twice counts it once and says "duplicate or empty keys".
- `presence-keys.csv` lists the **mismatching** keys with one yes/blank column per source and a pattern label; keys that are in every source are counted, not listed. In `sample` mode the list is capped per combination
  (counts stay exact), `full` lists all, `none` writes no file.
- **What the mismatching keys have in common:** for the three biggest mismatching combinations (at least three keys each) the extra columns of the query that has those keys are profiled against all its rows, for example
  "6 of 6 keys in this group (100%) have client = `X`, against 6.0% of all keys". Add the columns you want looked at to the query's SELECT.
- **Expectations:** `mismatched="0"` and `in-all=">=9000"` (same count forms as other `<expect>`). In expressions: `#id.keys`, `.inAll`, `.mismatched`, `.missingIn['source']`, `.distinct['source']`, `.rows['source']`, or `#p('id')`.
  A presence with keys missing somewhere is a difference (exit 1) unless an `<expect>` or `judges=` accounts for it.
- It shows up in the run `SUMMARY.md`, in `FINDINGS.md` and can be put in a ZIP with `<include ref="trade-presence"/>`. A presence whose source query was skipped is skipped; one whose source failed is listed in `MANUAL_ACTIONS.md`.
- Values are not compared (that is what `<compare>` does for two sources); use a comparison for the pair you care about once the presence has told you where the keys differ. See `rules/presence.example.xml`.

## Funnels: the first stage where two sides stop agreeing

A result is usually produced in ordered steps, each keeping only some rows. A `<funnel>` finds the step where the two sides start to differ.
Each stage is a pair of ordinary `<sql>` queries that return the **keys that survive that stage**; the tool does the rest.

```xml
<funnel id="deals" key="trade_id">
    <stage id="s1-deal"     source="legacy-s1" target="new-s1" reason="latest version, RMS sub-portfolio"/>
    <stage id="s2-eligible" source="legacy-s2" target="new-s2" reason="FY premium and active lots"/>
    <stage id="s3-master"   source="legacy-s3" target="new-s3" reason="in the security master"/>
</funnel>
<expect ref="deals" first-divergence="none" reason="same deals at every stage"/>
```

- For every key the tool records which stages it survived on each side. The **first stage where the two sides treat a key differently** is where
  that key diverges. A key is blamed on that one stage, not again on every later one (the "Only source / Only target" columns show the state at each stage
  and include keys that diverged earlier; "Diverges here" counts each key once).
- Output per funnel: `SUMMARY.md` (stage table and the verdict, for example "First divergence at `s2-eligible`: 31 keys agree in every earlier stage, then are
  kept only by the source", with the stage's `reason` and example keys) and `diverging-keys.csv` (key, first stage, which side kept it, stages each side kept
  it through). In `sample` mode the CSV holds a few keys per stage and side (counts stay exact), `full` lists all, `none` writes no CSV.
  The funnel also appears in `FINDINGS.md` and the run `SUMMARY.md`, and can be put in a ZIP with `<include ref="deals"/>`.
- **Chain mode** (`mode="chain"`, stages with `source` only): one side, and the stage where each key drops out ("37 of 40 keys break at s6"). It reports; it is never
  a difference. `group="outcome"` (one of the key columns) gives each value of that column its own reaching/dropped columns side by side, for example FAIL and OK pairs.
  `rules/grasa-funnel-cfs-v2.xml` is the GRASA funnel in this form.
- **Expectations:** `first-divergence="none"` or a stage id, and `diverging="N"` (same count forms as other `<expect>`; for a chain it counts keys dropping out).
  A comparison funnel with no `<expect>` that diverges is a difference (exit 1), like a comparison.
- **Rules of use:** stage queries must return the key columns (matched by name, any case; numeric keys compare by value) and may return more, which is ignored.
  Up to 30 stages. A stage query that failed, is PARTIAL or is an `engine="db"` input skips the funnel and is listed in `MANUAL_ACTIONS.md`.
  Matching uses the same disk partitions and memory budget as comparisons (`--partitions`, `--max-partition-mb`).
  A stage need not be a subset of the one before: the first differing stage is reported either way.
- **What the diverging keys have in common:** the stage queries may return more than the key (client, product, status, a date ...). For the first-divergence
  stage and the biggest other stages (at most three, each with at least three keys) the tool reads those columns for the diverging keys only and
  reports what stands out, for example "29 of 31 keys that diverge here (94%) have product = `CM CT`, against 12.0% of all keys of that query". A value is
  reported when it covers at least half of the keys and is at least twice as common as in the whole query. In a chain the keys are looked up in the rows of the
  stage before the one they dropped out at. With a single key column that column is an identifier and is left out; with a composite key its columns are profiled too.
  If the stage query returns only the key, the summary says so. The profile reads at most the first 100,000 keys per stage; it is in `SUMMARY.md` and `FINDINGS.md`.
- See `rules/funnel.example.xml`.

## Build commands

From PowerShell:

```powershell
cd D:\taomish\compare-db-tool
$env:JAVA_HOME = 'D:\software\jdk-21.0.7'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd clean package
.\compare.ps1 --help
.\compare.ps1 --datasources
.\compare.ps1 --rules rules\example.xml --validate
```

`clean package` runs the tests and builds `target\compare-db-tool.jar`. Tests use only isolated in-memory H2 databases.
They never use the remote datasource definitions. To run tests separately: `.\mvnw.cmd test`.

Smart output changes are verified with the isolated H2 test suite and a JDK 21 `clean package` build.
Remote connectivity, remote JDBC streaming and real query results remain unverified.

## External datasource file

The launcher uses `JAVA_HOME\bin\java.exe` if JAVA_HOME is set, otherwise `java.exe` from PATH. Set JAVA_HOME to the
installed JDK 21 directory on each machine; the launcher does not replace it with a developer-specific path.

The application imports `config/datasources.properties` and `config/logging.properties`, relative to its working directory. The PowerShell wrapper
uses the project directory. The actual local file is gitignored, as is `out/`.
`config/datasources.example.properties` is a portable example.

Your local file contains these names from the supplied screenshots:

| Name | Account / purpose | Password environment variable |
|---|---|---|
| `mysql-oprdb01` | `opera_migration_reader`, server OPRDB01 | `MYSQL_OPRDB01_PASSWORD` |
| `mysql-oprdb02` | `opera_it`, server OPRDB02 | `MYSQL_OPRDB02_PASSWORD` |
| `postgres-dev` | DEV migration account | `PG_DEV_PASSWORD` |
| `postgres-uat` | UAT migration account | `PG_UAT_PASSWORD` |
| `postgres-prod` | PROD migration account, explicitly marked production | `PG_PROD_PASSWORD` |

Set the required variables in your local terminal, using the actual credentials, before executing queries.
Passwords masked in screenshots must be supplied separately. The DEV definition uses `opera_migration_loader`
from the text screenshot; the other DEV screenshot uses `opera_aws_it`, which is a different account.
The OPRDB01 port defaults to 3306 because its screenshot did not specify a port.
MySQL screenshots did not select a database, so use qualified table names or add a database to their JDBC URLs.

Add any number of entries using the same structure:

```properties
compare.datasources.reporting.url=jdbc:postgresql://localhost:5432/reporting
compare.datasources.reporting.username=reader
compare.datasources.reporting.password=${REPORTING_PASSWORD}
compare.datasources.reporting.driver-class-name=org.postgresql.Driver
compare.datasources.reporting.read-only=true
compare.datasources.reporting.prod=false
```

Then reference `db="reporting"` in XML. Other JDBC database types require their driver dependency in `pom.xml`
and its driver class in this file. No MySQL/PostgreSQL-specific comparison logic is required.
Driver support for read-only mode, fetch size and query timeout varies by database.

For an external file in another location, use an absolute path:

```powershell
.\compare.ps1 --datasources --spring.config.additional-location=file:D:/private/datasources.properties
```

## XML rules

The direct children of `<rules>` are `<parameters>`, `<sql>`, `<compare>`, and `<compress>`.
SQL queries run in bounded datasource lanes; identical SQL/datasource pairs share one execution. Comparisons start
when both input queries finish. ZIP packaging follows all queries/comparisons. `--parallel 1` serializes queries in
first-occurrence declaration order.
Rule IDs are unique across all rule types, case-insensitively, and references use the exact declared spelling.
IDs must be safe filenames; reserved output names such as `SUMMARY` and Windows device names are rejected.

```xml
<rules name="trade-check">
    <parameters>
        <param name="businessUnit" type="string" value="CFS"/>
        <param name="reportDate" type="date" value="2026-10-01"/>
    </parameters>

    <sql id="old-trades" db="mysql-oprdb02" timeout="120" fetchSize="1000">
        <![CDATA[
            SELECT trade_id, amount, trade_date
            FROM legacy_schema.trades
            WHERE business_unit = :businessUnit AND trade_date = :reportDate
        ]]>
    </sql>
    <sql id="new-trades" db="postgres-dev" timeout="120" fetchSize="1000">
        <![CDATA[
            SELECT trade_id, amount, trade_date
            FROM opera.trades
            WHERE business_unit = :businessUnit AND trade_date = :reportDate
        ]]>
    </sql>

    <compare id="trades" source="old-trades" target="new-trades">
        <key columns="trade_id"/>
        <column name="amount" absTol="0.01"/>
        <column name="trade_date" dateOnly="true"/>
    </compare>

    <compress id="bundle" file="trade-check.zip">
        <include ref="old-trades"/>
        <include ref="new-trades"/>
        <include ref="trades"/>
    </compress>
</rules>
```

The table/column names in `rules/example.xml` are placeholders: edit them to your actual queries.
`rules/connection-check.xml` uses only `SELECT 1 AS check_value` on MySQL OPRDB02 and PostgreSQL DEV.
Running that rule does open real connections; `--validate` does not.

Parameters are bound by Spring JDBC, never substituted into SQL strings. Supported types:
`string`, `integer` (64-bit), `decimal`, `date` (`yyyy-MM-dd`), `timestamp` (`yyyy-MM-dd HH:mm:ss[.fraction]`),
`boolean`, and `null`. Default values must be valid for their declared types.

```powershell
# Validate without connecting:
.\compare.ps1 --rules rules\connection-check.xml --validate

# Actually execute the connection-check queries:
.\compare.ps1 --rules rules\connection-check.xml

# Run your edited comparison:
.\compare.ps1 --rules rules\example.xml --param businessUnit=CFS --param reportDate=2026-10-02

# Optional bounded run (PARTIAL when additional rows exist):
.\compare.ps1 --rules rules\example.xml --limit 10000
```

`timeout` is in seconds (default 120), `fetchSize` defaults to 1000, and `maxRows` may cap a single SQL rule.
`--limit` applies an additional global cap; the smaller positive cap wins. Zero means no cap.
There is no web server, scheduler, or automatic background query execution.

## Comparison rules

Rows are matched by `<key columns="trade_id"/>`; composite keys use commas, for example
`<key columns="client_id,product,trade_date"/>`. Column names match case-insensitively; their values do not unless configured.
Row order is irrelevant. All common columns are compared, with `<column>` elements overriding the defaults.
Unmapped columns found on only one side are reported as schema differences and cause exit code 1.
SQL outputs must have unique, nonempty column labels: use aliases for joined columns with the same name.

Available column attributes:

| Attribute | Behavior |
|---|---|
| `name` | Logical column name |
| `source`, `target` | Physical aliases when the two queries use different names |
| `ignore="true"` | Exclude this column; key columns cannot be ignored |
| `trim="true"` | Ignore leading/trailing whitespace |
| `caseInsensitive="true"` | Ignore case using locale-independent comparison |
| `nullAsEmpty="true"` | Treat SQL NULL as empty text |
| `nullAsZero="true"` | Treat SQL NULL as zero (takes precedence over nullAsEmpty) |
| `numeric="true"` | Compare text values as decimal numbers when parseable |
| `round="2"` | Round decimal values to 2 places, HALF_UP |
| `absTol="0.01"` | Accept absolute numeric differences at or below 0.01 |
| `relTol="0.001"` | Accept differences at or below 0.001 × max(abs(source), abs(target)) |
| `dateOnly="true"` | Compare recognized ISO dates/timestamps by their local calendar date |

Columns reported by either JDBC driver as numeric use decimal comparison automatically. Text identifiers retain leading
zeroes when both sides are text; if one side is numeric, numeric canonicalization applies to both sides.
No conversion through `double` is used for decimal comparisons. JDBC floating-point results retain the precision the
source driver provides. Binary values are exported as `base64:...`.
By default timestamps retain their full exported text. `dateOnly` explicitly drops time/offset precision; it does not
convert between timezones. Use SQL aliases/casts to harmonize booleans, timestamp conventions and vendor-specific types.

Example of mapped key and ignored column:

```xml
<compare id="mapped" source="old-trades" target="new-trades">
    <key columns="trade_id"/>
    <column name="trade_id" source="legacy_id" target="trade_id"/>
    <column name="amount" absTol="0.01"/>
    <column name="generated_at" ignore="true"/>
</compare>
```

Normalization also applies to keys. Tolerances do not fuzzily match keys; keys must match after deterministic
normalization. Duplicate keys, including duplicates introduced by normalization, are reported separately. Such keys
are ambiguous and excluded from matched/only-side counts; they never count as equal. Use full export to retain all original rows.

Cell changes are classified as `CASE_ONLY`, `WHITESPACE_ONLY`, `NULL_VS_EMPTY`, `NULL_VS_ZERO`, `FORMAT_ONLY`,
`ROUNDING`, `SIGN_FLIP`, `DATE_SHIFT`, or `OTHER`. Accepted normalization/tolerance changes are retained in
`differences.csv` with `accepted=true`; only `accepted=false` cells fail comparison. Automatic scale-ratio inference,
funnel/probe diagnostics and PDF/Excel input comparison are not included in this phase.

## Expected results: PASS or FAIL

A rule can state what it expects. The tool checks it and prints one verdict per check, plus an overall PASS or FAIL
at the top of `SUMMARY.md` (and in `EXPECTATIONS.md`, which every ZIP includes). Failing checks are listed first.
The checks use the exact counts and values of the finished run, in every export mode (`none`, `sample`, `full`).
`<expect>` elements are direct children of `<rules>`; `ref` names the query or comparison.

```xml
<!-- a query -->
<expect ref="inputs-summary" rows="1"/>
<expect ref="inputs-summary" column="only_in_reference" equals="0" reason="7 client names repaired by script 18: expected"/>
<expect ref="inputs-summary" column="row_count" between="73000,74500"/>
<expect ref="inputs-summary" column="differences" max="0"/>
<expect ref="by-date" column="row_count" each="true" min="1"/>     <!-- every row -->
<expect ref="by-date" column="value_date" row="2" equals="2026-10-02"/>   <!-- row 1 by default -->

<!-- a comparison -->
<expect ref="trades" different="0" only-source="0" only-target="0" reason="parity after the restore"/>
<expect ref="qc" only-target="254" reason="our deliberate correction: 254 rows may differ"/>
```

| Applies to | Attributes |
|---|---|
| Query | `rows`; `column` with `equals`, `min`, `max`, `between="lo,hi"`, `isNull`; `row` (1-based, default 1) or `each="true"` |
| Comparison | `matched`, `equal`, `different`, `only-source`, `only-target`, `duplicates` |
| Both | `reason`: short text printed with the verdict |

Counts accept `N`, `<=N`, `>=N`, `<N`, `>N` or `A..B`. Numbers compare as decimals (`2.50` equals `2.5`); other
values compare as text. Several attributes in one element are separate checks. `--validate` rejects unknown ids,
mixed query/comparison attributes, bad numbers and a `column` without a condition, without connecting.

Rules of the verdict:

- A comparison that has at least one `<expect>` is judged by it, so expected differences no longer give exit code 1.
  A comparison without one still counts as DIFFERENCES.
- A column that exists on one side only fails the comparison's checks (a `schema` verdict), whatever the counts say.
- A query or comparison that failed, or is PARTIAL, fails its checks. Exit code 2 still wins.
- The rows of an input used only by an `engine="db"` comparison are not kept: use `rows=` or check in SQL.
- Exit codes: 0 equal or all expectations met; 1 differences or an expectation not met; 2 error or PARTIAL.
- Lists of accepted keys are not supported: state the exact count instead (`only-target="254"`).

`SUMMARY.md` also prints queries with at most 5 rows and 12 columns under "Small query results" (about 3 KB at most),
so counts and one-row summaries are readable in `sample` and `none` mode without `--export full`.

### Restore: expected counts and verify

In a `--restore` JSON rule:

```json
{ "id": "cfs-prices-1002", "table": "...", "from": "...", "where": "...", "key": ["..."],
  "expect": { "targetBefore": "245109", "reference": "314822", "inserted": "314822", "removed": "245109", "targetAfter": "314822" } }
...
"verify": [ { "rule": "cash-refresh-12-verify-restore-cfs.xml", "params": { "valueDate": "2026-10-02" } } ]
```

- `expect` (keys `targetBefore`, `reference`, `replaced`, `inserted`, `extra`, `removed`, `targetAfter`): a preview marks unmet
  counts FAIL in `RESTORE.md` (exit 1); `--apply` refuses to commit when one is unmet, and everything is rolled back.
- `verify`: after `--apply` has committed, each named read-only rule (path relative to the restore file) is run
  and its `<expect>` verdict is written to `RESTORE.md`. The rule must contain at least one `<expect>`: `--validate`
  rejects one that cannot say PASS or FAIL. Each verify run has its own output folder. Preview does not run it, because
  its changes are rolled back. The data is already committed when verify fails: `RESTORE.md` then points at the undo rule.
  Exit code: 0 all pass, 1 a verify failed, 2 a verify rule could not run.

## Outputs and ZIP selection

Each execution creates a unique folder under `out/`:

```text
out/trade-check-<timestamp>-<unique>/
    RULES.xml
    PARAMETERS.csv
    SUMMARY.md
    old-trades.csv
    old-trades.sql
    old-trades.md
    new-trades.csv
    new-trades.sql
    new-trades.md
    trades/
        SUMMARY.md
        differences.csv
        only-source.csv
        only-target.csv
        duplicates.csv
    trade-check.zip
```

CSV files use UTF-8 and include a header. Non-null fields are quoted, including empty strings. Unquoted `\N` means
SQL NULL; quoted `"\N"` is literal text. This distinguishes NULL, empty text, literal markers, and embedded newlines.
Use Commons CSV with the documented format for lossless re-reading; spreadsheet viewers may interpret NULL markers
as text. Query SQL templates and supplied parameter values are saved alongside results for review by Claude/Codex.
Keep passwords/tokens in datasource configuration, not in XML or SQL literals; XML and SQL are deliberately included
in outputs. The application does not export datasource configuration.

Each `<include ref="query-id"/>` adds that query's CSV, SQL and metadata. Each comparison reference adds all its
comparison files. Every ZIP also includes the run summary, rule XML and parameter CSV. Duplicate references are
deduplicated. Multiple `<compress>` rules can create different ZIPs; ZIPs cannot reference other ZIP rules.
Without `<compare>`, the tool just exports; without `<compress>`, it leaves the files unzipped.
Summaries are capped at 8 KB each; detailed CSVs are complete. Failed runs get `ERROR.md`; incomplete CSVs from
failed queries must not be used as evidence of equality. Capped runs produce artifacts marked PARTIAL and exit 2.

## Larger datasets

### Parallel queries and reuse

`--parallel N` bounds query workers; the default is the number of distinct named datasources in the rule file.
There is at most one active query per datasource even if N is larger. `--parallel 1` provides sequential query
execution. Comparisons run serially on the coordinator as soon as their inputs are available while other datasource
queries continue, avoiding multiple large comparison maps in memory. Failures remain isolated and produce manual actions.

Exactly identical SQL text on the same datasource is executed once per run (shared parameters are fixed for the run).
All original IDs retain their own outputs and metadata. Mixed export settings and different row caps are honored:
the shared query fetches enough rows for the largest requested cap, or all rows when any alias is uncapped, then
individual capped aliases remain PARTIAL. A reused group uses the strictest statement timeout and smallest fetch
size from its members. Metadata identifies shared execution/time rather than reporting another database call.
Reuse also means volatile expressions are evaluated once for the group; use intentionally distinct SQL when separate
evaluations are required. This tool does not establish a shared snapshot between databases.

```powershell
.\compare.ps1 --rules rules\grasa-backup-cfs.xml --parallel 2
.\compare.ps1 --rules rules\grasa-backup-cfs.xml --parallel 1 --export full
```

### Comparison memory budget

`--max-partition-mb N` defaults to 256 MB. Conservative accounting covers both current partition maps and retained
breakdown groups; a clear error stops that comparison when the estimate exceeds the budget. No data or counts are
silently discarded. Increase `--partitions` (up to 256) to reduce partition sizes; increase the memory budget only
when the JVM has enough heap. High-cardinality breakdown counters persist across partitions, so reducing the number
of distinct breakdown groups may be necessary. Top-N output uses a bounded heap rather than sorting/copying all groups.
This is an estimate for retained comparison structures, not a hard cap on the entire JVM: drivers, file buffers,
temporary per-row objects and unusually large individual fields also consume memory. Source rows stay on disk and
are never collected into a full in-memory result list.

```powershell
java -Xmx2g -jar target\compare-db-tool.jar --rules rules\grasa-backup-cfs.xml --partitions 128 --max-partition-mb 256
```

Query results are written incrementally. PostgreSQL uses non-autocommit transactions and fetch size; MySQL pools
enable cursor fetching/server prepared statements. Other JDBC drivers may implement fetching differently.
Comparisons hash-partition exported rows to temporary CSVs and compare one partition at a time, then remove the
temporary files. Default: 64 partitions; use `--partitions 128` or `256` for larger/wider data. Temporary disk space is
required in the run directory. Very wide rows or uneven partition sizes may still require more heap or partitions.
No throughput or fixed-memory target has been benchmarked.

Queries execute in separate transactions and do not share a synchronized database snapshot. For
repeatable comparisons, use stable source data and the same business cut-off parameters on both sides.

## Read-only comparison operation

### Functions that create temporary tables

On a non-production datasource, `<sql tempTables="true">` opts a single guarded SELECT/WITH into a
separate writable pool (one connection, auto-commit off). Every execution logs a WARN with datasource
and query id and rolls back on success or failure. Production names/flags are refused even with `--allow-prod`.
Ordinary queries continue to use the read-only pool and session settings.

Temp tables are enabled by default for non-production datasources, including dev and UAT. Optional
`compare.datasources.<name>.allow-temp-tables=false` disables them explicitly; set it to `true` to re-enable.
Production remains refused regardless of this property or `--allow-prod`. The datasource `read-only` flag
governs ordinary queries only; temp-table queries use the separate writable pool with either flag value.
The SQL attribute `tempTables="true"` is still required; statements remain single SELECT/WITH and always roll back.
Optional
`compare.datasources.<name>.allowed-functions=opera.fn_*` restricts schema-qualified function calls
(comma-separated names or `*` patterns). When omitted it defaults to `*` (any function).
Validation performs these checks without opening connections. The account can in principle write:
use only trusted, allow-listed functions on non-prod; rollback cannot undo external side effects.

Startup and `--datasources` show the absolute paths of datasource files loaded by Spring (including
imports/additional locations) and each datasource's effective read-only, prod, allow-temp-tables and
allowed-functions settings. Connection URLs, usernames and passwords are excluded. File paths are listed
in configuration precedence order; environment/command-line overrides can still determine effective values.
The PowerShell launcher changes to the project directory, so the default file is
`<project>/config/datasources.properties`. Direct Java launches resolve it against their working directory.
No datasource file is copied into `target`; additional locations may load and override another file.

### Compare inside PostgreSQL

Set `<compare ... engine="db">` when both SQL rules use the same PostgreSQL datasource.
The default `engine="stream"` keeps the existing CSV comparison. The database engine materializes
the two inputs as CTEs, joins normalized keys, excludes ambiguous duplicate keys and computes counts,
cell classes and breakdowns in SQL. Both inputs share one transaction; if either uses `tempTables="true"`,
the complete statement uses the opt-in writable pool and is rolled back. Database comparisons start after
the query workers finish, preserving one active query per datasource.

Only aggregates and at most `--sample-rows` samples per cell class/acceptance, unmatched side or duplicate
side cross the wire. Equal rows are never fetched. CSV names, columns and SUMMARY.md tables keep their
existing format. Even `export="full"` keeps capped samples for this engine; summaries show exported/true
totals. `none` fetches aggregates and breakdowns only. Inputs referenced exclusively by database comparisons
produce SQL/metadata files without a standalone query CSV, even when their SQL export is full. An input
also needed by a streaming comparison retains its ordinary export behavior.

Supported comparison columns are native exact numeric types (integer/decimal) and variable text, with
`absTol`, `relTol`, HALF_UP `round`, `nullAsZero`, `nullAsEmpty`, `trim`, and ASCII `caseInsensitive` rules.
Keys use normalization, not tolerance. PostgreSQL text operations use the C collation. `dateOnly`,
cross-datasource comparisons and SQL `maxRows` fail rule validation. `--limit` is refused at execution.
JDBC metadata checks refuse floats, temporal/binary/fixed CHAR columns, mixed numeric/text mappings and
numeric rules on text before executing inputs. Date/numeric-looking or non-ASCII differing text that would
require Java-specific interpretation is detected by an SQL validity aggregate and fails clearly; project a
native numeric column or use the streaming engine. Non-ASCII/numeric/date-looking text keys are likewise
refused. These data/type checks require execution; offline `--validate` checks rule options and datasource
references without connecting. H2 is supported for the isolated test suite; its full join uses equivalent
left/right unions and its CTE planner is unsuitable for performance measurements. PostgreSQL requires 12+
for materialized CTEs.

Cross-database bucketed checksums remain a future phase. Numeric normalization/tolerance expressions
can be reused, but cross-engine canonical serialization and identical hashing need separate design and tests.


Only single SELECT/WITH statements pass the conservative SQL guard. Write operations, SELECT INTO, writable CTEs,
multiple statements, executable/nested comments and ambiguous quoting are rejected. Use bound parameters rather
than backslash or dollar quoting. Use whitespace after `--` comments. The guard may conservatively reject unusual
vendor syntax. SQL syntax still has to be valid for the selected database.

Connections use JDBC read-only mode and rollback after each query. PostgreSQL and MySQL also receive session
read-only settings. Use SELECT-only database accounts: arbitrary stored functions and all JDBC dialects cannot be
sandboxed by a lexical SQL guard or advisory JDBC flags alone. There is no universal JDBC guarantee of read-only
enforcement for every database. Comparison rules never deliberately issue data-changing SQL to source databases.
The separate `--template-upload ... --apply` command described below deliberately updates a template using a writable datasource.

Datasources with `prod=true` or 'prod' in their name require explicit `--allow-prod` during execution. Validation and
listing never connect. The supplied PROD datasource is marked accordingly. Confirm environment flags for any other
datasources you add. Console and file diagnostics include exception details and cause stacks with credential redaction.

Exit codes:

| Code | Meaning |
|---|---|
| 0 | Complete export, equal comparison, validation, listing, or help |
| 1 | Value/schema differences, missing rows, or duplicate keys |
| 2 | Configuration/query/file error or row-capped PARTIAL result |

## Libraries

Spring Boot 4.1.2, Spring JDBC/HikariCP, Jackson XML 3 (Boot-managed), Commons CSV 1.14.1, MySQL and PostgreSQL JDBC
drivers. Tests use Spring Boot test support and H2. ZIP creation, decimal/date handling, partitioning and comparisons
use Java 21 APIs. No ORM, PDF/Excel libraries, embedded comparison database or CLI framework is needed.

## Diagnostic logs

### Continue after query failures

Execution attempts every eligible SQL rule even if an earlier query fails. Successful CSVs are retained. Each
query still passes the SQL/read-only/production checks before connecting; blocked queries are reported and skipped.
Comparisons run only when both source queries completed without row caps. Failed/skipped comparisons do not stop
independent comparisons or ZIP packaging. Any failure, blocked query, skipped comparison or capped result returns 2.

Read `MANUAL_ACTIONS.md` in the output directory: it lists each failed query, its datasource, the underlying reason,
saved SQL filename, and the action to take. SQL templates use named parameters whose values are in `PARAMETERS.csv`.
Review failed SELECTs in your database client. Correct blocked configurations/safety issues instead of bypassing them.
Incomplete failed-query CSVs move under `manual/`; they are never presented as complete query exports.
Every ZIP includes the manual-actions report and failed-query SQL templates. If a selected comparison cannot run,
its ZIP entry is a diagnostic note. Final reports outside the ZIP also include any later packaging failures.

`--validate` remains strict and never connects. Invalid XML, invalid IDs/references, invalid shared parameter values
or conflicting output filenames are fatal structural errors; correct them before execution can start.

Spring Boot manages the SLF4J/Logback dependencies. `src/main/resources/logback-spring.xml` keeps the standard Boot
console format and adds two synchronous rolling files:

| File | Contents |
|---|---|
| `logs/compare-db-tool.log` | All enabled log levels, including application progress and exception details |
| `logs/compare-db-tool-error.log` | ERROR events with full exception/cause stacks |
| `logs/archive/*.log.gz` | Rotated daily/size-based archives |

Rotation defaults: 10 MB per file, seven days of history, 100 MB archive cap per appender. Logs append across runs.
The directory resolves from the working directory; the launcher sets that to the project directory. The logs folder
and `.log` files are gitignored and never included in result ZIPs.

The original exception cause is retained for XML, parameter, JDBC and file-processing errors. XML logs identify the
element and its starting line/column, and stack traces include the parser's detailed message and nested cause.
DEBUG logs show loaded IDs, rule counts, parameter names, datasource names, query settings and comparison partitions.
Query progress is reported every 100,000 rows. Parameter values and result rows are not intentionally logged.
Hikari lifecycle events are enabled; the previous blanket OFF setting has been removed.

Edit external `config/logging.properties` and rerun without rebuilding to change levels, retention or directory:

```properties
logging.file.path=logs
logging.level.root=INFO
logging.level.com.taomish.comparedbtool=DEBUG
logging.level.com.zaxxer.hikari=INFO
logging.level.org.springframework.jdbc=WARN
logging.logback.rollingpolicy.max-file-size=10MB
logging.logback.rollingpolicy.max-history=7
logging.logback.rollingpolicy.total-size-cap=100MB
```

For a one-run override:

```powershell
.\compare.ps1 --rules rules\grasa-inputs-cfs.xml --validate --logging.level.com.taomish.comparedbtool=DEBUG
Get-Content .\logs\compare-db-tool-error.log -Tail 120
```

The console and both file appenders redact configured datasource passwords, sensitive environment/property values,
JDBC URLs and common password/token assignments from messages and nested stack traces. No datasource configuration
is intentionally dumped. Use this supplied logging configuration to retain the redaction encoder; replacing it with
an arbitrary external Logback configuration bypasses that protection. Review logs before sharing them.

JVM/JAR launch failures before Spring initializes logging still appear on the console and may produce no log file.
After pulling Java/logback resource changes, rebuild the JAR once; subsequent external logging-property changes need
only a rerun. Logging/redaction tests are included in the H2-only build verification.

## Export, edit, and upload requested templates

Copy `rules/template-export.example.json` to your own rule and set `db`, `schema`,
`tenantId`, `businessApplication`, `businessUnit`, and the requested template names.
List one or more selections in `templates`. To select by UUID instead of name,
use `{ "uuid": "THE_TEMPLATE_UUID" }`; tenant ID is still mandatory. An entry can
override the shared tenant/application/BU. Name selection is case-insensitive
within the exact tenant and includes inactive records; ambiguous matches require
an explicit UUID. Requests do not accept arbitrary SQL or wildcard selection.

```powershell
.\compare.ps1 --template-export rules\my-template-export.json --validate
.\compare.ps1 --template-export rules\my-template-export.json
```

Export opens a read-only transaction and writes a unique directory such as:

```text
templates/export-123456/
  upload-rule.json
  README.txt
  <template-uuid>/
    template.html        # or template.docx / template.xlsx
    row.json
```

Each file contains the original database bytes. Each `row.json` records **every
column**, including UUID, tenant, creation/update audit fields and any additional
database columns. Nulls are preserved. JDBC types are recorded beside the values;
dates/UUIDs/text use their JDBC string representation and numbers/booleans are JSON
values. `document_content` is a file reference with size and SHA-256 rather than
duplicated binary text. Other binary columns, if present, use explicit base64.
`row.json` is an evidence snapshot; it is not executed as SQL or used to overwrite
creation audit fields on upload.

Edit or replace the exported template files in place, then use the generated rule:

```powershell
.\compare.ps1 --template-import templates\export-123456\upload-rule.json
.\compare.ps1 --template-import templates\export-123456\upload-rule.json --apply
```

The generated entries use `mode: "update"`, `contentOnly: true`, original UUID and
tenant, and `expectedSha256` of the original database content. Upload targets that
UUID within its tenant and changes only `document_content` and the update timestamp
(plus `updatedBy` if you explicitly supply it). Current filenames, MIME, format JSON,
status, description, creation audit fields and extra columns stay intact. Metadata
changes made in the database while you edit are preserved. Concurrent changes to
the **content** cause rejection; re-export and reconcile rather than removing the
hash guard. Re-export after each successful upload before starting another edit.

If you want to update supported template metadata too, edit the **upload rule**,
set `contentOnly` to false and supply the fields documented below. Editing
`row.json` alone does not change what is uploaded. Keep the extension/type aligned
with the original template for content-only updates.

Export and upload both work with the same datasource, including a `read-only=true` one used
for comparisons: `--apply` is the explicit write opt-in and opens its own read-write connection
for that run only (previews stay read-only). The database user still needs write permission on
the template table. Production datasources additionally require `--allow-prod`. A separate
writer alias (`uploadDb`) is optional, for setups where the comparison login cannot write.
Production-marked aliases require `--allow-prod`. Export never performs database
writes. Repeated exports create separate folders and do not overwrite local edits.
Export directories are gitignored. A missing/ambiguous/duplicate requested row
fails the export before files are written; the runnable upload rule is written last.

## Import one or more document templates from disk

Use `--template-import` with `rules/template-import.example.json` as the starting
point. Its `templates` array accepts one or more entries. Top-level `db`, `schema`,
`tenantId`, `businessApplication`, `businessUnit`, `updatedBy` and `mode` are defaults;
an entry can override them. All entries must use the same datasource and schema,
so the entire import commits in **one transaction**. A failure rolls back every
insert and update in the batch. Duplicate identities within the rule are rejected.
Local backup files from attempted operations may remain after rollback.

```powershell
# Validate files and rule without connecting
.\compare.ps1 --template-import rules\my-templates.json --validate
# Preview the complete import
.\compare.ps1 --template-import rules\my-templates.json
# Apply the complete import
.\compare.ps1 --template-import rules\my-templates.json --apply
```

Set `mode` globally or per entry:

| Mode | Existing template | Missing template |
|---|---|---|
| `update` (default) | Replace | Error |
| `create` | Error | Insert |
| `upsert` | Replace | Insert |

New templates require `allowedOutputFormats`, receive a generated UUID and
creation/update timestamps, and default to status `Active`. `updatedBy`, when
provided, is used for both creation and update audit names on inserts. An optional
`uuid` or `expectedSha256` asserts that an existing row must match and prevents an
insert. Each inserted template gets guarded DELETE rollback SQL under
`templates/.backups/`; replacements retain the previous bytes and restore SQL.
When rolling back a batch manually, run the generated undo statements in a single
transaction and verify each affected row before committing.

Single-template rules and `--template-upload` remain supported as described below.

The separate `--template-upload` command updates one **existing** row in
`SCHEMA.oga_doctemplatemaster_doctemplatemaster`. Comparison XML and query execution
remain read-only. This command accepts JSON; start with
`rules/template-upload.example.json` and save your own rule alongside it.

1. Put the original `.html`, `.docx` or `.xlsx` file in `templates/`.
2. Set `db` to a configured datasource name, `schema` to the exact target schema,
   and set `tenantId`, `docTemplateName`, `businessApplication`, `businessUnit`, and
   `file` in the JSON. `file` is relative to `templates/`; subdirectories work.
3. For applying writes, add a dedicated writer entry to your local
   `config/datasources.properties`. Existing comparison datasources should keep
   `read-only=true`. For example (replace database and credentials yourself):

```properties
compare.datasources.template-writer.url=jdbc:postgresql://localhost:5432/YOUR_DATABASE
compare.datasources.template-writer.username=${TEMPLATE_DB_USER}
compare.datasources.template-writer.password=${TEMPLATE_DB_PASSWORD}
compare.datasources.template-writer.driver-class-name=org.postgresql.Driver
compare.datasources.template-writer.read-only=false
compare.datasources.template-writer.prod=false
```

Build, validate without a connection, preview the matched row, then apply:

```powershell
.\mvnw.cmd package
.\compare.ps1 --template-upload rules\my-template.json --validate
.\compare.ps1 --template-upload rules\my-template.json
.\compare.ps1 --template-upload rules\my-template.json --apply
```

Preview opens a read-only connection, prints the matched UUID and old/new SHA-256,
and rolls back without changing data or creating backups. `--apply` requires a
writable datasource and updates in one transaction. Production-marked datasources
also require the existing `--allow-prod` option. Exit 0 indicates success; exit 2
indicates an error. Validation checks files/configuration, but cannot check the
database row or inherited metadata until preview/apply.

Matching uses exact tenant ID and case-insensitive template name, application,
and trimmed rule BU, as in `DocTemplateService`. Missing rows, multiple matching
rows (even if one is inactive), and a mismatched optional `uuid` are rejected.
Optional `expectedSha256` prevents replacing a template whose current bytes differ
from the previewed version. With default mode `update`, no rows are inserted.
The utility never creates tables.

The utility writes raw file bytes with JDBC binary binding, never a text/base64/CSV
representation. It derives MIME and file type from the extension, validates the
`jsreport` engine and selected/default output formats, and reads the bytes back
before commit. Existing `description`, `status` and `allowedOutputFormats` are
preserved when omitted. Engine defaults to `jsreport`; document/template filenames
default to the input filename. You can explicitly provide `templateFileName`,
`documentName`, `description`, `status`, `mimeType`, `templateEngine`,
`templateFileType`, `allowedOutputFormats` and `updatedBy`. Unknown JSON fields
are rejected. The existing UUID, tenant and creation audit fields are preserved;
the update timestamp is refreshed and `updatedBy` changes only if supplied.

Before updating, a unique directory under `templates/.backups/` receives
`original.bin`, `backup.json`, and `restore.sql`. The backup path is printed even
if the following update fails. Backups are local and gitignored. To roll back,
open `restore.sql` in the **same target database**, execute its BEGIN/UPDATE,
verify exactly one affected row, then explicitly COMMIT (or ROLLBACK if it does
not match). Its content guard refuses to overwrite bytes changed after this
replacement. The SQL restores original bytes, template metadata and update audit
fields; it does not undo database-trigger side effects. PostgreSQL and MySQL
binary literal syntax are generated according to the connected database.

This is a direct database utility: application audit listeners and the service's
`templateCache.clear()` are not invoked. Rendering pods pick up changes after
their configured `jsreportTemplateCacheSeconds` TTL (60 seconds by default in the
referenced service). Verify a generated document after the cache expires. No
running service configuration or database is modified merely by adding the utility.

## Troubleshooting missing console output

The launcher prints its Java executable, JAR path, working directory, and final exit code. The application prints
configuration startup, the rules path, validation results, and the start of each query/comparison. Validation creates
no result folder: success is the `VALID:` console message. Execution prints its output directory and summary path.

If the script still returns without those messages, invoke the JAR directly from the project directory to separate
the PowerShell launcher from Java/application behavior:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar .\target\compare-db-tool.jar --rules rules\grasa-inputs-cfs.xml --validate
Write-Host "Exit code: $LASTEXITCODE"
```

If JAVA_HOME is not set, use `java` instead of the full executable path. Confirm you are using the latest script
and rebuild after Java source changes. For a captured console log:

```powershell
.\compare.ps1 --rules rules\grasa-inputs-cfs.xml --validate *> validation.log
Get-Content .\validation.log
```

`rules/grasa-inputs-cfs.xml` references `postgres-dev` (database `operadb`, schema `opera`) and MySQL OPRDB02
(`dbsystemuat`). It needs no production flag. If you intentionally change it to `postgres-prod`, execution then
requires `--allow-prod`; without it the application must report an error before connecting.

## Restore table rows from a reference (`--restore`)

Puts rows of target tables back from reference tables (a migration copy, a snapshot, a backup) using a JSON rule.
Deliberately separate from the read-only comparison path, with the same safeguards as the template upload:

```powershell
.\compare.ps1 --restore rules\rrr-restore-20261002.json --validate   # check the rule, no connection
.\compare.ps1 --restore rules\rrr-restore-20261002.json              # preview: runs everything, rolls back
.\compare.ps1 --restore rules\rrr-restore-20261002.json --apply      # commit
```

Per step (`table`, `from`, `where`, optional `referenceWhere`, `key`, `deleteExtra`): a backup of the target rows in scope is
created in `backupSchema`; target rows whose key exists in the reference with other content are replaced; missing reference
rows are inserted; with `deleteExtra` target rows the reference does not have are removed; then the scope must equal the
reference, otherwise everything is rolled back. Content = every column both tables have except uuid and the audit columns.
All steps run in one transaction. `where` and `referenceWhere` must be conditions only (no statements, comments or semicolons).
The preview writes `RESTORE.md` with the real counts; `--apply` also writes `undo-<name>.json`, a restore rule that puts the
rows back from the backup tables. Production datasources need `--allow-prod`.
