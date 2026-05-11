# RDF 1.2 conformance testing

Kastor 0.2.0 ships a conformance harness that runs the official W3C RDF 1.2
syntax test suites against the Jena and RDF4J providers. The harness lives in
`:rdf:conformance` and is opt-in: the tests skip gracefully when the test data
is absent, so a stock `./gradlew test` stays fast and offline.

## What's covered

The current scope is **Phase 1: RDF 1.2 syntax** - that is, parser and
serialiser conformance. For each suite under
`https://github.com/w3c/rdf-tests/tree/main/rdf12`, every approved test row is
turned into a JUnit 5 dynamic test:

| Suite | Manifests under `test-data/rdf12/` | Test kinds |
| ----- | ---------------------------------- | ---------- |
| Turtle 1.2 | `rdf-turtle/manifest.ttl` | positive / negative syntax, eval |
| TriG 1.2 | `rdf-trig/manifest.ttl` | positive / negative syntax, eval |
| N-Triples 1.2 | `rdf-n-triples/manifest.ttl` | positive / negative syntax |
| N-Quads 1.2 | `rdf-n-quads/manifest.ttl` | positive / negative syntax |

For each row we run the same logical assertion against **both** providers, so
the JUnit report shows e.g. `[Jena/TURTLE] turtle-syntax-bad-num-01` and
`[RDF4J/TURTLE] turtle-syntax-bad-num-01` as separate entries. SPARQL 1.2 and
RDF Dataset Canonicalisation are explicitly out of scope for Phase 1.

## How to enable it

The W3C test data is checked in as a git submodule at
`rdf/conformance/test-data/`. To run the suite locally:

```bash
git submodule update --init --recursive

./gradlew :rdf:conformance:test
```

Without the submodule, the suite still builds and reports a single skipped
test per provider with a clear message:

```
Rdf 1 dot 2 syntax suite (Jena) > submodule not initialised
  W3C test data not present at .../test-data/rdf12/manifest.ttl. Run
  `git submodule update --init --recursive` to enable the RDF 1.2
  conformance suite.
```

`./gradlew check` does **not** trigger the conformance suite. We hold it out
of the default chain because the submodule alone is hundreds of megabytes; CI
runs it explicitly via `./gradlew :rdf:conformance:test`.

## What a failing run looks like

Each W3C row becomes one JUnit 5 dynamic test, so failures point at exactly
the row that broke:

```
JenaConformanceTest > RDF 1 dot 2 syntax suite (Jena) >
  rdf12/rdf-turtle (84 tests) > [Jena/TURTLE] turtle-syntax-bad-num-04 FAILED
    java.lang.IllegalStateException: negative-syntax test should have
    failed but parsed cleanly: ...turtle-syntax-bad-num-04
```

Eval tests log graph sizes when the comparison fails, so you can quickly tell
the difference between "we parsed nothing" and "we parsed a slightly different
graph":

```
[Jena/TRIG] reified-triple-1 FAILED
  java.lang.IllegalStateException: eval mismatch for ...reified-triple-1
    expected: .../reified-triple-1.nq
    actual size = 4, expected size = 5
```

## System properties

| Property | Default | Effect |
| -------- | ------- | ------ |
| `conformance.includeUnapproved` | `false` | If `true`, also runs tests whose `rdft:approval` is not `rdft:Approved`. Such tests may diverge from the spec; default is to skip them. |

## Implementation notes

The harness is built from three small, dependency-free Kotlin types in
`rdf/conformance/src/test/kotlin/.../`:

- `Rdf12ManifestParser` reads an `mf:Manifest` Turtle file (using Jena's
  `Model.read`, **not** Kastor's parsers, because that's what we're testing)
  and produces a typed list of `W3cTestCase` rows.
- `Conformer` is a thin adapter around `RdfProvider` that the harness calls
  for parsing - one `Conformer` per provider, so the same row can run against
  Jena and RDF4J in the same JVM.
- `Rdf12ConformanceRunner` walks the test-data tree, generates a
  `DynamicContainer` per manifest, and dispatches each row to a `parseGraph`,
  `parseDataset`, or graph-isomorphism call.

If you need to extend coverage:

- **Add a new provider**: instantiate a new `Conformer(label, provider, repoFactory)`
  and create a sibling test class - see `JenaConformanceTest` for the pattern.
- **Move beyond syntax**: build a new runner that consumes
  `mf:QueryEvaluationTest` / `mf:CSVResultFormatTest` rows from the
  `sparql-tests` submodule, reusing the same dynamic-test machinery.

## See also

- [W3C RDF 1.2 test suite repository](https://github.com/w3c/rdf-tests)
- [W3C test-manifest vocabulary](https://www.w3.org/2001/sw/DataAccess/tests/test-manifest)
- [RDF 1.2 in Kastor](rdf-1.2.md)
- [Migrating from 0.1.x](../guides/migrating-to-rdf-1.2.md)
