# RDF 1.2 conformance testing

The `:rdf:conformance` module compares **Kastor’s Jena and RDF4J providers** against the official **W3C RDF 1.2 syntax** suites (parser-focused Phase 1: Turtle, TriG, N-Triples, N-Quads).

Default `./gradlew test` **does not** execute the heavy corpus (`:rdf:conformance:test` is excluded from that aggregate run—see repository [**CONTRIBUTING**](https://github.com/geoknoesis/kastor/blob/main/CONTRIBUTING.md)). Two workflows matter in practice:

| Goal | Command |
|------|---------|
| **Quick regression check** (no submodule, small bundled fixture, both providers) | `./gradlew conformanceSmokeTest` |
| **Full W3C matrix** (requires submodule checkout) | `git submodule update --init --recursive` then `./gradlew :rdf:conformance:test` |

The bundled fixture lives beside the harness ([`Rdf12ConformanceSmokeTest`](https://github.com/geoknoesis/kastor/blob/main/rdf/conformance/src/test/kotlin/com/geoknoesis/kastor/rdf/conformance/Rdf12ConformanceSmokeTest.kt)); it covers positive syntax, negative syntax, and one eval case.

Maintenance automation also runs the full RDF corpus plus expanded SHACL checks on a schedule ([workflow](https://github.com/geoknoesis/kastor/blob/main/.github/workflows/conformance.yml)).

For Gradle filtering or tooling: fixture-only tests use tag **`conformance-smoke`**; submodule-driven factories use **`w3c-rdf12-full`**.

## What the W3C corpus covers

Each approved row under [`w3c/rdf-tests` RDF 12](https://github.com/w3c/rdf-tests/tree/main/rdf12) becomes a JUnit 5 dynamic test:

| Suite | Manifests under `test-data/rdf12/` | Test kinds |
| ----- | ---------------------------------- | ---------- |
| Turtle 1.2 | `rdf-turtle/manifest.ttl` | positive / negative syntax, eval |
| TriG 1.2 | `rdf-trig/manifest.ttl` | positive / negative syntax, eval |
| N-Triples 1.2 | `rdf-n-triples/manifest.ttl` | positive / negative syntax |
| N-Quads 1.2 | `rdf-n-quads/manifest.ttl` | positive / negative syntax |

Each row runs against **both** providers, so reports show labels such as `[Jena/TURTLE] …` and `[RDF4J/TURTLE] …`. **SPARQL 1.2** and RDF dataset canonicalisation are **out of scope** for this harness.

## Enable the full suite locally

The upstream trees live in a git submodule at `rdf/conformance/test-data/`.

```bash
git submodule update --init --recursive
./gradlew :rdf:conformance:test
```

If the submodule is missing, the harness still compiles; full-suite factories emit a **skipped** test with instructions instead of failing the build.

`./gradlew check` does **not** run `:rdf:conformance` tests (see `:rdf:conformance/build.gradle.kts`). Use **`conformanceSmokeTest`** or **`:rdf:conformance:test`** explicitly.

## Reading failures

Each W3C row is one dynamic test—the failure name identifies the row:

```
JenaConformanceTest > RDF 1 dot 2 syntax suite (Jena) >
  rdf12/rdf-turtle (84 tests) > [Jena/TURTLE] turtle-syntax-bad-num-04 FAILED
```

Eval mismatches include graph sizes to separate “empty parse” from “near miss”:

```
[Jena/TRIG] reified-triple-1 FAILED
  expected: .../reified-triple-1.nq
  actual size = 4, expected size = 5
```

## System properties

| Property | Default | Effect |
| -------- | ------- | ------ |
| `conformance.includeUnapproved` | `false` | When `true`, runs manifest rows whose `rdft:approval` is not `rdft:Approved` (may diverge from stable spec expectations). |

## Harness layout

These types drive the suite (`rdf/conformance/src/test/kotlin/…`):

- **`Rdf12ManifestParser`** — reads `mf:Manifest` Turtle via **Jena ARQ** `Model.read` (not Kastor parsers—the adapters under test are exercised separately).
- **`Conformer`** — thin `RdfProvider` adapter plus dataset parsing; one instance per provider so Jena and RDF4J share the same case list.
- **`Rdf12ConformanceRunner`** — builds `DynamicContainer` trees and dispatches to graph parse, dataset parse, or isomorphism checks.

**Extend coverage:** add another `Conformer` + test class (copy `JenaConformanceTest`). **Beyond syntax:** reuse the same dynamic-test pattern against other manifest kinds (for example SPARQL evaluation rows) if you wire a new runner.

## See also

- [W3C RDF 1.2 test suite repository](https://github.com/w3c/rdf-tests)
- [W3C test-manifest vocabulary](https://www.w3.org/2001/sw/DataAccess/tests/test-manifest)
- [RDF 1.2 in Kastor](rdf-1.2.md)
- [Migrating from 0.1.x](../guides/migrating-to-rdf-1.2.md)
