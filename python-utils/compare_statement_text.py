#!/usr/bin/env python
"""Compare two pdf_to_text_zip.py outputs (ZIPs or extracted folders).

python tools/compare_statement_text.py legacy_text.zip new_text.zip --out comparison

Matches client codes before the first underscore, ignoring filename timestamps.
Ignores .raw.txt sidecars. Rejects duplicate clients instead of choosing a run.
Legacy defines the comparison scope; extra new clients are informational only.
Writes summary.csv, number_differences.csv and per-client text diffs.
Numbers compare decimal values AND occurrence counts, including zero. Numeric
agreement does not establish matching placement, labels, order or PDF layout.
Exit codes: 0 = normalized text matches for every legacy client (extras allowed);
1 = differences or missing legacy clients; 2 = error.
"""
import argparse
import csv
import difflib
import re
import sys
import zipfile
from collections import Counter
from decimal import Decimal
from pathlib import Path, PurePosixPath


NUMBER = re.compile(r"(?<![\w.])(?:\(\s*[+-]?\d[\d,]*\.\d+\s*\)|[+-]?\d[\d,]*\.\d+)(?![\w.])")


def read_bundle(source):
    records = {}

    def add(name, data):
        base = PurePosixPath(name.replace('\\', '/')).name
        if not base.lower().endswith('.txt') or base.lower().endswith('.raw.txt'):
            return
        code = base[:-4].split('_', 1)[0].upper()
        if not re.fullmatch(r'[A-Z0-9-]+', code):
            raise ValueError(f'Unsupported client filename: {name}')
        if code in records:
            raise ValueError(f'Duplicate client {code} in {source}: {records[code][0]} and {name}')
        content = data.decode('utf-8-sig')
        if not content.strip():
            raise ValueError(f'Empty statement text: {source}: {name}')
        records[code] = (name, content)

    if source.is_dir():
        for path in sorted(source.rglob('*.txt')):
            add(str(path.relative_to(source)), path.read_bytes())
    else:
        with zipfile.ZipFile(source) as bundle:
            for info in bundle.infolist():
                if not info.is_dir():
                    add(info.filename, bundle.read(info))
    if not records:
        raise ValueError(f'No statement .txt files found in {source}')
    return records


def lines(text):
    # Preserve row order and wording, ignore spacing and blank/page-break lines.
    return [' '.join(line.split()) for line in text.splitlines() if line.strip()]


def numbers(text):
    values = Counter()
    for match in NUMBER.finditer(text):
        token = match.group().replace(',', '').strip()
        value = Decimal(token.strip('()').strip())
        if token.startswith('('):
            value = -abs(value)
        values[value] += 1
    return values


def write_csv(path, header, rows):
    with path.open('w', encoding='utf-8-sig', newline='') as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('legacy', type=Path)
    parser.add_argument('new', type=Path)
    parser.add_argument('--out', type=Path, default=Path('statement_text_comparison'))
    args = parser.parse_args()
    legacy, new = read_bundle(args.legacy), read_bundle(args.new)
    # Refuse reuse so old diff files cannot look like current results.
    args.out.mkdir(parents=True, exist_ok=False)
    diff_dir = args.out / 'diffs'
    diff_dir.mkdir()
    summary, numeric, counts = [], [], Counter()
    for code in sorted(legacy.keys() | new.keys()):
        old_name, old_text = legacy.get(code, ('', ''))
        new_name, new_text = new.get(code, ('', ''))
        a, b = numbers(old_text), numbers(new_text)
        old_only, new_only = a - b, b - a
        if code not in legacy:
            status = 'ONLY_NEW'
            # No legacy reference exists; this is coverage, not a difference.
            old_only, new_only = Counter(), Counter()
        elif code not in new:
            status = 'MISSING_NEW'
        elif lines(old_text) == lines(new_text):
            status = 'TEXT_MATCH'
        elif a == b:
            status = 'NUMBERS_MATCH_TEXT_DIFF'
        else:
            status = 'NUMBERS_DIFFER'
        counts[status] += 1
        summary.append([code, status, old_name, new_name,
                        sum(old_only.values()), sum(new_only.values())])
        for value in sorted(old_only.keys() | new_only.keys()):
            numeric.append([code, format(value, 'f'), a[value], b[value]])
        if status not in ('TEXT_MATCH', 'ONLY_NEW'):
            diff = difflib.unified_diff(lines(old_text), lines(new_text),
                                        fromfile=f'legacy/{old_name}', tofile=f'new/{new_name}',
                                        lineterm='')
            (diff_dir / f'{code}.diff.txt').write_text('\n'.join(diff) + '\n', encoding='utf-8')
    write_csv(args.out / 'summary.csv',
              ['client', 'status', 'legacy_file', 'new_file', 'legacy_only_numbers', 'new_only_numbers'], summary)
    write_csv(args.out / 'number_differences.csv',
              ['client', 'value', 'legacy_occurrences', 'new_occurrences'], numeric)
    print(f'Legacy: {len(legacy)} clients; new: {len(new)} clients')
    for status, count in sorted(counts.items()):
        print(f'{status}: {count}')
    print(f'Results: {args.out.resolve()}')
    print('Legacy defines comparison scope; ONLY_NEW clients are informational and do not fail the comparison.')
    print('Number comparison covers decimal amounts/rates; text diffs also retain integers and labels.')
    print('Use the same extractor for both PDF sets; text comparison does not verify visual layout.')
    return int(any(status not in ('TEXT_MATCH', 'ONLY_NEW') for status in counts))


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        sys.exit(2)
