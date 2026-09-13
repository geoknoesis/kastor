"""Query OSV for every pinned Maven coordinate; fail closed on errors/findings.

Only public package coordinates are sent. No source or credentials are uploaded.
API contract: https://google.github.io/osv.dev/post-v1-querybatch/
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="build/review/dependency-audit.json")
    args = parser.parse_args()
    files = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "*gradle.lockfile"], text=True).splitlines()
    coordinates = {}
    for name in set(files):
        for line in Path(name).read_text(encoding="utf-8").splitlines():
            if line.startswith("#") or "=" not in line:
                continue
            coordinate = line.split("=", 1)[0]
            if len(coordinate.split(":")) == 3:
                coordinates.setdefault(coordinate, []).append(name)
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
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                                 "source": "https://api.osv.dev", "packages": results, "details": details}, indent=2) + "\n", encoding="utf-8")
    print(f"Audited {len(results)} pinned coordinates: {len(ids)} advisory IDs; report: {output}")
    for result in results:
        if result["advisories"]:
            print(result["coordinate"] + ": " + ", ".join(result["advisories"]))
    raise SystemExit(1 if ids else 0)


if __name__ == "__main__":
    main()
