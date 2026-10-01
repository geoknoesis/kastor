# `rdf:shacl-validation`

SHACL validation API ([`ShaclValidator`](src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/ShaclValidatorProvider.kt)), native engine, and **provider SPI** ([`ShaclValidatorProvider`](src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/ShaclValidatorProvider.kt)).

## Validator providers (`providerId`)

| `providerId` | Gradle module | Notes |
|--------------|---------------|--------|
| `kastor` | `com.geoknoesis.kastor:shacl-validation` (this module) | Default native SHACL 1.2 Core engine. |
| `memory` | Same JAR | **Deprecated alias of `kastor`.** Kept so that existing `providerId = "memory"` configurations keep working: validators, capabilities and profiles are those of the native engine. (It used to be a stub that ignored targets and most constraints.) Never preferred over `kastor`, and not a bridge for `EnginePreference.BRIDGE_FIRST`. |
| `rdf4j` | Add **`project(":rdf:rdf4j")`** (artifact `com.geoknoesis.kastor:rdf-rdf4j`) | Eclipse RDF4J **`ShaclSail`**; use `validate(data, shapes)` with both as **`RdfGraph`**. |

Resolve a validator:

```kotlin
ShaclValidation.validator(
    ValidationConfig(
        profile = ValidationProfile.SHACL_CORE,
        providerId = "rdf4j", // or "kastor"
        parallelValidation = false,
    ),
)
```

## API limitations (all engines)

- Prefer **`validate(dataGraph, shapesGraph)`** with both graphs in RDF.
- **`validate(graph, List<ShaclShape>)`** and **`validateConstraints`** are **not** implemented by any bundled engine (**native**, its `memory` alias, **RDF4J**) when the list is non-empty; they throw **`UnsupportedOperationException`** with a message to use a shapes graph.
- **`EnginePreference.BRIDGE_FIRST`** prefers a bridge such as `rdf4j` when one is on the classpath and otherwise resolves to the native engine.

## Resource limits (all engines)

- **`ValidationConfig.maxCombinedGraphTriples`** — reject `data.size() + shapes.size()` above this value **before** validation (default **`Long.MAX_VALUE`**). Use **`ValidationConfig.rdf4jUntrustedInputLimits()`** for conservative starter values (applies to RDF4J and native).
- **`ValidationConfig.patternTimeout`** — time budget of one `sh:pattern` evaluation (default **1 s**, native engine); a catastrophically backtracking pattern fails validation with an error naming the pattern instead of running until `timeout`.
- **`ValidationConfig.maxViolations`** — max violation rows returned; if the engine collects more, **`ValidationReport.violationsTruncated`** is **`true`** and the returned list is capped.

## SPI registration

Providers are loaded via `META-INF/services/com.geoknoesis.kastor.rdf.shacl.ShaclValidatorProvider`. If a provider JAR is missing, its id will not appear in [`ValidatorRegistry`](src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/ValidatorRegistry.kt). Discovery warnings are logged at **`WARNING`** if loading fails.

## Further reading

- [SHACL validation architecture](../../../docs/kastor/design/shacl-validation-architecture.md) (if present in your docs checkout)
- [SHACL benchmarks](../../../benchmarks/shacl/README.md)

## Native engine semantics

- **Conformance.** `ValidationReport.isValid` mirrors `sh:conforms`: it is `false` as soon as the engine reports a
  result with severity `sh:Violation`, `sh:Warning`, `sh:Info` or a custom severity (SHACL 1.2 `sh:Debug` /
  `sh:Trace` results do not affect conformance). Use `ValidationReport.hasViolations` to ignore lower severities.
- **Value nodes and recursion.** Value nodes are sets. Nested shape checks (`sh:node`, `sh:and`/`sh:or`/`sh:xone`/
  `sh:not`, qualified value shapes, `sh:shape`, `sh:someValue`, `sh:memberShape`, `sh:reifierShape`) accept literal
  value nodes, are memoized per (value node, shape) within a run, and treat deactivated or constraint-free shapes as
  always conforming. Recursion is detected on (focus node, shape) pairs: finite acyclic data chains validate
  normally; a pair re-entered while being checked is assumed to conform (SHACL leaves recursion undefined).
- **Shape parameters.** Repeatable parameters (`sh:pattern`, `sh:hasValue`, `sh:equals`, bounds, …) produce one
  constraint per value; parameters limited to one value (`sh:minCount`, `sh:maxCount`, `sh:minLength`, `sh:flags`,
  `sh:in`, `sh:languageIn`, `sh:qualifiedMinCount`, …) fail compilation when repeated. `sh:pattern` is compiled once
  (flags `i`, `m`, `s`, `x`, `q`); invalid patterns fail compilation. Patterns have XPath `fn:matches` semantics: they
  are translated to `java.util.regex` so that `$`, multi-line `^`, `.`, `\d`, `\w` and `\s` mean what XML Schema
  regular expressions define (`\w` is Unicode-aware and does not include `_`). Ill-formed RDF lists (a cell with a
  missing or repeated `rdf:first` / `rdf:rest`) fail compilation.
- **SHACL-SPARQL.** `sh:sparql` constraints need a SPARQL-capable provider at runtime (`rdf-jena` or `rdf-rdf4j`);
  without one validation fails with a `ShaclValidationException` naming the missing module, and the provider
  reports `supportsShaclSparql = false`. Queries run over the **data graph**; `sh:prefixes`/`sh:declare`, `$PATH`,
  `$this`, `$currentShape` and `$shapesGraph` (bound to the named graph `urn:x-kastor:shacl:shapesGraph`) are
  supported; each solution is one result (`?value`, `?path`, `sh:message` templates `{?var}` / `{$var}`). Queries
  using MINUS, SERVICE, VALUES, sub-queries not projecting pre-bound variables, or re-binding them are rejected.
  SPARQL-based constraint components and SHACL 1.2 node expressions are not supported.
- **Reports.** `toShaclValidationReportRdf()` exports complete `sh:resultPath` structures, `sh:message` values
  (with language tags) as `sh:resultMessage`, `sh:sourceConstraint`, and `sh:value` only for components that
  define it. Kastor extension triples (`KastorShaclVocabulary`, namespace `https://kastor.geoknoesis.com/ns/shacl#`)
  mark undefined-recursion results (`ksh:resultStatus ksh:UndefinedRecursion`) and name the failing reifier of a
  `sh:reifierShape` result (`ksh:reifier`).
- **W3C conformance.** `Shacl12NativeConformanceTest` runs the SHACL 1.2 test suite (core, node-expr, sparql) when
  `test-data/w3c-shacl12` is present and compares result graphs up to blank-node isomorphism. Unsupported cases are
  listed with justifications in `W3cKnownDeviations`.
