"""Conservatively compare Kotlin/JVM ABI dump trees from two verified builds.

Reject removed/changed declarations and newly required abstract members. This is
an ABI review aid; source compatibility and behavior still require consumer tests.
Both trees must preserve module-relative api/*.api paths. Never regenerate the
baseline from candidate sources.
"""
import argparse
import json
from pathlib import Path


def read(root):
    result = {}
    for path in root.rglob("*.api"):
        if any(part in {"build", ".gradle", ".git"} for part in path.relative_to(root).parts):
            continue
        if "api" not in path.relative_to(root).parts:
            continue
        classes = {}
        current = None
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith("//"):
                continue
            if line.endswith("{") and not line.startswith((" ", "\t")):
                current = line
                classes[current] = set()
            elif line == "}":
                current = None
            elif current is not None:
                classes[current].add(line.strip())
            else:
                raise ValueError(f"Unrecognized ABI syntax in {path}: {line}")
        result[str(path.relative_to(root))] = classes
    if not result:
        raise ValueError(f"No ABI dumps in {root}")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--output", type=Path, default=Path("build/review/api-comparison.json"))
    args = parser.parse_args()
    old, new = read(args.baseline), read(args.candidate)
    issues, additions = [], []
    for module, classes in old.items():
        if module not in new:
            issues.append(f"Missing module: {module}")
            continue
        for declaration, members in classes.items():
            if declaration not in new[module]:
                issues.append(f"Removed/changed class: {module}: {declaration}")
                continue
            for member in members - new[module][declaration]:
                issues.append(f"Removed/changed member: {module}: {declaration}: {member}")
            for member in new[module][declaration] - members:
                additions.append(f"{module}: {declaration}: {member}")
                if " abstract " in " " + member + " ":
                    issues.append(f"New abstract requirement: {module}: {declaration}: {member}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"baseline": str(args.baseline), "candidate": str(args.candidate),
        "issues": issues, "additions_to_existing_classes": additions}, indent=2) + "\n", encoding="utf-8")
    print(f"Compared {len(old)} ABI dumps: {len(issues)} review blockers; {len(additions)} member additions. {args.output}")
    raise SystemExit(1 if issues else 0)


if __name__ == "__main__":
    main()
