# SHACL Validation

Kastor supports SHACL validation with full **SHACL 1.2** support, including Core features and SPARQL Extensions. Validation is available in two complementary ways:

- **Kastor Gen ValidationContext** for domain materialization and `RdfHandle` validation.
- **Repository-level SHACL validation** via the `rdf/shacl/validation` module.

For the **provider model** (native Kastor engine vs optional adapters to Jena, RDF4J, and others), module layout, and implementation roadmap, see [SHACL validation architecture](../design/shacl-validation-architecture.md). For **performance benchmarking** (JMH harness, ERA-SHACL-Benchmark CLI, baselines), see [SHACL native engine: cross-implementation performance benchmarks](../design/shacl-native-engine-benchmark.md).

## Kastor Gen ValidationContext

Validation is explicit and optional. You decide when validation is enforced by passing a `ValidationContext` during materialization.

### Add a Validation Adapter

Pick the adapter that matches your backend:

```kotlin
dependencies {
    runtimeOnly(project(":kastor-gen:validation-jena"))
    // or
    runtimeOnly(project(":kastor-gen:validation-rdf4j"))
}
```

### Validate During Materialization

```kotlin
val validation = JenaValidation()
val person: Person = rdfRef.asValidatedType(validation)
```

### Validate After Materialization

```kotlin
val validation = JenaValidation()
val person: Person = rdfRef.asType(validation)
person.asRdf().validateOrThrow()
```

## Repository-Level SHACL Validation

Module **`rdf/shacl/validation`**: API reference, **`providerId`** table, **`maxCombinedGraphTriples` / `rdf4jUntrustedInputLimits`**, and cross-engine smoke tests live in the [module README](../../../rdf/shacl/validation/README.md).

Use the `rdf/shacl/validation` module when you want to validate graphs directly (without materialization):

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation

// Create shapes using the Kotlin SHACL DSL (recommended; add dependency `com.geoknoesis.kastor:rdf-shacl-dsl`)
val shapesGraph = shacl {
    nodeShape("http://example.org/PersonShape") {
        targetClass(FOAF.Person)
        property(FOAF.name) {
            minCount = 1
        }
    }
}

// Or create shapes manually
val shapesGraph = Rdf.graph {
    // ... manual RDF triples
}

// Validate
val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
val report = validator.validate(dataGraph, shapesGraph)

if (!report.isValid) {
    report.violations.forEach { println(it.message) }
}
```

> **Tip**: Use the [SHACL DSL](../api/shacl-dsl-guide.md) to create shapes graphs more easily. The DSL supports all SHACL 1.2 features including:
> - **SHACL 1.2 Core**: `targetWhere` with node expressions, `shape` targets, `singleLine` constraint, `reifierShape` and `reificationRequired` for RDF-star
> - **SHACL 1.2 SPARQL Extensions**: SPARQL-based constraints using SELECT and ASK queries
>
> See [How to Create SHACL Shapes](../guides/how-to-create-shacl-shapes.md) for examples. For **bundled ontology-quality shapes**, the `onto-qa` CLI, and the optional embedding tier, see [How to Check Ontology Quality](../guides/how-to-ontology-quality.md).

## Notes

- `ValidationContext` is only enforced when provided.
- `ValidationResult.NotConfigured` is returned when no context is available.
- Repository-level validation remains independent of Kastor Gen materialization.
