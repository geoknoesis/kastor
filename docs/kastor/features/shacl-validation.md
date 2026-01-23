# SHACL Validation

Kastor supports SHACL validation in two complementary ways:

- **Kastor Gen ValidationContext** for domain materialization and `RdfHandle` validation.
- **Repository-level SHACL validation** via the `rdf/shacl-validation` module.

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

Use the `rdf/shacl-validation` module when you want to validate graphs directly (without materialization):

```kotlin
val validator = ShaclValidation.validator(ValidationProfile.SHACL_CORE)
val report = validator.validate(dataGraph, shapesGraph)

if (!report.isValid) {
    report.violations.forEach { println(it.message) }
}
```

## Notes

- `ValidationContext` is only enforced when provided.
- `ValidationResult.NotConfigured` is returned when no context is available.
- Repository-level validation remains independent of Kastor Gen materialization.
