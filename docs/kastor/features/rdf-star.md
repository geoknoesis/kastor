# RDF-star Support in Kastor

Kastor provides comprehensive support for RDF-star (also known as RDF*), enabling you to make statements about statements. This powerful feature allows for rich metadata modeling, provenance tracking, and complex knowledge representation scenarios.

## 🎯 Overview

RDF-star support in Kastor includes:

- **Quoted Triples**: Representing statements as subjects or objects
- **SPARQL 1.2 Functions**: Built-in functions for RDF-star manipulation
- **DSL Integration**: Natural language support for RDF-star in the DSL
- **Provider Capabilities**: Automatic detection and advertisement of RDF-star support
- **Service Description**: RDF-star capabilities in service descriptions
- **Query Support**: Full SPARQL 1.2 query support for RDF-star

## 🚀 Key Concepts

### Quoted Triples

RDF-star allows you to quote triples using the `<<` and `>>` syntax:

```turtle
:alice :knows :bob .
<< :alice :knows :bob >> :certainty 0.9 .
<< :alice :knows :bob >> :source :wikipedia .
```

This creates:
- A regular triple: `:alice :knows :bob`
- Metadata about that triple: certainty and source information

### Triple Terms

In RDF-star, quoted triples become first-class citizens that can be:
- Subjects of other statements
- Objects of other statements
- Values in SPARQL queries
- Manipulated with built-in functions

## 🎨 DSL Integration

### Creating Quoted Triples

```kotlin
val repo = Rdf.memory()

// Add RDF-star data using DSL
repo.add {
    // Regular triple
    :alice has :knows with :bob
    
    // Quoted triple with metadata
    quotedTripleOf {
        :alice has :knows with :bob
    } has :certainty with 0.9
    
    quotedTripleOf {
        :alice has :knows with :bob
    } has :source with :wikipedia
}
```

### Advanced RDF-star DSL

```kotlin
repo.add {
    // Multiple metadata properties on the same quoted triple
    val quotedTriple = quotedTripleOf {
        :alice has :knows with :bob
    }
    
    quotedTriple has :certainty with 0.9
    quotedTriple has :source with :wikipedia
    quotedTriple has :date with "2024-01-15"^^xsd:date
    quotedTriple has :author with :system
}
```

### Nested Quoted Triples

```kotlin
repo.add {
    // Quoted triple about another quoted triple
    val innerTriple = quotedTripleOf {
        :alice has :knows with :bob
    }
    
    val outerTriple = quotedTripleOf {
        innerTriple has :certainty with 0.9
    }
    
    outerTriple has :verified by :system
}
```

## 🔍 SPARQL 1.2 Functions

### TRIPLE Function

Creates a quoted triple from subject, predicate, and object:

```kotlin
val query = """
    SELECT ?quotedTriple WHERE {
        BIND(TRIPLE(:alice, :knows, :bob) AS ?quotedTriple)
    }
"""
```

### isTRIPLE Function

Checks if a term is a quoted triple:

```kotlin
val query = """
    SELECT ?term ?isTriple WHERE {
        ?term :hasValue ?value .
        BIND(isTRIPLE(?term) AS ?isTriple)
    }
"""
```

### Component Functions

Extract components from quoted triples:

```kotlin
val query = """
    SELECT ?subject ?predicate ?object WHERE {
        << :alice :knows :bob >> :certainty ?certainty .
        BIND(SUBJECT(<< :alice :knows :bob >>) AS ?subject)
        BIND(PREDICATE(<< :alice :knows :bob >>) AS ?predicate)
        BIND(OBJECT(<< :alice :knows :bob >>) AS ?object)
    }
"""
```

### Dynamic Component Extraction

```kotlin
val query = """
    SELECT ?subject ?predicate ?object ?certainty WHERE {
        ?quotedTriple :certainty ?certainty .
        BIND(SUBJECT(?quotedTriple) AS ?subject)
        BIND(PREDICATE(?quotedTriple) AS ?predicate)
        BIND(OBJECT(?quotedTriple) AS ?object)
    }
"""
```

## 📊 Provider Capabilities

### Checking RDF-star Support

```kotlin
val provider = RdfProviderRegistry.getProvider("memory")
val capabilities = provider.getCapabilities()

if (capabilities.supportsRdfStar) {
    println("Provider supports RDF-star")
    // Use RDF-star features
} else {
    println("Provider does not support RDF-star")
    // Use regular RDF features
}
```

### Capability Discovery

```kotlin
// Check if any provider supports RDF-star
val hasRdfStarSupport = RdfProviderRegistry.hasProviderWithFeature("RDF-star")
println("RDF-star support available: $hasRdfStarSupport")

// Find providers that support RDF-star
val rdfStarProviders = RdfProviderRegistry.getAllProviders().filter { 
    it.getCapabilities(it.defaultVariantId()).supportsRdfStar 
}
println("Providers supporting RDF-star: ${rdfStarProviders.size}")
```

### Service Description Integration

```kotlin
val provider = RdfProviderRegistry.getProvider("memory")
val serviceDescription = provider.generateServiceDescription(
    "http://example.org/sparql",
    provider.defaultVariantId()
)

if (serviceDescription != null) {
    val triples = serviceDescription.getTriples()
    val hasRdfStarSupport = triples.any { triple ->
        triple.predicate == SPARQL12.supportsRdfStar && 
        triple.obj == boolean(true)
    }
    println("Service description advertises RDF-star support: $hasRdfStarSupport")
}
```

## 🎯 Query Patterns

### Basic RDF-star Queries

```kotlin
// Find all statements with certainty information
val query = """
    SELECT ?subject ?predicate ?object ?certainty WHERE {
        << ?subject ?predicate ?object >> :certainty ?certainty
    }
"""
```

### Filtering by Metadata

```kotlin
// Find high-confidence statements
val query = """
    SELECT ?subject ?predicate ?object ?certainty WHERE {
        << ?subject ?predicate ?object >> :certainty ?certainty .
        FILTER(?certainty > 0.8)
    }
"""
```

### Aggregating Metadata

```kotlin
// Average certainty by predicate
val query = """
    SELECT ?predicate (AVG(?certainty) AS ?avgCertainty) WHERE {
        << ?subject ?predicate ?object >> :certainty ?certainty
    }
    GROUP BY ?predicate
"""
```

### Complex Metadata Queries

```kotlin
// Find statements with multiple metadata properties
val query = """
    SELECT ?subject ?predicate ?object ?certainty ?source ?date WHERE {
        << ?subject ?predicate ?object >> :certainty ?certainty .
        << ?subject ?predicate ?object >> :source ?source .
        << ?subject ?predicate ?object >> :date ?date .
    }
"""
```

### Conditional Queries

```kotlin
// Find statements that are either certain or from Wikipedia
val query = """
    SELECT ?subject ?predicate ?object ?reason WHERE {
        << ?subject ?predicate ?object >> ?metadata ?value .
        FILTER(
            (?metadata = :certainty && ?value > 0.9) ||
            (?metadata = :source && ?value = :wikipedia)
        )
        BIND(
            IF(?metadata = :certainty, "high certainty", "wikipedia source") 
            AS ?reason
        )
    }
"""
```

## 🔧 Advanced Use Cases

### Provenance Tracking

```kotlin
repo.add {
    // Original statement
    :alice has :knows with :bob
    
    // Provenance information
    quotedTripleOf {
        :alice has :knows with :bob
    } has :source with :wikipedia
    
    quotedTripleOf {
        :alice has :knows with :bob
    } has :extractedOn with "2024-01-15"^^xsd:date
    
    quotedTripleOf {
        :alice has :knows with :bob
    } has :extractedBy with :system
}
```

### Confidence and Uncertainty

```kotlin
repo.add {
    // Multiple statements with different confidence levels
    quotedTripleOf {
        :alice has :knows with :bob
    } has :certainty with 0.9
    
    quotedTripleOf {
        :alice has :knows with :charlie
    } has :certainty with 0.7
    
    quotedTripleOf {
        :bob has :knows with :alice
    } has :certainty with 0.8
}
```

### Temporal Metadata

```kotlin
repo.add {
    // Statements with temporal information
    quotedTripleOf {
        :alice has :knows with :bob
    } has :validFrom with "2024-01-01"^^xsd:date
    
    quotedTripleOf {
        :alice has :knows with :bob
    } has :validUntil with "2024-12-31"^^xsd:date
    
    quotedTripleOf {
        :alice has :knows with :bob
    } has :lastUpdated with "2024-01-15T10:30:00Z"^^xsd:dateTime
}
```

### Contextual Information

```kotlin
repo.add {
    // Statements with context
    quotedTripleOf {
        :alice has :knows with :bob
    } has :context with :professional
    
    quotedTripleOf {
        :alice has :knows with :bob
    } has :location with :office
    
    quotedTripleOf {
        :alice has :knows with :bob
    } has :witnessedBy with :colleague
}
```

## 🎨 Best Practices

### 1. Provider Selection

```kotlin
// Always check RDF-star support before using
val provider = RdfProviderRegistry.getProvider("memory")
if (provider.getCapabilities().supportsRdfStar) {
    // Use RDF-star features
    val query = """
        SELECT ?s ?p ?o ?certainty WHERE {
            << ?s ?p ?o >> :certainty ?certainty
        }
    """
} else {
    // Fallback to regular RDF
    val query = """
        SELECT ?s ?p ?o WHERE {
            ?s ?p ?o
        }
    """
}
```

### 2. Graceful Degradation

```kotlin
// Handle providers without RDF-star support
fun executeRdfStarQuery(query: String, provider: RdfApiProvider): QueryResult {
    return try {
        val variant = provider.defaultVariantId()
        val repo = provider.createRepository(variant, RdfConfig(providerId = provider.id, variantId = variant))
        repo.select(SparqlSelectQuery(query))
    } catch (e: UnsupportedOperationException) {
        if (!provider.getCapabilities(provider.defaultVariantId()).supportsRdfStar) {
            // Convert RDF-star query to regular SPARQL
            val fallbackQuery = query.replace("<<", "").replace(">>", "")
            val variant = provider.defaultVariantId()
            val repo = provider.createRepository(variant, RdfConfig(providerId = provider.id, variantId = variant))
            repo.select(SparqlSelectQuery(fallbackQuery))
        } else {
            throw e
        }
    }
}
```

### 3. Metadata Design

```kotlin
// Use consistent metadata properties
repo.add {
    val quotedTriple = quotedTripleOf {
        :alice has :knows with :bob
    }
    
    // Standard metadata properties
    quotedTriple has :certainty with 0.9
    quotedTriple has :source with :wikipedia
    quotedTriple has :date with "2024-01-15"^^xsd:date
    quotedTriple has :author with :system
}
```

### 4. Query Optimization

```kotlin
// Use efficient query patterns
val query = """
    SELECT ?subject ?predicate ?object ?certainty WHERE {
        << ?subject ?predicate ?object >> :certainty ?certainty .
        FILTER(?certainty > 0.8)
    }
    ORDER BY DESC(?certainty)
    LIMIT 100
"""
```

## 📖 Complete Example

```kotlin
fun rdfStarExample() {
    val repo = Rdf.memory()
    
    // Check RDF-star support
    val provider = RdfProviderRegistry.getProvider("memory")
    if (!provider.getCapabilities().supportsRdfStar) {
        println("Provider does not support RDF-star")
        return
    }
    
    // Add RDF-star data
    repo.add {
        // Regular triples
        :alice has :knows with :bob
        :alice has :knows with :charlie
        :bob has :knows with :alice
        
        // Metadata about statements
        quotedTripleOf {
            :alice has :knows with :bob
        } has :certainty with 0.9
        
        quotedTripleOf {
            :alice has :knows with :bob
        } has :source with :wikipedia
        
        quotedTripleOf {
            :alice has :knows with :bob
        } has :date with "2024-01-15"^^xsd:date
        
        quotedTripleOf {
            :alice has :knows with :charlie
        } has :certainty with 0.7
        
        quotedTripleOf {
            :alice has :knows with :charlie
        } has :source with :linkedin
        
        quotedTripleOf {
            :bob has :knows with :alice
        } has :certainty with 0.8
        
        quotedTripleOf {
            :bob has :knows with :alice
        } has :source with :wikipedia
    }
    
    // Query with RDF-star
    val query = """
        SELECT ?subject ?object ?certainty ?source WHERE {
            << ?subject :knows ?object >> :certainty ?certainty .
            << ?subject :knows ?object >> :source ?source .
            FILTER(?certainty > 0.7)
        }
        ORDER BY DESC(?certainty)
    """
    
    val results = repo.select(SparqlSelectQuery(query))
    results.forEach { binding ->
        val subject = binding.getIri("subject")
        val obj = binding.getIri("object")
        val certainty = binding.getDouble("certainty")
        val source = binding.getIri("source")
        
        println("$subject knows $obj (certainty: $certainty, source: $source)")
    }
    
    // Use SPARQL 1.2 functions
    val functionQuery = """
        SELECT ?subject ?predicate ?object ?certainty WHERE {
            << ?subject ?predicate ?object >> :certainty ?certainty .
            BIND(SUBJECT(<< ?subject ?predicate ?object >>) AS ?subject)
            BIND(PREDICATE(<< ?subject ?predicate ?object >>) AS ?predicate)
            BIND(OBJECT(<< ?subject ?predicate ?object >>) AS ?object)
        }
    """
    
    val functionResults = repo.select(SparqlSelectQuery(functionQuery))
    functionResults.forEach { binding ->
        val subject = binding.getIri("subject")
        val predicate = binding.getIri("predicate")
        val obj = binding.getIri("object")
        val certainty = binding.getDouble("certainty")
        
        println("Extracted: $subject $predicate $obj (certainty: $certainty)")
    }
    
    // Aggregate metadata
    val aggregateQuery = """
        SELECT ?source (AVG(?certainty) AS ?avgCertainty) (COUNT(?statement) AS ?statementCount) WHERE {
            ?statement :certainty ?certainty .
            ?statement :source ?source .
        }
        GROUP BY ?source
    """
    
    val aggregateResults = repo.select(SparqlSelectQuery(aggregateQuery))
    aggregateResults.forEach { binding ->
        val source = binding.getIri("source")
        val avgCertainty = binding.getDouble("avgCertainty")
        val statementCount = binding.getInt("statementCount")
        
        println("Source $source: avg certainty $avgCertainty, $statementCount statements")
    }
}
```

## 🔗 Related Documentation

- [SPARQL 1.2 Support](sparql-1.2.md)
- [Provider Capabilities](provider-capabilities.md)
- [Service Description](service-description.md)
- [Enhanced Providers](enhanced-providers.md)
- [Query DSL](query-dsl.md)

## 📞 Support

For questions about RDF-star support in Kastor:

- **Email**: stephanef@geoknoesis.com
- **Issues**: GitHub Issues
- **Documentation**: [Kastor Docs](https://docs.kastor.org)

---

*Kastor RDF-star support is developed by [GeoKnoesis LLC](https://geoknoesis.com) and maintained by Stephane Fellah.*



