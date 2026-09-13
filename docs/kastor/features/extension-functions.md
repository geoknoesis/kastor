# SPARQL Extension Functions

Kastor keeps a registry of SPARQL function descriptions so that providers can advertise the functions they support, for example in a SPARQL Service Description. The registry holds metadata only: it does not implement or execute functions, which is the job of the underlying engine (Jena, RDF4J or a remote endpoint).

## 🎯 Overview

The extension function system includes:

- **Function Registry**: `SparqlExtensionFunctionRegistry` (module `rdf-sparql-lang`, package `com.geoknoesis.kastor.rdf.sparql`)
- **Built-in Functions**: the SPARQL 1.2 functions listed below, registered automatically when the registry is first used
- **Custom Functions**: descriptions you register yourself
- **Function Discovery**: lookup by IRI or name
- **Service Description Integration**: `ProviderCapabilities.extensionFunctions` is emitted by `SparqlServiceDescriptionGenerator`

## 🚀 Function Registry

### SparqlExtensionFunctionRegistry

A thread-safe object; listings are snapshots in registration order:

```kotlin
object SparqlExtensionFunctionRegistry {
    // Register a function (replaces a function with the same IRI)
    fun register(function: SparqlExtensionFunction)

    // Get all registered functions
    fun getAllFunctions(): List<SparqlExtensionFunction>

    // Get function by IRI
    fun getFunction(iri: String): SparqlExtensionFunction?

    // Get functions by name
    fun getFunctionsByName(name: String): List<SparqlExtensionFunction>

    // Check if function is registered
    fun isRegistered(iri: String): Boolean

    // Get built-in functions only (isBuiltIn = true)
    fun getBuiltInFunctions(): List<SparqlExtensionFunction>

    // Get custom functions only (isBuiltIn = false)
    fun getCustomFunctions(): List<SparqlExtensionFunction>
}
```

### SparqlExtensionFunction

Defined in `rdf-core` (package `com.geoknoesis.kastor.rdf`):

```kotlin
data class SparqlExtensionFunction(
    val iri: String,                          // Function IRI
    val name: String,                         // Function name
    val description: String,                 // Function description
    val argumentTypes: List<String> = emptyList(),
    val returnType: String? = null,
    val isAggregate: Boolean = false,
    val isBuiltIn: Boolean = true             // set false for your own functions
)
```

## 📊 Built-in SPARQL 1.2 Functions

`Sparql12BuiltInFunctions.functions` contains the following entries. Their IRIs are in the `http://www.w3.org/ns/sparql#` namespace (constants on the `SPARQL12` vocabulary object), and argument and return types are given as prefixed names.

| Group | Name | Arguments | Returns |
|-------|------|-----------|---------|
| Triple terms | `TRIPLE` | `rdf:Resource`, `rdf:Property`, `rdf:Resource` | `rdf:TripleTerm` |
| Triple terms | `isTRIPLE` | `rdf:Resource` | `xsd:boolean` |
| Triple terms | `SUBJECT` | `rdf:TripleTerm` | `rdf:Resource` |
| Triple terms | `PREDICATE` | `rdf:TripleTerm` | `rdf:Property` |
| Triple terms | `OBJECT` | `rdf:TripleTerm` | `rdf:Resource` |
| String | `encodeForUri` | `xsd:string` | `xsd:string` |
| Language / direction | `LANGDIR` | `rdf:dirLangString` | `xsd:string` |
| Language / direction | `hasLANG` | `rdf:langString` | `xsd:boolean` |
| Language / direction | `hasLANGDIR` | `rdf:dirLangString` | `xsd:boolean` |
| Language / direction | `STRLANGDIR` | `xsd:string`, `xsd:string`, `xsd:string` (lexical, language, direction) | `rdf:dirLangString` |
| Date / time | `now` | none | `xsd:dateTime` |
| Date / time | `timezone` | `xsd:dateTime` | `xsd:dayTimeDuration` |
| Date / time | `tz` | `xsd:dateTime` | `xsd:dayTimeDuration` |
| Random | `rand` | none | `xsd:double` (in [0, 1)) |

The non-standard entries `replaceAll`, `decodeForUri`, `random()` and a zero-argument `timezone()` are **not** registered. SPARQL `REPLACE` already replaces every match; SPARQL has no `DECODE_FOR_URI` or `RANDOM()`; and `TIMEZONE` requires a dateTime argument. The matching query-DSL helpers are deprecated (see [SPARQL 1.2 Support](sparql-1.2.md)). The `SPARQL12` vocabulary still defines IRIs for these names, but no built-in function uses them.

## 🔧 Function Registration

### Automatic Registration

The registry registers `Sparql12BuiltInFunctions.functions` in its own initializer, so the built-ins are always present no matter which object is touched first. No setup is needed.

### Manual Registration

```kotlin
import com.geoknoesis.kastor.rdf.SparqlExtensionFunction
import com.geoknoesis.kastor.rdf.sparql.SparqlExtensionFunctionRegistry
import com.geoknoesis.kastor.rdf.vocab.XSD

val customFunction = SparqlExtensionFunction(
    iri = "http://example.org/functions#customFunction",
    name = "customFunction",
    description = "A custom SPARQL function that does something useful",
    argumentTypes = listOf(XSD.string.value),
    returnType = XSD.string.value,
    isAggregate = false,
    isBuiltIn = false
)

SparqlExtensionFunctionRegistry.register(customFunction)
```

Registering a description does not make an engine able to evaluate the function. Custom functions must also be implemented in the engine you use (for example as a Jena function).

### Aggregate Functions

```kotlin
val aggregateFunction = SparqlExtensionFunction(
    iri = "http://example.org/functions#customAggregate",
    name = "customAggregate",
    description = "A custom aggregate function",
    argumentTypes = listOf(XSD.double.value),
    returnType = XSD.double.value,
    isAggregate = true,
    isBuiltIn = false
)

SparqlExtensionFunctionRegistry.register(aggregateFunction)
```

## 🔍 Function Discovery

### Getting All Functions

```kotlin
val allFunctions = SparqlExtensionFunctionRegistry.getAllFunctions()
println("Total functions: ${allFunctions.size}")

allFunctions.forEach { func ->
    println("Function: ${func.name}")
    println("IRI: ${func.iri}")
    println("Description: ${func.description}")
    println("Return Type: ${func.returnType}")
    println("Is Aggregate: ${func.isAggregate}")
    println("---")
}
```

### Built-in and Custom Functions

```kotlin
val builtInFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions()
println("Built-in functions: ${builtInFunctions.map { it.name }}")

val customFunctions = SparqlExtensionFunctionRegistry.getCustomFunctions()
println("Custom functions: ${customFunctions.map { it.name }}")
```

### Function Lookup

```kotlin
import com.geoknoesis.kastor.rdf.vocab.SPARQL12

// Get function by IRI
val tripleFunction = SparqlExtensionFunctionRegistry.getFunction(SPARQL12.TRIPLE.value)
println("Found: ${tripleFunction?.name} - ${tripleFunction?.description}")

// Get functions by name
val tripleFunctions = SparqlExtensionFunctionRegistry.getFunctionsByName("TRIPLE")
println("Functions named 'TRIPLE': ${tripleFunctions.size}")

// Check if function is registered
println("TRIPLE registered: ${SparqlExtensionFunctionRegistry.isRegistered(SPARQL12.TRIPLE.value)}")
```

## 🎨 Provider Integration

### Function Capabilities

`ProviderCapabilities.extensionFunctions` lets a provider advertise functions. The bundled providers (`memory`, `jena`, `rdf4j`, `sparql`) leave it empty. Custom providers can publish the registry contents:

```kotlin
override fun getCapabilities(variantId: String?): ProviderCapabilities =
    ProviderCapabilities(
        sparqlVersion = "1.2",
        supportsServiceDescription = true,
        extensionFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions()
    )
```

### Service Description Integration

`SparqlServiceDescriptionGenerator` emits one `sd:extensionFunction` per entry of `extensionFunctions`, with `sd:functionName`, `sd:description`, `sd:returnType` and (for aggregates) `sd:isAggregate`:

```kotlin
import com.geoknoesis.kastor.rdf.ProviderCapabilities
import com.geoknoesis.kastor.rdf.sparql.SparqlServiceDescriptionGenerator
import com.geoknoesis.kastor.rdf.vocab.SPARQL_SD

val capabilities = ProviderCapabilities(
    extensionFunctions = SparqlExtensionFunctionRegistry.getAllFunctions()
)
val description = SparqlServiceDescriptionGenerator("http://example.org/sparql", capabilities)
    .generateServiceDescription()

val functionTriples = description.getTriples().filter { it.predicate == SPARQL_SD.extensionFunction }
println("Service advertises ${functionTriples.size} extension functions")
```

## 📋 Function Categories

```kotlin
val allFunctions = SparqlExtensionFunctionRegistry.getAllFunctions()

val tripleTermFunctions = allFunctions.filter {
    it.name in listOf("TRIPLE", "isTRIPLE", "SUBJECT", "PREDICATE", "OBJECT")
}
val languageFunctions = allFunctions.filter {
    it.name in listOf("LANGDIR", "hasLANG", "hasLANGDIR", "STRLANGDIR")
}
val dateTimeFunctions = allFunctions.filter {
    it.name in listOf("now", "timezone", "tz")
}
val aggregateFunctions = allFunctions.filter { it.isAggregate }
```

## 🎯 Best Practices

1. **Register complete metadata** (description, argument and return types) and set `isBuiltIn = false` for your own functions, so they appear in `getCustomFunctions()`.
2. **Use stable IRIs** in a namespace you control; registering the same IRI again replaces the earlier description.
3. **Check engine support**, not just the registry: the registry describes functions, while the engine executes them.

## 📖 Complete Example

```kotlin
import com.geoknoesis.kastor.rdf.SparqlExtensionFunction
import com.geoknoesis.kastor.rdf.sparql.SparqlExtensionFunctionRegistry
import com.geoknoesis.kastor.rdf.vocab.XSD

fun extensionFunctionExample() {
    println("Built-in functions: ${SparqlExtensionFunctionRegistry.getBuiltInFunctions().size}")

    SparqlExtensionFunctionRegistry.register(
        SparqlExtensionFunction(
            iri = "http://example.org/functions#customFunction",
            name = "customFunction",
            description = "A custom SPARQL function",
            argumentTypes = listOf(XSD.string.value),
            returnType = XSD.string.value,
            isBuiltIn = false
        )
    )

    val retrieved = SparqlExtensionFunctionRegistry.getFunction("http://example.org/functions#customFunction")
    println("Retrieved: ${retrieved?.name} (${retrieved?.description})")
    println("Custom functions: ${SparqlExtensionFunctionRegistry.getCustomFunctions().map { it.name }}")
}
```

## 🔗 Related Documentation

- [SPARQL 1.2 Support](sparql-1.2.md)
- [Service Description](service-description.md)
- [Enhanced Providers](enhanced-providers.md)
- [Provider Capabilities](provider-capabilities.md)
- [RDF-star Support](rdf-star.md)

## 📞 Support

For questions about SPARQL extension functions in Kastor:

- **Email**: stephanef@geoknoesis.com
- **Issues**: GitHub Issues
- **Documentation**: [Kastor Docs](https://docs.kastor.org)

---

*Kastor SPARQL extension function system is developed by [GeoKnoesis LLC](https://geoknoesis.com) and maintained by Stephane Fellah.*
