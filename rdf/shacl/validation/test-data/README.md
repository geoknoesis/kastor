# Optional local test data (`rdf/shacl/validation`)

Upstream layout and process are described in the **[SHACL Test Suite and Implementation Report](https://w3c.github.io/data-shapes/data-shapes-test-suite/)**. The authoritative corpus lives in the **`gh-pages`** branch of **[w3c/data-shapes](https://github.com/w3c/data-shapes)**.

This module does **not** commit those trees (they are gitignored); clone them locally next to this file.

## One-shot download (both suites)

From `rdf/shacl/validation/`:

```bash
mkdir -p test-data
rm -rf test-data/_data-shapes-src test-data/data-shapes-test-suite test-data/w3c-shacl12

git clone --depth 1 --branch gh-pages https://github.com/w3c/data-shapes.git test-data/_data-shapes-src

# Documented on the W3C test-suite page (SHACL 1.0 / classic Core + SPARQL manifests)
mv test-data/_data-shapes-src/data-shapes-test-suite test-data/data-shapes-test-suite

# SHACL 1.2 Core tree (used by Kastor’s `Shacl12NativeConformanceTest` when present)
mv test-data/_data-shapes-src/shacl12-test-suite test-data/w3c-shacl12

rm -rf test-data/_data-shapes-src
```

Top-level manifests:

| Suite | Path |
|-------|------|
| Classic suite (per published report links) | `test-data/data-shapes-test-suite/tests/core/manifest.ttl` (+ `sparql/`, …) |
| SHACL 1.2 | `test-data/w3c-shacl12/tests/core/manifest.ttl` |

## Running Kastor’s manifest harness

The bundled **approved subset** lives under `src/test/resources/w3c-shacl12-fixture/` so `./gradlew :rdf:shacl-validation:test` works without a checkout.

With **`test-data/w3c-shacl12/`** present, `Shacl12NativeConformanceTest` walks **`tests/core/manifest.ttl`** automatically.

Override manifest resolution:

```bash
./gradlew :rdf:shacl-validation:test -Dshacl.w3c.manifest=/absolute/path/to/manifest.ttl
```

Ignored directories: `test-data/w3c-shacl12/`, `test-data/data-shapes-test-suite/` (and any `_data-shapes-src` scratch clone).
