# compare-db-tool

Java 21 / Spring Boot CLI for XML-driven SQL execution, CSV export, key-based comparison and ZIP packaging.
Each `<sql db="...">` references an independently configured JDBC datasource. Queries can use the same database,
two instances of the same database type, or different database types. MySQL and PostgreSQL drivers are included.

The Spring Boot version is 4.1.2. Java source/bytecode targets 21; the launcher uses your JAVA_HOME or PATH and can
run on a newer JDK, including Java 27. The runtime Java and Spring Boot versions are recorded in the log at startup.

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
