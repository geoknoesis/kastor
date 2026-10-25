"""Ratchet production Kotlin file length and lexical branch counts; strings/comments do not count.

This is a small growth gate, not a substitute for design review or a full Kotlin semantic analyzer.
Existing large declarations have explicit per-file/function ceilings in the reviewed baseline.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OPAQUE = re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', re.S)
FUNCTION = re.compile(r'(?m)^([ \t]*)(?:(?:private|internal|public|protected|override|inline|suspend|tailrec|operator|infix|open|final)\s+)*fun\s+(?:<[^\n]+?>\s+)?([\w.<>?]+)\s*\(')
BRANCH = re.compile(r'\b(?:if|for|while|catch|when)\b|&&|\|\||->')


def masked(source):
    return OPAQUE.sub(lambda m: re.sub(r'[^\n]', ' ', m.group()), source)


def matching(text, at, opening, closing):
    depth = 0
    for i in range(at, len(text)):
        if text[i] == opening: depth += 1
        elif text[i] == closing:
            depth -= 1
            if depth == 0: return i
    return len(text) - 1


def metrics(source):
    clean = masked(source)
    functions = {}
    for match in FUNCTION.finditer(clean):
        params_end = matching(clean, match.end()-1, '(', ')')
        signature = re.sub(r'\s+', '', clean[match.start():params_end+1])
        suffix = hashlib.sha256(signature.encode()).hexdigest()[:12]
        start = params_end + 1
        first = re.search(r'[={]', clean[start:])
        if first is None: continue
        start += first.start()
        if clean[start] == '{':
            end = matching(clean, start, '{', '}') + 1
        else:
            # Expression bodies end before the next declaration at the same or lower indentation.
            end = len(clean)
            for following in FUNCTION.finditer(clean, start):
                if len(following.group(1)) <= len(match.group(1)):
                    end = following.start(); break
        key = match.group(2) + ':' + suffix
        functions[key] = len(BRANCH.findall(clean[start:end]))
    return {'lines': len(source.splitlines()), 'functions': functions}


def check(actual, baseline):
    failures = []
    for path, measured in actual.items():
        limits = baseline.get('files', {}).get(path, {})
        line_limit = limits.get('lines', baseline['defaultLines'])
        if measured['lines'] > line_limit:
            failures.append(f'{path}: {measured["lines"]} lines exceeds {line_limit}')
        for name, branches in measured['functions'].items():
            maximum = limits.get('functions', {}).get(name, baseline['defaultBranches'])
            if branches > maximum:
                failures.append(f'{path}::{name}: {branches} branch tokens exceeds {maximum}')
    if failures: raise ValueError('\n'.join(failures))


def parse_raises(values):
    """Parse `--raise PATH=REASON` values; every raise must carry a non-empty reason."""
    raises = {}
    for value in values:
        path, separator, reason = value.partition('=')
        if not separator or not path.strip() or not reason.strip():
            raise ValueError(f'--raise needs PATH=REASON, got {value!r}')
        raises[path.strip()] = reason.strip()
    return raises


def write_baseline(actual, previous, raises):
    """Rebuild the baseline: ceilings only fall unless `raises` names the file with a reviewed reason.

    Returns the new baseline and a human-readable summary. Raises ValueError for unapproved growth,
    for a new over-limit file or function, and for a raise that names a file that is not measured.
    """
    default_lines, default_branches = previous['defaultLines'], previous['defaultBranches']
    old_files = previous.get('files', {})
    unknown = sorted(set(raises) - set(actual))
    if unknown: raise ValueError('--raise names files that are not measured: ' + ', '.join(unknown))
    files, summary, refused = {}, [], []

    def ceiling(path, label, measured, old):
        if old is not None and measured < old: summary.append(f'lowered {path}: {label} {old} -> {measured}')
        if old is None or measured > old:
            before = 'new' if old is None else old
            if path in raises: summary.append(f'RAISED {path}: {label} {before} -> {measured} ({raises[path]})')
            else: refused.append(f'{path}: {label} {before} -> {measured}')
        return measured

    for path, measured in sorted(actual.items()):
        limits = old_files.get(path, {})
        record = {}
        if measured['lines'] > default_lines:
            record['lines'] = ceiling(path, 'lines', measured['lines'], limits.get('lines'))
        large = {name: ceiling(path, name, branches, limits.get('functions', {}).get(name))
                 for name, branches in sorted(measured['functions'].items()) if branches > default_branches}
        if large: record['functions'] = large
        if record:
            record['reason'] = raises.get(path) or limits.get('reason') or 'Growth requires explicit review.'
            files[path] = record
        elif path in old_files:
            summary.append(f'removed {path}')
    summary.extend(f'removed {path}' for path in old_files if path not in actual)
    if refused:
        raise ValueError('Ceilings would grow; split the code, or pass --raise PATH=REASON after review:\n'
                         + '\n'.join(refused))
    return {'defaultLines': default_lines, 'defaultBranches': default_branches, 'files': files}, summary


def slack(actual, baseline):
    """List ceilings above the measured value, so shrinking files can be ratcheted down."""
    notes = []
    for path, limits in baseline.get('files', {}).items():
        measured = actual.get(path)
        if measured is None:
            notes.append(f'{path}: no longer measured'); continue
        if 'lines' in limits and measured['lines'] < limits['lines']:
            notes.append(f'{path}: lines ceiling {limits["lines"]}, measured {measured["lines"]}')
        for name, maximum in limits.get('functions', {}).items():
            branches = measured['functions'].get(name)
            if branches is None: notes.append(f'{path}::{name}: no longer measured')
            elif branches < maximum: notes.append(f'{path}::{name}: ceiling {maximum}, measured {branches}')
    return notes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, default=ROOT/'scripts/code-complexity-baseline.json')
    parser.add_argument('--write-baseline', action='store_true',
                        help='Maintainer action: lower ceilings to the measured values and drop compliant files. '
                             'Growth and new entries are refused unless named by --raise.')
    parser.add_argument('--raise', dest='raises', action='append', default=[], metavar='PATH=REASON',
                        help='With --write-baseline: accept growth of one file, recording the reviewed reason.')
    args = parser.parse_args()
    if args.raises and not args.write_baseline: parser.error('--raise requires --write-baseline')
    paths = set(subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z', '--', '*.kt'], cwd=ROOT,
    ).decode('utf-8').split('\0'))
    actual = {Path(p).as_posix(): metrics((ROOT/p).read_text(encoding='utf-8-sig'))
              for p in paths if '/src/main/' in Path(p).as_posix() and (ROOT/p).is_file()}
    baseline = json.loads(args.baseline.read_text(encoding='utf-8'))
    try:
        if args.write_baseline:
            baseline, summary = write_baseline(actual, baseline, parse_raises(args.raises))
            # Binary write: keep LF on Windows, where text mode would translate to CRLF.
            args.baseline.write_bytes((json.dumps(baseline, indent=2) + '\n').encode('utf-8'))
            print('\n'.join(summary) or 'Baseline unchanged')
        else:
            check(actual, baseline)
            loose = slack(actual, baseline)
            if loose:
                print(f'{len(loose)} ceilings are above the measured value; ratchet them down with --write-baseline:')
                print('\n'.join('  ' + note for note in loose))
    except ValueError as failure: raise SystemExit(str(failure)) from failure
    print(f'Complexity growth gate: {len(actual)} production Kotlin files checked')


if __name__ == '__main__':
    main()
