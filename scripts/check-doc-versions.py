"""Keep documented Kastor coordinates in sync with the single version in gradle.properties.

Scans README.md and docs/**/*.md for Gradle/Maven coordinates, plugin versions and
"Kastor X.Y.Z" prose/banner mentions, and checks the JDK in docs/_includes banners against
the jvmToolchain in build.gradle.kts. Pages that describe past releases are listed in HISTORICAL.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HISTORICAL = {
    "CHANGELOG.md",
    "docs/kastor/guides/migrating-to-rdf-1.2.md",
}
# docs/_site/ is the local, git-ignored Jekyll build output and mirrors stale sources.
SKIPPED_TREES = ("docs/project/", "docs/superpowers/", "docs/_site/")
PATTERNS = [
    re.compile(r"com\.geoknoesis\.kastor(?:\.gen)?:[A-Za-z0-9_.-]+:(?P<v>\d+\.\d+\.\d+[A-Za-z0-9.-]*)"),
    re.compile(r'id\("com\.geoknoesis\.kastor\.gen"\)\s+version\s+"(?P<v>[^"]+)"'),
    # Prose / banner mentions such as "Kastor 0.2.0" or "Kastor RDF SDK `0.2.0`".
    re.compile(r"\bKastor(?:\s+RDF\s+SDK)?\s+[`*]*(?P<v>\d+\.\d+\.\d+[A-Za-z0-9.-]*)"),
    re.compile(r"<groupId>com\.geoknoesis\.kastor</groupId>\s*<artifactId>[^<]+</artifactId>\s*"
               r"<version>(?P<v>[^<]+)</version>"),
]
JAVA_MENTION = re.compile(r"\bJ(?:ava|DK)\s+`?(?P<v>\d+)\+")


def check_text(relative, text, version, jdk):
    """Return problem strings for one document (coordinates, prose versions, banner JDK)."""
    problems = []
    for pattern in PATTERNS:
        for match in pattern.finditer(text):
            if match.group("v") != version:
                line = text.count("\n", 0, match.start()) + 1
                problems.append(f"{relative}:{line}: {match.group('v')}")
    if relative.startswith("docs/_includes/"):
        for match in JAVA_MENTION.finditer(text):
            if match.group("v") != jdk:
                line = text.count("\n", 0, match.start()) + 1
                problems.append(f"{relative}:{line}: Java {match.group('v')}+ but build toolchain is {jdk}")
    return problems


def main():
    version = next(line.split("=", 1)[1].strip()
                   for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines()
                   if line.startswith("version="))
    jdk = re.search(r"jvmToolchain\((\d+)\)", (ROOT / "build.gradle.kts").read_text(encoding="utf-8")).group(1)
    problems = []
    for path in [ROOT / "README.md", *sorted((ROOT / "docs").rglob("*.md"))]:
        relative = path.relative_to(ROOT).as_posix()
        if relative in HISTORICAL or relative.startswith(SKIPPED_TREES):
            continue
        text = path.read_text(encoding="utf-8")
        for pattern in PATTERNS:
            for match in pattern.finditer(text):
                if match.group("v") != version:
                    line = text.count("\n", 0, match.start()) + 1
                    problems.append(f"{relative}:{line}: {match.group('v')}")
    if problems:
        raise SystemExit(f"Documented Kastor versions differ from gradle.properties version={version}:\n  "
                         + "\n  ".join(problems))
    print(f"Documented Kastor coordinates match version {version}")


if __name__ == "__main__":
    main()
