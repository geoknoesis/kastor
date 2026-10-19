"""Exercise the gates that prevent dependency, suite-population and coverage regressions."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('quality_gates', Path(__file__).with_name('check-quality-gates.py'))
GATES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATES)


class QualityGatesTest(unittest.TestCase):
    def test_unreviewed_dependency_and_plugin_advice_fail(self):
        report = {'projectAdvice': [{'projectPath': ':library', 'dependencyAdvice': [
            {'coordinates': {'identifier': 'group:library'}, 'toConfiguration': 'api'}],
            'pluginAdvice': [{'redundantPlugin': 'plugin'}]}]}
        with self.assertRaisesRegex(ValueError, 'Unreviewed'):
            GATES.dependencies(report, {'entries': []})
        entries = [dict(zip(['project','dependency','from','to'], key), reason='Public signature / reviewed plugin contract')
                   for key in GATES.dependency_findings(report)]
        GATES.dependencies(report, {'entries': entries})
        entries[0]['reason'] = ' '
        with self.assertRaisesRegex(ValueError, 'reason'):
            GATES.dependencies(report, {'entries': entries})

    def test_a_populated_other_module_cannot_hide_a_missing_module(self):
        with tempfile.TemporaryDirectory() as scratch:
            directory = Path(scratch)
            report = directory/'present/test-results/test/TEST-example.xml'
            report.parent.mkdir(parents=True)
            report.write_text('<testsuite tests="2"><testcase/><testcase><skipped/></testcase></testsuite>')
            GATES.tests(directory, {'modules': {'present': 1}})
            with self.assertRaisesRegex(ValueError, 'missing'):
                GATES.tests(directory, {'modules': {'present': 1, 'missing': 1}})
            with self.assertRaisesRegex(ValueError, 'expected at least 2'):
                GATES.tests(directory, {'modules': {'present': 2}})
            report.write_text('<testsuite tests="1"><testcase><error/></testcase></testsuite>')
            with self.assertRaisesRegex(ValueError, 'Failed tests'):
                GATES.tests(directory, {'modules': {'present': 1}})

    def test_empty_or_negative_coverage_counts_fail_closed(self):
        with tempfile.TemporaryDirectory() as scratch:
            directory = Path(scratch)
            report = directory/'module/reports/jacoco/test/jacocoTestReport.xml'
            report.parent.mkdir(parents=True)
            for covered, missed in [(0, 0), (-1, 5), (5, -1)]:
                with self.subTest(covered=covered, missed=missed):
                    report.write_text(f'<report><counter type="LINE" covered="{covered}" missed="{missed}"/></report>')
                    with self.assertRaisesRegex(ValueError, 'Empty or invalid'):
                        GATES.coverage(directory, {'modules': {'module': {'line': .9}}})

    def test_healthy_line_coverage_cannot_hide_branch_regression(self):
        with tempfile.TemporaryDirectory() as scratch:
            directory = Path(scratch)
            report = directory/'module/reports/jacoco/test/jacocoTestReport.xml'
            report.parent.mkdir(parents=True)
            report.write_text('<report><counter type="LINE" covered="95" missed="5"/>'
                              '<counter type="BRANCH" covered="30" missed="70"/></report>')
            with self.assertRaisesRegex(ValueError, 'branch coverage'):
                GATES.coverage(directory, {'modules': {'module': {'line': .9, 'branch': .7}}})
            GATES.coverage(directory, {'modules': {'module': {'line': .9, 'branch': .3}}})


if __name__ == '__main__':
    unittest.main()
