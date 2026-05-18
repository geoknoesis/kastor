#!/usr/bin/env python3
"""Scan Markdown (and HTML under docs/) for broken relative links.

By default walks the whole repository (excluding common generated/vendor dirs).
Pass `docs` as the first argument to only check the Jekyll site tree.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SKIP_PARTS = frozenset({".git", "node_modules", "_site", "build", ".gradle"})


def _skip_path(path: Path, root: Path) -> bool:
    try:
        rel = path.relative_to(root)
    except ValueError:
        return True
    return bool(SKIP_PARTS.intersection(rel.parts))


def links_in_file(path: Path) -> list[tuple[str, str]]:
    text = path.read_text(encoding="utf-8", errors="replace")
    out: list[tuple[str, str]] = []

    for m in re.finditer(r"\]\(([^)]+)\)", text):
        url = m.group(1).strip().split()[0]
        url = url.split("#")[0].split("?")[0]
        if not url or "{{" in url or "{%" in url:
            continue
        if url.startswith(("http://", "https://", "mailto:", "tel:")):
            continue
        if url.startswith("/"):
            continue
        out.append(("md", url))

    if path.suffix.lower() == ".html":
        for m in re.finditer(r'href=(["\'])([^"\']+)\1', text):
            url = m.group(2).strip()
            url = url.split("#")[0].split("?")[0]
            if not url or "{{" in url or "{%" in url:
                continue
            if url.startswith(("http://", "https://", "mailto:", "tel:")):
                continue
            if url.startswith("/"):
                continue
            out.append(("href", url))

    return out


def main() -> int:
    scan_root = REPO_ROOT / sys.argv[1] if len(sys.argv) > 1 else REPO_ROOT
    if not scan_root.is_dir():
        print(f"Not a directory: {scan_root}", file=sys.stderr)
        return 2

    broken: set[tuple[str, str, str]] = set()
    root_res = scan_root.resolve()

    for path in sorted(scan_root.rglob("*")):
        if not path.is_file():
            continue
        if _skip_path(path, REPO_ROOT):
            continue
        suffix = path.suffix.lower()
        if suffix == ".html" and "docs" not in path.parts:
            continue
        if suffix not in (".md", ".html"):
            continue
        for kind, url in links_in_file(path):
            target = (path.parent / url).resolve()
            try:
                target.relative_to(REPO_ROOT.resolve())
            except ValueError:
                continue
            if not target.exists():
                rel = path.relative_to(REPO_ROOT)
                broken.add((str(rel).replace("\\", "/"), kind, url.replace("\\", "/")))

    for row in sorted(broken):
        print(row[0], row[1], "->", row[2])
    if broken:
        print(f"FAILED: {len(broken)} broken link(s)", file=sys.stderr)
        return 1
    label = str(scan_root.relative_to(REPO_ROOT)) if scan_root != REPO_ROOT else "repository"
    print(f"OK: no broken relative links under {label}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
