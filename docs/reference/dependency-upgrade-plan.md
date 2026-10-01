# Dependency upgrade plan

Status as of 2026-09-14 (version `0.3.0-SNAPSHOT`). The audit-remediation round deliberately did
**not** upgrade libraries. Every bump rewrites module lockfiles and `gradle/verification-metadata.xml`,
and those files were being edited concurrently by several remediation branches. Upgrades happen as
follow-up pull requests, **one family per PR**, in the order below.

## Procedure for each upgrade

1. Change the version in `gradle/libs.versions.toml` only; no literal versions in build files.
2. Refresh locks and checksums with `scripts/refresh-dependency-locks.sh` (verification is strict, so the
   order of its three Gradle passes matters), then review the lockfile diff.
3. Record the tool classpaths that are only resolved at execution time by running the build once with
   `--write-verification-metadata sha256` (`check buildHealth jacocoTestReport publishAllPublicationsToStagingRepository`
   and the benchmark compile tasks), then remove components that are no longer referenced (for example
   `ai.djl:*:0.28.0`) and confirm with an empty Gradle user home (`-g <empty dir>`) that the build still verifies.
4. Run `./gradlew check conformanceSmokeTest` and `python scripts/audit-dependencies.py`.
5. Update `CHANGELOG.md` under *Changed (build)*.

## Order and rationale

| # | Dependency | Current | Target | Why / risk |
|---|---|---|---|---|
| 1 | GitHub Actions (SHA-pinned) | **done 2026-09-14**: checkout v7.0.1, setup-java v6.0.1, upload-artifact v7.0.1, download-artifact v8.0.1, configure-pages v6.0.0, upload-pages-artifact v5.0.0 | current majors (Node 24) | No build impact. Supersedes Dependabot PR #21. Dependabot keeps SHAs and `# vX.Y.Z` comments in sync. |
| 2 | `me.champeau.jmh` plugin | **done 2026-09-14**: 0.7.3 | 0.7.3 | Benchmarks only. The plugin is not in any lockfile; only its verification-metadata entries changed. Taken from Dependabot PR #22 (the rest of that group stays pending, steps 3-5 and 7). |
| 3 | `kotlinx-coroutines` | **done 2026-10-01**: 1.11.0 | 1.11.x | Kotlin 2.4.20 compiles and tests against it; no source changes were needed. From Dependabot PR #28. |
| 4 | `kotlinpoet` / `kotlinpoet-ksp` | **done 2026-10-01**: 2.4.0 | 2.4.x | Code generator output may change formatting; the processor and gradle-plugin generation tests gate it. From Dependabot PR #28. |
| 5 | `kotlinx-serialization-json` | **done 2026-10-01**: 1.11.0 | 1.11.x | Used by onto-quality LLM tooling and benchmarks (`benchmarks/shacl/jmh` already uses the catalog entry). From Dependabot PR #28. |
| 6 | JUnit Jupiter / Platform | 5.13.4 / 1.13.4 | 6.1.x | Major version (JDK 17+ baseline, removed deprecated APIs). Check the `@EnabledIf`/assumption usage behind the skip allowlist and update `scripts/test-skip-allowlist.json` if names change. |
| 7 | ONNX Runtime (with DJL tokenizers) | **done 2026-10-01**: 1.30.0 (DJL tokenizers 0.38.0) | 1.30.x | Native binaries: verified locally on Windows with the opt-in embedding tests; `native-lifecycle.yml` must still pass on Linux and Windows before merging. Large verification-metadata churn. From Dependabot PR #28. |
| 8 | `ai.koog:koog-agents` | 0.8.0 (pre-1.0) | 1.0.x | Breaking API changes expected. Isolated to `onto-quality-llm-koog`. Coordinate with its explanation tests (OpenAI tests stay opt-in). |
| 9 | OWL API + HermiT | 4.5.29 + 1.4.5.519 | OWL API 5.x with a maintained HermiT build | Largest change: OWL API 5 changes the Guava/RDF4J transitive graph and package APIs. Needs a spike: run the reasoning-hermit tests, the release-contract resource/timeout checks and a dependency audit. Consider an alternative reasoner if HermiT has no OWL API 5 build. |

Security floors (Jackson, Netty, OpenTelemetry, Guava, HttpComponents, mime4j, Thrift, jsoup,
commons-lang3, lz4) live in `gradle/build-platform` and the catalog. Raise them as part of the
same procedure when advisories appear. They are build-internal and are not published to consumers.

## Major upgrades: triage (2026-09-14)

Majors are never applied by the grouped minor/patch PR. Each gets its own branch following the procedure above.

| Dependabot PR | Upgrade | Decision | Reason / preconditions |
|---|---|---|---|
| #25 + #24 | JUnit Jupiter 5.13.4 -> 6.1.3, Platform launcher 1.13.4 -> 6.1.3 | **Deferred, planned (step 6)** | Must move together (one PR; close one of the two). JUnit 6 raises the baseline to Java 17 (we build on 21) and removes deprecated APIs. Check the conditional-execution annotations behind `scripts/test-skip-allowlist.json` and the executed-test floors in `ci.yml`/`release-readiness.yml` still count the same tests. |
| #23 | RDF4J 5.3.1 -> 6.0.1 | **Deferred, needs a spike** | Public API exposure: `rdf-rdf4j`, `rdf-rdf4j-reasoning` and `kastor-gen-validation-rdf4j` surface RDF4J types, so this is a breaking change for Kastor consumers and needs a minor Kastor release note. Check the RDF4J 6 migration notes (Java baseline, removed deprecated `Repository`/`Sail` APIs, SHACL sail changes), rerun the RDF 1.2 conformance corpus and the persistence/lifecycle tests, and review OWL API 4's transitive RDF4J pin (step 9) which may conflict. |
| #22 (group), superseded by #28 | onnxruntime 1.30.0, djl tokenizers 0.38.0, kotlinpoet 2.4.0, serialization 1.11.0, coroutines 1.11.0, clikt 5.1.0, slf4j 2.0.20, httpcore5 5.4.4, lz4-java 1.12.0, dependency-analysis 3.19.2, wrapper 9.8.0 | **Done 2026-10-01** (steps 3-5 and 7) | Applied from PR #28 with refreshed locks and verification metadata. dependency-analysis 3.19.2 resolves its own kotlin-metadata-jvm, so the buildscript pin was dropped (see below). |
| #12 | httpclient5 5.6.1 | **Close** | Older than the 5.6.4 security floor already in `gradle/build-platform`. |

## Temporary pins

None at present.

- **Removed 2026-10-01:** `build.gradle.kts` pinned `org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.20` in its
  `buildscript` classpath because dependency-analysis 3.12.0 bundled kotlin-metadata-jvm 2.2.x, which cannot read
  Kotlin 2.4 metadata. dependency-analysis 3.19.2 resolves kotlin-metadata-jvm 2.4.10 through its own
  `dependencyAnalysisKotlinMetadataClasspath` configuration (visible in the module lockfiles) and `./gradlew buildHealth`
  runs on Kotlin 2.4.20 without the pin. `scripts/check-build-pins.py` (CI) still compares such a literal with `kotlin`
  in `gradle/libs.versions.toml` if the pin is ever reintroduced; a missing pin passes.

## Open Dependabot branches (origin, historical list from 2026-09-13)

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

- `gradle/verification-metadata.xml` still lists superseded components: 232 of 1,121 components have more
  than one version (for example `ai.djl:api:0.28.0` next to `0.31.1`). Gradle only ever adds entries, so they
  accumulate with every upgrade. Regenerate the file periodically (quarterly, or after each upgrade family)
  on a machine with at least 8 GB free memory with `scripts/regenerate-verification-metadata.sh`, which
  starts from an empty component list, resolves every configuration and runs the build and consumer checks. Review the
  diff: it must only remove components (or re-add identical checksums), never change an existing checksum.
- `verify-signatures` is `false`: only SHA-256 checksums are verified. Enabling PGP signature
  verification (with `<trusted-keys>`) would detect a compromised upstream re-publication that
  checksums recorded after the fact cannot. Recommended once the dependency set is stable.
