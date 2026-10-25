"""Regression tests for the pinned-corpus fetcher (local git repositories only, no network)."""
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('fetch', Path(__file__).with_name('fetch-conformance-data.py'))
FETCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FETCH)


def git(*args, cwd):
    return subprocess.run(['git', '-c', 'user.name=t', '-c', 'user.email=t@example.org', '-c', 'commit.gpgsign=false',
                           *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


class FetchConformanceDataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.checkout = Path(self.temp.name) / 'corpus'
        self.checkout.mkdir()
        git('init', '--quiet', cwd=self.checkout)
        (self.checkout / 'manifest.ttl').write_text('# pinned\n', encoding='utf-8')
        git('add', 'manifest.ttl', cwd=self.checkout)
        git('commit', '--quiet', '-m', 'pin', cwd=self.checkout)
        self.spec = {'url': 'unused', 'commit': git('rev-parse', 'HEAD', cwd=self.checkout),
                     'checkout': self.checkout, 'install': None}

    def test_a_clean_checkout_at_the_pinned_commit_is_kept(self):
        FETCH.fetch('rdf', self.spec, force=False)
        self.assertTrue((self.checkout / 'manifest.ttl').is_file())

    def test_a_checkout_with_local_edits_is_refused(self):
        # Round 12: an edited corpus at the pinned commit passed as the pinned corpus.
        (self.checkout / 'manifest.ttl').write_text('# edited\n', encoding='utf-8')
        with self.assertRaisesRegex(SystemExit, 'local changes'):
            FETCH.fetch('rdf', self.spec, force=False)

    def test_a_checkout_at_another_commit_is_refused(self):
        with self.assertRaisesRegex(SystemExit, 'expected'):
            FETCH.fetch('rdf', dict(self.spec, commit='0' * 40), force=False)


if __name__ == '__main__':
    unittest.main()
