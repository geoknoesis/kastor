# SPARQL Extension Functions

Kastor provides a comprehensive extension function system that supports both built-in SPARQL 1.2 functions and custom extension functions. This system enables providers to advertise their function capabilities and allows for easy registration and discovery of available functions.

## 🎯 Overview

The extension function system includes:

- **Function Registry**: Central registry for all SPARQL functions
- **Built-in Functions**: All SPARQL 1.2 built-in functions automatically registered
- **Custom Functions**: Support for custom extension functions
- **Function Discovery**: Easy discovery and querying of available functions
- **Service Description Integration**: Functions automatically included in service descriptions
- **Provider Integration**: Functions advertised through provider capabilities

## 🚀 Function Registry

### SparqlExtensionFunctionRegistry

The central registry for managing SPARQL extension functions:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

object SparqlExtensionFunctionRegistry {
    // Register a function
    fun register(function: SparqlExtensionFunction)
    
    // Get all registered functions
    fun getAllFunctions(): List<SparqlExtensionFunction>
    
    // Get function by IRI
    fun getFunction(iri: String): SparqlExtensionFunction?
    
    // Get functions by name
    fun getFunctionsByName(name: String): List<SparqlExtensionFunction>
    
    // Check if function is registered
    fun isRegistered(iri: String): Boolean
    
    // Get built-in functions only
    fun getBuiltInFunctions(): List<SparqlExtensionFunction>
    
    // Get custom functions only
    fun getCustomFunctions(): List<SparqlExtensionFunction>
}
```

### SparqlExtensionFunction

Represents a SPARQL extension function:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

data class SparqlExtensionFunction(
    val iri: String,                    // Function IRI
    val name: String,                   // Function name
    val description: String,            // Function description
    val argumentTypes: List<String>?,   // Expected argument types
    val returnType: String?,           // Return type
    val isAggregate: Boolean = false,   // Whether it's an aggregate function
    val isBuiltIn: Boolean = false      // Whether it's a built-in function
)
```

## 📊 Built-in SPARQL 1.2 Functions

### RDF-star Functions

Functions for working with quoted triples:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

// TRIPLE(subject, predicate, object)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#TRIPLE",
    name = "TRIPLE",
    description = "Creates a quoted triple from subject, predicate, and object",
    argumentTypes = listOf(
        "http://www.w3.org/2001/XMLSchema#anyType",
        "http://www.w3.org/2001/XMLSchema#anyType", 
        "http://www.w3.org/2001/XMLSchema#anyType"
    ),
    returnType = RDF.Statement.value,
    isBuiltIn = true
)

// isTRIPLE(term)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#isTRIPLE",
    name = "isTRIPLE",
    description = "Returns true if the term is a quoted triple",
    argumentTypes = listOf("http://www.w3.org/2001/XMLSchema#anyType"),
    returnType = XSD.boolean.value,
    isBuiltIn = true
)

// SUBJECT(triple)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#SUBJECT",
    name = "SUBJECT",
    description = "Returns the subject of a quoted triple",
    argumentTypes = listOf(RDF.Statement.value),
    returnType = "http://www.w3.org/2001/XMLSchema#anyType",
    isBuiltIn = true
)

// PREDICATE(triple)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#PREDICATE",
    name = "PREDICATE",
    description = "Returns the predicate of a quoted triple",
    argumentTypes = listOf(RDF.Statement.value),
    returnType = "http://www.w3.org/2001/XMLSchema#anyType",
    isBuiltIn = true
)

// OBJECT(triple)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#OBJECT",
    name = "OBJECT",
    description = "Returns the object of a quoted triple",
    argumentTypes = listOf(RDF.Statement.value),
    returnType = "http://www.w3.org/2001/XMLSchema#anyType",
    isBuiltIn = true
)
```

### String Functions

Enhanced string manipulation functions:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

// replaceAll(string, pattern, replacement)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#replaceAll",
    name = "replaceAll",
    description = "Replaces all occurrences of a pattern in a string",
    argumentTypes = listOf(
        XSD.string.value,
        XSD.string.value,
        XSD.string.value
    ),
    returnType = XSD.string.value,
    isBuiltIn = true
)

// encodeForUri(string)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#encodeForUri",
    name = "encodeForUri",
    description = "Encodes a string for use in URIs",
    argumentTypes = listOf(XSD.string.value),
    returnType = XSD.string.value,
    isBuiltIn = true
)

// decodeForUri(string)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#decodeForUri",
    name = "decodeForUri",
    description = "Decodes a URI-encoded string",
    argumentTypes = listOf(XSD.string.value),
    returnType = XSD.string.value,
    isBuiltIn = true
)
```

### Language and Direction Functions

Functions for internationalization support:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

// LANGDIR(term)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#LANGDIR",
    name = "LANGDIR",
    description = "Returns the language direction of a term",
    argumentTypes = listOf("http://www.w3.org/2001/XMLSchema#anyType"),
    returnType = XSD.string.value,
    isBuiltIn = true
)

// hasLANG(term, language)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#hasLANG",
    name = "hasLANG",
    description = "Returns true if the term has the specified language",
    argumentTypes = listOf(
        "http://www.w3.org/2001/XMLSchema#anyType",
        XSD.string.value
    ),
    returnType = XSD.boolean.value,
    isBuiltIn = true
)

// hasLANGDIR(term, direction)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#hasLANGDIR",
    name = "hasLANGDIR",
    description = "Returns true if the term has the specified language direction",
    argumentTypes = listOf(
        "http://www.w3.org/2001/XMLSchema#anyType",
        XSD.string.value
    ),
    returnType = XSD.boolean.value,
    isBuiltIn = true
)

// STRLANGDIR(string, direction)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#STRLANGDIR",
    name = "STRLANGDIR",
    description = "Creates a language-tagged string with direction",
    argumentTypes = listOf(
        XSD.string.value,
        XSD.string.value
    ),
    returnType = RDF.langString.value,
    isBuiltIn = true
)
```

### Date/Time Functions

Functions for temporal data handling:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

// now()
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#now",
    name = "now",
    description = "Returns the current date and time",
    argumentTypes = emptyList(),
    returnType = XSD.dateTime.value,
    isBuiltIn = true
)

// timezone()
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#timezone",
    name = "timezone",
    description = "Returns the current timezone",
    argumentTypes = emptyList(),
    returnType = XSD.string.value,
    isBuiltIn = true
)

// dateTime(string)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#dateTime",
    name = "dateTime",
    description = "Creates a dateTime from a string",
    argumentTypes = listOf(XSD.string.value),
    returnType = XSD.dateTime.value,
    isBuiltIn = true
)

// date()
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#date",
    name = "date",
    description = "Returns the current date",
    argumentTypes = emptyList(),
    returnType = XSD.date.value,
    isBuiltIn = true
)

// time()
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#time",
    name = "time",
    description = "Returns the current time",
    argumentTypes = emptyList(),
    returnType = XSD.time.value,
    isBuiltIn = true
)

// tz(datetime, timezone)
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#tz",
    name = "tz",
    description = "Converts a datetime to a different timezone",
    argumentTypes = listOf(
        XSD.dateTime.value,
        XSD.string.value
    ),
    returnType = XSD.dateTime.value,
    isBuiltIn = true
)
```

### Random Functions

Functions for statistical and sampling operations:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

// random()
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#random",
    name = "random",
    description = "Returns a random number between 0 and 1",
    argumentTypes = emptyList(),
    returnType = XSD.double.value,
    isBuiltIn = true
)

// rand()
SparqlExtensionFunction(
    iri = "http://www.w3.org/ns/sparql#rand",
    name = "rand",
    description = "Returns a random integer",
    argumentTypes = emptyList(),
    returnType = XSD.integer.value,
    isBuiltIn = true
)
```

## 🔧 Function Registration

### Automatic Registration

Built-in functions are automatically registered when the system starts:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

object Sparql12BuiltInFunctions {
    private val functions = listOf(
        // All SPARQL 1.2 built-in functions
        // ... (function definitions)
    )
    
    init {
        // Register all functions
        functions.forEach { function -> 
            SparqlExtensionFunctionRegistry.register(function) 
        }
    }
}
```

### Manual Registration

Register custom functions:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
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

### Aggregate Functions

Register aggregate functions:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

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
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

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

### Getting Built-in Functions

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

val builtInFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions()
println("Built-in functions: ${builtInFunctions.size}")

builtInFunctions.forEach { func ->
    println("Built-in: ${func.name}")
}
```

### Getting Custom Functions

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

val customFunctions = SparqlExtensionFunctionRegistry.getCustomFunctions()
println("Custom functions: ${customFunctions.size}")

customFunctions.forEach { func ->
    println("Custom: ${func.name}")
}
```

### Function Lookup

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

// Get function by IRI
val tripleFunction = SparqlExtensionFunctionRegistry.getFunction(
    "http://www.w3.org/ns/sparql#TRIPLE"
)

if (tripleFunction != null) {
    println("Found function: ${tripleFunction.name}")
    println("Description: ${tripleFunction.description}")
}

// Get functions by name
val tripleFunctions = SparqlExtensionFunctionRegistry.getFunctionsByName("TRIPLE")
println("Functions named 'TRIPLE': ${tripleFunctions.size}")

// Check if function is registered
val isRegistered = SparqlExtensionFunctionRegistry.isRegistered(
    "http://www.w3.org/ns/sparql#TRIPLE"
)
println("TRIPLE function registered: $isRegistered")
```

## 🎨 Provider Integration

### Function Capabilities

Providers advertise their function capabilities:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

val provider = RdfProviderRegistry.getProvider("memory")
val capabilities = provider.getCapabilities()

println("Extension Functions: ${capabilities.extensionFunctions.size}")
capabilities.extensionFunctions.forEach { func ->
    println("- ${func.name}: ${func.description}")
}
```

### Service Description Integration

Functions are automatically included in service descriptions:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

val serviceDescription = provider.generateServiceDescription("http://example.org/sparql")
val triples = serviceDescription.getTriples()

// Find function-related triples
val functionTriples = triples.filter { triple ->
    triple.predicate == SPARQL_SD.extensionFunction
}

println("Service advertises ${functionTriples.size} extension functions")
```

## 📋 Function Categories

### By Type

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

val allFunctions = SparqlExtensionFunctionRegistry.getAllFunctions()

// RDF-star functions
val rdfStarFunctions = allFunctions.filter { 
    it.iri.startsWith("http://www.w3.org/ns/sparql#") && 
    it.name in listOf("TRIPLE", "isTRIPLE", "SUBJECT", "PREDICATE", "OBJECT")
}

// String functions
val stringFunctions = allFunctions.filter { 
    it.iri.startsWith("http://www.w3.org/ns/sparql#") && 
    it.name in listOf("replaceAll", "encodeForUri", "decodeForUri")
}

// Date/time functions
val dateTimeFunctions = allFunctions.filter { 
    it.iri.startsWith("http://www.w3.org/ns/sparql#") && 
    it.name in listOf("now", "timezone", "dateTime", "date", "time", "tz")
}

// Random functions
val randomFunctions = allFunctions.filter { 
    it.iri.startsWith("http://www.w3.org/ns/sparql#") && 
    it.name in listOf("random", "rand")
}
```

### By Aggregation

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

val allFunctions = SparqlExtensionFunctionRegistry.getAllFunctions()

// Aggregate functions
val aggregateFunctions = allFunctions.filter { it.isAggregate }
println("Aggregate functions: ${aggregateFunctions.size}")

// Non-aggregate functions
val nonAggregateFunctions = allFunctions.filter { !it.isAggregate }
println("Non-aggregate functions: ${nonAggregateFunctions.size}")
```

## 🎯 Best Practices

### 1. Function Registration

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

// Register functions with complete metadata
val function = SparqlExtensionFunction(
    iri = "http://example.org/functions#myFunction",
    name = "myFunction",
    description = "Clear, descriptive function description",
    argumentTypes = listOf(XSD.string.value),
    returnType = XSD.string.value,
    isAggregate = false,
    isBuiltIn = false
)

SparqlExtensionFunctionRegistry.register(function)
```

### 2. Function Discovery

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

// Check if function is available before using
val functionIri = "http://www.w3.org/ns/sparql#TRIPLE"
if (SparqlExtensionFunctionRegistry.isRegistered(functionIri)) {
    val function = SparqlExtensionFunctionRegistry.getFunction(functionIri)
    println("Function available: ${function?.name}")
} else {
    println("Function not available")
}
```

### 3. Provider Capabilities

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

// Check provider function capabilities
val provider = RdfProviderRegistry.getProvider("memory")
val capabilities = provider.getCapabilities()

if (capabilities.extensionFunctions.isNotEmpty()) {
    println("Provider supports ${capabilities.extensionFunctions.size} extension functions")
    // Use extension functions
} else {
    println("Provider does not support extension functions")
}
```

## 📖 Complete Example

```kotlin
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD

fun extensionFunctionExample() {
    // Get all functions
    val allFunctions = SparqlExtensionFunctionRegistry.getAllFunctions()
    println("Total functions: ${allFunctions.size}")
    
    // Get built-in functions
    val builtInFunctions = SparqlExtensionFunctionRegistry.getBuiltInFunctions()
    println("Built-in functions: ${builtInFunctions.size}")
    
    // Get custom functions
    val customFunctions = SparqlExtensionFunctionRegistry.getCustomFunctions()
    println("Custom functions: ${customFunctions.size}")
    
    // Register a custom function
    val customFunction = SparqlExtensionFunction(
        iri = "http://example.org/functions#customFunction",
        name = "customFunction",
        description = "A custom SPARQL function",
        argumentTypes = listOf(XSD.string.value),
        returnType = XSD.string.value,
        isAggregate = false,
        isBuiltIn = false
    )
    
    SparqlExtensionFunctionRegistry.register(customFunction)
    println("Custom function registered")
    
    // Check if function is registered
    val isRegistered = SparqlExtensionFunctionRegistry.isRegistered(
        "http://example.org/functions#customFunction"
    )
    println("Custom function registered: $isRegistered")
    
    // Get function by IRI
    val retrievedFunction = SparqlExtensionFunctionRegistry.getFunction(
        "http://example.org/functions#customFunction"
    )
    if (retrievedFunction != null) {
        println("Retrieved function: ${retrievedFunction.name}")
        println("Description: ${retrievedFunction.description}")
    }
    
    // Get functions by name
    val tripleFunctions = SparqlExtensionFunctionRegistry.getFunctionsByName("TRIPLE")
    println("Functions named 'TRIPLE': ${tripleFunctions.size}")
    
    // Check provider capabilities
    val provider = RdfProviderRegistry.getProvider("memory")
    val capabilities = provider.getCapabilities()
    println("Provider extension functions: ${capabilities.extensionFunctions.size}")
    
    // List all function categories
    val rdfStarFunctions = allFunctions.filter { 
        it.name in listOf("TRIPLE", "isTRIPLE", "SUBJECT", "PREDICATE", "OBJECT")
    }
    println("RDF-star functions: ${rdfStarFunctions.size}")
    
    val stringFunctions = allFunctions.filter { 
        it.name in listOf("replaceAll", "encodeForUri", "decodeForUri")
    }
    println("String functions: ${stringFunctions.size}")
    
    val dateTimeFunctions = allFunctions.filter { 
        it.name in listOf("now", "timezone", "dateTime", "date", "time", "tz")
    }
    println("Date/time functions: ${dateTimeFunctions.size}")
    
    val randomFunctions = allFunctions.filter { 
        it.name in listOf("random", "rand")
    }
    println("Random functions: ${randomFunctions.size}")
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




