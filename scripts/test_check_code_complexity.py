import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('complexity', Path(__file__).with_name('check-code-complexity.py'))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class ComplexityTest(unittest.TestCase):
    def test_strings_comments_and_nested_bodies(self):
        source = '''fun simple(x: Boolean) {
            // if while catch ->
            val message = "if { while } ->"
            if (x) { while (x) { println(message) } }
        }
        fun next() { println("if") }
        '''
        values = GATE.metrics(source)['functions']
        self.assertEqual([2, 0], list(values.values()))

    def test_existing_ceilings_do_not_allow_new_or_growing_complex_functions(self):
        baseline = {'defaultLines': 20, 'defaultBranches': 1,
                    'files': {'legacy.kt': {'lines': 30, 'functions': {'old': 5}}}}
        GATE.check({'legacy.kt': {'lines': 30, 'functions': {'old': 5}}}, baseline)
        for path, lines, functions in [('legacy.kt',31,{'old':5}), ('legacy.kt',30,{'old':6}),
                                       ('legacy.kt',30,{'new':5}), ('new.kt',21,{})]:
            with self.subTest(path=path, lines=lines, functions=functions), self.assertRaises(ValueError):
                GATE.check({path:{'lines':lines,'functions':functions}}, baseline)


class WriteBaselineTest(unittest.TestCase):
    BASELINE = {'defaultLines': 20, 'defaultBranches': 1,
                'files': {'legacy.kt': {'lines': 30, 'functions': {'old': 5}, 'reason': 'frozen'},
                          'gone.kt': {'lines': 40, 'reason': 'deleted later'}}}

    def test_shrinking_files_lower_their_ceilings_and_compliant_files_leave_the_baseline(self):
        actual = {'legacy.kt': {'lines': 25, 'functions': {'old': 3}}, 'gone.kt': {'lines': 10, 'functions': {}}}
        baseline, summary = GATE.write_baseline(actual, self.BASELINE, {})
        self.assertEqual({'legacy.kt': {'lines': 25, 'functions': {'old': 3}, 'reason': 'frozen'}}, baseline['files'])
        self.assertEqual((20, 1), (baseline['defaultLines'], baseline['defaultBranches']))
        self.assertIn('lowered legacy.kt: lines 30 -> 25', summary)
        self.assertIn('removed gone.kt', summary)

    def test_growth_and_new_entries_are_refused_without_an_explicit_raise(self):
        for actual in [{'legacy.kt': {'lines': 31, 'functions': {'old': 5}}},
                       {'legacy.kt': {'lines': 30, 'functions': {'old': 6}}},
                       {'legacy.kt': {'lines': 30, 'functions': {'renamed': 5}}},
                       {'new.kt': {'lines': 21, 'functions': {}}}]:
            with self.subTest(actual=actual), self.assertRaises(ValueError):
                GATE.write_baseline(actual, self.BASELINE, {})

    def test_an_explicit_raise_records_its_reason_and_reports_the_growth(self):
        actual = {'legacy.kt': {'lines': 31, 'functions': {'old': 5}}}
        baseline, summary = GATE.write_baseline(actual, self.BASELINE, {'legacy.kt': 'reviewed split pending'})
        self.assertEqual({'lines': 31, 'functions': {'old': 5}, 'reason': 'reviewed split pending'},
                         baseline['files']['legacy.kt'])
        self.assertIn('RAISED legacy.kt: lines 30 -> 31 (reviewed split pending)', summary)

    def test_a_raise_needs_a_reason_and_a_measured_file(self):
        with self.assertRaises(ValueError):
            GATE.parse_raises(['legacy.kt='])
        with self.assertRaises(ValueError):
            GATE.parse_raises(['legacy.kt'])
        with self.assertRaises(ValueError):
            GATE.write_baseline({}, self.BASELINE, {'missing.kt': 'typo'})
        self.assertEqual({'a.kt': 'why = because'}, GATE.parse_raises(['a.kt=why = because']))

    def test_slack_lists_ceilings_above_the_measured_value(self):
        actual = {'legacy.kt': {'lines': 25, 'functions': {'old': 5}}}
        self.assertEqual(['legacy.kt: lines ceiling 30, measured 25', 'gone.kt: no longer measured'],
                         GATE.slack(actual, self.BASELINE))


if __name__ == '__main__':
    unittest.main()
