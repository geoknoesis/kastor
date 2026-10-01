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
| 6 | JUnit Jupiter / Platform | **done 2026-10-01**: 6.1.3 (one `junit` catalog version for both) | 6.1.x | Major version (JDK 17+ baseline, removed deprecated APIs). No test code used a removed API. The same tests run and skip as on 5.13.4, so `scripts/test-skip-allowlist.json` and the executed-test floors are unchanged. Only visible change: JUnit 6 quotes string arguments in `@ParameterizedTest` display names (`[1] "jena"` instead of `[1] jena`); no allowlist entry or required suite depends on those names. Supersedes Dependabot PRs #24 and #25. |
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
| #25 + #24 | JUnit Jupiter 5.13.4 -> 6.1.3, Platform launcher 1.13.4 -> 6.1.3 | **Done 2026-10-01 (step 6)** | Applied together on one branch; close both PRs as superseded. JUnit 6 raises the baseline to Java 17 (we build on 21) and removes deprecated APIs, none of which the tests used. The conditional-execution annotations behind `scripts/test-skip-allowlist.json` skip the same tests, and the executed-test floors in `ci.yml`/`release-readiness.yml` count the same tests (4,419 locally, as before). `.github/dependabot.yml` now groups `org.junit*` for all update types so the two artifacts are never proposed separately again. |
| #23 | RDF4J 5.3.1 -> 6.1.0 (latest 6.x; 6.0.1 has the same constraints) | **Blocked: needs a Java 25 baseline decision** (spiked 2026-10-01, see below) | Every RDF4J 6.x jar is compiled for Java 25 (class file version 69); Kastor builds, tests and documents JDK 21. On top of that the upgrade is a redesign of the RDF-star compatibility layer in `rdf-rdf4j`, not a rename. PR #23 is closed and `.github/dependabot.yml` ignores RDF4J major versions until the baseline question is decided; RDF4J 5.3.2 is available as a patch update. |
| #22 (group), superseded by #28 | onnxruntime 1.30.0, djl tokenizers 0.38.0, kotlinpoet 2.4.0, serialization 1.11.0, coroutines 1.11.0, clikt 5.1.0, slf4j 2.0.20, httpcore5 5.4.4, lz4-java 1.12.0, dependency-analysis 3.19.2, wrapper 9.8.0 | **Done 2026-10-01** (steps 3-5 and 7) | Applied from PR #28 with refreshed locks and verification metadata. dependency-analysis 3.19.2 resolves its own kotlin-metadata-jvm, so the buildscript pin was dropped (see below). |
| #12 | httpclient5 5.6.1 | **Close** | Older than the 5.6.4 security floor already in `gradle/build-platform`. |

## RDF4J 6 spike (2026-10-01, branch `deps/rdf4j6`)

Outcome: **not applied**. `rdf4j = "6.1.0"` resolves and locks cleanly, but the build cannot be made green on JDK 21.

Blockers, in order:

1. **Java baseline.** All 47 RDF4J 6.1.0 jars on the `rdf-rdf4j` runtime classpath have class file version 69 (the
   RDF4J parent POM sets `java.version` 25 for 6.0.0, 6.0.1 and 6.1.0). On JDK 21 they fail to load with
   `UnsupportedClassVersionError`. The Kotlin compiler still reads them, so `compileKotlin` reports only the API errors
   below; every test JVM would fail. Adopting RDF4J 6 means moving the toolchain (`jvmToolchain(21)`, the daemon pin in
   `gradle/gradle-daemon-jvm.properties`, the nine `java-version: "21"` workflow entries, `release-smoke`) and the
   documented consumer requirement ("JDK 21+") to Java 25, at least for every module that loads RDF4J: `rdf-rdf4j`,
   `rdf-rdf4j-reasoning`, `kastor-gen-validation-rdf4j`, and the tests of `testkit`, `conformance`, `shacl-validation`,
   `jena-reasoning`, `examples` and the SHACL benchmarks.
2. **RDF-star model removed.** `org.eclipse.rdf4j.model.Triple` (a `Resource`, usable as a subject) is gone. RDF 1.2
   triple terms are `org.eclipse.rdf4j.model.TripleTerm`, a `Value` that is not a `Resource`, created with
   `ValueFactory.createTripleTerm`. This is the only removed class among the 50 RDF4J types Kastor imports, and it
   causes all 62 compile errors (`Rdf4jTerms.kt`, `Rdf4jGraph.kt`, `Rdf4jRepository.kt`, `Rdf4jFormatSupport.kt`;
   downstream modules were not reached). The quoted-triple-subject machinery built on it has no equivalent and has to
   be deleted rather than ported: deterministic `kastor-star-` reifier blank nodes, `QuotedLevel` tracking, the
   explicit `rdf:reifies` bookkeeping, and about 520 lines of tests (`Rdf4jRdfStarSubjectTest`,
   `Rdf4jReifierLimitsTest`, `Rdf4jReifierRoundTripTest`, `Rdf4jReifiesSetSemanticsTest`).
3. **Behaviour changes to adopt** (measured with a standalone probe on JDK 25):
   - Rio parses `<< s p o >> q z` and annotations into `_:b rdf:reifies <<( s p o )>>` with fresh blank nodes, reads and
     writes `<<( s p o )>>`, and writers emit a `VERSION "1.2"` / `@version` header.
   - Literals carry a native base direction (`createLiteral(label, lang, BaseDirection)`, datatype `rdf:dirLangString`).
     `createLiteral("x", "ar--rtl")` is a different, direction-less literal, so the `lang--dir` tag encoding must be
     replaced, and native stores written by Kastor on RDF4J 5 hold the encoded form (data migration or a read fallback).
   - `NativeStore` now stores triple terms, so the `native*` variants would advertise them.
   - SPARQL: `LANG` returns the plain tag, `LANGDIR`/`hasLANGDIR`, `"x"@ar--rtl`, `VERSION "1.2"`, `<<( )>>` in triple
     patterns and `setBinding` with a triple term all work. `<<( )>>` inside `FILTER` is still a syntax error and
     `BIND(<<( s p o )>> AS ?t)` yields no row, so `TRIPLE(...)` is the form to use in expressions.
     `Rdf4jRepository.sparqlConstant` writes `<< s p o >>`, which now means a reified triple, and must be rewritten.
   - Capabilities (`rdfVersion`, `supportsBaseDirection`, `sparqlVersion`, advertised `-1.2` formats), the conformance
     skip allowlist (RDF4J baseline 1050 run / 175 skipped) and the provider docs change accordingly.
4. **Footprint.** RDF4J 6 adds Jackson 3 (`tools.jackson` 3.2.1), `datasketches-java` 9.0.0, `httpclient5` 5.6.4 /
   `httpcore5` 5.4.3 and `rdf4j-http-client-{api,jdk,apache5}`, and raises `jsonld-java` to 0.13.6 and `commons-text`
   to 1.15.0. `scripts/audit-dependencies.py` and the staging gate were not run against this set.

Not a blocker: OWL API 4.5.29 pulls RDF4J Rio **2.3.2** into `rdf-reasoning-hermit` (and the `onto-quality` tools).
No module in this build combines it with `rdf-rdf4j`, so those lockfiles do not change. A consumer that puts both on
one classpath already gets a mixed RDF4J graph today; with 6.x OWL API's Rio parsers would run against a major version
four releases newer, so that combination should be treated as unsupported until step 9.

The Kotlin ABI of the three modules names only `Repository`, `Resource`, `Statement` and `Value`, which all still
exist, so the ABI dumps would stay unchanged. The break for consumers is the Java baseline, the RDF4J major on their
classpath, and the changed reification and base-direction behaviour.

Estimate once Java 25 is accepted: 3 to 5 days. Toolchain and CI move (0.5 to 1 day, including checking JaCoCo,
Dokka and KSP on Java 25); provider rewrite and replacement tests (1.5 to 2 days); SPARQL bindings, capabilities,
conformance allowlist and SHACL sail checks (1 day); docs, migration note and release gates (0.5 day).

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
| `dependabot/gradle/org.junit.jupiter-junit-jupiter-6.0.3` | JUnit Jupiter 6.0.3 | Step 6, done with 6.1.3: close. |
| `dependabot/gradle/org.junit.platform-junit-platform-launcher-6.0.3` | JUnit Platform 6.0.3 | Step 6, done with 6.1.3: close. |

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
