#!/usr/bin/env python
"""pdf_to_text_zip.py - turn a folder (or zip) of client statement PDFs into ONE small zip of text, so it can be e-mailed.

Why: the statement comparison only needs the printed text and numbers, not the PDF files. 165 PDFs are ~50 MB; their text is ~1-3 MB zipped.
The text keeps the page layout (columns stay on one line), exactly what the comparer reads.

Usage (Windows):
    py pdf_to_text_zip.py  <folder-or-zip-with-pdfs>  [output.zip]
Examples:
    py pdf_to_text_zip.py "C:\\Users\\me\\Downloads\\client_statements_2026-11-06.zip" statements_text.zip
    py pdf_to_text_zip.py "C:\\statements\\pdf"

Needs ONE of these text extractors (it uses the first one found, in this order):
    1. pdftotext (Poppler) on PATH            <- preferred, same as our reference output
    2. PyMuPDF      (pip install pymupdf)
    3. pypdf        (pip install pypdf)
    4. pdfplumber   (pip install pdfplumber)
Nothing is sent anywhere; the script only reads the PDFs and writes the output zip.
Output zip contains <pdf name>.txt per PDF (pages separated by a form feed) and index.csv (file, pages, chars, extractor, sha256 of the PDF).
"""
import csv, hashlib, io, os, shutil, subprocess, sys, tempfile, zipfile


def find_extractor():
    if shutil.which("pdftotext"):
        return "pdftotext"
    for mod, name in (("fitz", "pymupdf"), ("pypdf", "pypdf"), ("pdfplumber", "pdfplumber")):
        try:
            __import__(mod)
            return name
        except ImportError:
            pass
    return None


def extract(path, how):
    """Return the text of the whole PDF, pages separated by \\f."""
    if how == "pdftotext":
        out = subprocess.run(["pdftotext", "-layout", path, "-"], capture_output=True)
        if out.returncode != 0:
            raise RuntimeError(out.stderr.decode("utf-8", "replace")[:200])
        return out.stdout.decode("utf-8", "replace")
    if how == "pymupdf":
        import fitz
        with fitz.open(path) as doc:
            return "\f".join(page.get_text("text", sort=True) for page in doc) + "\f"
    if how == "pypdf":
        import pypdf
        reader = pypdf.PdfReader(path)
        return "\f".join((p.extract_text(extraction_mode="layout") or "") for p in reader.pages) + "\f"
    if how == "pdfplumber":
        import pdfplumber
        with pdfplumber.open(path) as pdf:
            return "\f".join((p.extract_text(layout=True) or "") for p in pdf.pages) + "\f"
    raise RuntimeError("no extractor")


def collect(source, workdir):
    """List (display name, path) of every PDF in a folder or inside a zip (zip is unpacked into workdir)."""
    if os.path.isfile(source) and source.lower().endswith(".zip"):
        with zipfile.ZipFile(source) as z:
            z.extractall(workdir)
        source = workdir
    found = []
    for root, _, files in os.walk(source):
        for f in sorted(files):
            if f.lower().endswith(".pdf"):
                found.append((f, os.path.join(root, f)))
    return sorted(found)


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        return 2
    source = sys.argv[1]
    target = sys.argv[2] if len(sys.argv) > 2 else "statements_text.zip"
    how = find_extractor()
    if not how:
        print("ERROR: no PDF text extractor found. Install one:  py -m pip install pymupdf   (or install Poppler for pdftotext)")
        return 1
    with tempfile.TemporaryDirectory() as tmp:
        pdfs = collect(source, tmp)
        if not pdfs:
            print("ERROR: no PDF files found in", source)
            return 1
        print(f"extractor: {how}; {len(pdfs)} PDFs")
        rows, failed = [], []
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for i, (name, path) in enumerate(pdfs, 1):
                try:
                    text = extract(path, how)
                except Exception as e:  # keep going, report at the end
                    failed.append((name, str(e)[:120]))
                    continue
                with open(path, "rb") as fh:
                    digest = hashlib.sha256(fh.read()).hexdigest()
                z.writestr(os.path.splitext(name)[0] + ".txt", text)
                if how == "pdftotext":   # reading-order pass, used to compare the row order
                    raw = subprocess.run(["pdftotext", "-raw", path, "-"], capture_output=True).stdout.decode("utf-8", "replace")
                    z.writestr(os.path.splitext(name)[0] + ".raw.txt", raw)
                rows.append((name, text.count("\f"), len(text), how, digest))
                if i % 25 == 0:
                    print(f"  {i}/{len(pdfs)}")
            buf = io.StringIO()
            w = csv.writer(buf)
            w.writerow(["file", "pages", "chars", "extractor", "pdf_sha256"])
            w.writerows(rows)
            z.writestr("index.csv", buf.getvalue())
    size = os.path.getsize(target)
    print(f"done: {len(rows)} text files, {size/1024/1024:.2f} MB -> {os.path.abspath(target)}")
    if failed:
        print("FAILED:", len(failed))
        for n, e in failed:
            print("  ", n, e)
        return 1
    if size > 20 * 1024 * 1024:
        print("NOTE: larger than 20 MB; split the input folder in two and run twice.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
