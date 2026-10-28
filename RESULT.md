# Coding handoff

## Smart output stage 2

Implemented bounded datasource lanes, one active query per datasource, dependency-ready comparisons, identical-SQL
reuse with per-alias export/cap metadata, and estimated partition/group memory accounting (default 256 MB).
PASS: JDK 21 clean package, 42 tests, 0 failures/errors; all eight shipped XML files validate using placeholder
datasources and no source connections. Synthetic default mail ZIP: 4,120 bytes. Stage 2 tests cover concurrent H2
queries plus failure isolation, early comparison, actual single execution of reused SQL, mixed exports, different
row caps, sequential ordering and a clear memory-budget failure. Final build results
and stage commit IDs are recorded in D:/oga/analysis/result-compare-tool-smart-output.md. Optional funnel rule type
is skipped; the existing single-SQL funnel XML remains supported unchanged. No remote performance/ZIP-size claim.

## Smart output stage 1

PASS: JDK 21 mvnw clean package, 36 tests, 0 failures/errors. Safe-config validation of grasa-backup-cfs.xml:
11 queries, 7 comparisons, 1 ZIP; no source connections or private datasource file inspection.
Added none/sample/full exports (default sample), capped comparison files with true totals, streaming breakdown counts,
accepted-change aggregation, compact ZIP selection and byte-size reporting. Fixed a stale six-vs-seven assertion
after the shipped GRASA rule changed. Full/none fixture counts/classifications match; synthetic mail ZIP is below 1 MB.
Actual remote GRASA ZIP size remains unmeasured. Stage 2 follows in a separate commit.

## Logging update

Execution now continues after per-query/comparison failures, retains successful CSVs, and produces MANUAL_ACTIONS.md
with datasource/reason/SQL/manual follow-up. Partial query files are marked and skipped comparisons are reported.
ZIP packaging continues using available artifacts/diagnostic notes. Any such issue returns exit 2. Added an isolated
H2 workflow regression test for failure continuation; it has not been run.

Added Spring Boot Logback configuration with Boot console patterns, rolling application/error files, external
config/logging.properties, SLF4J stage/progress logs and full retained exception cause chains. XML logs identify the
element/line/column and runtime logs identify Java/Spring versions. Encoders redact registered secrets and JDBC URLs.
Added logging/redaction tests. Logging code, configuration initialization and tests have NOT been executed.

The project is published to the configured GitHub remote; the original handoff below predates publication.

## Original coding handoff

Status: IMPLEMENTED; NOT BUILT OR TESTED, at the user's explicit request.

Implemented XML rules, external named JDBC datasources, typed bind parameters, SELECT/WITH guard, lazy read-only
connection pools, streaming UTF-8 CSV export, disk-partitioned key matching, duplicate/missing/schema diagnostics,
normalization/tolerances, bounded summaries, selected-ID ZIP packaging, CLI options and PowerShell launcher.

Added example/connection-check XML rules, portable datasource example, README, and tests using isolated in-memory H2.
Remote databases have not been contacted. No commits or pushes.

The initial dependency-resolution command started before the user's no-run instruction finished with exit code 0;
no build, application, tests, or database queries were executed. No further execution was performed after that instruction.

Files: pom.xml; .mvn/wrapper/maven-wrapper.properties; src/main/resources/application.properties;
src/main/java/com/taomish/comparedbtool/*.java; src/test/java/com/taomish/comparedbtool/*.java;
config/datasources.properties (local, gitignored); config/datasources.example.properties; rules/*.xml;
compare.ps1; README.md; RESULT.md. Existing .gitignore already excludes local datasource configuration and out/.

Next commands (PowerShell):

```powershell
cd D:\taomish\compare-db-tool
$env:JAVA_HOME='D:\software\jdk-21.0.7'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd clean package
.\compare.ps1 --rules rules\connection-check.xml --validate
# Set the two required password environment variables before the next command.
.\compare.ps1 --rules rules\connection-check.xml
```

Real trade-query table names in rules/example.xml must be replaced. Supply datasource passwords through the
environment. Compilation, test results, actual JDBC behavior, remote connectivity, throughput and memory bounds
remain unverified. Automatic SCALE inference, funnel/probe diagnostics and report-file inputs are deferred.
