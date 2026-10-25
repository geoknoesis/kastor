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

    def test_branches_inside_string_templates_count(self):
        # Round-12 probe: a template expression is code, so masking the whole literal hid every branch in it.
        source = 'fun big(a: Boolean) {\n' + '  val s = "x ${if (a) "y" else "z"}"\n' * 45 + '}\n'
        self.assertEqual([45], list(GATE.metrics(source)['functions'].values()))
        raw = 'fun raw(a: Boolean) = """${if (a) "1" else "2"} $a ${listOf(1).map { it -> it }}"""\n'
        self.assertEqual([2], list(GATE.metrics(raw)['functions'].values()))

    def test_template_braces_and_quotes_do_not_end_the_body_early(self):
        source = 'fun a(x: String): String {\n  return "open ${x.ifEmpty { "}" }} brace"\n  if (x.isEmpty()) {}\n}\n'
        self.assertEqual([1], list(GATE.metrics(source)['functions'].values()))

    def test_comments_char_literals_and_backtick_names_stay_opaque(self):
        source = ("fun f(c: Char) {\n  /* if /* nested if */ while */\n  val q = '\"'\n  val w = `when`\n"
                  "  val t = '\\''\n  if (c == '{') {}\n}\nfun g() { if (true) {} }\n")
        self.assertEqual([1, 1], list(GATE.metrics(source)['functions'].values()))

    def test_annotated_and_platform_functions_are_measured(self):
        # Round-12 self-audit: an annotation on the declaration line used to hide the whole function.
        for prefix in ['@JvmStatic ', '@Throws(IOException::class) ', '@Suppress("X") override ', 'actual ',
                       'external ', '@JvmStatic @JvmOverloads ']:
            source = f'class C {{\n  {prefix}fun f(a: Boolean) {{ if (a) {{}} }}\n}}\n'
            with self.subTest(prefix=prefix):
                self.assertEqual([1], list(GATE.metrics(source)['functions'].values()))

    def test_same_signature_functions_in_one_file_keep_the_largest_count(self):
        # Round-12 self-audit: a later simple function with the same signature used to overwrite a complex one.
        source = 'class A {\n  fun x() { if (a) {}; if (b) {}; if (c) {} }\n}\nclass B {\n  fun x() { }\n}\n'
        self.assertEqual([3], list(GATE.metrics(source)['functions'].values()))

    def test_expression_bodies_end_at_the_kotlin_statement_boundary(self):
        # A local expression function used to absorb the rest of its enclosing function.
        source = ('fun outer(a: Boolean) {\n    fun inner(x: Int) = x > 0 && x < 9\n'
                  '    if (a) {}\n    if (inner(1)) {}\n}\n')
        values = GATE.metrics(source)['functions']
        self.assertEqual({'outer': 3, 'inner': 1}, {k.split(':')[0]: v for k, v in values.items()})

    def test_unindented_or_wrapped_expression_bodies_keep_their_branches(self):
        for source in ['fun f(a: Boolean) =\nif (a) 1\nelse if (!a) 2\nelse 3\n',
                       'fun f(a: Boolean) = listOf(1)\n    .map { if (a) it else 0 }\n    .filter { a || it > 0 }\n',
                       'fun f(a: Boolean) = a &&\n    (if (a) true else false)\n']:
            with self.subTest(source=source):
                self.assertEqual([2], list(GATE.metrics(source)['functions'].values()))

    def test_a_trailing_string_literal_is_an_operand_not_an_operator(self):
        # `x ?: "D"` blanked to `x ?:     ` looked like a line ending in an operator, so the body ran on.
        source = ('class C {\n    fun f(a: String?) = a ?: "D"\n\n    val v = if (b) 1 else 2\n'
                  '    private val w = c || d\n}\n')
        self.assertEqual([0], list(GATE.metrics(source)['functions'].values()))

    def test_body_less_functions_do_not_borrow_the_next_body(self):
        source = 'interface I {\n    fun a(): Int\n    val b: Int\n}\nclass C { init { if (x) {} } }\nfun c() { }\n'
        values = GATE.metrics(source)['functions']
        self.assertEqual({'c': 0}, {k.split(':')[0]: v for k, v in values.items()})

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


class BaselineRatchetTest(unittest.TestCase):
    """Round-12 P3-2(b): check mode trusted any baseline, so a hand-edited raise passed CI."""
    BASE = {'defaultLines': 20, 'defaultBranches': 1,
            'files': {'legacy.kt': {'lines': 30, 'functions': {'old': 5}, 'reason': 'frozen'}}}

    def changed(self, **files):
        return {'defaultLines': 20, 'defaultBranches': 1, 'files': files}

    def test_lowered_removed_and_unchanged_ceilings_pass(self):
        for current in [self.BASE, self.changed(),
                        self.changed(**{'legacy.kt': {'lines': 25, 'reason': 'frozen'}})]:
            with self.subTest(current=current):
                self.assertEqual([], GATE.ratchet_failures(self.BASE, current))

    def test_hand_edited_raises_and_new_entries_fail_without_a_new_reason(self):
        for current in [self.changed(**{'legacy.kt': {'lines': 31, 'functions': {'old': 5}, 'reason': 'frozen'}}),
                        self.changed(**{'legacy.kt': {'lines': 30, 'functions': {'old': 6}, 'reason': 'frozen'}}),
                        self.changed(**{'legacy.kt': {'lines': 30, 'functions': {'old': 5, 'new': 2},
                                                      'reason': 'frozen'}}),
                        self.changed(**{'legacy.kt': self.BASE['files']['legacy.kt'],
                                        'new.kt': {'lines': 21, 'reason': 'Growth requires explicit review.'}}),
                        self.changed(**{'legacy.kt': self.BASE['files']['legacy.kt'], 'new.kt': {'lines': 21}})]:
            with self.subTest(current=current):
                self.assertTrue(GATE.ratchet_failures(self.BASE, current))

    def test_default_ceilings_never_rise(self):
        current = dict(self.BASE, defaultLines=21)
        self.assertTrue(GATE.ratchet_failures(self.BASE, current))
        current = dict(self.BASE, defaultBranches=2)
        self.assertTrue(GATE.ratchet_failures(self.BASE, current))

    def test_a_raise_with_a_changed_reviewed_reason_passes(self):
        current = self.changed(**{'legacy.kt': {'lines': 31, 'functions': {'old': 5}, 'reason': 'split pending'}})
        self.assertEqual([], GATE.ratchet_failures(self.BASE, current))

    def test_an_unreadable_baseline_ref_fails_closed(self):
        with self.assertRaises(ValueError):
            GATE.baseline_at('refs/does/not/exist', Path('scripts/code-complexity-baseline.json'))


if __name__ == '__main__':
    unittest.main()
