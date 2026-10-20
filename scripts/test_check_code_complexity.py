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


if __name__ == '__main__':
    unittest.main()
