# SPARQL 1.2 Support in Kastor

Kastor provides comprehensive support for SPARQL 1.2, the latest version of the SPARQL query language specification. This includes new functions, enhanced syntax, RDF 1.2 triple terms, and improved query capabilities.

> **RDF 1.2 syntax note.** Quoted triples now use the RDF 1.2 form
> `<<( s p o )>>` (with parentheses) and may appear only as the object of a
> triple. Kastor's renderer emits this form everywhere; both Jena and RDF4J
> parsers continue to accept the legacy `<<s p o>>` for backwards
> compatibility. See [RDF 1.2 in Kastor](../concepts/rdf-1.2.md).

## 🎯 Overview

SPARQL 1.2 introduces several significant enhancements over SPARQL 1.1:

- **RDF 1.2 Triple Terms**: Representing metadata about statements via triple terms and `rdf:reifies`
- **Enhanced String Functions**: More powerful text manipulation
- **Language and Direction Functions**: Better internationalization support
- **Date/Time Functions**: Improved temporal data handling
- **Random Functions**: Statistical and sampling capabilities
- **Version Declaration**: Explicit SPARQL version specification

## 🚀 Key Features

### 1. RDF-star Support

RDF-star allows you to make statements about statements, enabling rich metadata modeling.

#### Reified Triples
```kotlin
val ex = "http://example.org/"
val alice = iri("${ex}alice")
val bob = iri("${ex}bob")
val knows = iri("${ex}knows")

// Annotate a triple through an RDF 1.2 reifier (use a jena or rdf4j repository)
repo.add {
    alice has knows with bob
    reifies(alice, knows, bob) { r ->
        r - iri("${ex}certainty") - 0.9
        r - iri("${ex}source") - iri("${ex}wikipedia")
    }
}

// Querying reified triples
val query = """
    PREFIX : <http://example.org/>
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

#### `REPLACE` Function
```kotlin
val query = """
    SELECT ?result WHERE {
        BIND(REPLACE("Hello World", "World", "Universe") AS ?result)
    }
"""
// Result: "Hello Universe"
```

In the Kotlin query DSL use `replace(expr, pattern, replacement)`; `replaceAll(...)` is a deprecated alias that renders the same `REPLACE` call.

#### URI Encoding
```kotlin
val query = """
    SELECT ?encoded WHERE {
        BIND(ENCODE_FOR_URI("Hello World!") AS ?encoded)
    }
"""
```

SPARQL has no `DECODE_FOR_URI`; the DSL's `decodeForUri(...)` is deprecated with level ERROR.

### 3. Language and Direction Functions

#### Language Direction Support
```kotlin
val query = """
    SELECT ?langdir ?hasLang ?hasLangdir WHERE {
        ?s rdfs:label "Hello"@en .
        BIND(LANGDIR(?s) AS ?langdir)
        BIND(hasLANG(?s) AS ?hasLang)
        BIND(hasLANGDIR(?s) AS ?hasLangdir)
    }
"""
```

`hasLANG` and `hasLANGDIR` take one argument. In the Kotlin DSL, `hasLang(x)` / `hasLangdir(x)` render these built-ins, while `hasLang(x, "en")` renders `LANGMATCHES(LANG(?x), "en")` and `hasLangdir(x, "ltr")` renders `LANGDIR(?x) = "ltr"`.

### 4. Date/Time Functions

#### Current Time Functions
```kotlin
val query = """
    SELECT ?now ?timezone WHERE {
        BIND(NOW() AS ?now)
        BIND(TIMEZONE(NOW()) AS ?timezone)
    }
"""
```

`TIMEZONE` requires a dateTime argument; the DSL's zero-argument `timezone()` is deprecated with level ERROR (use `timezone(expr)`).

#### Date/Time Construction
```kotlin
val query = """
    PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
    SELECT ?datetime WHERE {
        BIND(xsd:dateTime("2024-01-15T10:30:00Z") AS ?datetime)
    }
"""
```

The DSL's `dateTime(expr)`, `date(expr)` and `time(expr)` render these XSD casts.

### 5. Random Functions

#### Random Number Generation
```kotlin
val query = """
    SELECT ?rand WHERE {
        BIND(RAND() AS ?rand)
    }
"""
```

SPARQL has no `RANDOM()`; the DSL's `random()` is deprecated with level ERROR (use `rand()`).

### 6. Version Declaration

#### Explicit SPARQL Version
```kotlin
val query = """
    VERSION "1.2"
    SELECT ?s ?p ?o WHERE {
        ?s ?p ?o
    }
"""
```

The version specifier is a string literal. The DSL's `version("1.2")` renders `VERSION "1.2"` and rejects values that are not of the form `X.Y`.

## 🔧 Provider Capabilities

### Checking SPARQL 1.2 Support

```kotlin
val provider = RdfProviderRegistry.getProvider("jena") ?: error("rdf-jena is not on the classpath")
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

// Feature flags reported by the provider (empty for providers that do not override getDetailedCapabilities)
val supportedFeatures = detailedCapabilities.supportedSparqlFeatures
println("Supported Features: $supportedFeatures")

// SPARQL 1.2 function descriptions (module rdf-sparql-lang)
val functions = SparqlExtensionFunctionRegistry.getBuiltInFunctions()
println("Built-in functions: ${functions.map { it.name }}")
```

## 📊 SPARQL 1.2 Vocabulary

The W3C SPARQL Service Description vocabulary has no terms for most SPARQL 1.2 capability flags, and Kastor does not invent terms in the W3C-owned `http://www.w3.org/ns/sparql#` namespace. The terms that service descriptions use are in `KastorSparqlVocabulary` (module `rdf-sparql-lang`, namespace `https://kastor.geoknoesis.com/ns/sparql#`, prefix `ksparql`):

```kotlin
import com.geoknoesis.kastor.rdf.sparql.KastorSparqlVocabulary

val service = KastorSparqlVocabulary.Sparql12Service                // ksparql:Sparql12Service
val supportsRdfStar = KastorSparqlVocabulary.supportsRdfStar        // ksparql:supportsRdfStar
val version = KastorSparqlVocabulary.supportedSparqlVersion         // ksparql:supportedSparqlVersion
val tripleFunctionId = KastorSparqlVocabulary.function("TRIPLE")    // registry identifier of TRIPLE
```

The terms available are `Sparql12Service`, `supportedSparqlVersion`, `supportsRdfStar`, `supportsPropertyPaths`, `supportsAggregation`, `supportsSubSelect` and `supportsVersionDeclaration`. Federation uses the standard `sd:feature sd:BasicFederatedQuery`. The `SPARQL12` object in `rdf-core` still exists, but the service description generator and the function registry no longer use it.

## 🎨 DSL Integration

### RDF 1.2 Reifiers in DSL

```kotlin
repo.add {
    // _:r rdf:reifies <<( alice knows bob )>>, with metadata attached to _:r
    reifies(alice, knows, bob) { r ->
        r - iri("${ex}certainty") - 0.9
        r - iri("${ex}source") - iri("${ex}wikipedia")
    }
}
```

In the query DSL, `quotedTriple(s, p, o)` renders the reified-triple pattern `<< s p o >>`; see [SPARQL fundamentals](../concepts/sparql-fundamentals.md) and [RDF-star and triple terms](rdf-star.md).

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

SPARQL 1.2 services can describe their capabilities. The bundled providers do not generate descriptions themselves (`generateServiceDescription` returns `null`), so build one from a provider's capabilities:

```kotlin
import com.geoknoesis.kastor.rdf.sparql.SparqlServiceDescriptionGenerator

val serviceDescription = SparqlServiceDescriptionGenerator("http://example.org/sparql", provider.getCapabilities())
    .generateServiceDescription()
println(serviceDescription.getTriples().size)
```

The service description includes:
- SPARQL version support (`ksparql:supportedSparqlVersion`, plus type `ksparql:Sparql12Service` for 1.2)
- RDF-star and other feature flags, in the Kastor namespace `https://kastor.geoknoesis.com/ns/sparql#`
- Federation as the standard `sd:feature sd:BasicFederatedQuery`
- Custom extension functions (`isBuiltIn = false`) listed in `ProviderCapabilities.extensionFunctions`; built-in SPARQL functions are not advertised
- Supported languages and result/input formats
- Default and named graph information

See [Service Description](service-description.md) for the full structure.

## 📚 Built-in Functions

Commonly used SPARQL 1.2 functions, evaluated by the underlying engine (the subset described in `SparqlExtensionFunctionRegistry` is listed in [Extension Functions](extension-functions.md)):

### RDF-star Functions
- `TRIPLE(subject, predicate, object)`
- `isTRIPLE(term)`
- `SUBJECT(triple)`
- `PREDICATE(triple)`
- `OBJECT(triple)`

### String Functions
- `REPLACE(string, pattern, replacement)`
- `ENCODE_FOR_URI(string)`
- `STRSTARTS(string, prefix)` / `STRENDS(string, suffix)`

### Language/Direction Functions
- `LANGDIR(term)`
- `hasLANG(term)`
- `hasLANGDIR(term)`
- `STRLANGDIR(string, language, direction)`

### Date/Time Functions
- `NOW()`
- `TIMEZONE(datetime)`
- `TZ(datetime)`
- `xsd:dateTime(...)`, `xsd:date(...)`, `xsd:time(...)` casts

### Random Functions
- `RAND()`

## 🎯 Best Practices

### 1. Version Declaration
Always declare SPARQL version for clarity:
```kotlin
val query = """
    VERSION "1.2"
    SELECT * WHERE { ?s ?p ?o }
"""
```

### 2. RDF 1.2 Reifiers
Use reifiers for statement metadata:
```kotlin
repo.add {
    reifies(alice, knows, bob) { r ->
        r - iri("${ex}certainty") - 0.9
        r - iri("${ex}source") - iri("${ex}wikipedia")
    }
}
```

### 3. Extension Functions
Look up SPARQL 1.2 function descriptions in the registry:
```kotlin
if (SparqlExtensionFunctionRegistry.isRegistered(KastorSparqlVocabulary.function("TRIPLE").value)) {
    // TRIPLE is described; evaluation still depends on the engine (Jena, RDF4J)
}
```

### 4. Error Handling
Handle queries the engine rejects:
```kotlin
try {
    val result = repo.select(SparqlSelectQuery(sparql12Query))
} catch (e: RdfQueryException) {
    // e.g. syntax the provider does not support; fall back to a SPARQL 1.1 query
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
    val ex = "http://example.org/"
    val alice = iri("${ex}alice")
    val bob = iri("${ex}bob")
    val charlie = iri("${ex}charlie")
    val knows = iri("${ex}knows")
    val certainty = iri("${ex}certainty")
    val source = iri("${ex}source")

    val repo = Rdf.repository {
        providerId = "jena"
        variantId = "memory"
    }

    // Add RDF 1.2 reifier data
    repo.add {
        reifies(alice, knows, bob) { r ->
            r - certainty - 0.9
            r - source - iri("${ex}wikipedia")
        }
        reifies(bob, knows, charlie) { r ->
            r - certainty - 0.7
        }
    }

    // Query with SPARQL 1.2 features
    val query = """
        VERSION "1.2"
        PREFIX : <http://example.org/>
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
    VERSION "1.2"
    SELECT ?s ?p ?o WHERE { ?s ?p ?o }
"""
```

### 2. Use an RDF 1.2 Provider
No configuration flag is needed: Jena and RDF4J repositories support triple terms out of the box. Check before relying on them:
```kotlin
val repo = Rdf.repository {
    providerId = "jena"
    variantId = "memory"
}
println("Triple terms: ${repo.getCapabilities().supportsTripleTerms}")
```

### 3. Use New Functions
```kotlin
// Use new string functions
val query = """
    SELECT ?result WHERE {
        BIND(REPLACE("Hello World", "World", "Universe") AS ?result)
    }
"""
```

## 🔗 Related Documentation

- [SPARQL Service Description](service-description.md)
- [Provider Capabilities](provider-capabilities.md)
- [RDF-star Support](rdf-star.md)
- [Extension Functions](extension-functions.md)
- [Query DSL tutorial](../guides/kastor-query-dsl-tutorial.md)

## 📞 Support

For questions about SPARQL 1.2 support in Kastor:

- **Email**: stephanef@geoknoesis.com
- **Issues**: GitHub Issues
- **Documentation**: [Kastor Docs](https://docs.kastor.org)

---

*Kastor SPARQL 1.2 support is developed by [GeoKnoesis LLC](https://geoknoesis.com) and maintained by Stephane Fellah.*



