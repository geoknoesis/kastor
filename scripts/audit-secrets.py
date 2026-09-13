"""Redacted credential-pattern scan of working files and all reachable Git blobs.

This is a repeatable local check, not proof that no secret exists. Nothing is uploaded.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess

RULES = {
    "private-key": rb"-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY(?: BLOCK)?-----",
    "github-token": rb"\b(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{60,255})\b",
    "aws-access-key": rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
    "slack-token": rb"\bxox[baprs]-[A-Za-z0-9-]{20,}\b",
    "openai-key": rb"\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{40,}\b",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="build/review/secret-audit.json")
    args = parser.parse_args()
    patterns = {name: re.compile(pattern) for name, pattern in RULES.items()}
    findings = []
    def check(data, source):
        for name, pattern in patterns.items():
            if pattern.search(data):
                findings.append({"source": source, "rule": name})
    files = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"])
    working = 0
    for name in sorted(set(files.split(b"\0")) - {b""}):
        path = Path(name.decode("utf-8"))
        if path.is_file():
            check(path.read_bytes(), str(path))
            working += 1
    objects = subprocess.check_output(["git", "rev-list", "--objects", "--all"], text=True, encoding="utf-8").splitlines()
    blobs = 0
    with subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE) as process:
        for entry in objects:
            oid = entry.split(" ", 1)[0]
            process.stdin.write((oid + "\n").encode())
            process.stdin.flush()
            header = process.stdout.readline().decode().split()
            if len(header) != 3:
                raise RuntimeError("Unable to inspect Git object")
            data = process.stdout.read(int(header[2]))
            if len(data) != int(header[2]) or process.stdout.read(1) != b"\n":
                raise RuntimeError("Incomplete Git object")
            if header[1] == "blob":
                check(data, "git:" + oid)
                blobs += 1
        process.stdin.close()
        if process.wait() != 0:
            raise RuntimeError("Git object scan failed")
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"working_files": working, "history_blobs": blobs, "rules": list(RULES), "findings": findings}, indent=2) + "\n", encoding="utf-8")
    print(f"Scanned {working} files and {blobs} historical blobs; {len(findings)} redacted findings. {output}")
    raise SystemExit(1 if findings else 0)


if __name__ == "__main__":
    main()
