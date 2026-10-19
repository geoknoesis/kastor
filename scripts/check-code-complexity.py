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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, default=ROOT/'scripts/code-complexity-baseline.json')
    parser.add_argument('--write-baseline', action='store_true', help='Maintainer action: review every new ceiling in the diff')
    args = parser.parse_args()
    paths = set(subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z', '--', '*.kt'], cwd=ROOT,
    ).decode('utf-8').split('\0'))
    actual = {Path(p).as_posix(): metrics((ROOT/p).read_text(encoding='utf-8-sig'))
              for p in paths if '/src/main/' in Path(p).as_posix() and (ROOT/p).is_file()}
    if args.write_baseline:
        baseline = {'defaultLines': 600, 'defaultBranches': 40, 'files': {}}
        for path, measured in sorted(actual.items()):
            record = {}
            if measured['lines'] > baseline['defaultLines']: record['lines'] = measured['lines']
            large = {k:v for k,v in measured['functions'].items() if v > baseline['defaultBranches']}
            if large: record['functions'] = large
            if record:
                record['reason'] = 'Existing implementation or DSL; growth requires explicit review. Prefer extraction over raising the ceiling.'
                baseline['files'][path] = record
        args.baseline.write_text(json.dumps(baseline, indent=2)+'\n', encoding='utf-8')
    else:
        baseline = json.loads(args.baseline.read_text(encoding='utf-8'))
        try: check(actual, baseline)
        except ValueError as failure: raise SystemExit(str(failure)) from failure
    print(f'Complexity growth gate: {len(actual)} production Kotlin files checked')


if __name__ == '__main__':
    main()
