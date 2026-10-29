Place original HTML, DOCX or XLSX template files in this directory. The utility
reads their raw bytes; do not paste CSV-exported or base64-encoded content here.

For the export/edit/upload workflow, copy `rules/template-export.example.json`,
fill in the requested names or UUIDs and target details, then run:

```powershell
.\compare.ps1 --template-export rules\my-template-export.json
```

The command prints a new `templates/export-...` directory containing the original
files, `row.json` for each row, and an automatically generated `upload-rule.json`.
Edit the template files and run:

```powershell
.\compare.ps1 --template-import templates\export-...\upload-rule.json
.\compare.ps1 --template-import templates\export-...\upload-rule.json --apply
```

Replace `export-...` with the printed folder name. The generated rule updates file
content by UUID and tenant, preserves template metadata and checks that the DB
content has not changed since export. `row.json` records metadata for reference;
editing that snapshot alone does not alter the upload rule. Re-export after a
successful upload before the next edit.

`file` in a template upload rule is relative to this directory, including any
subdirectories. Files outside this directory are rejected.

Applied replacements save the previous bytes, metadata and a `restore.sql` file
under `.backups/`. Keep these local backups until you have verified rendering.
Backups are gitignored and may contain tenant-specific template content.

Use `--template-import rules\my-templates.json` for one or more files. Start from
`rules/template-import.example.json`: set shared target details and list each
file plus its metadata in the `templates` array. Mode `upsert` inserts missing
templates and updates existing ones; `create` and `update` enforce either action.
Imports preview by default; add `--apply` to commit all entries together.
