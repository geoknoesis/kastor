"""Keep documented Kastor coordinates in sync with the single version in gradle.properties.

Scans README.md and docs/**/*.md for Gradle/Maven coordinates and plugin versions of
com.geoknoesis.kastor artifacts. Pages that describe past releases are listed in HISTORICAL.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HISTORICAL = {
    "CHANGELOG.md",
    "docs/kastor/guides/migrating-to-rdf-1.2.md",
    "docs/kastor/reference/versioning-policy.md",
}
SKIPPED_TREES = ("docs/project/", "docs/superpowers/")
PATTERNS = [
    re.compile(r"com\.geoknoesis\.kastor(?:\.gen)?:[A-Za-z0-9_.-]+:(?P<v>\d+\.\d+\.\d+[A-Za-z0-9.-]*)"),
    re.compile(r'id\("com\.geoknoesis\.kastor\.gen"\)\s+version\s+"(?P<v>[^"]+)"'),
    re.compile(r"<groupId>com\.geoknoesis\.kastor</groupId>\s*<artifactId>[^<]+</artifactId>\s*"
               r"<version>(?P<v>[^<]+)</version>"),
]


def main():
    version = next(line.split("=", 1)[1].strip()
                   for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines()
                   if line.startswith("version="))
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
