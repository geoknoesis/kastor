"""Regression checks for Kastor's staged JVM publication contract."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<groupId>example</groupId><artifactId>library</artifactId><version>1</version>
<name>Library</name><description>A library</description><url>https://example.org</url>
<licenses><license><name>Apache 2</name><url>https://example.org/license</url></license></licenses>
<scm><url>https://example.org/source</url><connection>scm:git:https://example.org/source.git</connection>
<developerConnection>scm:git:ssh://git@example.org/source.git</developerConnection></scm>
<issueManagement><system>Issues</system><url>https://example.org/issues</url></issueManagement>
<developers><developer><id>dev</id><name>Developer</name><url>https://example.org/dev</url></developer></developers>
{packaging}</project>'''


class StagedPublicationGate(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / "build/review/publication-gate-checks"
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.pom = self.directory / "library-1.pom"
        self.pom.write_text(POM.format(packaging=""), encoding="utf-8")
        self.jar("", "Library.class", b"bytecode")
        self.jar("-sources", "Library.kt", b"class Library")
        self.jar("-javadoc", "index.html", b"<html>Library</html>")
        self.sbom("example", "library")

    def sbom(self, group, artifact):
        purl = f"pkg:maven/{group}/{artifact}@1?type=jar"
        (self.directory / "library-1-cyclonedx.json").write_text(json.dumps(
            {"bomFormat": "CycloneDX", "specVersion": "1.6", "metadata": {"component": {"purl": purl}}}),
            encoding="utf-8")
        (self.directory / "library-1-cyclonedx.xml").write_text(
            '<bom xmlns="http://cyclonedx.org/schema/bom/1.6"><metadata><component>'
            f'<purl>{purl}</purl></component></metadata></bom>', encoding="utf-8")

    def jar(self, classifier, name, content):
        with ZipFile(self.directory / f"library-1{classifier}.jar", "w") as archive:
            archive.writestr(name, content)

    def gate(self):
        return subprocess.run([sys.executable, str(ROOT / "scripts/check-staged-publications.py"),
                               str(self.directory)], capture_output=True, text=True)

    def test_complete_library_passes(self):
        result = self.gate()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_dependency_without_version_fails(self):
        self.pom.write_text(POM.format(packaging="<dependencies><dependency><groupId>at.yawk.lz4</groupId>"
                                                 "<artifactId>lz4-java</artifactId><scope>runtime</scope>"
                                                 "</dependency></dependencies>"), encoding="utf-8")
        result = self.gate()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("at.yawk.lz4:lz4-java", result.stdout + result.stderr)

    def test_module_dependency_without_version_fails(self):
        (self.directory / "library-1.module").write_text(json.dumps({"variants": [{"name": "runtimeElements",
            "dependencies": [{"group": "at.yawk.lz4", "module": "lz4-java"}]}]}), encoding="utf-8")
        result = self.gate()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("at.yawk.lz4:lz4-java", result.stdout + result.stderr)

    def test_module_dependency_with_version_passes(self):
        (self.directory / "library-1.module").write_text(json.dumps({"variants": [{"name": "runtimeElements",
            "dependencies": [{"group": "g", "module": "m", "version": {"requires": "1.0"}}]}]}), encoding="utf-8")
        result = self.gate()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_pom_only_publication_does_not_require_jars(self):
        self.pom.write_text(POM.format(packaging="<packaging>pom</packaging>"), encoding="utf-8")
        for jar in self.directory.glob("*.jar"):
            jar.unlink()
        self.assertEqual(0, self.gate().returncode)

    def test_missing_main_binary_fails(self):
        (self.directory / "library-1.jar").unlink()
        self.assertNotEqual(0, self.gate().returncode)

    def test_empty_metadata_fails(self):
        for tag, value in (("name", "Library"), ("groupId", "example"), ("id", "dev")):
            with self.subTest(tag=tag):
                self.pom.write_text(POM.format(packaging="").replace(
                    f"<{tag}>{value}</{tag}>", f"<{tag}> </{tag}>"), encoding="utf-8")
                self.assertNotEqual(0, self.gate().returncode)

    def test_empty_license_container_fails(self):
        start = POM.index("<licenses>")
        end = POM.index("</licenses>") + len("</licenses>")
        self.pom.write_text((POM[:start] + "<licenses/>" + POM[end:]).format(packaging=""), encoding="utf-8")
        self.assertNotEqual(0, self.gate().returncode)

    def test_empty_archive_entries_fail(self):
        for classifier, name in (("", "Library.class"), ("-sources", "Library.kt"),
                                 ("-javadoc", "index.html")):
            with self.subTest(classifier=classifier):
                self.jar(classifier, name, b"")
                self.assertNotEqual(0, self.gate().returncode)
                self.jar(classifier, name, b"content")

    def test_kastor_artifact_ids_follow_naming_scheme(self):
        for artifact, ok in (("rdf-sparql-lang", True), ("kastor-gen-runtime", True), ("onto-quality", True),
                             ("kastor-bom", True), ("sparql-lang", False), ("gradle-plugin", False)):
            with self.subTest(artifact=artifact):
                self.sbom("com.geoknoesis.kastor", artifact)
                self.pom.write_text(POM.format(packaging="").replace("<groupId>example</groupId>", "<groupId>com.geoknoesis.kastor</groupId>")
                                    .replace("<artifactId>library</artifactId>", f"<artifactId>{artifact}</artifactId>"), encoding="utf-8")
                self.assertEqual(ok, self.gate().returncode == 0)

    def test_missing_sbom_fails(self):
        for extension in ("json", "xml"):
            with self.subTest(extension=extension):
                (self.directory / f"library-1-cyclonedx.{extension}").unlink()
                self.assertNotEqual(0, self.gate().returncode)
                self.sbom("example", "library")

    def test_sbom_for_another_artifact_fails(self):
        self.sbom("example", "other")
        self.assertNotEqual(0, self.gate().returncode)

    def test_malformed_sbom_fails(self):
        for extension in ("json", "xml"):
            with self.subTest(extension=extension):
                (self.directory / f"library-1-cyclonedx.{extension}").write_text("{<", encoding="utf-8")
                self.assertNotEqual(0, self.gate().returncode)
                self.sbom("example", "library")

    def test_missing_release_pom_links_fail(self):
        for element in ("<developerConnection>scm:git:ssh://git@example.org/source.git</developerConnection>",
                        "<url>https://example.org/issues</url>", "<url>https://example.org/dev</url>"):
            with self.subTest(element=element):
                self.pom.write_text(POM.format(packaging="").replace(element, ""), encoding="utf-8")
                self.assertNotEqual(0, self.gate().returncode)

    def test_invalid_zip_fails(self):
        (self.directory / "library-1.jar").write_bytes(b"not a zip")
        self.assertNotEqual(0, self.gate().returncode)


if __name__ == "__main__":
    unittest.main()
