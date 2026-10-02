# Contributing to Kastor

Thank you for your interest in improving Kastor. This document explains how to build the project, run tests, and submit changes.

## Prerequisites

- **JDK 21+** installed locally. The build uses a JDK 21 toolchain and never downloads JDKs (`org.gradle.java.installations.auto-download=false`).
- Python 3.10+ for the verification scripts under [`scripts/`](scripts/).
- No global Gradle install is needed; use the wrapper (`./gradlew` / `gradlew.bat`).

**Repo layout:** see [**Repository architecture**](docs/kastor/concepts/architecture.md) (modules, layers, Gradle targets) and [**Physical repository layout**](docs/kastor/concepts/architecture.md#physical-repository-layout) (on-disk grouping vs `:module:` paths).

## Build and test

From the repository root:

```bash
./gradlew check -x :rdf:conformance:test
./gradlew conformanceSmokeTest
```

- **`check`** runs tests, ABI checks (`checkKotlinAbi`, wired into `check` by the Kotlin Gradle plugin's `abiValidation {}` for published modules), per-module coverage floors (`jacocoTestCoverageVerification`) and BOM completeness (`:bom:verifyBomCoverage`). The heavy RDF 1.2 corpus (`:rdf:conformance:test`) is excluded here.
- **`conformanceSmokeTest`** — a fast RDF harness check that uses a **bundled fixture** in `:rdf:conformance`.

**Full W3C RDF 1.2 and SHACL 1.2 suites.** Fetch the pinned corpora (the same commits CI uses) into their git-ignored locations:

```bash
python scripts/fetch-conformance-data.py      # rdf/conformance/test-data + rdf/shacl/validation/test-data/w3c-shacl12
./gradlew :rdf:conformance:test :rdf:shacl-validation:test
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

### Memory and parallelism

Test JVMs are capped by the root build: `maxHeapSize = 1g`, one fork per test task. Override the caps for your machine in **`~/.gradle/gradle.properties`**, not in the repository:

```properties
kastor.test.maxHeap=2g
kastor.test.maxParallelForks=2
org.gradle.workers.max=2
```

On memory-constrained machines, avoid running several Gradle builds at once. Every daemon, Kotlin daemon, test JVM and Gradle TestKit build is a separate JVM.

### Dependencies, locks and verification metadata

All versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Every configuration is locked, and artifacts are checksum-verified against [`gradle/verification-metadata.xml`](gradle/verification-metadata.xml). After changing a dependency:

```bash
./gradlew resolveAndLockAll --write-locks
./gradlew --write-verification-metadata sha256 resolveAndLockAll
```

Review both diffs in the same pull request.

**Dependabot Gradle PRs.** Dependabot bumps `gradle/libs.versions.toml` but cannot refresh the lockfiles or `verification-metadata.xml`, so its Gradle PRs fail CI until a maintainer runs the refresh on the PR branch:

```bash
git fetch origin && git switch dependabot/gradle/<branch>
scripts/refresh-dependency-locks.sh --commit    # resolveAndLockAll --write-locks + --write-verification-metadata sha256
git push
```

Check that new checksums belong to the artifacts the PR updates. This step is deliberately not automated: a `pull_request_target` job would run Gradle from the PR with a write-scoped token. Minor and patch bumps are grouped into one weekly PR (`.github/dependabot.yml`); major upgrades follow the upgrade plan. The JUnit artifacts (`org.junit*`) share one catalog version, so they have their own group covering every update type. Repositories are declared only in `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`); `mavenLocal()` is not used. Third-party security floors belong in [`gradle/build-platform`](gradle/build-platform/build.gradle.kts), which is never published. [`kastor-bom`](bom/build.gradle.kts) lists Kastor modules only. See the [dependency upgrade plan](docs/reference/dependency-upgrade-plan.md).

### Tests that skip

Known skips (backend limitations, opt-in native/remote tests) are listed in [`scripts/test-skip-allowlist.json`](scripts/test-skip-allowlist.json), keyed by test class and test name. CI fails when a test skips without being listed. If you add an intentionally skipping test, add it to the allowlist with a reason. Every entry needs a non-empty reason without absolute machine paths (`file:///`, `C:\`, `/home/...`); `scripts/check-test-results.py` enforces this. If you fix a limitation, remove its entry; the gate reports allowlisted tests that now run.

Suites that must never disappear are named with `--require-suite CLASS=MIN`. The suite must execute at least `MIN` tests, and only allowlisted skips are tolerated. This is how CI detects the W3C SHACL harness silently falling back to its 4-case bundled fixture (`Shacl12NativeConformanceTest=150`).

### Automation reference

| Workflow | When it runs | Role |
|----------|----------------|------|
| [`ci.yml`](.github/workflows/ci.yml) | Push & PR to `main` / `master`; weekly | `check`, conformance smoke, test-count/skip gate, docs version check, `buildHealth` (Linux, Windows; macOS on push/schedule); `shacl-w3c-suite` runs the pinned W3C SHACL 1.2 corpus (Linux) |
| [`conformance.yml`](.github/workflows/conformance.yml) | Weekly + manual | Full upstream RDF 1.2 and SHACL 1.2 corpora |
| [`release-readiness.yml`](.github/workflows/release-readiness.yml) | Manual; called by `publish.yml` | Staging, metadata inspection, independent consumer builds, dependency audit (Linux, Windows); `local-signing` restages HEAD with an ephemeral key and verifies every signature |
| [`publish.yml`](.github/workflows/publish.yml) | Tag `vX.Y.Z` | Release readiness, then signed Maven Central Portal upload from the protected `release` environment; waits for Central validation |
| [`dependency-review.yml`](.github/workflows/dependency-review.yml) | Pull requests | OSV audit of dependencies introduced by the PR, secret-pattern scan, GitHub dependency review |
| [`native-lifecycle.yml`](.github/workflows/native-lifecycle.yml) | Manual | Opt-in ONNX embedding lifecycle and soak tests |
| [`wrapper-validation.yml`](.github/workflows/wrapper-validation.yml) | When `gradle/wrapper/**` changes | Validates official `gradle-wrapper.jar` checksums |
| [`pages.yml`](.github/workflows/pages.yml) | Push to `main` / `master` & manual | Jekyll build from `docs/` → GitHub Pages |

Actions are pinned to full commit SHAs, with the release tag as a trailing comment. Dependabot updates both. Every workflow declares least-privilege `permissions`.

Useful variants:

- **Single module:** `./gradlew :rdf:core:test` or `./gradlew :kastor-gen:runtime:test`
- **Examples:** `./gradlew :examples:dcat-us:check` (if you touch example code)
- **Dependency hygiene:** `./gradlew buildHealth` — Dependency Analysis aggregate advice; see [Repository architecture — Dependency hygiene](docs/kastor/concepts/architecture.md#dependency-hygiene-automated).

## Pull requests

1. **Fork** the repository and create a **feature branch** from `main`.
2. Keep changes **focused** on one concern when possible (easier review, cleaner history).
3. **Run tests** locally before opening a PR (at minimum `./gradlew check -x :rdf:conformance:test conformanceSmokeTest`). Fix any failures and add tests for new behavior.
4. In the PR description, explain **what** changed and **why** (link issues with `Fixes #123` when applicable).
5. Match existing **Kotlin style** and patterns in the touched modules; avoid unrelated reformatting.

## Documentation

- User-facing docs live under [`docs/`](docs/). The **published site** is built with **Jekyll** by [`pages.yml`](.github/workflows/pages.yml). Internal material (`docs/project/reviews/`, `docs/superpowers/`) is excluded from the site.
- **Local preview:** from the `docs/` directory, run `bundle install` then `bundle exec jekyll serve --livereload` (see [`docs/Gemfile`](docs/Gemfile)).
- **Versions in docs:** the version is defined once, in `gradle.properties`. `python scripts/check-doc-versions.py` (run in CI) fails if README or docs show a different Kastor version in dependency coordinates.
- **README code samples must compile.** Every Kotlin snippet in the root `README.md` is mirrored in [`examples/hello-world/src/main/kotlin/ReadmeSnippets.kt`](examples/hello-world/src/main/kotlin/ReadmeSnippets.kt), which the build compiles. When you edit a README sample, update the mirror in the same PR.
- If you change public API or behavior, update the relevant **tutorial** or **reference** page in the same PR when practical.

## Releases

- The version lives only in [`gradle.properties`](gradle.properties) (`version=`). `main` always carries the next `-SNAPSHOT`. **A released version is never reused:** fixes after a release go into a new version.
- Tags use the form **`vX.Y.Z`**. The historical tag `0.2.1` (no `v`) predates this convention; it is kept, never moved or reused.
- Follow the [release checklist](docs/reference/release-checklist.md) for the first CI run, GitHub configuration and tagging.
- **GitHub settings** (release environment, secrets, tag and branch rulesets, Pages source, Dependabot alerts and security updates, secret scanning with push protection, private vulnerability reporting) are applied by [`scripts/configure-github-release.sh`](scripts/configure-github-release.sh). It prints every call by default; pass `--apply` to change the repository.
- To release:
  1. Commit `version=X.Y.Z` and the dated `CHANGELOG.md` section.
  2. Push the tag `vX.Y.Z`. [`publish.yml`](.github/workflows/publish.yml) reruns release readiness, refuses versions already on Maven Central, signs the artifacts that the readiness steps of the same run staged, bundles them for the Central Portal, uploads the bundle once the `release` environment is approved, and waits until the Central Portal reports it `VALIDATED` (or fails with the portal's errors). Release the validated deployment manually in the portal.
  3. Bump `main` to the next `-SNAPSHOT`.
- The build refuses `centralBundle` for `-SNAPSHOT` versions or without a non-blank `KASTOR_SIGNING_KEY`.

## Code of conduct

All contributors are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Security issues

Please do **not** open a public issue for security vulnerabilities. See [SECURITY.md](SECURITY.md).

## Licensing

By contributing, you agree that your contributions will be licensed under the same terms as the project: **Apache License 2.0** (see [LICENSE](LICENSE)).
