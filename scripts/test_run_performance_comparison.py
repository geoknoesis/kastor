import importlib.util
from pathlib import Path
import json
import tempfile
import struct
import unittest

SPEC = importlib.util.spec_from_file_location('performance_runner', Path(__file__).with_name('run-performance-comparison.py'))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class PerformanceRunnerTest(unittest.TestCase):
    def test_hybrid_selection_avoids_smt_siblings_and_slow_cores(self):
        rows = [dict(group=0,logical=i,core=i//2,efficiency=1 if i < 6 else 0,flags=0) for i in range(10)]
        self.assertEqual([2,4], RUNNER.select_cpus(range(10), rows))
        self.assertEqual([0,4], RUNNER.select_cpus([0,1,4,5,6], rows))

    def test_exclusive_allocations_and_other_groups_are_not_selected(self):
        rows = [dict(group=0,logical=i,core=i,efficiency=1,flags=0) for i in range(4)]
        rows[3]['flags'] = 2  # allocated to a different process
        rows[2]['group'] = 1
        self.assertEqual([0,1], RUNNER.select_cpus(range(4), rows))
        rows[3]['flags'] = 6  # allocated to this process
        self.assertEqual([1,3], RUNNER.select_cpus(range(4), rows))

    def test_allowed_linux_cpu_set_is_respected(self):
        self.assertEqual([3,8], RUNNER.select_cpus([8,3,11]))
        with self.assertRaises(ValueError): RUNNER.select_cpus([3])

    def test_variable_sized_records_and_unknown_types(self):
        row = bytearray(40)
        struct.pack_into('<IIIHBBBBBB', row, 0, 40, 0, 123, 0, 8, 4, 1, 0, 1, 0)
        data = struct.pack('<II', 8, 99) + row
        self.assertEqual([dict(group=0, logical=8, core=4, efficiency=1, flags=0)], RUNNER.decode_cpu_sets(data))
        for broken in [b'x', struct.pack('<II', 0, 0), row[:-1]]:
            with self.assertRaises(ValueError): RUNNER.decode_cpu_sets(broken)

    def test_longer_isomorphism_warmup_is_equal_for_both_revisions(self):
        before = RUNNER.command('java','baseline.jar','isomorphism',1,Path('baseline.json'),15)
        after = RUNNER.command('java','candidate.jar','isomorphism',1,Path('candidate.json'),15)
        self.assertEqual(before[:2]+before[3:-1], after[:2]+after[3:-1])
        self.assertEqual('15', before[before.index('-wi')+1])
        self.assertEqual('8', before[before.index('-i')+1])

    def test_scaling_merge_preserves_all_samples_and_requires_both_groups(self):
        with tempfile.TemporaryDirectory() as scratch:
            output = Path(scratch)
            first = {'benchmark':'lookup', 'primaryMetric':{'rawData':[[1,2],[3,4]]}}
            second = {'benchmark':'isomorphism', 'primaryMetric':{'rawData':[[5,6],[7,8]]}}
            (output/'baseline-scaling.json').write_text(json.dumps([first]))
            with self.assertRaises(FileNotFoundError): RUNNER.merge_scaling_reports(output,'baseline')
            self.assertFalse((output/'baseline.json').exists())
            (output/'baseline-isomorphism.json').write_text(json.dumps([second]))
            RUNNER.merge_scaling_reports(output,'baseline')
            self.assertEqual([first,second],json.loads((output/'baseline.json').read_text()))

    def test_comparison_commands_use_the_same_jvm_and_measurement_profile(self):
        before=RUNNER.command('java','baseline.jar','case',4,Path('baseline.json'))
        after=RUNNER.command('java','candidate.jar','case',4,Path('candidate.json'))
        self.assertEqual(before[:2]+before[3:-1], after[:2]+after[3:-1])
        self.assertIn('gc', before)
        self.assertIn('-Xmx1024m -XX:ActiveProcessorCount=2', before)
        self.assertEqual('true', before[before.index('-foe')+1])


if __name__ == '__main__':
    unittest.main()
