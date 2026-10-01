#!/usr/bin/env bash
# Regenerates Gradle dependency locks and SHA-256 verification metadata after a version change
# (for example on a Dependabot branch), then shows the resulting diff.
#
#   git fetch origin && git switch dependabot/gradle/<branch>
#   scripts/refresh-dependency-locks.sh            # rewrite locks + verification metadata
#   scripts/refresh-dependency-locks.sh --commit   # ... and commit them on the current branch
#
# Dependabot updates gradle/libs.versions.toml but not gradle.lockfile, settings-gradle.lockfile or
# gradle/verification-metadata.xml, so its Gradle PRs fail CI until this runs. It is deliberately
# manual: an automatic `pull_request_target` job would execute Gradle (build scripts and freshly
# resolved plugins) from the PR with a write-scoped token.
#
# Review the verification-metadata diff: new checksums must belong to the artifacts the PR updates.
set -euo pipefail
cd "$(dirname "$0")/.."

commit=false
[ "${1:-}" = "--commit" ] && commit=true

gradlew=./gradlew
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) gradlew=./gradlew.bat ;; esac

# One pass rewrites the locks and records the checksums of what they now resolve: verification is strict
# and the old locks pin the old versions strictly, so neither can be refreshed before the other. A second pass
# adds cyclonedxDirectBom, because the SBOM tasks read dependency POMs that ordinary resolution of
# Gradle-metadata modules never downloads.
"$gradlew" --write-verification-metadata sha256 --write-locks resolveAndLockAll --no-configuration-cache
"$gradlew" --write-verification-metadata sha256 resolveAndLockAll cyclonedxDirectBom --no-configuration-cache

git status --short -- '*.lockfile' gradle/verification-metadata.xml
if $commit; then
  git add -- '*.lockfile' gradle/verification-metadata.xml
  if git diff --cached --quiet; then
    echo "Locks already up to date"
  else
    git commit -m "build(deps): refresh dependency locks and verification metadata"
  fi
fi
