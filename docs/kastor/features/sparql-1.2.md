# SPARQL 1.2 Support in Kastor

Kastor provides comprehensive support for SPARQL 1.2, the latest version of the SPARQL query language specification. This includes new functions, enhanced syntax, RDF-star support, and improved query capabilities.

## 🎯 Overview

SPARQL 1.2 introduces several significant enhancements over SPARQL 1.1:

- **RDF-star Support**: Representing metadata about statements
- **Enhanced String Functions**: More powerful text manipulation
- **Language and Direction Functions**: Better internationalization support
- **Date/Time Functions**: Improved temporal data handling
- **Random Functions**: Statistical and sampling capabilities
- **Version Declaration**: Explicit SPARQL version specification

## 🚀 Key Features

### 1. RDF-star Support

RDF-star allows you to make statements about statements, enabling rich metadata modeling.

#### Quoted Triples
```kotlin
// Creating quoted triples in DSL
repo.add {
    << :alice :knows :bob >> :certainty 0.9
    << :alice :knows :bob >> :source :wikipedia
}

// Querying quoted triples
val query = """
    SELECT ?person ?certainty WHERE {
        << ?person :knows :bob >> :certainty ?certainty
    }
"""
```

#### SPARQL 1.2 RDF-star Functions
```kotlin
val query = """
    SELECT ?subject ?predicate ?object WHERE {
        ?statement :certainty ?certainty .
        FILTER(?certainty > 0.8)
        BIND(SUBJECT(?statement) AS ?subject)
        BIND(PREDICATE(?statement) AS ?predicate)
        BIND(OBJECT(?statement) AS ?object)
    }
"""
```

### 2. Enhanced String Functions

#### `replaceAll` Function
```kotlin
val query = """
    SELECT ?result WHERE {
        BIND(replaceAll("Hello World", "World", "Universe") AS ?result)
    }
"""
// Result: "Hello Universe"
```

#### URI Encoding/Decoding
```kotlin
val query = """
    SELECT ?encoded ?decoded WHERE {
        BIND(encodeForUri("Hello World!") AS ?encoded)
        BIND(decodeForUri("Hello%20World%21") AS ?decoded)
    }
"""
```

### 3. Language and Direction Functions

#### Language Direction Support
```kotlin
val query = """
    SELECT ?langdir ?hasLang ?hasLangdir WHERE {
        ?s rdfs:label "Hello"@en .
        BIND(LANGDIR(?s) AS ?langdir)
        BIND(hasLANG(?s, "en") AS ?hasLang)
        BIND(hasLANGDIR(?s, "ltr") AS ?hasLangdir)
    }
"""
```

### 4. Date/Time Functions

#### Current Time Functions
```kotlin
val query = """
    SELECT ?now ?timezone ?date ?time WHERE {
        BIND(now() AS ?now)
        BIND(timezone() AS ?timezone)
        BIND(date() AS ?date)
        BIND(time() AS ?time)
    }
"""
```

#### Date/Time Construction
```kotlin
val query = """
    SELECT ?datetime WHERE {
        BIND(dateTime("2024-01-15T10:30:00Z") AS ?datetime)
    }
"""
```

### 5. Random Functions

#### Random Number Generation
```kotlin
val query = """
    SELECT ?random ?rand WHERE {
        BIND(random() AS ?random)
        BIND(rand() AS ?rand)
    }
"""
```

### 6. Version Declaration

#### Explicit SPARQL Version
```kotlin
val query = """
    VERSION 1.2
    SELECT ?s ?p ?o WHERE {
        ?s ?p ?o
    }
"""
```

## 🔧 Provider Capabilities

### Checking SPARQL 1.2 Support

```kotlin
val provider = RdfProviderRegistry.getProvider("memory")
val capabilities = provider.getCapabilities()

// Check SPARQL version
println("SPARQL Version: ${capabilities.sparqlVersion}")

// Check specific features
println("RDF-star Support: ${capabilities.supportsRdfStar}")
println("Property Paths: ${capabilities.supportsPropertyPaths}")
println("Aggregation: ${capabilities.supportsAggregation}")
println("Federation: ${capabilities.supportsFederation}")
```

### Enhanced Capabilities

```kotlin
val detailedCapabilities = provider.getDetailedCapabilities()

// Check supported features
val supportedFeatures = detailedCapabilities.supportedSparqlFeatures
println("Supported Features: $supportedFeatures")

// Check extension functions
val functions = capabilities.extensionFunctions
println("Extension Functions: ${functions.size}")
```

## 📊 SPARQL 1.2 Vocabulary

Kastor includes comprehensive vocabulary support for SPARQL 1.2:

```kotlin
import com.geoknoesis.kastor.rdf.vocab.SPARQL12

// Use SPARQL 1.2 vocabulary terms
val service = SPARQL12.Sparql12Service
val supportsRdfStar = SPARQL12.supportsRdfStar
val tripleFunction = SPARQL12.TRIPLE
```

## 🎨 DSL Integration

### RDF-star in DSL

```kotlin
repo.add {
    // Create quoted triple
    val quotedTriple = quotedTripleOf {
        :alice has :knows with :bob
    }
    
    // Add metadata about the statement
    quotedTriple has :certainty with 0.9
    quotedTriple has :source with :wikipedia
}
```

### Property Paths

```kotlin
val query = """
    SELECT ?person WHERE {
        ?person :knows+ :alice  # Transitive closure
    }
"""
```

### Aggregation Functions

```kotlin
val query = """
    SELECT ?category (COUNT(?item) AS ?count) WHERE {
        ?item :category ?category
    }
    GROUP BY ?category
    HAVING (COUNT(?item) > 5)
"""
```

## 🔍 Service Description

SPARQL 1.2 services can describe their capabilities:

```kotlin
val serviceDescription = provider.generateServiceDescription("http://example.org/sparql")
println(serviceDescription.getTriples().size)
```

The service description includes:
- SPARQL version support
- RDF-star capabilities
- Extension functions
- Supported formats
- Dataset information

## 📚 Built-in Functions

Kastor registers all SPARQL 1.2 built-in functions:

### RDF-star Functions
- `TRIPLE(subject, predicate, object)`
- `isTRIPLE(term)`
- `SUBJECT(triple)`
- `PREDICATE(triple)`
- `OBJECT(triple)`

### String Functions
- `replaceAll(string, pattern, replacement)`
- `encodeForUri(string)`
- `decodeForUri(string)`

### Language/Direction Functions
- `LANGDIR(term)`
- `hasLANG(term, language)`
- `hasLANGDIR(term, direction)`
- `STRLANGDIR(string, direction)`

### Date/Time Functions
- `now()`
- `timezone()`
- `dateTime(string)`
- `date()`
- `time()`
- `tz(datetime, timezone)`

### Random Functions
- `random()`
- `rand()`

## 🎯 Best Practices

### 1. Version Declaration
Always declare SPARQL version for clarity:
```kotlin
val query = """
    VERSION 1.2
    SELECT * WHERE { ?s ?p ?o }
"""
```

### 2. RDF-star Usage
Use quoted triples for metadata:
```kotlin
repo.add {
    << :alice :knows :bob >> :certainty 0.9
    << :alice :knows :bob >> :source :wikipedia
}
```

### 3. Extension Functions
Check provider capabilities before using extension functions:
```kotlin
if (provider.getCapabilities().extensionFunctions.isNotEmpty()) {
    // Use extension functions
}
```

### 4. Error Handling
Handle unsupported features gracefully:
```kotlin
try {
    val result = repo.select(SparqlSelectQuery(sparql12Query))
} catch (e: UnsupportedOperationException) {
    // Fallback to SPARQL 1.1 query
}
```

## 🔧 Configuration

### Enabling SPARQL 1.2 Features

```kotlin
val repo = Rdf.repository {
    providerId = "jena"
    variantId = "memory"
}

val capabilities = repo.getCapabilities()
println("SPARQL version: ${capabilities.sparqlVersion}")
println("RDF-star: ${capabilities.supportsRdfStar}")
```

### Provider-Specific Configuration

```kotlin
val jenaRepo = Rdf.repository {
    providerId = "jena"
    variantId = "tdb2"
    location = "./data"
}
```

## 📖 Examples

### Complete SPARQL 1.2 Example

```kotlin
fun sparql12Example() {
    val repo = Rdf.memory()
    
    // Add RDF-star data
    repo.add {
        << :alice :knows :bob >> :certainty 0.9
        << :alice :knows :bob >> :source :wikipedia
        << :bob :knows :charlie >> :certainty 0.7
    }
    
    // Query with SPARQL 1.2 features
    val query = """
        VERSION 1.2
        SELECT ?person ?certainty ?source WHERE {
            << ?person :knows :bob >> :certainty ?certainty .
            << ?person :knows :bob >> :source ?source .
            FILTER(?certainty > 0.8)
        }
    """
    
    val results = repo.select(SparqlSelectQuery(query))
    results.forEach { binding ->
        val person = binding.get("person") as? Iri
        val source = binding.get("source") as? Iri
        println("Person: $person")
        println("Certainty: ${binding.getDouble("certainty")}")
        println("Source: $source")
    }
}
```

## 🚀 Migration from SPARQL 1.1

### 1. Add Version Declaration
```kotlin
// Before (SPARQL 1.1)
val query = "SELECT ?s ?p ?o WHERE { ?s ?p ?o }"

// After (SPARQL 1.2)
val query = """
    VERSION 1.2
    SELECT ?s ?p ?o WHERE { ?s ?p ?o }
"""
```

### 2. Enable RDF-star
```kotlin
val config = RdfConfig {
    enableRdfStar = true
}
```

### 3. Use New Functions
```kotlin
// Use new string functions
val query = """
    SELECT ?result WHERE {
        BIND(replaceAll("Hello World", "World", "Universe") AS ?result)
    }
"""
```

## 🔗 Related Documentation

- [SPARQL Service Description](service-description.md)
- [Provider Capabilities](provider-capabilities.md)
- [RDF-star Support](rdf-star.md)
- [Extension Functions](extension-functions.md)
- [Query DSL](query-dsl.md)

## 📞 Support

For questions about SPARQL 1.2 support in Kastor:

- **Email**: stephanef@geoknoesis.com
- **Issues**: GitHub Issues
- **Documentation**: [Kastor Docs](https://docs.kastor.org)

---

*Kastor SPARQL 1.2 support is developed by [GeoKnoesis LLC](https://geoknoesis.com) and maintained by Stephane Fellah.*



