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
# Annotations (`@JvmStatic`, `@Throws(X::class)`; string arguments are already blanked) and modifiers may precede
# `fun` on the declaration line or on the lines above it.
ANNOTATION = r'@[\w.:]+(?:\([^()]*\))?'
MODIFIER = (r'private|internal|public|protected|override|inline|suspend|tailrec|operator|infix|open|final|abstract'
            r'|actual|expect|external')
FUNCTION = re.compile(r'(?m)^(?P<indent>[ \t]*)(?:(?:' + ANNOTATION + '|' + MODIFIER + r')\s+)*'
                      r'(?P<fun>fun)\s+(?:<[^\n]+?>\s+)?(?P<name>[\w.<>?]+)\s*\(')
BRANCH = re.compile(r'\b(?:if|for|while|catch|when)\b|&&|\|\||->')
# A literal's last character becomes this marker, so `x ?: "text"` still ends a line with an operand.
LITERAL = '\x00'
CHAR = re.compile(r"'(?:\\[^\n]|[^'\\\n])*'")


class _Masker:
    """Blank comments, literal text and backtick names, keeping string-template expressions as code.

    `"${if (a) x else y}"` contributes its `if`: a template expression is code, and masking the whole literal let
    any amount of branching hide inside strings. Offsets and newlines are preserved.
    """

    def __init__(self, source):
        self.src, self.out = source, list(source)

    def blank(self, start, end):
        for k in range(start, end):
            if self.out[k] != '\n': self.out[k] = ' '

    def code(self, i, template=False):
        """Scan code from `i`; inside a template, stop at the unmatched `}` and return its index."""
        src, depth = self.src, 0
        while i < len(src):
            c = src[i]
            if src.startswith('//', i):
                end = src.find('\n', i); end = len(src) if end < 0 else end
                self.blank(i, end); i = end
            elif src.startswith('/*', i):
                i = self.comment(i)
            elif src.startswith('"""', i):
                i = self.string(i, '"""')
            elif c == '"':
                i = self.string(i, '"')
            elif c == "'":
                literal = CHAR.match(src, i)
                end = literal.end() if literal else i + 1
                if literal: self.blank(i, end); self.out[end - 1] = LITERAL
                i = end
            elif c == '`':
                end = src.find('`', i + 1)
                end = len(src) if end < 0 or '\n' in src[i:end] else end + 1
                self.blank(i, end); i = end
            elif template and c == '{':
                depth += 1; i += 1
            elif template and c == '}':
                if depth == 0: return i
                depth -= 1; i += 1
            else:
                i += 1
        return i

    def comment(self, i):
        """Kotlin block comments nest."""
        src, depth, start = self.src, 0, i
        while i < len(src):
            if src.startswith('/*', i): depth += 1; i += 2
            elif src.startswith('*/', i):
                depth -= 1; i += 2
                if depth == 0: break
            else: i += 1
        self.blank(start, i)
        return i

    def string(self, i, quote):
        src, start = self.src, i
        i += len(quote)
        while i < len(src):
            if src.startswith(quote, i):
                # A raw string may end with extra quotes (`""""` is a quote followed by the terminator).
                end = i + len(quote)
                while quote == '"""' and end < len(src) and src[end] == '"': end += 1
                self.blank(start, end)
                self.out[end - 1] = LITERAL
                return end
            if quote == '"' and src[i] == '\\':
                i += 2
            elif quote == '"' and src[i] == '\n':
                break  # unterminated single-line literal: stop at the line end
            elif src.startswith('${', i):
                self.blank(start, i + 2)
                close = self.code(i + 2, template=True)
                start = min(close + 1, len(src))
                self.blank(close, start)
                i = start
            else:
                i += 1
        self.blank(start, i)
        return i


def masked(source):
    masker = _Masker(source)
    masker.code(0)
    return ''.join(masker.out)


def matching(text, at, opening, closing):
    depth = 0
    for i in range(at, len(text)):
        if text[i] == opening: depth += 1
        elif text[i] == closing:
            depth -= 1
            if depth == 0: return i
    return len(text) - 1


NEXT_DECLARATION = re.compile(r'\n[ \t]*(?:[}@]|(?:\w+[ \t]+)*(?:fun|val|var|class|interface|object|init)\b)')
CONTINUATION = re.compile(r'[.?&|+\-*/%:=)\]]|as\b|is\b|in\b|else\b|catch\b|finally\b')
OPERATOR_END = set('=+-*/%,.(&|:>?!<')


def expression_end(clean, start, indent):
    """End of an expression body (`start` is at its `=`), following Kotlin's newline rule.

    The body ends where the enclosing scope closes, or at a newline outside brackets once the expression has
    begun, when the line does not end with an operator and the next line neither starts with a continuation token
    nor is indented deeper than the declaration. An unindented body therefore cannot end before its first line.
    """
    depth, i, begun = 0, start + 1, False
    while i < len(clean):
        c = clean[i]
        if c in '([{': depth += 1; begun = True
        elif c in ')]}':
            depth -= 1
            if depth < 0: return i
        elif c == '\n' and depth == 0 and begun:
            line_end = clean.find('\n', i + 1)
            line = clean[i+1:len(clean) if line_end < 0 else line_end]
            text = line.lstrip(' \t')
            back = i - 1
            while back > start and clean[back].isspace(): back -= 1
            previous = clean[back]
            if (text and len(line) - len(text) <= indent and not CONTINUATION.match(text)
                    and previous not in OPERATOR_END):
                return i
        elif not c.isspace(): begun = True
        i += 1
    return len(clean)


def metrics(source):
    clean = masked(source)
    functions = {}
    for match in FUNCTION.finditer(clean):
        params_end = matching(clean, match.end()-1, '(', ')')
        # Annotations are not part of the key, so annotating a function does not rename its baseline entry.
        modifiers = re.sub(ANNOTATION, '', clean[match.start():match.start('fun')])
        signature = re.sub(r'[\s' + LITERAL + ']+', '', modifiers + clean[match.start('fun'):params_end+1])
        suffix = hashlib.sha256(signature.encode()).hexdigest()[:12]
        start = params_end + 1
        first = re.search(r'[={]', clean[start:])
        # A body-less (abstract or interface) function must not borrow the next declaration's body.
        if first is None or NEXT_DECLARATION.search(clean, start, start + first.start()): continue
        start += first.start()
        if clean[start] == '{':
            end = matching(clean, start, '{', '}') + 1
        else:
            end = expression_end(clean, start, len(match.group('indent')))
        key = match.group('name') + ':' + suffix
        # Same-name, same-signature functions in different classes share a key; keep the largest, so a later
        # simple declaration cannot overwrite (and hide) an earlier complex one.
        functions[key] = max(functions.get(key, 0), len(BRANCH.findall(clean[start:end])))
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


PLACEHOLDER_REASON = 'Growth requires explicit review.'


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
            record['reason'] = raises.get(path) or limits.get('reason') or PLACEHOLDER_REASON
            files[path] = record
        elif path in old_files:
            summary.append(f'removed {path}')
    summary.extend(f'removed {path}' for path in old_files if path not in actual)
    if refused:
        raise ValueError('Ceilings would grow; split the code, or pass --raise PATH=REASON after review:\n'
                         + '\n'.join(refused))
    return {'defaultLines': default_lines, 'defaultBranches': default_branches, 'files': files}, summary


def baseline_at(ref, path):
    """The baseline committed at a git ref. A missing ref or file fails closed: the ratchet cannot be verified."""
    relative = path.resolve().relative_to(ROOT).as_posix()
    try:
        text = subprocess.run(['git', 'show', f'{ref}:{relative}'], cwd=ROOT, check=True, capture_output=True,
                              encoding='utf-8').stdout
    except (subprocess.CalledProcessError, OSError) as failure:
        raise ValueError(f'cannot read {relative} at {ref}; fetch the base branch first '
                         f'(git fetch --depth=1 origin <branch>): {failure}') from failure
    return json.loads(text)


def ratchet_failures(base, current):
    """Ceilings in `current` that exceed `base` without a newly written reason.

    Check mode alone accepts whatever the baseline says, so a hand-edited raise would pass. Against the base
    branch's baseline, every rise (a larger ceiling, a new file entry or a new function entry) must come with a
    `reason` that differs from the base entry's and is not the writer's placeholder; default ceilings never rise.
    """
    failures = [f'{key} rose from {base[key]} to {current[key]}' for key in ('defaultLines', 'defaultBranches')
                if current[key] > base[key]]
    old_files = base.get('files', {})
    for path, limits in sorted(current.get('files', {}).items()):
        old = old_files.get(path, {})
        rises = []
        if 'lines' in limits and limits['lines'] > old.get('lines', base['defaultLines']):
            rises.append(f'lines {old.get("lines", "new")} -> {limits["lines"]}')
        for name, maximum in sorted(limits.get('functions', {}).items()):
            before = old.get('functions', {}).get(name)
            if before is None or maximum > before:
                rises.append(f'{name} {before if before is not None else "new"} -> {maximum}')
        reason = str(limits.get('reason', '')).strip()
        reviewed = reason and reason != PLACEHOLDER_REASON and reason != str(old.get('reason', '')).strip()
        if rises and not reviewed:
            failures.append(f'{path}: {", ".join(rises)} without a new reviewed reason '
                            '(use --write-baseline --raise PATH=REASON)')
    return failures


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
    parser.add_argument('--baseline-ref', metavar='REF',
                        help='Also fail when the baseline raises a ceiling above the one committed at REF (for '
                             'example origin/main) without a new reviewed reason. CI passes this on pull requests.')
    args = parser.parse_args()
    if args.raises and not args.write_baseline: parser.error('--raise requires --write-baseline')
    if args.baseline_ref and args.write_baseline: parser.error('--baseline-ref is a check-mode option')
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
            if args.baseline_ref:
                rises = ratchet_failures(baseline_at(args.baseline_ref, args.baseline), baseline)
                if rises:
                    raise ValueError(f'Baseline ceilings rose relative to {args.baseline_ref}:\n' + '\n'.join(rises))
                print(f'Baseline ratchet: no unreviewed rise relative to {args.baseline_ref}')
            loose = slack(actual, baseline)
            if loose:
                print(f'{len(loose)} ceilings are above the measured value; ratchet them down with --write-baseline:')
                print('\n'.join('  ' + note for note in loose))
    except ValueError as failure: raise SystemExit(str(failure)) from failure
    print(f'Complexity growth gate: {len(actual)} production Kotlin files checked')


if __name__ == '__main__':
    main()
