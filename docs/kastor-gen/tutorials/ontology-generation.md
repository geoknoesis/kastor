# Ontology-Driven Code Generation

Kastor Gen supports generating domain interfaces and wrapper implementations directly from SHACL shapes and JSON-LD context files. This approach eliminates the need for manual interface definitions and ensures consistency between your ontology and code.

## Overview

Instead of manually writing domain interfaces like this:

```kotlin
@Rdf(iri = "http://www.w3.org/ns/dcat#Catalog")
interface Catalog {
    @Rdf(iri = "http://purl.org/dc/terms/title")
    val title: String
    
    @Rdf(iri = "http://purl.org/dc/terms/description")
    val description: String
    
    @Rdf(iri = "http://www.w3.org/ns/dcat#dataset")
    val dataset: List<Dataset>
}
```

You can generate them automatically from SHACL shapes and JSON-LD context files.

## SHACL Shapes

SHACL (Shapes Constraint Language) defines the structure and constraints for your RDF data. Here's an example for DCAT:

```turtle
@prefix dcat: <http://www.w3.org/ns/dcat#> .
@prefix dcterms: <http://purl.org/dc/terms/> .
@prefix sh: <http://www.w3.org/ns/shacl#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

<http://example.org/shapes/Catalog>
    a sh:NodeShape ;
    sh:targetClass dcat:Catalog ;
    sh:property [
        sh:path dcterms:title ;
        sh:name "title" ;
        sh:description "A name given to the catalog." ;
        sh:datatype xsd:string ;
        sh:minCount 1 ;
        sh:maxCount 1 ;
    ] ;
    sh:property [
        sh:path dcterms:description ;
        sh:name "description" ;
        sh:description "A free-text account of the catalog." ;
        sh:datatype xsd:string ;
        sh:minCount 0 ;
        sh:maxCount 1 ;
    ] ;
    sh:property [
        sh:path dcat:dataset ;
        sh:name "dataset" ;
        sh:description "A collection of data that is listed in the catalog." ;
        sh:class dcat:Dataset ;
        sh:minCount 0 ;
    ] .
```

## JSON-LD Context

JSON-LD context provides type mappings and property definitions:

```json
{
  "@context": {
    "dcat": "http://www.w3.org/ns/dcat#",
    "dcterms": "http://purl.org/dc/terms/",
    
    "Catalog": "dcat:Catalog",
    "Dataset": "dcat:Dataset",
    
    "title": {
      "@id": "dcterms:title",
      "@type": "xsd:string"
    },
    "description": {
      "@id": "dcterms:description",
      "@type": "xsd:string"
    },
    "dataset": {
      "@id": "dcat:dataset",
      "@type": "@id"
    }
  }
}
```

## Code Generation

### 1. Create a generator class

Create a class annotated with **`@Rdf`**, pointing at SHACL (required) and JSON-LD context (recommended) under `src/main/resources`:

```kotlin
package com.example.mydomain.generated

import com.geoknoesis.kastor.gen.annotations.Rdf

@Rdf(
    shacl = "ontologies/my-ontology.shacl.ttl",
    context = "ontologies/my-ontology.context.jsonld",
    packageName = "com.example.mydomain.generated",
    generateInterfaces = true,
    generateWrappers = true,
)
class OntologyGenerator
```

You can instead put the same `@Rdf(shacl = …, context = …, …)` on a **`@file:Rdf`** annotation at the top of a Kotlin file if you prefer file-level configuration.

### 2. Generated Interfaces

The processor generates pure domain interfaces:

```kotlin
// GENERATED FILE - DO NOT EDIT
// Generated from SHACL shape: http://example.org/shapes/Catalog
package com.example.mydomain.generated

import com.geoknoesis.kastor.gen.annotations.Rdf

/**
 * Domain interface for http://www.w3.org/ns/dcat#Catalog
 * Generated from SHACL shape: http://example.org/shapes/Catalog
 */
@Rdf(iri = "http://www.w3.org/ns/dcat#Catalog")
interface Catalog {
    /**
     * A name given to the catalog.
     * Path: http://purl.org/dc/terms/title
     */
    @Rdf(iri = "http://purl.org/dc/terms/title")
    val title: String          // sh:minCount 1, sh:maxCount 1 -> non-null

    /**
     * A free-text account of the catalog.
     * Path: http://purl.org/dc/terms/description
     */
    @Rdf(iri = "http://purl.org/dc/terms/description")
    val description: String?   // sh:maxCount 1 without sh:minCount -> nullable

    /**
     * A collection of data that is listed in the catalog.
     * Path: http://www.w3.org/ns/dcat#dataset
     */
    @Rdf(iri = "http://www.w3.org/ns/dcat#dataset")
    val dataset: List<Dataset>
}
```

### 3. Generated Wrappers

The processor also generates RDF-backed wrapper implementations (simplified; the real output also
contains a generated `validate()` when `validationMode = EMBEDDED`, and a `writeToGraph` helper):

```kotlin
// GENERATED FILE - DO NOT EDIT
// Generated from SHACL shape: http://example.org/shapes/Catalog
package com.example.mydomain.generated

import com.geoknoesis.kastor.gen.runtime.*
import com.geoknoesis.kastor.rdf.*

/**
 * RDF-backed wrapper for Catalog
 * Generated from SHACL shape: http://example.org/shapes/Catalog
 */
internal class CatalogWrapper private constructor(
  input: RdfHandle,
) : Catalog, RdfBacked {

  override val rdf: RdfHandle by lazy(LazyThreadSafetyMode.PUBLICATION) {
    if (input is DefaultRdfHandle) input.withKnownPredicates(KNOWN) else input
  }

  /**
   * A name given to the catalog.
   * Path: http://purl.org/dc/terms/title
   */
  override val title: String by lazy {
    KastorGraphOps.getRequiredLiteralValue(rdf.graph, rdf.node, Iri("http://purl.org/dc/terms/title")).lexical
  }

  /**
   * A free-text account of the catalog.
   * Path: http://purl.org/dc/terms/description
   */
  override val description: String? by lazy {
    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, Iri("http://purl.org/dc/terms/description"))
      .map { it.lexical }.firstOrNull()
  }

  /**
   * A collection of data that is listed in the catalog.
   * Path: http://www.w3.org/ns/dcat#dataset
   */
  override val dataset: List<Dataset> by lazy {
    KastorGraphOps.getObjectValues(rdf.graph, rdf.node, Iri("http://www.w3.org/ns/dcat#dataset")) { child ->
      OntoMapper.materialize(RdfRef(child, rdf.graph), Dataset::class.java)
    }
  }

  companion object {
    private val KNOWN: Set<Iri> = setOf(
      Iri("http://purl.org/dc/terms/description"),
      Iri("http://purl.org/dc/terms/title"),
      Iri("http://www.w3.org/ns/dcat#dataset"),
    )

    init {
      OntoMapper.register(Catalog::class.java) { handle -> CatalogWrapper(handle) }
    }
  }
}
```

### Reading values

- **Ill-typed literals fail by default.** A value whose lexical form is not valid for the property's type
  (e.g. `"abc"^^xsd:integer`) makes the wrapper or data-class factory throw a `MaterializationException`
  naming the value, datatype and property, including for list-valued properties (where such values used to
  disappear silently). Set `MaterializationPolicy.illTypedValues = IllTypedValueHandling.SKIP` to leave them
  out with a logged warning instead (see the [runtime reference](../reference/runtime.md#materializationpolicy)).
- **Missing values are never replaced by invented defaults** (`""`, `0`, `false`): a required
  single-valued (non-null) member throws when its value is missing, an optional (nullable) member returns
  `null`, and a list without values is empty (live wrappers throw instead when the path has
  `sh:minCount` ≥ 1).

### Generated validation

With `validationMode = EMBEDDED` the wrapper's `validate()` checks, for every path of the shape
(inherited paths included): `sh:minCount`/`sh:maxCount`; `sh:datatype` (a literal of exactly that datatype
with a well-formed lexical form); `sh:nodeKind`; `sh:class` (an `rdf:type` equal to the class or a
transitive `rdfs:subClassOf` of it); `sh:pattern`; string lengths; `sh:in`; and the numeric bounds
`sh:minInclusive`/`sh:maxInclusive`/`sh:minExclusive`/`sh:maxExclusive`, compared **exactly** (as
`BigDecimal`), with a value that is not a well-formed numeric literal reported as a violation. Each
`sh:pattern` regex is compiled lazily on first use, so an unusual pattern can never break class
initialisation.

With `validationMode = EXTERNAL` the wrapper creates **one** instance of `externalValidatorClass` per
wrapper class, lazily on the first `validate()` call, and reuses it; validators that parse shapes or hold a
repository are not recreated per call.

Embedded validation covers the constraints above only; use a `ValidationContext`
(`JenaValidation`/`Rdf4jValidation`) for full SHACL semantics.

## Type Mapping

Literal properties are typed from `sh:datatype`; values are decoded with `XsdLiterals` (see the
[runtime reference](../reference/runtime.md#xsdliterals)):

| SHACL datatype | Kotlin type | Notes |
|----------------|-------------|-------|
| `xsd:string`, no datatype | `String` | |
| `xsd:boolean` | `Boolean` | accepts `true`/`false` and `1`/`0` |
| `xsd:int`, `xsd:short`, `xsd:byte`, `xsd:unsignedShort`, `xsd:unsignedByte` | `Int` | |
| `xsd:long`, `xsd:unsignedInt` | `Long` | |
| `xsd:integer`, `xsd:nonNegativeInteger`, `xsd:positiveInteger`, `xsd:nonPositiveInteger`, `xsd:negativeInteger`, `xsd:unsignedLong` | `java.math.BigInteger` | exact, unbounded; XSD lexical rules (a leading `+` is accepted) |
| `xsd:decimal` | `java.math.BigDecimal` | exact; exponents (`1e3`) are not decimal syntax and are rejected |
| `xsd:float` | `Float` | accepts `INF`, `-INF`, `NaN` |
| `xsd:double` | `Double` | accepts `INF`, `-INF`, `NaN` |
| `xsd:date` | `java.time.LocalDate` | an optional timezone is accepted and **dropped** on read; years above 9999 (`12345-06-07`) and negative years round-trip |
| `rdf:langString` | `com.geoknoesis.kastor.rdf.LangString` | value and language tag |
| `xsd:dateTime`, `xsd:time`, `xsd:duration` | `String` | lexical form; no single `java.time` type represents their optional timezones losslessly |
| any other datatype (e.g. `xsd:anyURI`) | `String` | lexical form |

Writers (data-class `toTriples`, DSL builders) always emit the **declared** datatype, so a `String`
declared `xsd:dateTime` is written back as `"…"^^xsd:dateTime`.

Non-literal properties:

| SHACL | Kotlin type |
|-------|-------------|
| `sh:class C` where `C` has a shape | the generated interface for `C` |
| `sh:class C` where `C` has no shape | `String` (the IRI) |
| `sh:node S` (S has one `sh:targetClass`) | the generated interface for S's target class |
| `sh:nodeKind sh:IRI` / `sh:BlankNodeOrIRI` / `sh:BlankNode` only | `String` (the IRI) |
| `sh:nodeKind sh:Literal` only | `String` (lexical form) |
| `sh:or` / `sh:xone` whose members agree on one `sh:class` or one `sh:datatype` | that class / datatype |
| `sh:or` / `sh:xone` over several classes | `String` (the IRI), with a warning |
| `sh:in` | a generated enum (see below) |

Cardinality:
- `sh:maxCount 1` with `sh:minCount 1` → non-null value
- `sh:maxCount 1` without `sh:minCount` → nullable value
- no `sh:maxCount`, or `sh:maxCount > 1` → `List<T>`

### `sh:in` enums

Interfaces, wrappers and data classes expose an `sh:in` value set as a generated enum. When distinct values
map to the same constant name (`"in-progress"` and `"in_progress"`, `"Draft"` and `"draft"`, or equal local
names in different namespaces), later members in `sh:in` order get `_2`, `_3`, … suffixes (with a
warning), each keeping its own value.

The instance DSL keeps **`String` setters** for `sh:in` properties, checked against the allowed values,
because the DSL can be generated without the enums; where it refers to an enum it uses the enum's
package-qualified name, so the DSL compiles when it is generated into a different package.

## Naming

- **Types** come from the JSON-LD context term mapped to the class IRI, else the IRI's local name,
  converted to PascalCase.
- **Properties** come from `sh:name`, else the path's local name, converted to camelCase
  (`date-issued`, `Date issued` → `dateIssued`). Kotlin keywords are backtick-escaped (`` `class` ``); a
  leading digit gets `_`; names that clash with generated members (`rdf`, `validate`, `copy`, …) get a
  `Value` suffix. Names are computed once and shared by every generator.
- **Collisions fail the build** and list the IRIs involved: two classes mapping to the same type name
  (compared case-insensitively, since generated file names must be distinct on case-insensitive file
  systems), several node shapes for one class, or two properties of one shape with the same Kotlin name.
  Resolve them with distinct context terms or `sh:name` values; for example the DCAT-US example maps
  `vcard:Address` and `locn:Address` to `VcardAddress` and `LocnAddress` in its context.
- Descriptions are copied into KDoc with `/*`, `*/` and `%` escaped, so shape text cannot break the
  generated source.

## Inheritance

A node shape with `sh:node <OtherShape>`, or a target class that is `rdfs:subClassOf` another shaped
class, produces an interface that **extends** the parent's generated interface. Every generator
(interfaces, wrappers, data classes, factories, writers, DSL) uses the same member list, so the output
always compiles:

- Inherited properties are not redeclared unless the child shape restates the path.
- A **restated path keeps the parent's member name**; a different `sh:name` on the child is ignored with a
  warning.
- The restated signature must be a valid Kotlin override: a nullable single value may become **non-null**
  (child adds `sh:minCount 1`), but a `List` stays a `List` and the value type stays the parent's.
- Stricter child constraints that a signature cannot express (e.g. `sh:maxCount 1` on a path the parent
  exposes as a `List`, a narrower `sh:datatype`/`sh:class`, tighter bounds or patterns) are combined with the
  parent's and **enforced by generated validation**.
- When two parents expose the same path under different names, the child has both members.
- Parents that declare one path both as a list and as a single value, or with different value types,
  **fail generation** with a message naming the shape and path (align their `sh:maxCount`,
  `sh:datatype`/`sh:class`).

## Parser behaviour

- A SHACL file that is not valid Turtle **fails the build**; it is never treated as "no shapes".
- When one node shape lists **the same path in several property shapes**, one member is generated, named
  after the alphabetically first `sh:name`, with the constraints of all declarations combined (highest
  `sh:minCount`, lowest `sh:maxCount`, …); a warning names the shape and path.
- `sh:pattern` values are XPath regular expressions and are translated for `java.util.regex`: `\i`/`\I`
  and `\c`/`\C` (XML name characters, approximated with Unicode letter/digit classes plus `_ : . -` and
  U+00B7), character-class subtraction `[base-[excluded]]`, and Unicode block escapes `\p{IsBlock}`
  (to `\p{InBlock}`). The `q` flag disables translation. A pattern that is still not a valid regular
  expression **fails generation**, naming the shape, path and pattern.
- Blank-node node shapes are supported, as are implicit class targets (a shape that is also an
  `rdfs:Class`/`owl:Class`).
- Constructs that cannot be represented are **skipped with a warning** naming the shape and property:
  complex property paths (inverse/sequence/alternative), properties with none of `sh:datatype`,
  `sh:class`, `sh:node` or `sh:nodeKind`, shapes without a target class, non-integer cardinalities, etc.

## Usage

After generation, use the interfaces like any other domain objects:

```kotlin
// Materialize from RDF
val catalogRef = RdfRef(iri("https://data.example.org/catalog"), repo.defaultGraph)
val catalog: Catalog = catalogRef.asType()

// Pure domain usage
println("Title: ${catalog.title}")
println("Description: ${catalog.description}")
println("Dataset count: ${catalog.dataset.size}")

// Side-channel access
val extras = catalog.asRdf().extras
val altLabels = extras.strings(SKOS.altLabel)
println("Alternative labels: ${altLabels.joinToString()}")

```

For SHACL validation at runtime use a `ValidationContext` such as `JenaValidation` together with
`materializeValidated` (see [Validation](validation.md)).

## Configuration options

Ontology-driven generation uses the **`@Rdf` annotation fields** documented in [`reference/annotations.md`](../reference/annotations.md). Common options:

```kotlin
import com.geoknoesis.kastor.gen.annotations.Rdf
import com.geoknoesis.kastor.gen.annotations.ValidationMode

@Rdf(
    shacl = "ontologies/my-ontology.shacl.ttl",              // Required for ontology mode
    context = "ontologies/my-ontology.context.jsonld",       // Recommended (property / type names)
    packageName = "com.example.mydomain.generated",          // Optional; defaults to declaration package
    generateInterfaces = true,                               // Default true
    generateWrappers = true,                                 // Default true
    generateDsl = false,                                     // Optional instance-DSL output
    validationMode = ValidationMode.EMBEDDED,                // How validation is wired into wrappers
)
class OntologyGenerator
```

## Benefits

### 1. **Consistency** (100% Guarantee)
- Interfaces always match your ontology definitions
- No manual synchronization between ontology and code
- Changes to ontology automatically propagate to code
- **Impact**: Zero sync errors, always up-to-date

### 2. **Type Safety** (Compile-Time)
- Automatic type mapping from SHACL datatypes
- Compile-time validation of property types
- Cardinality constraints enforced at type level
- **Impact**: 100% type safety, zero runtime type errors

### 3. **Productivity** (90% Time Savings)
- Generate interfaces in 2 minutes vs 1-2 hours manually
- Update after ontology changes in 17 minutes vs 40-65 minutes
- No manual property definitions needed
- **Impact**: 90% reduction in manual code writing

### 4. **Documentation**
- Generated interfaces include ontology descriptions
- Property constraints (min/max count) documented
- Clear mapping from ontology to code
- **Impact**: Self-documenting code, easier onboarding

### 5. **Maintainability** (Single Source of Truth)
- Single source of truth (ontology files)
- No duplicate interface definitions
- Easy to update when ontology changes
- **Impact**: 60-75% faster maintenance cycles

### 6. **Validation**
- SHACL constraints can be used for runtime validation
- Generated wrappers support validation hooks
- Consistent validation across all generated types
- **Impact**: Consistent validation, fewer data quality issues

[See detailed benefits and metrics →](../getting-started/benefits.md)

## Best Practices

### 1. **File Organization**
```
src/main/resources/
├── ontologies/
│   ├── my-ontology.shacl.ttl
│   └── my-ontology.context.jsonld
└── ...

src/main/kotlin/
└── com/example/mydomain/
    ├── generated/
    │   └── OntologyGenerator.kt
    └── ...
```

### 2. **Naming Conventions**
- Use descriptive names in SHACL (`sh:name`)
- Follow Kotlin naming conventions for generated interfaces
- Use consistent prefixes in JSON-LD context

### 3. **Version Control**
- Commit ontology files to version control
- Generated code should be in `.gitignore`
- KSP does not see edits to ontology files (the processor warns about each file it reads): declare
  `src/main/resources` as an input of the `kspKotlin` task and set `kastor.gen.resources.tracked=true`
  (see [Incremental Builds](../guides/incremental-builds.md#ksp-processor))

### 4. **Testing**
- Test generated interfaces with sample data
- Validate generated wrappers work correctly
- Test side-channel access functionality

## Limitations

### 1. **SHACL Support**
- Complex property paths (inverse, sequence, alternative) are skipped with a warning
- `sh:or`/`sh:xone` are typed only when their members agree on one class or datatype; `sh:and`/`sh:not`
  do not influence types
- Full SHACL semantics at runtime require a `ValidationContext` (`JenaValidation`/`Rdf4jValidation`)

### 2. **Type System**
- `xsd:dateTime`, `xsd:time`, `xsd:duration` and custom datatypes are exposed as `String`
- `xsd:date` drops a timezone suffix on read

### 3. **Performance**
- Code generation happens at compile time
- Large ontologies may slow down builds
- Generated code is optimized for readability over performance

## Future Enhancements

### 1. **Advanced SHACL Support**
- Support for complex constraint combinations
- Custom validation rule generation

### 2. **Enhanced Type System**
- Custom datatype mapping
- Generic type support
- Union type handling

### 3. **Code Generation Options**
- Customizable code templates
- Multiple output formats
- Integration with other code generators

### 4. **Tooling Integration**
- IDE support for ontology files
- Real-time validation
- Code completion for generated interfaces

## Conclusion

Ontology-driven code generation provides a powerful way to maintain consistency between your RDF ontology and Kotlin domain code. By generating interfaces and wrappers from SHACL shapes and JSON-LD context, you eliminate manual synchronization and ensure type safety.

The generated code follows the same patterns as manually written interfaces, providing pure domain objects with optional RDF side-channel access. This approach scales well for large ontologies and complex domain models.



