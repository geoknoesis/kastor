"""Check that README Kotlin samples import what they use, like their compiled mirror does.

examples/hello-world/src/main/kotlin/Readme*.kt compile the README samples, but a README block can still
drop an import the mirror has (e.g. `FOAF.name` without `import com.geoknoesis.kastor.rdf.vocab.FOAF`),
and a reader copying the block then gets an unresolved reference. For every README ```kotlin block that
has import lines:

  1. each vocabulary object used as `NAME.` (FOAF, RDF, XSD, ...) must be imported explicitly or via
     `com.geoknoesis.kastor.rdf.vocab.*`, and each core type in CORE_TYPES (e.g. `Direction.`) explicitly or
     via `com.geoknoesis.kastor.rdf.*`;
  2. each import must also appear in a mirror file, so the mirror really compiles the same symbols.

A block without imports is a continuation of the previous sample only when no heading separates them; such
fragments are skipped. A block that opens its own section (a heading since the previous kotlin block) is
checked like any other, so it must import the vocabulary objects and core types it uses.

Usage: python scripts/check-readme-imports.py [repository-root]
"""
import re
import sys
from pathlib import Path

VOCAB_PACKAGE = "com.geoknoesis.kastor.rdf.vocab"
CORE_PACKAGE = "com.geoknoesis.kastor.rdf"
# Core types README samples reference as `Name.`; they need an import just like vocabulary objects.
CORE_TYPES = {"Direction"}
HEADING = re.compile(r"^#{1,6}\s", re.M)
MIRROR_GLOB = "examples/hello-world/src/main/kotlin/Readme*.kt"
BLOCK = re.compile(r"^```kotlin[^\n]*\n(.*?)^```", re.S | re.M)
IMPORT = re.compile(r"^import\s+(\w+(?:\.\w+)*(?:\.\*)?)", re.M)


def vocabularies(root):
    """Vocabulary object names declared in rdf/core (fallback list when sources are absent)."""
    names = set()
    for source in Path(root).glob("rdf/core/src/main/kotlin/**/vocab/*.kt"):
        names.update(re.findall(r"^\s*(?:public\s+)?object\s+([A-Z][A-Z0-9_]*)\b", source.read_text(encoding="utf-8"), re.M))
    return names or {"FOAF", "RDF", "RDFS", "XSD", "OWL", "SHACL", "SKOS", "DCTERMS", "DCAT", "PROV"}


def check_text(readme, mirror_imports, vocab_names):
    problems = []
    packages = {name: VOCAB_PACKAGE for name in vocab_names}
    packages.update({name: CORE_PACKAGE for name in CORE_TYPES})
    previous_end = 0
    for block_number, match in enumerate(BLOCK.finditer(readme), start=1):
        body = match.group(1)
        imports = set(IMPORT.findall(body))
        opens_section = HEADING.search(readme, previous_end, match.start()) is not None
        previous_end = match.end()
        if not imports and not opens_section:
            continue  # continuation fragment of the previous sample
        line = readme.count("\n", 0, match.start()) + 1
        code = "\n".join(l.split("//", 1)[0] for l in body.splitlines() if not l.lstrip().startswith("import "))
        used = {name for name in packages if re.search(rf"(?<![\w.]){name}\.", code)}
        for name in sorted(used):
            package = packages[name]
            if f"{package}.{name}" not in imports and f"{package}.*" not in imports:
                problems.append(f"README.md:{line}: kotlin block {block_number} uses {name} without "
                                f"`import {package}.{name}`")
        for imported in sorted(imports):
            if imported.endswith(".*"):
                continue  # wildcard imports are README shorthand; the mirror uses explicit imports
            if imported not in mirror_imports:
                problems.append(f"README.md:{line}: kotlin block {block_number} imports {imported}, "
                                f"which no {MIRROR_GLOB} file imports")
    return problems


def check(root):
    root = Path(root)
    mirror_imports = set()
    for mirror in root.glob(MIRROR_GLOB):
        mirror_imports.update(IMPORT.findall(mirror.read_text(encoding="utf-8")))
    if not mirror_imports:
        return [f"no README mirror found at {MIRROR_GLOB}"]
    return check_text((root / "README.md").read_text(encoding="utf-8"), mirror_imports, vocabularies(root))


def main(argv):
    root = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parents[1]
    problems = check(root)
    if problems:
        print("README Kotlin samples are missing imports:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1
    print("README Kotlin sample imports match the compiled mirror")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
