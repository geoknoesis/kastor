# Changelog

All notable changes to Kastor are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres
to semantic versioning where every breaking change bumps at least the minor
version while we are in 0.x.

## [Unreleased]

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

Published artifacts now use **`artifactId`** names that match the documentation (**`rdf-core`**, **`rdf-jena`**, **`rdf-rdf4j`**, **`rdf-sparql`**, **`rdf-sparql-contract`**, **`rdf-shacl-dsl`**, **`kastor-gen-runtime`**, **`kastor-gen-processor`**, …) instead of bare Gradle **`project.name`** values (**`core`**, **`jena`**, **`runtime`**, …). Update Maven POMs or external Gradle builds that pinned the old IDs. **`:rdf:rdf4j`** and **`:rdf:sparql`** now declare **`maven-publish`** so they publish under **`rdf-rdf4j`** and **`rdf-sparql`** respectively.

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
