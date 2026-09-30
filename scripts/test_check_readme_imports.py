import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-readme-imports.py")
spec = importlib.util.spec_from_file_location("check_readme_imports", SCRIPT)
readme = importlib.util.module_from_spec(spec)
spec.loader.exec_module(readme)

VOCAB = {"FOAF", "RDF", "XSD"}
MIRROR = {"com.geoknoesis.kastor.rdf.vocab.FOAF", "com.geoknoesis.kastor.rdf.vocab.RDF", "org.apache.jena.rdf.model.Model"}


def block(*lines):
    return "```kotlin\n" + "\n".join(lines) + "\n```\n"


class CheckReadmeImportsTest(unittest.TestCase):
    def test_missing_vocabulary_import_is_reported(self):
        text = "# Title\n\n" + block("import com.geoknoesis.kastor.rdf.*", "", 'person has FOAF.name with "Alice"')
        problems = readme.check_text(text, MIRROR, VOCAB)
        self.assertEqual(len(problems), 1)
        self.assertIn("README.md:3", problems[0])
        self.assertIn("uses FOAF", problems[0])

    def test_explicit_and_wildcard_vocabulary_imports_pass(self):
        explicit = block("import com.geoknoesis.kastor.rdf.vocab.FOAF", "x - FOAF.name - y")
        wildcard = block("import com.geoknoesis.kastor.rdf.vocab.*", "x - RDF.type - FOAF.Person")
        self.assertEqual(readme.check_text(explicit + wildcard, MIRROR, VOCAB), [])

    def test_fragment_without_imports_is_skipped(self):
        self.assertEqual(readme.check_text(block('person - FOAF.name - "x"'), MIRROR, VOCAB), [])

    def test_block_opening_a_section_needs_imports(self):
        sample = block("import com.geoknoesis.kastor.rdf.vocab.FOAF", 'x - FOAF.name - "x"')
        continuation = block('person - FOAF.name - "x"')
        self.assertEqual(readme.check_text("# A\n\n" + sample + "\n" + continuation, MIRROR, VOCAB), [])
        problems = readme.check_text("# A\n\n" + sample + "\n### B\n\n" + continuation, MIRROR, VOCAB)
        self.assertEqual(len(problems), 1)
        self.assertIn("block 2 uses FOAF", problems[0])

    def test_core_type_needs_import(self):
        missing = block("import com.geoknoesis.kastor.rdf.vocab.FOAF", 'x - FOAF.name - lang("a", "ar", Direction.RTL)')
        problems = readme.check_text(missing, MIRROR | {"com.geoknoesis.kastor.rdf.Direction"}, VOCAB)
        self.assertEqual(len(problems), 1)
        self.assertIn("uses Direction", problems[0])
        wildcard = block("import com.geoknoesis.kastor.rdf.*", 'lang("a", "ar", Direction.RTL)')
        self.assertEqual(readme.check_text(wildcard, MIRROR, VOCAB), [])

    def test_import_absent_from_mirror_is_reported(self):
        text = block("import com.geoknoesis.kastor.rdf.vocab.XSD", "lit(\"1\", XSD.integer)")
        problems = readme.check_text(text, MIRROR, VOCAB)
        self.assertEqual(len(problems), 1)
        self.assertIn("no examples/hello-world", problems[0])

    def test_names_in_comments_and_qualified_references_are_ignored(self):
        text = block("import org.apache.jena.rdf.model.Model", "// FOAF.name is used later", "val m: Model = foo.RDF.type")
        self.assertEqual(readme.check_text(text, MIRROR, VOCAB), [])

    def test_real_readme_is_consistent(self):
        self.assertEqual(readme.check(SCRIPT.resolve().parents[1]), [])


if __name__ == "__main__":
    unittest.main()
