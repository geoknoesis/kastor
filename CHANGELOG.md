# Changelog

All notable changes to Kastor are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres
to semantic versioning where every breaking change bumps at least the minor
version while we are in 0.x.

## [Unreleased]

Version on `main`: `0.3.0-SNAPSHOT`. Nothing from this section has been published yet.

### Breaking changes (Maven `artifactId`s)

Six artifacts that kept bare Gradle project names in 0.2.x now follow the `rdf-*` scheme used by every other RDF module. Gradle project paths are unchanged:

| Gradle project | Old `artifactId` | New `artifactId` |
|---|---|---|
| `:rdf:sparql-lang` | `sparql-lang` | `rdf-sparql-lang` |
| `:rdf:reasoning` | `reasoning` | `rdf-reasoning` |
| `:rdf:reasoning-hermit` | `reasoning-hermit` | `rdf-reasoning-hermit` |
| `:rdf:jena-reasoning` | `jena-reasoning` | `rdf-jena-reasoning` |
| `:rdf:rdf4j-reasoning` | `rdf4j-reasoning` | `rdf-rdf4j-reasoning` |
| `:rdf:shacl-validation` | `shacl-validation` | `rdf-shacl-validation` |

### Breaking changes (BOM)

- **`kastor-bom` now constrains Kastor modules only.** It no longer imports `jackson-bom`, `netty-bom` or `opentelemetry-bom`, and no longer publishes security version floors (Guava, commons-beanutils, HttpComponents, apache-mime4j, libthrift, jsoup, commons-lang3, lz4-java). Previously every Kastor module forced these onto consumers through its published metadata; for example, Netty 4.1 users were upgraded to 4.2. The build still applies them internally through the non-published `gradle/build-platform`. Declare your own floors if you relied on them ([release contract](docs/reference/release-contract.md)).
- **`kastor-bom` now includes the ontology-quality artifacts:** `onto-quality`, `onto-quality-metrics`, `onto-quality-embed`, `onto-quality-llm-koog`, `onto-quality-cli`. `:bom:verifyBomCoverage` fails the build when a published module is missing.

### Changed (toolchain and build)

- Kotlin 2.3.21 → **2.4.20** and KSP → **2.3.12**. Consumers need a compatible Kotlin compiler.
- Apache Jena 6.1.0 → **6.2.0**.
- Dependency locking for every configuration (`gradle.lockfile`, `settings-gradle.lockfile`) and SHA-256 dependency verification (`gradle/verification-metadata.xml`). `./gradlew resolveAndLockAll --write-locks` refreshes locks.
- Kotlin ABI validation (`checkKotlinAbi`, baselines under `*/api/`) and Dokka HTML documentation jars for published modules.
- The version is defined once in `gradle.properties`; release tags use `vX.Y.Z`.
- Repositories are declared only in `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`); `mavenLocal()` was removed from the build.
- Test JVMs are capped (`kastor.test.maxHeap`, default `1g`; `kastor.test.maxParallelForks`, default `1`), and per-module JaCoCo line-coverage floors are enforced by `check`.
- Publishing: `centralBundle` builds a signed Maven Central Portal bundle and refuses unsigned or `-SNAPSHOT` releases. The tag-triggered `publish.yml` uploads it after release readiness passes.
- CI: actions pinned to commit SHAs, least-privilege `permissions`, a macOS job, executed-test floors plus a committed skip allowlist, the pinned conformance corpora fetched by `scripts/fetch-conformance-data.py` (the unused git submodule declaration was removed), and PR dependency audits scoped to newly introduced coordinates.

### Changed

- `rdf:jena`: truly streaming triple parsing via Jena `AsyncParser`.
- `rdf:rdf4j`: thread-safe per-operation connections and streaming serialization.

### Fixed (audit remediation)

<!-- Coordinator: fill in at integration from the remediation branches (rdf/core, providers, sparql,
     shacl, kastor-gen, tools). Refine breaking changes above if any branch changes public API. -->
- _Placeholder — rdf-core fixes_
- _Placeholder — provider (Jena / RDF4J) fixes_
- _Placeholder — SPARQL fixes_
- _Placeholder — SHACL fixes_
- _Placeholder — kastor-gen fixes_
- _Placeholder — onto-quality tools fixes_
- Build, CI and documentation: README samples now match the current DSL and are compiled via `examples/hello-world`. Install docs require JDK 21, show `0.3.0-SNAPSHOT` and state that artifacts are not yet published.

## [0.2.1] - 2026-06-27

Tagged as `0.2.1` (without the `v` prefix used by other tags).

### Added

- **`kastor-gen`: Write-path support (`generateWriteSupport`).** `@Rdf` now accepts
  `generateWriteSupport = true` alongside `generateDataClass = true`. When enabled, the
  generated factory `object` gains a `toTriples(record, subject): List<RdfTriple>` function
  that serializes a data-class snapshot back to RDF triples — the exact mirror of the `from()`
  read path. Behavior by `nestedMode`: `INTERFACE` extracts the backing node via `RdfBacked`;
  `IRI_ONLY` emits `Iri(string)` directly; `DATA_CLASS` skips nested object properties (no
  subject IRI available) with a comment and a KSP warning. `rdf:type` is always emitted.
  Runtime utilities `MutableRdfGraph.replaceValues(subject, predicate, triples)` and
  `MutableRdfGraph.replaceResource(subject, triples)` provide fine-grained and full-resource
  update helpers for callers integrating `toTriples` output into a live graph.

- **`kastor-gen`: Immutable data-class generation from SHACL shapes.** `@Rdf` accepts
  `generateDataClass = true` alongside the existing `generateInterfaces`/`generateWrappers`
  flags. When enabled, the processor emits an eagerly-loaded Kotlin `data class` (read-model
  snapshot) and a companion factory `object` that registers itself in `OntoMapper`.
  New annotation fields: `dataClassSuffix` (name suffix, e.g. `"Record"` → `PersonRecord`),
  `dataClassImplementsInterface` (structural alignment with the generated interface),
  `nestedMode` (`INTERFACE` | `DATA_CLASS` | `IRI_ONLY`, controls typing of `sh:class`
  object properties inside the data class). The `RdfProjection` marker interface
  distinguishes snapshot instances from live `RdfBacked` wrappers at the type level.
  `OntoMapper` now auto-discovers `*Factory` classes alongside `*Wrapper` classes on first use.

- **`kastor-gen`: SHACL-native enum generation.** `sh:in` value sets produce sealed `Known`/`Unknown` enum types. Wrappers read them via `from()`, the instance DSL has type-safe setters, and data classes support them for reads and writes.
- **`rdf4j-reasoning`:** materializes real RDFS inference.

### Fixed

- **Security:** `rdf-core` IRI validation rejects RFC 3987-illegal characters (an injection vector into serialized output). Residual SPARQL-injection paths were closed in the SPARQL endpoint adapter and the `sparql-lang` renderer, and the HTTP client was hardened.
- `sparql-lang` emits grammar-valid `ORDER BY`. SHACL `sh:select` no longer lets `$this` pre-binding clobber the projection.
- `rdf:jena` preserves language direction and triple terms in SPARQL results. `rdf:rdf4j` scopes graph operations to their context, closes cursors, and deduplicates/rolls back dataset IO.
- `rdf-core`: graph-isomorphism fidelity, datatype handling, thread safety and lazy-sequence lifetime.
- `kastor-gen`: generated `validate()` compiles (keyword escaping, `sh:in` escaping, IRI-typed values). `sh:pattern` uses SPARQL `REGEX` find semantics. Bootstrap-classloader types materialize. The Gradle plugin generates interfaces without validation annotations.

### Changed (build)

- Gradle wrapper upgraded from 8.13 to 9.5.1.
- Apache Jena upgraded from 5.6.0 to 6.1.0. `rdf/providers/jena` promotes `jena-libs`, `jena-arq` and `jena-tdb2` from `implementation` to `api`, so consumers can resolve Jena types without an extra dependency.
- The whole build (toolchain and daemon) runs on **JDK 21** (was 17); JDK auto-download is disabled.
- RDF4J upstream RDF 1.2 gaps are reported as skipped conformance tests instead of failures.

## [0.2.0] - 2026-05-17

### Changed (repository layout)

- Related modules now live under **domain folders** on disk (`rdf/sparql/`, `rdf/providers/`, `rdf/reasoning/`, `rdf/shacl/`, `tools/onto-quality/`, `benchmarks/shacl/`). **Gradle project paths (`:rdf:jena`, …) are unchanged**; root [`settings.gradle.kts`](settings.gradle.kts) sets `projectDir` where needed. See [**Physical repository layout** in the architecture doc](docs/kastor/concepts/architecture.md#physical-repository-layout).
- **Docs alignment:** published **`artifactId`** for **``:rdf:shacl-validation`** is **`shacl-validation`** (README tables that said **`rdf-shacl-validation`** were corrected). SHACL benchmark runners and design docs now reference **`benchmarks/shacl/jmh/…`** on disk; a **duplicate** bench tree that mirrored **`jmh/`** next to it was removed.

### Breaking changes (SPARQL modules)

- **`rdf:core` no longer contains the SPARQL AST/DSL** (`com.geoknoesis.kastor.rdf.sparql`),
  **`ShaclDsl`**, **`Rdf.shacl`**, or **`SelectBuilder.addCommonPrefixes`**.
  Add **`sparql-lang`** (Gradle **`:rdf:sparql-lang`**) when you use **`select {}`**, **`SparqlRenderer`**, flows/helpers under **`sparql`**, or **`addCommonPrefixes`**.
  Add **`shacl-dsl`** (Maven **`rdf-shacl-dsl`**, Gradle **`:rdf:shacl-dsl`**) when you use **`shacl {}`** or **`Rdf.shacl`** — it **`api`**-depends on **`sparql-lang`** for SPARQL-shaped constraints.
  String-marker types (**`SparqlSelectQuery`**, **`UpdateQuery`**, …) remain available transitively via **`rdf-sparql-contract`**
  (**Maven `artifactId`**, Gradle **`:rdf:sparql-contract`**) when you depend on **`rdf-core`**.

### Breaking changes (Maven `artifactId`s)

Published artifacts now use **`artifactId`** names that match the documentation (**`rdf-core`**, **`rdf-jena`**, **`rdf-rdf4j`**, **`rdf-sparql`**, **`rdf-sparql-contract`**, **`rdf-shacl-dsl`**, **`kastor-gen-runtime`**, **`kastor-gen-processor`**, …) instead of bare Gradle **`project.name`** values (**`core`**, **`jena`**, **`runtime`**, …). *(Six modules — `sparql-lang`, `reasoning`, `reasoning-hermit`, `jena-reasoning`, `rdf4j-reasoning`, `shacl-validation` — kept bare names in 0.2.x; they are renamed to `rdf-*` in the next release. See [Unreleased].)* Update Maven POMs or external Gradle builds that pinned the old IDs. **`:rdf:rdf4j`** and **`:rdf:sparql`** now declare **`maven-publish`** so they publish under **`rdf-rdf4j`** and **`rdf-sparql`** respectively.

### Breaking changes (adapter classpath)

- **`rdf:jena` and `rdf:rdf4j` no longer depend on `rdf:reasoning`.** Jena- and
  RDF4J-backed **`RdfReasonerProvider`** implementations (SPI + direct types)
  live in **`jena-reasoning`** and **`rdf4j-reasoning`**. Add those artifacts
  when you use **`JenaReasonerProvider`**, **`Rdf4jReasonerProvider`**, or
  **`ReasonerRegistry`** discovery for those ids—or consume **`kastor-bom`**, which
  pins them alongside the store adapters. See
  [Repository architecture — Dependency profiles](docs/kastor/concepts/architecture.md#dependency-profiles-gradle).

### Breaking changes (RDF 1.2 adoption)

Kastor's data model is now [W3C RDF 1.2](https://www.w3.org/TR/rdf12-concepts/)
end-to-end. RDF 1.2 is *not* fully backwards compatible with the previous RDF-star
based model that 0.1.x exposed.

- **`TripleTerm` is no longer a `RdfResource`.** It now implements `RdfTerm`
  only. Triple terms in RDF 1.2 are object-position-only and carry no assertional
  force. Any code that put a `TripleTerm` in subject position will not compile;
  see the migration guide for replacements using `rdf:reifies`.
- **`LangString` gained an optional `direction: Direction?` field.** When
  `direction != null`, `LangString.datatype` is `rdf:dirLangString` (RDF 1.2's
  new directional language string datatype); otherwise `rdf:langString` as before.
- **Quoted-triple syntax changed.** Serialization, `toString()`, and the SPARQL
  renderer now emit `<<( s p o )>>` (with parentheses, RDF 1.2 spec) instead of
  the legacy `<<s p o>>` RDF-star syntax. Parsers continue to accept both because
  Jena/RDF4J's parsers do.
- **Old reification vocabulary deprecated.** `rdf:Statement`, `rdf:subject`,
  `rdf:predicate`, `rdf:object` are still in `RDF` but marked `@Deprecated`. The
  RDF 1.2 idiomatic replacement is `rdf:reifies` paired with a triple term.
- **`SparqlAst` triple patterns:** `QuotedTriplePatternAst` and
  `RdfStarTriplePatternAst` are deprecated; new code should use
  `TripleTermPatternAst` (object-position only) and `ReifierPatternAst`. The
  deprecated types still render to RDF 1.2 syntax for one minor cycle.
- **Provider capabilities** gained `rdfVersion: String` and
  `supportsTripleTerms: Boolean`. `JenaProvider` and `Rdf4jProvider` advertise
  `1.2` / `true`; `MemoryRepositoryProvider` advertises `1.1` / `false`.

### Added

- `Direction { LTR, RTL }` enum and directional-language helpers
  (`lang(text, "ar", Direction.RTL)`, `Literal.invoke(value, lang, dir)`).
- `RDF.dirLangString`, `RDF.reifies`, `RDF.TripleTerm`, `RDF.reifier`,
  `RDF.HTML`, `RDF.JSON`, `RDF.CompoundLiteral` vocabulary constants.
- `reifies(triple) { ... }` DSL builder for attaching metadata to a triple via
  the RDF 1.2 reifier pattern.
- SPARQL 1.2 built-ins `LANGDIR`, `STRLANGDIR` registered alongside the
  existing `TRIPLE` / `SUBJECT` / `PREDICATE` / `OBJECT` / `isTRIPLE`.
- `RdfFormat` aliases for the 1.2 format flavours (`TURTLE-1.2`, `TRIG-1.2`,
  `N-TRIPLES-1.2`, `N-QUADS-1.2`, plus the legacy `TURTLESTAR`).
- `Rdf12Test` suites in `:rdf:jena` and `:rdf:rdf4j` covering parse/serialize
  round-trips for triple terms, directional strings, `rdf:reifies` reifiers, and
  SPARQL `ASK` patterns over triple terms.
- `kastor-gen` codegen now emits `LangString(value, lang, dir)` for SHACL
  properties whose `sh:datatype` is `rdf:dirLangString`.

### Changed

- **`rdf-shacl-dsl`** module (**`:rdf:shacl-dsl`**, Maven **`rdf-shacl-dsl`**): **`ShaclDsl`**, **`shacl {}`**, and **`Rdf.shacl`** moved from **`sparql-lang`**; **`shacl-dsl`** **`api`**-depends on **`sparql-lang`** for SPARQL-shaped constraints. **`rdf:shacl-validation`** is unchanged and does not depend on **`shacl-dsl`**.
- The SPARQL renderer emits `<<( s p o )>>` (RDF 1.2) and `"text"@lang--ltr` /
  `--rtl` for directional language literals.
- Jena bridge uses `NodeFactory.createTripleTerm(...)` (Jena 5.4+ API);
  RDF4J bridge prefers `ValueFactory.createTripleTerm(...)` and falls back to
  `createTriple(...)` on older RDF4J builds.

### Migration

Read [docs/kastor/guides/migrating-to-rdf-1.2.md](docs/kastor/guides/migrating-to-rdf-1.2.md)
before upgrading.

## [0.1.0]

Initial release.

[Unreleased]: https://github.com/geoknoesis/kastor/compare/0.2.1...HEAD
[0.2.1]: https://github.com/geoknoesis/kastor/compare/v0.2.0...0.2.1
[0.2.0]: https://github.com/geoknoesis/kastor/releases/tag/v0.2.0
