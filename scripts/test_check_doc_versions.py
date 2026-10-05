import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-doc-versions.py")
spec = importlib.util.spec_from_file_location("check_doc_versions", SCRIPT)
docs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(docs)


class CheckDocVersionsTest(unittest.TestCase):
    def test_stale_banner_version_and_jdk_are_reported(self):
        text = "> **Version**: Kastor RDF SDK `0.2.0` · Kotlin `2.x` · Java `17+`\n".replace("\n", "\n")
        problems = docs.check_text("docs/_includes/version-banner.md", text, "0.3.0-SNAPSHOT", "21")
        self.assertEqual(len(problems), 2)
        self.assertIn("0.2.0", problems[0])
        self.assertIn("toolchain is 21", problems[1])

    def test_current_banner_passes(self):
        text = "> **Version**: Kastor RDF SDK `0.3.0-SNAPSHOT` · Java `21+`\n"
        self.assertEqual(docs.check_text("docs/_includes/version-banner.md", text, "0.3.0-SNAPSHOT", "21"), [])

    def test_prose_mention_is_reported(self):
        for text in ("Kastor 0.2.0 targets RDF 1.2", "Kastor **0.2.0** targets", "> **Kastor 0.2.0 update.**"):
            self.assertEqual(len(docs.check_text("docs/a.md", text, "0.3.0-SNAPSHOT", "21")), 1, text)

    def test_historical_phrasing_and_placeholders_pass(self):
        text = "Kastor (since 0.2.0) implements RDF 1.2\n" 'implementation(platform("com.geoknoesis.kastor:kastor-bom:X.Y.Z"))\n'
        self.assertEqual(docs.check_text("docs/a.md", text, "0.3.0-SNAPSHOT", "21"), [])

    def test_jdk_only_checked_in_includes(self):
        self.assertEqual(docs.check_text("docs/a.md", "needs Java 17+", "0.3.0-SNAPSHOT", "21"), [])

    def test_versioning_policy_is_no_longer_exempt(self):
        self.assertNotIn("docs/kastor/reference/versioning-policy.md", docs.HISTORICAL)


if __name__ == "__main__":
    unittest.main()
