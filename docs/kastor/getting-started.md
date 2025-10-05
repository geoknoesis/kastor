## Getting Started

### Requirements
- Kotlin JVM 17
- Gradle with Kotlin plugin

### Install
Add module dependencies based on the provider you want to use. Coordinates inherit the root group and version from the build: `com.geoknoesis.kastor:rdf-*` at version `0.1.0`.

Gradle Kotlin DSL:
```kotlin
dependencies {
  implementation("com.geoknoesis.kastor:rdf-core:0.1.0")
  // Choose one or more providers
  implementation("com.geoknoesis.kastor:rdf-jena:0.1.0")
  implementation("com.geoknoesis.kastor:rdf-rdf4j:0.1.0")
  implementation("com.geoknoesis.kastor:rdf-sparql:0.1.0")
}
```

### First repository
```kotlin
import com.geoknoesis.kastor.rdf.Rdf

val api = Rdf.factory {
  type("jena:memory")
}
val repo = api.repository
```

### Provider discovery
Providers are discovered via Java `ServiceLoader`. If you add a provider dependency, it becomes available to `RdfApiRegistry` and the factory DSL.

```kotlin
import com.geoknoesis.kastor.rdf.RdfApiRegistry

val ids: List<String> = RdfApiRegistry.providerIds() // e.g., ["jena", "rdf4j", "sparql"]
```

### Creating RDF Terms

The library provides strongly typed functions for creating RDF terms, especially literals:

```kotlin
import com.geoknoesis.kastor.rdf.*

// IRIs
val personClass = iri("http://example.org/Person")
val nameProperty = iri("http://example.org/name")

// Blank nodes
val person1 = bnode("person1")

// Literals - strongly typed for better safety
val personName = langLiteral("John Smith", "en")           // Language-tagged literal
val personAge = integerLiteral(30)                         // Typed literal (xsd:integer)
val personHeight = decimalLiteral(175.5)                   // Typed literal (xsd:decimal)
val personActive = booleanLiteral(true)                    // Typed literal (xsd:boolean)
val plainText = plainLiteral("Some text")                 // Plain literal (xsd:string)

// Triples
val triple = triple(person1, nameProperty, personName)

// Or use the natural language DSL
val tripleDsl = person1 has nameProperty with personName
```

**Benefits of strongly typed literals:**
- **Type safety**: Prevents invalid combinations (e.g., language + datatype)
- **Clarity**: Intent is explicit in the function name
- **Better IDE support**: Improved autocomplete and documentation
- **Validation**: Constraints enforced at compile time

The old generic `literal()` function is still available for backward compatibility but is deprecated.

