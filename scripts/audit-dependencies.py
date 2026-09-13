"""Query OSV for every pinned Maven coordinate; fail closed on errors/findings.

Only public package coordinates are sent. No source or credentials are uploaded.
API contract: https://google.github.io/osv.dev/post-v1-querybatch/

With --baseline-ref (pull requests), only advisories on coordinates that are new relative to
that ref fail the run; advisories on coordinates already locked at the baseline are reported
but do not block unrelated changes. Network/API errors still fail closed.
"""
import argparse
import datetime
import json
from pathlib import Path
import subprocess
import urllib.request


def request(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request("https://api.osv.dev/v1/" + path, data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def parse_locks(named_texts):
    coordinates = {}
    for name, text in named_texts:
        for line in text.splitlines():
            if line.startswith("#") or "=" not in line:
                continue
            coordinate = line.split("=", 1)[0]
            if len(coordinate.split(":")) == 3:
                coordinates.setdefault(coordinate, []).append(name)
    return coordinates


def working_tree_locks():
    files = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "*gradle.lockfile"], text=True).splitlines()
    return parse_locks((name, Path(name).read_text(encoding="utf-8")) for name in sorted(set(files)))


def baseline_locks(ref):
    names = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", ref], text=True).splitlines()
    names = [name for name in names if name.endswith("gradle.lockfile")]
    return parse_locks((name, subprocess.check_output(["git", "show", f"{ref}:{name}"], text=True)) for name in names)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="build/review/dependency-audit.json")
    parser.add_argument("--baseline-ref", help="Only fail on advisories for coordinates absent at this git ref")
    args = parser.parse_args()
    coordinates = working_tree_locks()
    if not coordinates:
        raise SystemExit("No dependency locks found; audit cannot pass")
    results = []
    items = sorted(coordinates)
    for start in range(0, len(items), 100):
        pending = [(coordinate, {"package": {"ecosystem": "Maven", "name": coordinate.rsplit(":", 1)[0]},
                                 "version": coordinate.rsplit(":", 1)[1]}) for coordinate in items[start:start+100]]
        findings = {coordinate: set() for coordinate, _ in pending}
        while pending:
            responses = request("querybatch", {"queries": [query for _, query in pending]})["results"]
            if len(responses) != len(pending):
                raise RuntimeError("Incomplete OSV response")
            again = []
            for (coordinate, query), response in zip(pending, responses):
                findings[coordinate].update(v["id"] for v in response.get("vulns", []))
                if response.get("next_page_token"):
                    again.append((coordinate, {**query, "page_token": response["next_page_token"]}))
            pending = again
        results.extend({"coordinate": coordinate, "locks": sorted(coordinates[coordinate]), "advisories": sorted(ids)}
                       for coordinate, ids in findings.items())
    ids = sorted({advisory for result in results for advisory in result["advisories"]})
    details = {advisory: request("vulns/" + advisory) for advisory in ids}
    baseline = baseline_locks(args.baseline_ref) if args.baseline_ref else None
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                 "source": "https://api.osv.dev", "baseline_ref": args.baseline_ref,
                                 "packages": results, "details": details}, indent=2) + "\n", encoding="utf-8")
    print(f"Audited {len(results)} pinned coordinates: {len(ids)} advisory IDs; report: {output}")
    blocking = []
    for result in results:
        if not result["advisories"]:
            continue
        preexisting = baseline is not None and result["coordinate"] in baseline
        label = " (pre-existing at baseline; not blocking)" if preexisting else ""
        print(result["coordinate"] + ": " + ", ".join(result["advisories"]) + label)
        if not preexisting:
            blocking.append(result["coordinate"])
    if baseline is not None:
        print(f"{len(blocking)} vulnerable coordinate(s) introduced relative to {args.baseline_ref}")
    raise SystemExit(1 if blocking else 0)


if __name__ == "__main__":
    main()
