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
- **`validate(graph, List<ShaclShape>)`** and **`validateConstraints`** are **not** implemented by any bundled engine (**native**, its `memory` alias, **RDF4J**) when the list is non-empty. The native engine and its `memory` alias throw **`UnsupportedShaclOperationException`** (a `ShaclValidationException`) with a message to use a shapes graph; the RDF4J bridge throws **`UnsupportedOperationException`**.
- **`ValidationConfig.parallelValidation = true`** and **`streamingMode = true`** are rejected by the native engine and its `memory` alias with **`UnsupportedShaclOperationException`** when the validator is created.
- **`ValidationConfig.includeWarnings = false`** empties `ValidationReport.warnings` (results of severity `sh:Warning` are still reported). **`batchSize`**, **`enableExplanations`**, **`enableSuggestions`**, **`validateInactiveShapes`**, **`customParameters`** and **`streaming`** are deprecated: they never had an effect. **`ShapesDigestMode.SHAPES_RDF_CANONICAL_DIGEST`** (not implemented: every validation fails) and **`ImportConfig.allowImportFetch`** (nothing is fetched; `true` only turns an unresolved import into an exception instead of a warning) are deprecated too.
- **`EnginePreference.BRIDGE_FIRST`** prefers a bridge such as `rdf4j` when one is on the classpath and otherwise resolves to the native engine.

## Resource limits (all engines)

- **`ValidationConfig.maxCombinedGraphTriples`** — reject `data.size() + shapes.size()` above this value **before** validation (default **`Long.MAX_VALUE`**). Use **`ValidationConfig.rdf4jUntrustedInputLimits()`** for conservative starter values (applies to RDF4J and native).
- **`ValidationConfig.patternTimeout`** — budget of one `sh:pattern` evaluation (default **1 s**, native engine), counted in steps of the regular expression engine (50,000 per millisecond) so that the outcome does not depend on the machine; the wall clock is a backstop at ten times the duration. Long values are matched on a dedicated thread with a fixed 16 MiB stack. A value on which a pattern exceeds the budget, or on which the regular expression engine runs out of stack, is **reported** as an undecided result naming the pattern (`isPatternTimeout` / `isPatternTooComplex`, `ksh:resultStatus ksh:PatternTimeout` / `ksh:PatternTooComplex` in RDF) that blocks conformance; validation continues with the rest of the data. With `strictMode = true` validation fails instead. Only `timeout` aborts a run.
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
  normally. Recursion through monotone operators conforms unless a constraint fails; recursion through `sh:not`,
  `sh:xone`, `sh:qualifiedMaxCount` or disjoint qualified value shapes is undefined and reported as such
  (`isUndefinedRecursion`).
- **Shape parameters.** Repeatable parameters (`sh:pattern`, `sh:hasValue`, `sh:equals`, bounds, …) produce one
  constraint per value; parameters limited to one value (`sh:minCount`, `sh:maxCount`, `sh:minLength`, `sh:flags`,
  `sh:in`, `sh:languageIn`, `sh:qualifiedMinCount`, …) fail compilation when repeated. `sh:pattern` is compiled once
  (flags `i`, `m`, `s`, `x`, `q`); invalid patterns fail compilation. Patterns have XPath `fn:matches` semantics: they
  are translated to `java.util.regex` so that `$`, multi-line `^`, `.`, `\d`, `\w` and `\s` mean what XML Schema
  regular expressions define, with one exception: `\w` is `[_\p{L}\p{M}\p{N}\p{S}]`, i.e. Unicode-aware **and**
  accepting `_` as `java.util.regex` engines do (`\W` is its complement). A leading inline flag group (`(?i)…`,
  flags `i`, `m`, `s`, `x`), script names (`\p{IsLatin}`) and POSIX bracket expressions (`[[:alpha:]]`) are translated
  with their intended meaning. Idioms that would change meaning (`&&`, nested classes, inline flags elsewhere, a `]`
  right after `[`, `\p{IsX}` for a name that is neither a block nor a script) fail compilation instead of being
  reinterpreted. The `i` flag does not affect category escapes (`\p{Lu}`). Ill-formed RDF lists (a cell with a missing or repeated `rdf:first` / `rdf:rest`) and sequence or
  alternative paths with fewer than two members fail compilation; list cells may be IRIs.
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
  mark undecided results (`ksh:resultStatus` with `ksh:UndefinedRecursion`, `ksh:PatternTimeout` or
  `ksh:PatternTooComplex`), name the reifier of a `sh:reifierShape` result (`ksh:reifier`) and carry report-level
  warnings (`ksh:warning`). The vocabulary document ships in the JAR:
  `/com/geoknoesis/kastor/rdf/shacl/kastor-shacl.ttl` (`KastorShaclVocabulary.VOCABULARY_RESOURCE`).
- **W3C conformance.** `Shacl12NativeConformanceTest` runs the SHACL 1.2 test suite (core, node-expr, sparql) when
  `test-data/w3c-shacl12` is present and compares result graphs up to blank-node isomorphism. Unsupported cases are
  listed with justifications in `W3cKnownDeviations`. A case fails when the engine emits an undecided result
  (`ksh:resultStatus`) where the expected report has a definite one.
