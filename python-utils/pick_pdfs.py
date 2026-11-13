#!/usr/bin/env python
"""pick_pdfs.py - copy only the client statement PDFs you need out of a big zip (or folder) into one small zip for e-mail.

The client code is the part of the file name before the first underscore (AAC_06-Nov-2026_11-10-51.pdf -> AAC).
Usage (Windows):
    py pick_pdfs.py  <zip-or-folder>  <out.zip>  [--preset NAME]  [--excel]  [CLIENT ...]
Presets:
    open     the 5 clients still different from legacy:      UNF ORM ALG MAO SBC
    check    the 56 clients that cover every case of the 12-Nov change (BFA, the 33 rounding clients, the 12 FX swaps clients,
             the 5 open ones, 5 controls)
    all      every PDF in the input (just re-zips them)
Examples:
    py pick_pdfs.py "C:\\Users\\me\\Downloads\\client_statements.zip" five.zip --preset open
    py pick_pdfs.py statements.zip out.zip BFA NFO LIB          (any codes, upper or lower case)
    py pick_pdfs.py statements.zip out.zip --preset check --excel     (also copies each client's .xlsx from the excel folder)
Prints which codes were found, which were missing and the size of the result. Nothing is sent anywhere.
"""
import os, sys, zipfile, tempfile, shutil

PRESETS = {
    'open': 'UNF ORM ALG MAO SBC',
    'check': ('BFA AMC AQC AST AXI BFE BFL BOV BUE CFF CGE CTS CWE DAI DFE DYE FAB FMP GIL HSD IOL JUP LIB MOA MSF NFO PGE RWF SCF SIC '
              'TVE UDA VBN WSF AAE GBS MFA OBC OBT OGB PAD SFX SOR TSD WBS SBC ACM ALG MAO ORM UNF AAC AAI AAL CAD CFR'),
}


def code_of(name):
    return os.path.basename(name).split('_')[0].upper()


def main(argv):
    if len(argv) < 3 or argv[1] in ('-h', '--help'):
        print(__doc__)
        return 2
    src, out = argv[1], argv[2]
    rest = argv[3:]
    wanted, preset, excel = [], None, False
    i = 0
    while i < len(rest):
        a = rest[i]
        if a == '--preset':
            preset = rest[i + 1].lower(); i += 2; continue
        if a == '--excel':
            excel = True; i += 1; continue
        wanted.append(a.upper()); i += 1
    if preset:
        if preset == 'all':
            wanted = None
        elif preset in PRESETS:
            wanted = PRESETS[preset].split() + (wanted or [])
        else:
            print('unknown preset', preset, '- use open, check or all'); return 2
    elif not wanted:
        print('give client codes or --preset'); return 2
    work = None
    folder = src
    if os.path.isfile(src) and src.lower().endswith('.zip'):
        work = tempfile.mkdtemp(prefix='pick_pdfs_')
        with zipfile.ZipFile(src) as z:
            z.extractall(work)
        folder = work
    pdfs, xlsx = {}, {}
    for root, _, files in os.walk(folder):
        for f in files:
            low = f.lower()
            if low.endswith('.pdf'):
                pdfs.setdefault(code_of(f), []).append(os.path.join(root, f))
            elif low.endswith('.xlsx') and excel:
                xlsx.setdefault(code_of(f), []).append(os.path.join(root, f))
    codes = sorted(pdfs) if wanted is None else sorted(set(wanted))
    found = [c for c in codes if c in pdfs]
    missing = [c for c in codes if c not in pdfs]
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for c in found:
            for p in pdfs[c]:
                z.write(p, os.path.basename(p))
            for p in xlsx.get(c, []):
                z.write(p, 'excel/' + os.path.basename(p))
    if work:
        shutil.rmtree(work, ignore_errors=True)
    size = os.path.getsize(out) / 1024 / 1024
    print(f'found {len(found)} of {len(codes)} clients -> {os.path.abspath(out)} ({size:.1f} MB)')
    if missing:
        print('NOT in the input:', ' '.join(missing))
    if size > 20:
        print('NOTE: over 20 MB; use a smaller preset or split the list in two.')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
