# Statement PDF comparison utilities

Standalone Python utilities copied from `D:\oga\tools`. They run independently of the Java compare-db-tool and do not access databases or send files anywhere. Use `python` on Windows.

| Script | Purpose |
|---|---|
| `pdf_to_text_zip.py` | Convert a PDF ZIP or folder into one smaller ZIP containing extracted text and an `index.csv` manifest. |
| `compare_statement_text.py` | Compare legacy and new text ZIPs directly, or compare extracted text folders, matching by client code. |
| `pick_pdfs.py` | Optionally select clients' PDFs (and Excel workbooks) from a larger ZIP or folder. |

## Requirements

Python 3.10 or later. Comparison and PDF selection use only the Python standard library.

PDF extraction needs one extractor. The script chooses the first available:

1. Poppler `pdftotext` on PATH (preferred; uses layout extraction).
2. PyMuPDF (`python -m pip install pymupdf`).
3. pypdf (`python -m pip install pypdf`).
4. pdfplumber (`python -m pip install pdfplumber`).

Convert both PDF sets using the **same extractor**, ideally on the same machine without changing installed extractors between runs. The selected extractor is printed and recorded in `index.csv`.

## Compare legacy against new

Run these commands from `D:\taomish\compare-db-tool`. Replace the input paths with your actual files:

```powershell
python python-utils\pdf_to_text_zip.py "C:\path\legacy_pdfs.zip" "legacy_text.zip"
python python-utils\pdf_to_text_zip.py "C:\path\new_pdfs.zip" "new_text.zip"

python python-utils\compare_statement_text.py "legacy_text.zip" "new_text.zip" --out "statement_comparison_01"
```

No manual ZIP extraction is necessary. A PDF folder can replace either PDF ZIP; an extracted text folder can replace either text ZIP.

Use a **new output folder** for each comparison. The comparer refuses an existing output folder so stale results cannot be mistaken for current differences. The PDF converter overwrites its named output ZIP, so choose distinct filenames for legacy and new.

### Matching and comparison scope

The client code is the filename stem before its first underscore, compared in uppercase:

- `AAC.txt` and `AAC_06-Nov-2026_11-10-51.txt` both match client `AAC`.
- Legacy defines the scope. For example, 165 legacy clients versus 623 new clients is supported.
- Extra new clients are listed as `ONLY_NEW`; they do not fail the comparison.
- Missing new files for legacy clients are flagged as `MISSING_NEW`.
- Multiple files for one client are accepted if their text matches after whitespace normalization. Conflicting duplicates for a compared client are rejected; select one run first. Duplicate files for extra new clients outside legacy scope do not block comparison. All skipped duplicates are recorded in `duplicate_files.csv`.
- `.raw.txt` sidecars and `index.csv` are excluded from statement comparison.

### Outputs

| Output | Contents |
|---|---|
| `summary.csv` | One row per client, status, input filenames and unmatched decimal-number occurrence counts. |
| `number_differences.csv` | Decimal values whose occurrence counts differ, with counts on both sides. Extra new clients are excluded. |
| `duplicate_files.csv` | Retained and skipped filenames, with the reason: matching normalized text or outside legacy scope. |
| `diffs\<CLIENT>.diff.txt` | Unified text diff for differing or missing legacy clients. Extra new clients get no diff file. |

| Status | Meaning |
|---|---|
| `TEXT_MATCH` | Same text after whitespace normalization and removal of blank/page-break lines. |
| `NUMBERS_MATCH_TEXT_DIFF` | Decimal values and occurrence counts match, but wording, row order or other text differs. |
| `NUMBERS_DIFFER` | At least one decimal value or its occurrence count differs. |
| `MISSING_NEW` | Legacy client has no corresponding new statement. |
| `ONLY_NEW` | Extra new client with no legacy reference; informational. |

Exit codes: `0` = all legacy clients have matching normalized text, allowing extra new clients; `1` = a legacy client differs or is missing; `2` = input/output error. Numeric-only matches with text differences still return `1` for review.

Decimal comparison treats `(1,234.50)` and `-1234.50` as the same number and includes zero. It compares values and repetition counts without a tolerance. Integer-only values, labels and dates are covered by the text comparison rather than the decimal-number comparison. Matching decimal values does not establish matching placement or ordering.

These utilities compare **extracted text**, not visual PDF appearance. Review rendering separately for fonts, columns, pagination and other layout differences. The comparer does not automatically verify extractor consistency or valuation dates; check the manifests and input selection.

## Select a smaller PDF set (optional)

```powershell
python python-utils\pick_pdfs.py "C:\path\all_statements.zip" "selected.zip" BFA NFO LIB
python python-utils\pick_pdfs.py "C:\path\all_statements.zip" "selected_with_excel.zip" --excel BFA NFO LIB
python python-utils\pdf_to_text_zip.py "selected.zip" "selected_text.zip"
```

Explicit client codes are recommended for a new investigation. Historical presets are also available:

- `--preset open`: UNF, ORM, ALG, MAO, SBC (the five open clients at the 12-Nov-2026 checkpoint; not a live status list).
- `--preset check`: the historical regression/control set.
- `--preset all`: all PDFs.

`--excel` also copies matching `.xlsx` files into an `excel/` folder in the output ZIP. Selection prints found and missing clients but does not compare their contents.

## Validation of this copy

The three copied scripts were checked against their source SHA-256 hashes and parsed for Python syntax. No PDF extraction or end-to-end comparison was run as part of copying them.
