#!/usr/bin/env bash
# Regenerates gradle/verification-metadata.xml from scratch so superseded component versions disappear.
#
#   scripts/regenerate-verification-metadata.sh            # rewrite the file, then show a summary
#   scripts/regenerate-verification-metadata.sh --check    # ... and run `check` with strict verification
#
# Gradle's --write-verification-metadata only ever ADDS entries, so every upgrade leaves the old versions
# behind (232 of 1,121 components had several versions on 2026-09-14). Run this periodically (quarterly, or
# after an upgrade family lands) on a machine with at least 8 GB of free memory; it resolves every
# configuration of every project and runs the representative build tasks whose tool classpaths
# (Dokka, JaCoCo, KSP, JMH, dependency-analysis) are only resolved at execution time.
#
# Review the diff before committing: it must only REMOVE components or re-add identical checksums.
# Any changed sha256 for an existing coordinate is a red flag (possible upstream re-publication).
set -euo pipefail
cd "$(dirname "$0")/.."

run_check=false
[ "${1:-}" = "--check" ] && run_check=true

gradlew=./gradlew
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) gradlew=./gradlew.bat ;; esac

metadata=gradle/verification-metadata.xml
backup="$(mktemp)"
cp "$metadata" "$backup"
trap 'echo "Failed: restore with  cp \"$backup\" $metadata" >&2' ERR

# Keep the <configuration> block (verify-metadata, verify-signatures, trusted artifacts); empty <components>.
python3 - "$metadata" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
text, n = re.subn(r"<components>.*</components>", "<components>\n   </components>", text, flags=re.S)
if n != 1:
    sys.exit("verification-metadata.xml has no <components> block")
open(path, "w", encoding="utf-8", newline="\n").write(text)
PY

# Resolution-only pass first (cheap), then the tasks that resolve tool classpaths at execution time.
"$gradlew" --write-verification-metadata sha256 --no-configuration-cache \
  resolveAndLockAll
"$gradlew" --write-verification-metadata sha256 --no-configuration-cache \
  check conformanceSmokeTest buildHealth jacocoTestReport \
  :benchmarks:shacl:jmhCompileGeneratedClasses :benchmarks:shacl-era-cli:classes \
  publishAllPublicationsToStagingRepository -x :rdf:conformance:test

python3 - "$backup" "$metadata" <<'PY'
import re, sys
def components(path):
    text = open(path, encoding="utf-8").read()
    return {m.group(1) for m in re.finditer(r'<component (group="[^"]+" name="[^"]+" version="[^"]+")', text)}
def checksums(path):
    text = open(path, encoding="utf-8").read()
    return dict(re.findall(r'<artifact name="([^"]+)">\s*<sha256 value="([0-9a-f]+)"', text))
old, new = components(sys.argv[1]), components(sys.argv[2])
print(f"components: {len(old)} -> {len(new)} (removed {len(old - new)}, added {len(new - old)})")
for added in sorted(new - old):
    print("  added:", added)
old_sums, new_sums = checksums(sys.argv[1]), checksums(sys.argv[2])
changed = sorted(a for a in new_sums.keys() & old_sums.keys() if new_sums[a] != old_sums[a])
if changed:
    print("CHANGED CHECKSUMS (investigate before committing):", *changed, sep="\n  ")
    sys.exit(1)
PY

if $run_check; then
  "$gradlew" check conformanceSmokeTest -x :rdf:conformance:test
fi
rm -f "$backup"
git status --short -- "$metadata"
