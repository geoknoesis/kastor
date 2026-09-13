# Dependency upgrade plan

Status as of 2026-09-13 (version `0.3.0-SNAPSHOT`). The audit-remediation round deliberately did
**not** upgrade libraries. Every bump rewrites module lockfiles and `gradle/verification-metadata.xml`,
and those files were being edited concurrently by several remediation branches. Upgrades happen as
follow-up pull requests, **one family per PR**, in the order below.

## Procedure for each upgrade

1. Change the version in `gradle/libs.versions.toml` only; no literal versions in build files.
2. Refresh locks: `./gradlew resolveAndLockAll --write-locks`, then review the lockfile diff.
3. Refresh checksums: `./gradlew --write-verification-metadata sha256 resolveAndLockAll`, then remove
   components that are no longer referenced (for example `ai.djl:*:0.28.0`).
4. Run `./gradlew check conformanceSmokeTest` and `python scripts/audit-dependencies.py`.
5. Update `CHANGELOG.md` under *Changed (build)*.

## Order and rationale

| # | Dependency | Current | Target | Why / risk |
|---|---|---|---|---|
| 1 | GitHub Actions (SHA-pinned) | see workflows | latest patch in same major | No build impact. Dependabot keeps SHAs and `# vX.Y.Z` comments in sync. |
| 2 | `me.champeau.jmh` plugin | 0.7.2 | 0.7.3 | Benchmarks only. |
| 3 | `kotlinx-coroutines` | 1.10.2 | 1.11.x | Check Kotlin 2.4 compatibility; public API exposure in `rdf-core` flows. |
| 4 | `kotlinpoet` / `kotlinpoet-ksp` | 2.2.0 | 2.3.x | Code generator output may change formatting. Regenerate the gen golden snapshots and review. |
| 5 | `kotlinx-serialization-json` | 1.8.1 | 1.11.x | Used by onto-quality LLM tooling and benchmarks. Align the serialization compiler plugin (Kotlin 2.4). Also replace the literal `1.8.1` in `benchmarks/shacl/jmh/build.gradle.kts` with the catalog entry. |
| 6 | JUnit Jupiter / Platform | 5.13.4 / 1.13.4 | 6.0.x | Major version (JDK 17+ baseline, removed deprecated APIs). Check the `@EnabledIf`/assumption usage behind the skip allowlist and update `scripts/test-skip-allowlist.json` if names change. |
| 7 | ONNX Runtime | 1.18.0 | 1.26.x | Native binaries: verify the ownership/close lifecycle with `native-lifecycle.yml` on Linux and Windows before merging. Large verification-metadata churn. |
| 8 | `ai.koog:koog-agents` | 0.8.0 (pre-1.0) | 1.0.x | Breaking API changes expected. Isolated to `onto-quality-llm-koog`. Coordinate with its explanation tests (OpenAI tests stay opt-in). |
| 9 | OWL API + HermiT | 4.5.29 + 1.4.5.519 | OWL API 5.x with a maintained HermiT build | Largest change: OWL API 5 changes the Guava/RDF4J transitive graph and package APIs. Needs a spike: run the reasoning-hermit tests, the release-contract resource/timeout checks and a dependency audit. Consider an alternative reasoner if HermiT has no OWL API 5 build. |

Security floors (Jackson, Netty, OpenTelemetry, Guava, HttpComponents, mime4j, Thrift, jsoup,
commons-lang3, lz4) live in `gradle/build-platform` and the catalog. Raise them as part of the
same procedure when advisories appear. They are build-internal and are not published to consumers.

## Open Dependabot branches (origin)

These branches predate the remediation round. Several are stale because the default branch is
already newer. Close or rebase them rather than merging as-is:

| Branch | Proposed | Note |
|---|---|---|
| `dependabot/github_actions/actions-184f916e35` | grouped action bumps | Superseded by SHA pinning; Dependabot will reopen with SHA updates. |
| `dependabot/gradle/com.microsoft.onnxruntime-onnxruntime-1.26.0` | onnxruntime 1.26.0 | Step 7. |
| `dependabot/gradle/com.squareup-kotlinpoet-2.3.0` | kotlinpoet 2.3.0 | Step 4 (merge with the `-ksp` branch). |
| `dependabot/gradle/com.squareup-kotlinpoet-ksp-2.3.0` | kotlinpoet-ksp 2.3.0 | Step 4. |
| `dependabot/gradle/coroutines-1.11.0` | coroutines 1.11.0 | Step 3. |
| `dependabot/gradle/gradle-wrapper-9.5.1` | wrapper 9.5.1 | Already on 9.5.1: close. |
| `dependabot/gradle/me.champeau.jmh-0.7.3` | jmh plugin 0.7.3 | Step 2. |
| `dependabot/gradle/org.apache.httpcomponents.client5-httpclient5-5.6.1` | httpclient5 5.6.1 | Older than the current 5.6.4 floor: close. |
| `dependabot/gradle/org.jetbrains.kotlinx-kotlinx-serialization-json-1.11.0` | serialization 1.11.0 | Step 5. |
| `dependabot/gradle/org.junit.jupiter-junit-jupiter-6.0.3` | JUnit Jupiter 6.0.3 | Step 6 (together with the platform launcher branch). |
| `dependabot/gradle/org.junit.platform-junit-platform-launcher-6.0.3` | JUnit Platform 6.0.3 | Step 6. |

Dependabot PRs cannot pass on their own because they don't refresh lockfiles or verification
metadata. Check out the branch, run steps 2–4 of the procedure, and push.

## Verification metadata hygiene

- `gradle/verification-metadata.xml` still lists superseded components (for example `ai.djl:api:0.28.0`
  next to `0.31.1`). Prune them with a full `--write-verification-metadata sha256` run on a machine
  with enough memory, then review the diff.
- `verify-signatures` is `false`: only SHA-256 checksums are verified. Enabling PGP signature
  verification (with `<trusted-keys>`) would detect a compromised upstream re-publication that
  checksums recorded after the fact cannot. Recommended once the dependency set is stable.
