"""Fetch the pinned W3C conformance corpora used by CI into their git-ignored locations.

    python scripts/fetch-conformance-data.py            # RDF 1.2 + SHACL 1.2 corpora
    python scripts/fetch-conformance-data.py --only rdf
    python scripts/fetch-conformance-data.py --only shacl

The commits below are the single source of truth for the corpus versions; the conformance
and release-readiness workflows call this script. Re-running is idempotent: an existing
checkout at the pinned commit is left alone, a different commit is refused unless --force.
"""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

CORPORA = {
    "rdf": {
        "url": "https://github.com/w3c/rdf-tests.git",
        "commit": "369a90d1a60c021b746df2e411da0ff36258a758",
        "checkout": ROOT / "rdf/conformance/test-data",
        "install": None,
    },
    "shacl": {
        "url": "https://github.com/w3c/data-shapes.git",
        "commit": "94d8bc2bd4fc4fdc6f2964d1ec4a892329e05f06",
        "checkout": ROOT / "build/upstream-shacl",
        # The SHACL module reads the 1.2 suite from this directory.
        "install": ("shacl12-test-suite", ROOT / "rdf/shacl/validation/test-data/w3c-shacl12"),
    },
}


def git(*args, cwd=None):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def fetch(name, spec, force):
    target = spec["checkout"]
    if (target / ".git").is_dir():
        head = git("rev-parse", "HEAD", cwd=target)
        if head == spec["commit"]:
            print(f"{name}: {target.relative_to(ROOT)} already at {head}")
        elif not force:
            raise SystemExit(f"{name}: {target} is at {head}, expected {spec['commit']} (use --force)")
        else:
            shutil.rmtree(target)
    elif target.exists() and any(target.iterdir()):
        if not force:
            raise SystemExit(f"{name}: {target} exists and is not a git checkout (use --force)")
        shutil.rmtree(target)
    if not (target / ".git").is_dir():
        target.mkdir(parents=True, exist_ok=True)
        git("init", "--quiet", cwd=target)
        git("remote", "add", "origin", spec["url"], cwd=target)
        print(f"{name}: fetching {spec['url']} @ {spec['commit']}")
        git("fetch", "--quiet", "--depth", "1", "origin", spec["commit"], cwd=target)
        git("checkout", "--quiet", "--detach", "FETCH_HEAD", cwd=target)
    if spec["install"]:
        source, destination = spec["install"]
        if destination.exists():
            shutil.rmtree(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(target / source, destination)
        print(f"{name}: installed {source} -> {destination.relative_to(ROOT)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", choices=sorted(CORPORA), action="append")
    parser.add_argument("--force", action="store_true", help="Replace checkouts at a different commit")
    args = parser.parse_args()
    for name in args.only or sorted(CORPORA):
        fetch(name, CORPORA[name], args.force)


if __name__ == "__main__":
    sys.exit(main())
