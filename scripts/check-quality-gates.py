"""Gate dependency advice, module test populations and opt-in coverage against reviewed policies."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def dependency_findings(report):
    findings = set()
    for project in report['projectAdvice']:
        path = project['projectPath']
        for advice in project['dependencyAdvice']:
            findings.add((path, advice['coordinates']['identifier'], advice.get('fromConfiguration', ''), advice.get('toConfiguration', '')))
        for advice in project.get('pluginAdvice', []):
            findings.add((path, advice['redundantPlugin'], 'plugin', ''))
    return findings


def dependencies(report, policy):
    allowed = set()
    for entry in policy['entries']:
        if not entry.get('reason', '').strip():
            raise ValueError('Every dependency exception needs a reason')
        key = tuple(entry[k] for k in ['project', 'dependency', 'from', 'to'])
        if key in allowed:
            raise ValueError(f'Duplicate dependency exception: {key}')
        allowed.add(key)
    actual = dependency_findings(report)
    unexpected = actual - allowed
    if unexpected:
        raise ValueError('Unreviewed dependency advice:\n' + '\n'.join(map(str, sorted(unexpected))))
    print(f'Dependency gate: {len(actual)} reviewed exceptions, {len(allowed - actual)} inactive exceptions')


def tests(directory, policy):
    for module, minimum in policy['modules'].items():
        reports = list((directory / module / 'test-results/test').glob('TEST-*.xml'))
        executed = 0
        for report in reports:
            suite = ET.parse(report).getroot()
            cases = suite.findall('testcase')
            if int(suite.get('tests', 0)) != len(cases):
                raise ValueError(f'Invalid test count: {report}')
            if any(c.find('failure') is not None or c.find('error') is not None for c in cases):
                raise ValueError(f'Failed tests: {report}')
            executed += sum(c.find('skipped') is None for c in cases)
        if executed < minimum:
            raise ValueError(f'{module}: {executed} executed tests; expected at least {minimum}')
        print(f'{module}: {executed} executed, minimum {minimum}')


def coverage(directory, policy):
    for module, limits in policy['modules'].items():
        report = directory / module / 'reports/jacoco/test/jacocoTestReport.xml'
        counters = {c.get('type'): c for c in ET.parse(report).getroot().findall('counter')}
        for kind, minimum in limits.items():
            counter = counters.get(kind.upper())
            if counter is None:
                raise ValueError(f'Missing {kind} counter: {report}')
            covered, missed = int(counter.get('covered')), int(counter.get('missed'))
            ratio = covered / (covered + missed) if covered + missed else 1
            if ratio < minimum:
                raise ValueError(f'{module}: {kind} coverage {ratio:.1%}; expected {minimum:.1%}')
            print(f'{module}: {kind} {ratio:.1%}, minimum {minimum:.1%}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('gate', choices=['dependencies', 'tests', 'coverage'])
    parser.add_argument('input', type=Path)
    parser.add_argument('policy', type=Path)
    args = parser.parse_args()
    policy = json.loads(args.policy.read_text(encoding='utf-8'))
    try:
        if args.gate == 'dependencies':
            dependencies(json.loads(args.input.read_text(encoding='utf-8')), policy)
        elif args.gate == 'tests':
            tests(args.input, policy)
        else:
            coverage(args.input, policy)
    except (ValueError, FileNotFoundError, ET.ParseError) as failure:
        raise SystemExit(str(failure)) from failure


if __name__ == '__main__':
    main()
