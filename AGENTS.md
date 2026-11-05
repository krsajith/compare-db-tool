# Instructions for AI coding agents (Codex, Claude Code, Cursor, Copilot, ...)

## If you are asked to USE this tool (find a data issue, compare, explain, restore)

Read **all of HOW-TO-USE.md** first (every section, not only the top: the "READ THIS FIRST" checklist and sections 5, 6 and 10 decide whether a rule works). It is self-contained: the working loop, every command-line flag, every rule element with examples, how to read the results, recipes and the common errors.
The short version:

1. Write an XML rule in `rules/` (SQL in `<![CDATA[...]]>`, parameters, `<compare>` / `<presence>` / `<funnel>`, and `<expect>` so the tool says PASS or FAIL).
2. `--validate` (offline) then `--check` (asks the databases, reads nothing) then run with `--export none`, then read `SUMMARY.md` and `FINDINGS.md` of the output folder.
3. Windows: `.\compare.ps1 --rules rules\x.xml ...`. Elsewhere: `java -jar target/compare-db-tool.jar --rules rules/x.xml ...`. Exit codes: 0 ok, 1 differences or an expectation not met, 2 error or PARTIAL.

Critical for every rule: a standalone `<sql>` keeps its rows only with `export="full"` (sample keeps none); add `<expect>` and, if the result is sent, `<compress>`; `--validate` and `--check` print `advice:` lines, read them. The export flag is `none|sample|full` (no `all`).

Hard rules: never pass `--allow-prod` or `--apply` unless the user asked for it; a restore is previewed first; do not read or edit `config/datasources.properties`; SQL is one `SELECT`/`WITH`
with bound `:parameters`; never write `--` inside an XML comment.

## If you are asked to CHANGE this tool

- Java 21, Maven. `.\mvnw.cmd clean package` (or `mvn test`) must pass. All tests use in-memory H2 only; no network, no real databases.
- Keep `README.md` and `HOW-TO-USE.md` in sync with behaviour. `GuideTests` validates the examples, flags and function names in `HOW-TO-USE.md`; update both when you add a feature.
- Add tests with the feature. Rule files in `rules/` are all validated by the suite; XML comments must not contain `--`.
- Never commit `config/datasources.properties`, `out/`, `logs/` or credentials. Open items are in `BACKLOG.md`.
