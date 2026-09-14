# DCAT-US 3.0 Kastor Gen Example

This example generates Kotlin domain interfaces and RDF-backed wrappers from the
[DCAT-US 3.0 SHACL shapes](https://github.com/DOI-DO/dcat-us/blob/main/shacl/dcat-us_3.0_shacl_shapes.ttl)
with the Kastor KSP processor, and shows plain-RDF and hand-written alternatives next to it.

## What is in here

| File | What it shows |
|------|---------------|
| `src/main/resources/dcat-us_3.0_shacl_shapes.ttl` | The DCAT-US 3.0 SHACL shapes (input to generation) |
| `src/main/resources/dcat-us_3.0_context.jsonld` | JSON-LD context: prefixes and Kotlin type names for classes |
| `DCAT_US_Generated_Example.kt` | `@file:Rdf(shacl = …, context = …)` — generation trigger and a materialized `Catalog` |
| `DCAT_US_Example.kt` | Building and querying DCAT-US data with the plain Kastor RDF API |
| `DCAT_US_Manual_Example.kt` | Hand-written domain classes (no generation), for comparison |

## How generation works

The root build adds the Kastor KSP processor to every `:examples:*` project. During `kspKotlin` the
processor reads the SHACL shapes and context referenced by the `@file:Rdf` annotation (paths are resolved
against this project's `src/main/resources`) and writes, into
`com.geoknoesis.kastor.examples.dcat.generated`:

- one interface per node shape (e.g. `Catalog`, `Dataset`, `Distribution`), named after the JSON-LD
  context term for the class or, if there is none, the class IRI's local name;
- one internal `…Wrapper` per interface that reads values lazily from an `RdfGraph` and registers itself
  with `OntoMapper`, plus an embedded `validate()`.

Two DCAT-US shapes target classes with the same local name (`vcard:Address`, `locn:Address`). The
generator refuses ambiguous names, so the context maps them to `VcardAddress` and `LocnAddress`.

Generated sources end up under `build/…/generated/ksp/main/kotlin`; they are not checked in.

KSP itself does not observe the `.ttl` / `.jsonld` files. `build.gradle.kts` therefore declares
`src/main/resources` as an input of the `kspKotlin` task (so editing the shapes re-runs generation) and sets the
KSP option `kastor.gen.resources.tracked=true`; without that the processor warns that the generated code can go
stale. Projects outside this repository should prefer the `com.geoknoesis.kastor.gen` Gradle plugin, which tracks
ontology files as task inputs.

## Run

```bash
./gradlew :examples:dcat-us:runGeneratedExample   # materialize generated types
./gradlew :examples:dcat-us:run                   # plain RDF API
./gradlew :examples:dcat-us:runManualExample      # hand-written classes
```

## Using the generated types

```kotlin
val graph = MemoryGraph()
// ... add DCAT-US triples ...
val catalog: Catalog = graph.materialize(Iri("https://data.example.gov/catalog"))
```

Property names come from `sh:name` (or the path's local name) converted to Kotlin identifiers; literal
types follow the shape's `sh:datatype` (for example `xsd:integer` → `BigInteger`, `xsd:date` → `LocalDate`,
`rdf:langString` → `LangString`, other datatypes keep their lexical form as `String`).
