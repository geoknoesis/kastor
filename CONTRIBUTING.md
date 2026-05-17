# Contributing to Kastor

Thank you for your interest in improving Kastor. This document explains how to build the project, run tests, and submit changes.

## Prerequisites

- **JDK 17** (Gradle uses the JVM toolchain from the build scripts)
- No extra global tools are required beyond a recent **Gradle** wrapper (`./gradlew` / `gradlew.bat`)

**Repo layout:** see [**Repository architecture**](docs/kastor/concepts/architecture.md) (modules, layers, Gradle targets) and [**Physical repository layout**](docs/kastor/concepts/architecture.md#physical-repository-layout) (on-disk grouping vs `:module:` paths).

## Build and test

From the repository root:

```bash
./gradlew test -x :rdf:conformance:test
./gradlew conformanceSmokeTest
```

- **`test`** — all modules except the heavy RDF 1.2 corpus (`:rdf:conformance:test` is excluded from this aggregate run).
- **`conformanceSmokeTest`** — fast RDF harness check using a **bundled fixture** in `:rdf:conformance` (no git submodule). Equivalent to `./gradlew :rdf:conformance:conformanceSmokeTest` (the root project exposes the same task name as a convenience alias).

**Full W3C RDF 1.2 syntax suites** (large submodule under `rdf/conformance/test-data/`):

```bash
git submodule update --init --recursive
./gradlew :rdf:conformance:test
```

**SHACL:** `./gradlew :rdf:shacl-validation:test` always exercises a **small bundled** W3C subset. For the full upstream SHACL 1.2 tree locally, follow [`rdf/shacl/validation/test-data/README.md`](rdf/shacl/validation/test-data/README.md) (clone [w3c/data-shapes](https://github.com/w3c/data-shapes) `gh-pages` into that directory), then re-run the module tests.

On Windows, use `gradlew.bat` in place of `./gradlew`.

### Automation reference

| Workflow | When it runs | Role |
|----------|----------------|------|
| [`ci.yml`](.github/workflows/ci.yml) | Push & PR to `main` / `master` | `./gradlew test -x :rdf:conformance:test`, `./gradlew :rdf:conformance:conformanceSmokeTest`, and `./gradlew buildHealth` |
| [`conformance.yml`](.github/workflows/conformance.yml) | Weekly + manual | `:rdf:conformance:test` and `:rdf:shacl-validation:test` against full upstream corpora (workflow clones W3C SHACL data when needed) |
| [`wrapper-validation.yml`](.github/workflows/wrapper-validation.yml) | When `gradle/wrapper/**` changes | Validates official `gradle-wrapper.jar` checksums |
| [`dependency-review.yml`](.github/workflows/dependency-review.yml) | Pull requests | Flags new vulnerable dependencies (requires [Dependency graph](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/about-the-dependency-graph) enabled on the repo) |
| [`pages.yml`](.github/workflows/pages.yml) | Push to `main` / `master` & manual | Jekyll build from `docs/` → [GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site#publishing-with-a-custom-github-actions-workflow) |

Dependabot opens weekly PRs for **GitHub Actions** and **Gradle** ([`dependabot.yml`](.github/dependabot.yml)); keep CI green when merging bumps. If **Dependency review** fails on your PR, address or document any new vulnerable dependencies flagged in the check.

Useful variants:

- **Single module:** `./gradlew :rdf:core:test` or `./gradlew :kastor-gen:runtime:test`
- **Examples:** `./gradlew :examples:dcat-us:check` (if you touch example code)
- **Dependency hygiene:** `./gradlew buildHealth` — Dependency Analysis aggregate advice (library modules; `:benchmarks:*` and `:examples:*` excluded for CI footprint); see [Repository architecture — Dependency hygiene](docs/kastor/concepts/architecture.md#dependency-hygiene-automated).

## Pull requests

1. **Fork** the repository and create a **feature branch** from `main`.
2. Keep changes **focused** on one concern when possible (easier review, cleaner history).
3. **Run tests** locally before opening a PR (at minimum `./gradlew test -x :rdf:conformance:test` and `./gradlew conformanceSmokeTest`); fix any failures or add tests that cover new behavior.
4. In the PR description, explain **what** changed and **why** (link issues with `Fixes #123` when applicable).
5. Match existing **Kotlin style** and patterns in the touched modules; avoid unrelated reformatting.

## Documentation

- User-facing docs live under [`docs/`](docs/).
- The **published site** is built with **Jekyll** and deployed by [`.github/workflows/pages.yml`](.github/workflows/pages.yml) to GitHub Pages (`https://<owner>.github.io/<repo>/`).
- **Local preview:** from the `docs/` directory, run `bundle install` then `bundle exec jekyll serve --livereload` (see [`docs/Gemfile`](docs/Gemfile)).
- If you change public API or behavior, update the relevant **tutorial** or **reference** page in the same PR when practical.

## Code of conduct

All contributors are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Security issues

Please do **not** open a public issue for security vulnerabilities. See [SECURITY.md](SECURITY.md).

## Licensing

By contributing, you agree that your contributions will be licensed under the same terms as the project: **Apache License 2.0** (see [LICENSE](LICENSE)).
