# `rdf:shacl-validation`

SHACL validation API ([`ShaclValidator`](src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/ShaclValidatorProvider.kt)), native engine, and **provider SPI** ([`ShaclValidatorProvider`](src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/ShaclValidatorProvider.kt)).

## Validator providers (`providerId`)

| `providerId` | Gradle module | Notes |
|--------------|---------------|--------|
| `kastor` | `com.geoknoesis.kastor:shacl-validation` (this module) | Default native SHACL 1.2 Core engine. |
| `memory` | Same JAR | Simplified checks over extracted shapes; supports **`validate(graph, List<ShaclShape>)`** heuristically—**not** a full SHACL implementation. Prefer **`RdfGraph`** shapes for parity with other engines. |
| `rdf4j` | Add **`project(":rdf:rdf4j")`** (artifact `com.geoknoesis.kastor:rdf-rdf4j`) | Eclipse RDF4J **`ShaclSail`**; use `validate(data, shapes)` with both as **`RdfGraph`**. |

Resolve a validator:

```kotlin
ShaclValidation.validator(
    ValidationConfig(
        profile = ValidationProfile.SHACL_CORE,
        providerId = "rdf4j", // or "kastor", "memory"
        parallelValidation = false,
    ),
)
```

## API limitations (all engines)

- Prefer **`validate(dataGraph, shapesGraph)`** with both graphs in RDF.
- **`validate(graph, List<ShaclShape>)`** and **`validateConstraints`** are **not** implemented for **native** or **RDF4J** when the list is non-empty; they throw **`UnsupportedOperationException`** with a message to use a shapes graph.

## Resource limits (all engines)

- **`ValidationConfig.maxCombinedGraphTriples`** — reject `data.size() + shapes.size()` above this value **before** validation (default **`Long.MAX_VALUE`**). Use **`ValidationConfig.rdf4jUntrustedInputLimits()`** for conservative starter values (applies to RDF4J, native, and memory).
- **`ValidationConfig.maxViolations`** — max violation rows returned; if the engine collects more, **`ValidationReport.violationsTruncated`** is **`true`** and the returned list is capped.

## SPI registration

Providers are loaded via `META-INF/services/com.geoknoesis.kastor.rdf.shacl.ShaclValidatorProvider`. If a provider JAR is missing, its id will not appear in [`ValidatorRegistry`](src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/ValidatorRegistry.kt). Discovery warnings are logged at **`WARNING`** if loading fails.

## Further reading

- [SHACL validation architecture](../../../docs/kastor/design/shacl-validation-architecture.md) (if present in your docs checkout)
- [SHACL benchmarks](../../../benchmarks/shacl/README.md)
