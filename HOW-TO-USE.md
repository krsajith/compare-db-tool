# compare-db-tool: how to use it

For AI coding agents (Claude Code, Codex, Cursor, Copilot, ...) and for people. Self-contained: you do not need the README to use the tool.
Every complete example marked `xml rule`, `json restore` or `json load` is validated by the test suite (`GuideTests`), so the examples work as written.

**What it is.** A read-only command-line tool for finding data issues. You describe the question in an XML *rule file* (SQL queries, how to compare them, what you expect).
The tool runs it against MySQL / PostgreSQL, compares, explains the differences, says PASS or FAIL, and writes small result files you can read.
It can also restore rows from a backup table (always preview first) and move report templates between environments.

---

## READ THIS FIRST (agents)

**Read this whole file before you write a rule, not only section 0.** It is about 500 lines. Sections 5 (the rule file), 6 (capabilities) and 10 (playbook) hold the details that decide whether your rule works. Agents that skimmed only the top wrote rules that ran but kept no data.

**Critical checks for every rule (do these before you call it done):**
1. **Does each standalone query keep its rows?** A `<sql>` that is not compared, reused or put in a funnel/presence keeps its rows **only** with `export="full"`. With `sample` or `none` you get just its `.sql` and `.md` (columns and row count). Discovery, config and lookup queries therefore need `export="full"`.
2. **Is there an `<expect>`?** Without one the run never says PASS or FAIL.
3. **Is there a `<compress>`?** Nothing is zipped unless the rule says so. Add one when the result will be sent or attached.
4. **Parameters, not literals,** for dates and ids; one rule per question.
5. **Run `--validate`, then `--check`.** Both print `advice:` lines for the points above. Read them.
6. **Output size:** the flag is `--export none|sample|full` (there is no `all`). A rule's own `export=` beats the flag.

---

## 0. The 14 rules for agents

1. **Work in a loop, never in one big step:** write the rule, `--validate`, `--check`, run, read `SUMMARY.md` (section 2).
2. **Put the expectation in the rule.** Add `<expect>` elements so the tool prints PASS or FAIL. Do not eyeball counts.
3. **Read the small files first.** `SUMMARY.md` (about 8 KB) then `FINDINGS.md`. Open a CSV only to look up specific keys. Never paste a large CSV into a conversation.
4. **Ask for the least data about big comparisons, all of it for small lookups.** First pass: `--export none` (counts only). Second: the default `sample` (capped samples of *comparisons*). Use `full` for a key list you will read **and for every standalone query whose rows you need** (config, discovery, lookups): sample mode keeps no rows for those.
5. **Put SQL in `<![CDATA[ ... ]]>`.** Otherwise `<`, `>` and `&` break the XML.
6. **In XML attributes escape `<` as `&lt;` and `&` as `&amp;`** (`expr`, `when`, ...). `>` may stay or be `&gt;`.
7. **Never write `--` inside an XML comment.** `<!-- use --validate -->` is invalid XML. Write "use the validate flag".
8. **SQL is a single `SELECT` or `WITH` statement.** Bind values with `:name` parameters; never concatenate values into SQL. Writes, locks, file output and side-effecting functions are rejected; a function, column, alias or table that merely is called `replace`, `truncate`, `load`, `call`, `begin`... is accepted (section 5.3).
9. **List the columns you need; do not `SELECT *`.** Less data to move, and the comparison and findings work on those columns.
10. **Return extra columns in queries** (client, product, status, `updated_by`, a timestamp). They are what makes the findings and profiles say *why*.
11. **Use `uses=` / `when=` / `<suggest>` instead of a second round trip** when the next question depends on this result (section 6.6, 6.7, 6.9).
12. **Never pass `--allow-prod` or `--apply` without the user's explicit instruction.** A restore is always previewed first. The tool never restores by itself.
13. **Do not read or edit `config/datasources.properties`** (it holds credentials). Use `--datasources` to list the names that exist.
14. **Trust the exit code:** 0 success / all expectations met, 1 differences or an expectation not met, 2 error or PARTIAL. Never treat exit 2 output as proof of equality.

---

## 1. What it can do (find the feature for your question)

| You want to know | Use | Section |
|---|---|---|
| Do two queries return the same data? What differs? | `<compare>` (key-based, tolerances, classes of change) | 5.4, 6.1 |
| Say PASS / FAIL automatically | `<expect>` | 5.7, 6.2 |
| *Why* do they differ? (re-keyed rows, timing, who, a ratio ...) | Findings (automatic) | 6.3 |
| Which of N sources has each key? (legacy / backup / current) | `<presence>` | 6.4 |
| At which step of a pipeline do two sides stop agreeing? | `<funnel>` (compare or chain mode) | 6.5 |
| Use query 1's result in query 2, even across databases | `uses="alias=query-id"`, `:alias.col`, `:alias.col[]` | 6.6 |
| Look up the keys a comparison found missing | `uses="m=cmp-id:only-source"` | 6.6 |
| Run a follow-up only if something is true; compute dates; stop a bad run early | expressions: `when`, `after`, `<check>`, `expr` | 6.7 |
| Find mistakes in a rule before the long run | `--validate` (offline), `--check` (asks the databases) | 6.8 |
| Fix wrong data from a backup table in the same database | `--restore` JSON rule, `verify`, `<suggest>` | 6.9 |
| Copy rows from any database (MySQL, another PostgreSQL) into a table, insert only | `--load` JSON rule, `expect`, `verify` | 6.9b |
| Move report templates between environments | `--template-export`, `--template-import` | 6.10 |
| Browse, re-run and confirm risky runs interactively | menu (`.\compare.ps1` with no arguments) | 6.11 |
| Bundle selected outputs for sending | `<compress>` | 5.8 |

---

## 2. The working loop (copy this)

Windows (PowerShell) from the project folder; on Linux/macOS replace `.\compare.ps1` by `java -jar target/compare-db-tool.jar`. Build once: `.\mvnw.cmd clean package` (needs JDK 21).

```powershell
.\compare.ps1 --rules rules\my-rule.xml --validate        # 1. offline: syntax, SQL guard, ids, expressions. Opens no connection.
.\compare.ps1 --rules rules\my-rule.xml --check           # 2. asks each database to EXPLAIN every query and describe its columns. Reads no data.
.\compare.ps1 --rules rules\my-rule.xml --export none     # 3. first real run: counts, verdict, findings, small files
.\compare.ps1 --rules rules\my-rule.xml                   # 4. default sample export, when you need example rows
```

After a run the console ends with `summary: <folder>\SUMMARY.md`. Read, in this order:

1. `SUMMARY.md`: status, `Verdict: PASS|FAIL`, query table (rows, time), small query results, findings, skipped items, suggested next step.
2. `FINDINGS.md`: ranked explanations of the differences, each with evidence.
3. `EXPECTATIONS.md`: every check with expected / actual / why. `MANUAL_ACTIONS.md`: failed or skipped items with the reason.
4. `<comparison-id>/SUMMARY.md`, `<funnel-id>/SUMMARY.md`, `<presence-id>/SUMMARY.md` for detail. CSVs last.

Then decide the next step: fix the rule and repeat, add a drill-down, or follow the `Suggested next step`.

Exit codes: `0` success / equal / all `<expect>` met · `1` differences or an `<expect>` not met · `2` error, failed query, or PARTIAL result.

---

## 3. Command line

`.\compare.ps1 [--rules FILE] [options]`. Every flag:

| Flag | Meaning |
|---|---|
| `--rules FILE` | The XML rule file (or give FILE as the only positional argument). |
| `--validate` | Offline check of the rule: XML, SQL guard, parameters, ids, expressions, restore rules named in `<suggest>`. Also evaluates `<check>` with your `--param` values. |
| `--check` | Ask the databases to prepare + EXPLAIN every query and describe its columns, then check the rule against the real columns. Nothing is executed or read. Writes `CHECK.md`. |
| `--param NAME=VALUE` | Override a declared `<param>` (repeat for several). Undeclared names are an error. Not allowed with `--restore`. |
| `--export none\|sample\|full` | Output size. `none`: counts and summaries only. `sample` (default): capped sample CSVs for comparisons, funnels and presence; a **standalone query keeps no rows** (only `.sql` and `.md`). `full`: every query's complete CSV. A rule's own `export=` wins; there is no `all`. |
| `--sample-rows N` | Records per sample CSV (default 200). Counts are always exact. |
| `--limit N` | Row cap per query. Results become PARTIAL (exit 2); comparisons on them are skipped. Debugging only. |
| `--parallel N` | Query workers (default: number of distinct datasources; one active query per datasource). |
| `--partitions N`, `--max-partition-mb N` | Comparison memory tuning (defaults 64, 256). Raise `--partitions` if a memory-budget error appears. |
| `--out DIR` | Output base folder (default `out`). |
| `--target-env NAME` | The datasource that `db="target"` in a rule refers to for this run, so one rule file serves dev and UAT (`--target-env postgres-uat`). The environment variable `TARGET_ENV` does the same; the flag wins. NAME must be a configured datasource, and the console prints which one `target` means. A production datasource stays production (needs `--allow-prod`). Without it, a rule that uses `db="target"` stops with a message. |
| `--allow-prod` | Allow datasources marked `prod=true` or with `prod` in the name. **Only when the user says so.** |
| `--restore FILE` | Restore rows from a backup table using a JSON rule. **Preview by default.** |
| `--load FILE` | Copy rows from a SELECT on any datasource into a table using a JSON rule (section 6.9b). Insert only. **Preview by default.** `--param` overrides a rule parameter. |
| `--apply` | Commit a restore, a load or a template import. Without it everything is rolled back. |
| `--template-export FILE`, `--template-import FILE` (`--template-upload`) | Report templates (section 6.10). Import is a preview unless `--apply`. |
| `--datasources` | List configured datasource names and flags (no secrets). |
| `--quiet` | No progress messages (warnings and errors still show). |
| `--menu` | Interactive menu (also the default when started with no arguments in a terminal). |
| `--help` | Print the flags. |

Logging: files in `logs/`; console shows progress messages, warnings and errors. More detail: `--logging.console.level=DEBUG`.

---

## 4. Datasources and safety

- Datasources live in `config/datasources.properties` (not in git, holds credentials, **do not open or edit it**). Names are arbitrary; each `<sql db="NAME">` uses one. Typical names here: `mysql-oprdb02`, `postgres-dev`, `postgres-uat`, `postgres-prod`.
  Properties per name: `url`, `username`, `password` (may be `${ENV_VAR}`), `driver-class-name`, `read-only` (must be `true` for normal queries), `prod`, `allow-temp-tables`, `allowed-functions`.
- A datasource file in another place: `--spring.config.additional-location=file:D:/private/datasources.properties` (with `=`; it adds to or overrides the default file).
- Queries are guarded: one `SELECT`/`WITH` only, read-only sessions, rolled back. Production names need `--allow-prod`.
- `tempTables="true"` on a `<sql>` lets a non-production query call a function that creates temp tables; it is always rolled back and refused on production.
- Anything that writes (`--restore --apply`, `--load --apply`, `--template-import --apply`) is separate, previews first, backs up what it changes and writes an undo rule. The database account decides what may be written: give the datasource an account that can insert into the one table, and nothing else.
- Everything the tool writes is in the output folder: the run's `RULES.xml` and `PARAMETERS.csv` too, so a run can be reproduced.

---

## 5. The rule file

One XML file, root `<rules name="...">`. Children can appear in any order (`<parameters>` once). Ids must be unique across all kinds, start with a letter, and use letters, digits, `_`, `.`, `-`.
Reserved ids: `summary`, `parameters`, `rules`, `error`, `expectations`, `findings`.

### 5.1 `<parameters>`
```xml
<parameters>
  <param name="cob" type="date" value="2026-10-01"/>
  <param name="prevCob" type="date" expr="#prevBusinessDay(#cob)"/>   <!-- computed, section 6.7 -->
</parameters>
```
Types: `string` (default), `integer`, `decimal`, `date` (`yyyy-MM-dd`), `timestamp` (`yyyy-MM-dd HH:mm:ss[.f]`), `boolean`, `null`. Use in SQL as `:name`. Values are bound, never pasted.

### 5.2 `<sql>`: a query
| Attribute | Meaning |
|---|---|
| `id`, `db` | Required. `db` is a datasource name. |
| `timeout` | Seconds (default 120). |
| `fetchSize` | Default 1000. |
| `maxRows` | Cap for this query; capped results are PARTIAL. |
| `export` | `none` / `sample` / `full` for this query. **A query that is not compared, reused or put in a funnel/presence keeps its rows only with `full`.** |
| `uses` | Inputs from earlier queries: `"alias=query-id, other"`. SQL then uses `:alias.col` (one value) or `:alias.col[]` (a list). Also `"alias=cmp-id:only-source"`. Section 6.6. |
| `after` | Run after these queries/comparisons (ids, comma separated) without using their rows. |
| `when` | Expression; run only if true, otherwise **skipped** (not a failure). Section 6.7. |
| `listBatch` | Longest list per statement (default 1000, max 30000); longer lists run in batches. |
| `tempTables` | `true` for non-production temp-table functions. |

Identical SQL on the same datasource with identical inputs runs once and is shared.

**What a query leaves behind, by export mode**

| Mode | Standalone query | Query used by a comparison / funnel / presence |
|---|---|---|
| `none` | row count in `SUMMARY.md`; no files | counts only |
| `sample` | `.sql` and `.md` (columns, row count); **no rows** (a result of at most 5 rows and 12 columns is also printed in `SUMMARY.md`) | samples from the comparison |
| `full` | `.sql`, `.md` and the complete `.csv` | the complete query `.csv` as well |

### 5.3 SQL dialect notes
Named parameters `:name`; PostgreSQL `::` casts are fine. A hyphen ends a parameter name, so `:legacy-trades.x` is not valid: give the input an alias in `uses=`. Comments: `-- text` needs a space after the dashes. No `;` except a trailing one. No `$$` quoting, no backslash escapes.

**Why a SQL guard exists.** Read-only sessions and a SELECT-only account are the real protection, but a read-only session still allows things that hurt production: `SELECT ... INTO OUTFILE`, `GET_LOCK`, `pg_advisory_lock`, `lo_import`/`lo_export`, locking reads (`FOR UPDATE`, `FOR SHARE`, `LOCK IN SHARE MODE`) and writable CTEs. The guard is a second layer that does not depend on how the database was configured.

**It is position-aware.** The guard looks at where a word stands, not only at the word. Accepted (a warning is printed for the flagged names):
```sql accepted
SELECT replace(name, 'a', 'b') AS replace FROM t
SELECT truncate(amount, 2), load, call FROM t WHERE lock = 1
SELECT * FROM load JOIN copy ON copy.id = load.id
(SELECT 1) UNION (SELECT 2)
```
Rejected (statements, writes, locks, file output, side effects, nested statements):
```sql rejected
SELECT * FROM t FOR UPDATE
SELECT * FROM t FOR SHARE
SELECT a FROM t INTO OUTFILE '/tmp/x'
SELECT nextval('seq')
SELECT pg_advisory_lock(1)
WITH x AS MATERIALIZED (DELETE FROM t RETURNING *) SELECT * FROM x
SELECT 1; DELETE FROM t
```
If a name is rejected by mistake, quote it (`"name"`).

### 5.4 `<compare>`: key-based comparison of two queries
```xml
<compare id="trades" source="legacy" target="new" export="sample">
  <key columns="trade_id"/>                                   <!-- or "client,product,trade_date" -->
  <column name="amount" absTol="0.005"/>
  <column name="client_code" trim="true" caseInsensitive="true"/>
  <column name="trade_date" dateOnly="true"/>
  <column name="generated_at" ignore="true"/>
  <column name="trade_id" source="legacy_id" target="trade_id"/>   <!-- different column names -->
  <breakdown columns="client_code" top="10"/>
</compare>
```
- Rows match by key regardless of order. All common columns are compared unless `ignore`d. A column on one side only is a schema difference (and fails the comparison) unless mapped or ignored.
- `<column>` attributes: `name`, `source`, `target`, `ignore`, `trim`, `caseInsensitive`, `nullAsEmpty`, `nullAsZero`, `numeric`, `round="2"`, `absTol`, `relTol`, `dateOnly`.
- Changes are classified `CASE_ONLY`, `WHITESPACE_ONLY`, `NULL_VS_EMPTY`, `NULL_VS_ZERO`, `FORMAT_ONLY`, `ROUNDING`, `SIGN_FLIP`, `DATE_SHIFT`, `OTHER`; only unaccepted changes fail.
- Duplicate keys are reported separately and never counted as equal.
- `engine="db"` compares inside one PostgreSQL datasource when both queries use it (nothing but counts and samples leaves the database). No `dateOnly`, no `--limit`, no findings, and it cannot feed or use `uses=`.

### 5.5 `<funnel>`: see 6.5.  `<presence>`: see 6.4.

### 5.6 `<check>`, `<suggest>`: see 6.7 and 6.9.

### 5.7 `<expect>`: what you expect, checked by the tool
Count values: `N`, `<=N`, `>=N`, `<N`, `>N`, `A..B`. Put a short `reason=` on each; it is printed with the verdict.

| Refers to (`ref=`) | Attributes |
|---|---|
| a `<sql>` query | `rows`; or `column` + one of `equals`, `min`, `max`, `between="lo,hi"`, `isNull`; `row="n"` (1-based, default 1) or `each="true"` |
| a `<compare>` | `matched`, `equal`, `different`, `only-source`, `only-target`, `duplicates` |
| a `<funnel>` | `first-divergence` (`none` or a stage id), `diverging` |
| a `<presence>` | `mismatched`, `in-all` |
| nothing | `expr="..."` (must be true); `judges="cmp-id"` says it accounts for that comparison/funnel/presence; any expect can have `when="..."` |

A comparison, funnel or presence that has at least one `<expect>` is judged by it (an expected difference no longer gives exit 1). One without an `<expect>` that differs gives exit 1.

### 5.8 `<compress>`: bundle outputs
```xml
<compress id="bundle" file="trade-parity.zip"><include ref="trades"/><include ref="legacy"/></compress>
```
`ref` is a query, comparison, funnel or presence id. Summary, rules, parameters, manual actions, expectations and findings are always included. The console prints the size. Nothing is zipped unless the rule has a `<compress>`.

### 5.9 Output checklist and a discovery rule
Before you finish a rule: (1) every standalone query whose rows you need has `export="full"`; (2) there is an `<expect>`; (3) there is a `<compress>` if the result is to be sent; (4) `--validate` and `--check` print no `advice:` you disagree with. A complete read-only discovery rule that does all of this:
```xml rule
<rules name="discovery-example">
  <sql id="config" db="postgres-dev" export="full"><![CDATA[
    SELECT code, template_name, created_timestamp FROM opera.upload_rules WHERE code = 'PIPELINE CONFIG' ORDER BY created_timestamp DESC LIMIT 3
  ]]></sql>
  <sql id="counts" db="postgres-dev" export="full"><![CDATA[
    SELECT 'trades' AS table_name, count(*) AS row_count FROM opera.trades
    UNION ALL SELECT 'positions', count(*) FROM opera.positions
  ]]></sql>
  <expect ref="config" rows=">=1" reason="the pipeline config should be seeded"/>
  <expect ref="counts" rows="2"/>
  <compress id="bundle" file="discovery-example.zip"><include ref="config"/><include ref="counts"/></compress>
</rules>
```


---

## 6. Capabilities, with examples

### 6.1 Compare two queries
Complete rule (`xml rule` blocks are tested):

```xml rule
<rules name="trade-parity">
  <parameters>
    <param name="cob" type="date" value="2026-10-01"/>
  </parameters>
  <sql id="legacy" db="mysql-oprdb02"><![CDATA[
    SELECT trade_id, client_code, amount, trade_date, updated_by
    FROM legacy_schema.trades WHERE settle_date = :cob
  ]]></sql>
  <sql id="new" db="postgres-dev"><![CDATA[
    SELECT trade_id, client_code, amount, trade_date, updated_by
    FROM opera.trades WHERE settle_date = :cob
  ]]></sql>
  <compare id="trades" source="legacy" target="new">
    <key columns="trade_id"/>
    <column name="amount" absTol="0.005"/>
    <column name="client_code" trim="true" caseInsensitive="true"/>
    <column name="trade_date" dateOnly="true"/>
    <column name="updated_by" ignore="true"/>
    <breakdown columns="client_code" top="10"/>
  </compare>
  <expect ref="trades" different="0" only-source="0" only-target="0" reason="legacy and new should hold the same trades"/>
  <compress id="bundle" file="trade-parity.zip"><include ref="trades"/></compress>
</rules>
```
Run `--param cob=2026-10-02` to change the date. Output: `trades/SUMMARY.md` (counts, breakdown, classes), `trades/only-source.csv`, `only-target.csv`, `differences.csv`, `duplicates.csv`.

### 6.2 Verdicts
`SUMMARY.md` starts with `Status:` and `Verdict: PASS (n of n expectations met)` or `FAIL (k of n ...)`; `EXPECTATIONS.md` lists failing checks first. In sample/none mode, queries with at most 5 rows and 12 columns are printed in `SUMMARY.md`, so a one-row summary query needs no CSV.

```xml
<sql id="inputs" db="postgres-dev"><![CDATA[SELECT count(*) AS n, sum(amount) AS total FROM opera.trades WHERE settle_date = :cob]]></sql>
<expect ref="inputs" rows="1"/>
<expect ref="inputs" column="n" between="9000,9500" reason="a normal day"/>
<expect ref="inputs" column="total" min="0"/>
```

### 6.3 Findings (automatic, no configuration)
For every stream comparison the tool writes `<comparison>/FINDINGS.md`, `findings.json`, and a run-level `FINDINGS.md`. Ranked, with evidence:

| Finding | Meaning |
|---|---|
| `rekeyed` | missing rows exist on the other side with identical values under another key: probably not missing |
| `near-miss-key` | keys that match after ignoring case, punctuation, leading zeros |
| `timing-*` | unmatched rows are all newer/older than the other side on a timestamp column: one side is an older snapshot |
| `concentration` | most unmatched/differing rows share one value (a user, product, date, batch) compared with how common it is |
| `constant-ratio`, `constant-offset`, `sign-flip` | all differing numbers differ by the same factor / amount / sign |
| `few-rows-drive-gap`, `numeric-gap`, `one-column` | what carries the gap |
| `empty-side` | a query returned no rows: check filters and parameters first |

They need columns to explain with: return `updated_by`, timestamps, product, client, status in the queries. A finding states what the data shows, not the cause.

### 6.4 Presence: which source has each key

```xml rule
<rules name="trade-presence">
  <parameters><param name="cob" type="date" value="2026-10-01"/></parameters>
  <sql id="legacy"  db="mysql-oprdb02"><![CDATA[SELECT trade_id, client_code FROM legacy_schema.trades WHERE settle_date = :cob]]></sql>
  <sql id="backup"  db="postgres-dev"><![CDATA[SELECT trade_id, client_code FROM opera_backup_04_10.trades WHERE settle_date = :cob]]></sql>
  <sql id="current" db="postgres-dev"><![CDATA[SELECT trade_id, client_code FROM opera.trades WHERE settle_date = :cob]]></sql>
  <presence id="deals" key="trade_id" sources="legacy, backup, current"/>
  <expect ref="deals" mismatched="0" reason="every trade should exist in all three"/>
</rules>
```
`deals/SUMMARY.md` shows, for every combination of sources, how many keys are in exactly those (counts are exact), examples, which source lacks how many, and a profile of what the mismatching keys have in common. `deals/presence-keys.csv` lists the mismatching keys.
In expressions: `#deals.missingIn['current']`, `#deals.inAll`, `#deals.mismatched`, `#p('deals')`. For values of keys that exist in two sources, add a `<compare>` for that pair.

### 6.5 Funnel: where do two sides stop agreeing?
A funnel is an ordered list of stages; each stage is a pair of queries returning the keys that survive that stage. The tool finds the first stage where a key is treated differently.

```xml rule
<rules name="deal-funnel">
  <sql id="legacy-s1" db="mysql-oprdb02"><![CDATA[SELECT deal_id, product_code FROM legacy_schema.deals WHERE business_unit = 'CFS']]></sql>
  <sql id="new-s1"    db="postgres-dev"><![CDATA[SELECT deal_id, product_code FROM opera.deals WHERE business_unit = 'CFS']]></sql>
  <sql id="legacy-s2" db="mysql-oprdb02"><![CDATA[SELECT deal_id, product_code FROM legacy_schema.deals WHERE business_unit = 'CFS' AND status = 'ACTIVE']]></sql>
  <sql id="new-s2"    db="postgres-dev"><![CDATA[SELECT deal_id, product_code FROM opera.deals WHERE business_unit = 'CFS' AND status = 'ACTIVE']]></sql>
  <funnel id="deals" key="deal_id">
    <stage id="s1-in-bu"  source="legacy-s1" target="new-s1" reason="deals of the business unit"/>
    <stage id="s2-active" source="legacy-s2" target="new-s2" reason="status ACTIVE"/>
  </funnel>
  <expect ref="deals" first-divergence="none" reason="same deals at every stage"/>
</rules>
```
- Output: a stage table (source, target, only-source, only-target, *diverges here*), a verdict ("First divergence at `s2-active`: 31 keys ..."), `diverging-keys.csv`, and a profile of what the diverging keys have in common (uses the extra columns the stage queries return, here `product_code`).
- `mode="chain"` (stages with only `source`): one side; shows the stage where each key drops out, e.g. the stages of one report pipeline. Add `group="outcome"` (one of the key columns) to get side-by-side columns per group (FAIL vs OK). A chain is a report; it never gives exit 1.
- Up to 30 stages. `rules/grasa-funnel-cfs-v2.xml` is a real chain funnel.

### 6.6 Use one query's result in another (chaining, drill-down, batching)
A query declares its inputs with `uses`; the SQL refers to them by alias. Values are always bound parameters.

```xml rule
<rules name="missing-trades-why">
  <parameters>
    <param name="cob" type="date" value="2026-10-01"/>
    <param name="bu" type="string" value="CFS"/>
  </parameters>
  <sql id="valuation-date" db="postgres-dev"><![CDATA[
    SELECT value_date FROM opera.valuation_date_table WHERE business_unit = :bu
  ]]></sql>
  <sql id="legacy" db="mysql-oprdb02"><![CDATA[SELECT trade_id, client_code FROM legacy_schema.trades WHERE settle_date = :cob]]></sql>
  <sql id="new" db="postgres-dev" uses="vd=valuation-date"><![CDATA[
    SELECT trade_id, client_code FROM opera.trades WHERE settle_date = :cob AND value_date = :vd.value_date
  ]]></sql>
  <compare id="trades" source="legacy" target="new"><key columns="trade_id"/></compare>

  <!-- the trades only legacy has: do they exist in the backup, and what are they there? (complete key list, batched) -->
  <sql id="in-backup" db="postgres-dev" uses="m=trades:only-source" export="full"><![CDATA[
    SELECT trade_id, status, updated_by, updated_timestamp
    FROM opera_backup_04_10.trades WHERE trade_id IN (:m.trade_id[])
  ]]></sql>

  <expect ref="trades" only-source="0" reason="no trade should be missing from new"/>
</rules>
```
- `:alias.col` is **exactly one row** (error otherwise). `:alias.col[]` is a **list** (NULLs and duplicates removed) for `IN (...)`.
- `uses="m=trades:only-source"` takes the **complete** unmatched rows of a stream comparison (not the 200-row sample). Parts: `:only-source`, `:only-target`, `:different`. The alias gives you the source query's columns (`only-target`: the target's). The query waits for the comparison.
- An empty list means "no rows" (the normal "nothing missing" answer); `NOT IN` with an empty list is refused.
- Lists longer than `listBatch` run in batches and the rows are joined. A query that combines rows (`GROUP BY`, `DISTINCT`, aggregates, `LIMIT`, `UNION`) is never split and fails with a clear message instead of giving a wrong answer. Max 100,000 values.
- If something it uses failed or is PARTIAL, the query is not run and `MANUAL_ACTIONS.md` says why.
- Ids with hyphens need an alias (`uses="mk=missing-keys"`).

### 6.7 Expressions: conditions, computed dates, checks (restricted SpEL)
Where: `<param expr>`, `<check expr>`, `<sql when>`, `<expect expr|when>`, `<suggest when>`.

```xml rule
<rules name="expressions-demo">
  <parameters>
    <param name="cob" type="date" value="2026-10-01"/>
    <param name="prevCob" type="date" expr="#prevBusinessDay(#cob)"/>
    <param name="valueDate" type="date" expr="#nextBusinessDay(#cob)"/>
  </parameters>
  <check expr="#prevCob &lt; #cob and #cob &lt;= #valueDate" message="Expected PrevCob &lt; Cob &lt;= ValueDate. Check the dates."/>
  <sql id="query1" db="postgres-dev"><![CDATA[SELECT first_name, age FROM opera.people ORDER BY id]]></sql>
  <sql id="people" db="postgres-dev"><![CDATA[SELECT id, first_name, age, amount FROM opera.people]]></sql>
  <sql id="detail" db="postgres-dev" after="query1" when="#query1.first.first_name == 'sajith'"><![CDATA[SELECT id FROM opera.people]]></sql>
  <expect expr="#contains(#values('people', 'first_name'), 'anna')" reason="anna is in the list"/>
  <expect expr="#size(#rows('people').?[age &gt; 25]) &gt;= 1 and #sum(#values('people', 'amount')) &gt; 0"/>
</rules>
```

Reading results by id: `#query1.first.first_name`, `#query1.rows`, `#trades.onlySource`, `#deals.mismatched`. A result variable exists for every query / comparison / funnel / presence whose id is a plain identifier (letters, digits, `_`); otherwise write `#q('legacy-trades')`, `#c('trades')`, `#f('deals')`, `#p('deals')`.
- Query map: `rows`, `partial`, `columns`, `first` (first row by lower-case column name). Comparison map: `matched`, `equal`, `different`, `onlySource`, `onlyTarget`, `duplicates`, `differentCells`, `partial`. Funnel: `diverging`, `firstDivergence`. Presence: `keys`, `inAll`, `mismatched`, `missingIn`, `distinct`, `rows` (maps by source id).
- `#rows('id')` = every row as a map (at most 20,000); `#values('id','col')` = one column as a list (at most 100,000). SpEL selection/projection works: `#rows('p').?[age > 25]`, `#rows('p').![first_name]`.
- Operators: `+ - * / %  == != < <= > >=  and or not  ? :  ?:  [index]  .property`; text in single quotes; `#name` for a parameter or result.
- **Functions** (methods cannot be called on values, so these are functions): dates `plusDays minusDays plusMonths minusMonths startOfMonth endOfMonth nextBusinessDay prevBusinessDay addBusinessDays isBusinessDay daysBetween dayOfWeek date today`; numbers `abs min max round sum`; text and collections `upper lower trim isBlank startsWith endsWith equalsIgnoreCase size isEmpty contains distinct`. Business days are Monday to Friday (no holidays). Result functions: `q c f p rows values` (only where results exist: `<expect>`, `when`, `<suggest>`).
- **Refused when the rule is checked:** `T(...)`, `new`, `@bean`, calling a method on a value, assignment, `matches`, `instanceof`, unknown `#variables` or functions (a typo is an error, not a silent null), expressions over 500 characters. Expressions cannot reach files, the network or the JVM and cannot build SQL.
- **`<check>`** is evaluated right after the parameters, before anything connects, also with `--validate` (with your `--param` values). A failing check stops the run with your message.
- **`when` on a `<sql>`** (with `after=` or `uses=` for what it reads): false means **skipped, not a failure**. What depends on a skipped query is skipped too; `<expect ref>` on skipped things is "not evaluated"; an expression that reads a skipped result fails with "was skipped". A `when` that cannot be evaluated blocks the query as a failure.
- A `--param` override replaces a computed parameter.

### 6.8 `--validate` and `--check`: catch mistakes before the long run
- `--validate` (offline): XML, ids, SQL guard, parameters, aliases, cycles, expressions, key names.
- `--check` (connects, reads nothing): for every query the database prepares the statement, describes its columns and runs `EXPLAIN` (never `ANALYZE`). Finds missing tables/columns, syntax and type errors with the database's own message; checks key columns of comparisons/funnels/presence, `:alias.col` inputs and `<expect column>` against the real column lists; shows the planner's row estimate on PostgreSQL (warns above 5 million). An unreachable database is reported once (10 s) and its other queries are skipped. Temp-table queries and `engine="db"` types are not checked.
  Output: console lines `OK / WARN / FAIL / SKIP` and `CHECK.md`.
- Both print `advice:` lines about a rule that is valid but probably not what you want: a standalone query that keeps no rows, no `<expect>`, no `<compress>`, `SELECT *`. Advice never stops a run.

### 6.9 Restore from a backup table, verify, and be told when to
A restore copies rows into a table from a **table in the same database** (a backup or migration copy). It cannot read from another database; to bring rows over from MySQL or another PostgreSQL use `--load` (6.9b).

```json restore
{
  "name": "fix-prices",
  "db": "postgres-dev",
  "backupSchema": "parity_work",
  "description": "Puts the CFS prices of 2026-10-02 back from the 04-Oct migration copy.",
  "steps": [
    {
      "id": "prices",
      "table": "opera.security_settlement_value_table",
      "from": "opera_backup_04_10.security_settlement_value_table",
      "where": "business_unit = 'CFS'",
      "referenceWhere": "value_date = DATE '2026-10-02'",
      "key": ["business_unit", "security_id", "value_date"],
      "deleteExtra": true,
      "expect": { "reference": ">0", "targetAfter": ">0" }
    }
  ],
  "verify": [ { "rule": "verify-prices.xml" } ]
}
```
- `where` = the scope in the target table; `referenceWhere` filters the reference; `key` columns must not be NULL; `deleteExtra` removes target rows in scope that the reference lacks.
- Preview first: `.\compare.ps1 --restore rules\fix-prices.json` runs every statement in a transaction and rolls it back; `RESTORE.md` shows rows before/after, replaced, inserted, extra removed. Step `expect` counts are checked (an apply is refused if one is unmet).
- Then `--apply`: commits, writes a backup table per step and an undo rule `undo-<name>.json`, then runs the `verify` rules (read-only rules next to the JSON, each must contain `<expect>`).
- **The tool can tell you to restore:** in the comparison rule, `<suggest restore="fix-prices.json" when="#cmp.different > 0 or #cmp.onlySource > 0" reason="current differs from the migration copy"/>`. When the condition holds the console, `SUMMARY.md` and the menu show `Suggested next step` with the exact preview command. It never restores by itself.

```xml rule
<rules name="verify-prices">
  <sql id="current" db="postgres-dev"><![CDATA[
    SELECT security_id, value_date, settle_price FROM opera.security_settlement_value_table WHERE business_unit = 'CFS'
  ]]></sql>
  <sql id="reference" db="postgres-dev"><![CDATA[
    SELECT security_id, value_date, settle_price FROM opera_backup_04_10.security_settlement_value_table
    WHERE business_unit = 'CFS' AND value_date = DATE '2026-10-02'
  ]]></sql>
  <compare id="cmp" source="reference" target="current"><key columns="security_id,value_date"/></compare>
  <expect ref="cmp" different="0" only-source="0" only-target="0" reason="current equals the reference"/>
  <suggest restore="fix-prices.json" when="#cmp.different &gt; 0 or #cmp.onlySource &gt; 0 or #cmp.onlyTarget &gt; 0" reason="current differs from the 04-Oct copy"/>
</rules>
```

### 6.9b Load rows from any database into a table (`--load`)
Use it when data exists in one database (legacy MySQL, another PostgreSQL) and is missing in another, for example rows a migration never loaded. The source is a SELECT, so it can be a different database from the target. **Insert only**: nothing that exists is changed.

```json load
{
  "name": "copy-multipliers",
  "db": "postgres-dev",
  "backupSchema": "parity_work",
  "description": "Copies the legacy multiplier rows of two value dates into the new table.",
  "params": { "d1": { "type": "date", "value": "2026-11-03" }, "d2": { "type": "date", "value": "2026-11-05" } },
  "steps": [
    {
      "id": "mult",
      "table": "opera.helper_var_mult_factor_view",
      "where": "created_by = 'mult-load'",
      "key": ["invenio_product_code", "value_date"],
      "source": { "db": "mysql-oprdb02", "sql": "SELECT invenio_product_code, value_date, curr_mult, lot_mult, mult_factor FROM dbsystemuat.helper_var_mult_factor_view WHERE value_date IN (:d1, :d2)" },
      "constants": { "uuid": "$uuid", "tenant_id": "227cd5f5-4498-4dba-bda9-05b8edc41113", "created_by": "mult-load", "created_timestamp": "$now" },
      "expect": { "targetBefore": "0", "source": ">=1", "inserted": ">=1" }
    }
  ],
  "verify": [ { "rule": "verify-multipliers.xml" } ]
}
```
- `db` is the datasource written to. `source.db` and `source.sql` say where the rows come from; the SQL is an ordinary rule query (one `SELECT`/`WITH`, bound `:name` parameters declared in `params`, read-only session). Every column the SELECT returns must be a column of `table` (alias columns in the SQL).
- `where` is the **scope**: the rows that belong to this load. Every inserted row must fall inside it (the tool checks that the scope grew by exactly the rows inserted), so make the loaded rows identifiable, here through `created_by` in `constants`. `key` is the business key: after the load no key may exist twice in the scope (a duplicate in the source, or rows loaded before), and the key is needed for the undo rule.
- `constants` fills columns the source does not supply: a text value, `$uuid` (a new random UUID per row) or `$now` (the time of the load). Values are converted to the type of the target column; an empty source value becomes NULL.
- `expect` keys: `targetBefore` (rows in the scope before), `source` (rows read), `inserted`, `targetAfter`. Same syntax as in restore. An apply is refused when one is unmet.
- Preview first: `.\compare.ps1 --load rules\copy-multipliers.json` reads the source, inserts inside a transaction and rolls it back; `LOAD.md` shows the real counts and the exact rows read are kept as `<step id>.csv`. `--param d1=2026-11-05` overrides a parameter. Then `--apply` commits, makes a backup table per step, writes the undo rule `undo-<name>.json` and runs the `verify` rules (read-only rules with `<expect>`, next to the JSON file).
- Zip: every load and restore (preview and apply) also writes `<rule name>.zip` into its result folder (summary, the rows read, the undo rule) and the console prints its path. Send that file; no manual zipping.
- Undo: `.\compare.ps1 --restore out\<run>\undo-copy-multipliers.json --apply` puts every scope back as it was (it removes exactly the inserted rows).
- Production: a production datasource (source or target) needs `--allow-prod`, as everywhere. A partial source (row cap) is never loaded.

### 6.10 Report templates
`--template-export rules\export.json` writes the template files, row metadata and an upload rule; `--template-import rules\upload.json` previews a replacement (SHA-256 guarded) and `--apply` commits it; production needs `--allow-prod`. See `rules/template-export.example.json`, `template-import.example.json`, `template-upload.example.json`.

### 6.11 Interactive menu
`.\compare.ps1` with no arguments in a terminal opens a menu: recent runs, favourites, all rules, parameter prompts with type checks, export/limit/parallel options, validate-only and check-only modes, the equivalent command shown before running, typed confirmation for production and for `APPLY`, the result with verdict, and "Preview restore" when a rule suggests one. Keys: arrows, type to filter, `/` lists commands (`/rules /restore /open /history /datasources /help /quit`), Enter selects, Esc goes back. State: `out\.history.json` (no passwords).

### 6.12 What the console prints
```
compare-db-tool: trade-parity - 2 queries on mysql-oprdb02, postgres-dev, 1 comparison; export sample
  > legacy on mysql-oprdb02 ...
  OK  found 9,251 rows in legacy (mysql-oprdb02) in 4.2 s
  compare trades: 9,236 matched (9,000 equal, 236 differ), 15 only in legacy, 12 only in new
    finding: 12 of 12 missing rows exist on the other side under a different key ...
  expectations: FAIL (1 of 3 expectations not met)
    FAIL trades: different expected 0, got 236 (legacy and new should hold the same trades)
  SUGGESTED: restore with fix-prices (preview first) - ...
compare-db-tool: done in 6.1 s - exit 1 (differences, or an expectation was not met), expectations FAIL
  summary: out\trade-parity-...\SUMMARY.md
```
A step slower than 30 s prints "still running (...)" every 30 s with rows so far.

---

## 7. Reading results efficiently (for agents)

| File | What it answers |
|---|---|
| `SUMMARY.md` | status, verdict, row counts and timing per query, small results, findings, skipped, suggested restore |
| `FINDINGS.md` | ranked explanations with evidence (one section per comparison / funnel / presence) |
| `EXPECTATIONS.md` | each check, expected vs actual, reason; "not evaluated" items |
| `MANUAL_ACTIONS.md` | failed / blocked / skipped items and what to do |
| `CHECK.md` | result of `--check` |
| `<comparison>/SUMMARY.md` | matched / equal / different / only-source / only-target, breakdowns, classes of change |
| `<comparison>/only-source.csv`, `only-target.csv`, `differences.csv`, `duplicates.csv` | samples (all rows only with `full`) |
| `<funnel>/SUMMARY.md`, `diverging-keys.csv` (`dropped-keys.csv` for a chain) | the stage table, first divergence, profile |
| `<presence>/SUMMARY.md`, `presence-keys.csv` | combinations of sources, mismatching keys |
| `<query>.sql`, `<query>.md` | the SQL used and metadata (rows, columns, inputs from earlier queries); `<query>.csv` only with `full` |
| `RULES.xml`, `PARAMETERS.csv` | exactly what ran |

When you report to the user: state the verdict and exit code, the 3 most important numbers, the top finding with its evidence, what you would do next, and the exact command if a restore is suggested. Say plainly what was not verified (for example a PARTIAL run).

Cheapest investigation order: `--check` -> `--export none` -> read `SUMMARY.md` and `FINDINGS.md` -> add a drill-down (`uses="m=cmp:only-source"`) or a `<presence>` -> only then `full` for the keys you will read.

---

## 8. Recipes

**"Rows are missing in the new system"** -> section 6.1 plus the drill-down in 6.6. Return `updated_by` and a timestamp in the queries so findings can say *who* and *when*.

**"Where does this key exist?"** -> 6.4.

**"The totals differ; at which step does it go wrong?"** -> 6.5 (compare funnel) or the chain funnel with `group=` for a report pipeline.

**"Did the data change since it matched / did my fix work?"** -> run the same rule again; the verify rule of a restore does exactly that. Put the expected state in `<expect>`.

**"Is this a unit or format problem?"** -> findings `constant-ratio`, `constant-offset`, `sign-flip`, `FORMAT_ONLY`, `ROUNDING` in the comparison summary; fix with `absTol`, `relTol`, `round`, `dateOnly`, `trim`, `caseInsensitive` on the `<column>`.

**"Only look further if X"** -> `when=` on a follow-up query (6.7).

**"Dates for a cut-off day"** -> computed parameters and a `<check>` (6.7).

---

## 9. Pitfalls and fixes

| Message / symptom | Cause | Fix |
|---|---|---|
| `Cannot read rules XML ... (WstxParsingException)` | invalid XML: `--` in a comment, an unescaped `<` or `&`, unclosed tag | use CDATA for SQL, `&lt;` in attributes, no `--` in comments |
| `SQL contains a disallowed operation: REPLACE` (or `SET`, `INTO`, `LOAD`, `COPY`, `DO`, `CALL`, `EXEC`, `BEGIN`, `LOCK`, `ANALYZE`, ...) | the word stands where a statement, write, lock, file output or side-effecting function would (e.g. `INSERT ...`, `INTO OUTFILE`, `FOR UPDATE`, `nextval(...)`) | rewrite as a plain read; if it is only a name that was caught by mistake, quote it (`"name"`) - see section 5.3 |
| `Only SELECT/WITH queries are supported` / `single statement` | wrong first word or a second statement | one `SELECT`/`WITH` only |
| `Missing/invalid bind parameters` | `:name` not declared, or a typo | declare it in `<parameters>` |
| `Undeclared parameter override` | `--param` for a name not in the rule | declare the parameter |
| `Rule ids must be unique safe filenames` | duplicate or invalid id | unique ids; letters first |
| `Datasource X requires --allow-prod` | production datasource | ask the user; do not add the flag yourself |
| `Unknown datasource` | name not in `datasources.properties` | `--datasources` to list names |
| `':x.y' ... alias ... not declared` | chaining without `uses=` | `uses="x=query-id"` |
| `needs exactly one row` | scalar `:a.col` but the query returned 0 or many | use `:a.col[]` or make the query return one row |
| `cannot be split into batches safely` | a long list used with `GROUP BY`/`DISTINCT`/aggregate/`LIMIT` | raise `listBatch`, or aggregate after the lookup |
| `#x is not a parameter` | a variable name not available at that place | for `when` on a query, the id must be in `uses`/`after`; use `#q('id')` for hyphenated ids |
| `a type reference T(...) is not allowed` | restricted expressions | use the listed functions |
| `Comparison memory budget exceeded` | many keys in one partition | raise `--partitions` (up to 256) or `--max-partition-mb` |
| Exit 2 and `PARTIAL` | `--limit` or `maxRows` reached | rerun without the cap; do not compare partial data |
| A comparison fails `Column X is missing` | a compared column exists on one side only | alias it in SQL, map it with `source=`/`target=`, or `ignore` it |
| Queries are slow | large export or heavy SQL | `--check` shows the planner estimate; narrow the filter; `--export none` |
| Menu does not open | not a terminal | use `--menu` for numbered prompts, or run with arguments |

---

## 10. Writing rules that analyse well (agent playbook)

1. One rule per question; name it after the question; keep ids short and meaningful (`legacy`, `new`, `trades`).
2. Choose the key as the business identity (`trade_id`, or `client,product,trade_date`); check it is unique per side (the comparison reports duplicates).
3. Select only what you need, but include the audit and context columns that explain differences (`updated_by`, `created_timestamp`, `status`, `product_code`).
4. Pin the scope with parameters (a date or snapshot), so two runs mean the same thing; a moving scope makes every run differ.
5. Prefer the tool's analysis to reading rows: expectations for the verdict, findings for the why, presence for where, funnel for at which step, drill-down for follow-ups.
6. Put the known-good result into `<expect>` (including expected differences with `reason=`), so a future run proves the fix by itself.
7. For long investigations, add `<suggest>` so the next person is told which restore rule to preview.
8. Keep production runs explicit: the user passes `--allow-prod`; you propose the command.

---

## 11. Building and changing the tool

Java 21, Maven. `.\mvnw.cmd clean package` builds `target\compare-db-tool.jar` and runs all tests (in-memory H2 only, no network). Keep this file and `README.md` in sync: `GuideTests` validates the examples above, the flags and the function names. Do not commit `config/datasources.properties`, `out/` or `logs/`.
Details of every feature are also in `README.md`; open items are in `BACKLOG.md`.
