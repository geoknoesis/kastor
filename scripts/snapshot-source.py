"""Create a fresh source-only snapshot and SHA-256 manifest for clean verification.

Includes tracked and untracked non-ignored files, never build outputs or .git.
Refuses an existing destination and paths outside build/review. No deletion.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", help="A new directory name under build/review")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    parent = (root / "build/review").resolve()
    target = (parent / args.name).resolve()
    if target.parent != parent or target.exists():
        parser.error("Use a new simple directory name under build/review")
    names = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root)
    manifest = {}
    target.mkdir(parents=True)
    for raw in sorted(set(names.split(b"\0")) - {b""}):
        name = raw.decode("utf-8")
        source = root / name
        if not source.exists():
            continue  # tracked deletion is part of the working-tree candidate
        if source.is_symlink() or not source.resolve().is_relative_to(root):
            raise ValueError(f"External/symlink source is not supported: {name}")
        data = source.read_bytes()
        destination = target / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        if source.name == "gradlew" or source.suffix == ".sh":
            destination.chmod(0o755)
        manifest[name] = hashlib.sha256(data).hexdigest()
    encoded = json.dumps(manifest, sort_keys=True, indent=2) + "\n"
    # Avoid Windows newline translation: the printed hash must match the file bytes.
    (target / "SOURCE_MANIFEST.json").write_bytes(encoded.encode("utf-8"))
    print(f"{len(manifest)} source files: {target}")
    print("Manifest SHA-256: " + hashlib.sha256(encoded.encode()).hexdigest())


if __name__ == "__main__":
    main()
