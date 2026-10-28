# compare-db-tool

Java 21 / Spring Boot CLI for XML-driven SQL execution, CSV export, key-based comparison and ZIP packaging.
Each `<sql db="...">` references an independently configured JDBC datasource. Queries can use the same database,
two instances of the same database type, or different database types. MySQL and PostgreSQL drivers are included.

## Build and start

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

The code and tests were written without executing a build/test after the user's instruction to leave execution to them.
Remote connectivity, JDBC streaming and real query results remain unverified.

## External datasource file

The launcher uses `JAVA_HOME\bin\java.exe` if JAVA_HOME is set, otherwise `java.exe` from PATH. Set JAVA_HOME to the
installed JDK 21 directory on each machine; the launcher does not replace it with a developer-specific path.

The application imports `config/datasources.properties`, relative to its working directory. The PowerShell wrapper
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
All SQL rules execute once, in declaration order, followed by comparisons, then ZIP packaging.
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
are ambiguous and excluded from matched/only-side counts; they never count as equal. Original rows remain in query CSVs.

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

Query results are written incrementally. PostgreSQL uses non-autocommit transactions and fetch size; MySQL pools
enable cursor fetching/server prepared statements. Other JDBC drivers may implement fetching differently.
Comparisons hash-partition exported rows to temporary CSVs and compare one partition at a time, then remove the
temporary files. Default: 64 partitions; use `--partitions 128` or `256` for larger/wider data. Temporary disk space is
required in the run directory. Very wide rows or uneven partition sizes may still require more heap or partitions.
No throughput or fixed-memory target has been benchmarked.

Queries execute sequentially in separate transactions and do not share a synchronized database snapshot. For
repeatable comparisons, use stable source data and the same business cut-off parameters on both sides.

## Read-only operation

Only single SELECT/WITH statements pass the conservative SQL guard. Write operations, SELECT INTO, writable CTEs,
multiple statements, executable/nested comments and ambiguous quoting are rejected. Use bound parameters rather
than backslash or dollar quoting. Use whitespace after `--` comments. The guard may conservatively reject unusual
vendor syntax. SQL syntax still has to be valid for the selected database.

Connections use JDBC read-only mode and rollback after each query. PostgreSQL and MySQL also receive session
read-only settings. Use SELECT-only database accounts: arbitrary stored functions and all JDBC dialects cannot be
sandboxed by a lexical SQL guard or advisory JDBC flags alone. There is no universal JDBC guarantee of read-only
enforcement for every database. The tool never deliberately issues data-changing SQL to source databases.

Datasources with `prod=true` or 'prod' in their name require explicit `--allow-prod` during execution. Validation and
listing never connect. The supplied PROD datasource is marked accordingly. Confirm environment flags for any other
datasources you add. Console errors omit raw driver exception details and secrets.

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
