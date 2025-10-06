# SPARQL Provider

The SPARQL provider allows Kastor to work with remote SPARQL endpoints, enabling querying and federated access to distributed RDF data.

## Features

- **Remote Query Execution**: Query remote SPARQL endpoints
- **Federation Support**: Query multiple endpoints simultaneously
- **Authentication**: Support for various authentication methods
- **Connection Pooling**: Efficient connection management
- **Timeout Handling**: Configurable request timeouts

## Quick Start

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.sparql.*

// Create a SPARQL repository
val repo = Rdf.factory {
    sparql("https://dbpedia.org/sparql")
}

// Query remote data
val results = repo.query("""
    SELECT ?name ?birthDate WHERE {
        ?person rdfs:label ?name .
        ?person dbo:birthDate ?birthDate .
        FILTER(LANG(?name) = "en")
    } LIMIT 10
""")

results.forEach { binding ->
    val name = binding.getString("name")
    val birthDate = binding.getString("birthDate")
    println("$name was born on $birthDate")
}
```

## Configuration Options

### Basic Configuration

```kotlin
val repo = Rdf.factory {
    sparql {
        endpoint = "https://dbpedia.org/sparql"
        timeout = Duration.ofMinutes(5)
        userAgent = "MyApp/1.0"
    }
}
```

### Authentication

```kotlin
val repo = Rdf.factory {
    sparql {
        endpoint = "https://secure-endpoint.com/sparql"
        authentication = BasicAuth("username", "password")
    }
}
```

### Custom Headers

```kotlin
val repo = Rdf.factory {
    sparql {
        endpoint = "https://api.example.com/sparql"
        headers = mapOf(
            "X-API-Key" to "your-api-key",
            "Accept" to "application/sparql-results+json"
        )
    }
}
```

## Federation

Query multiple endpoints simultaneously:

```kotlin
val repo = Rdf.factory {
    federation {
        endpoint("https://dbpedia.org/sparql", "dbpedia")
        endpoint("https://wikidata.org/sparql", "wikidata")
        endpoint("https://data.gov/sparql", "data.gov")
    }
}

val results = repo.query("""
    SELECT ?person ?name ?birthDate WHERE {
        SERVICE <https://dbpedia.org/sparql> {
            ?person rdfs:label ?name .
            ?person dbo:birthDate ?birthDate .
        }
        SERVICE <https://wikidata.org/sparql> {
            ?person wdt:P31 wd:Q5 .  # human
        }
    } LIMIT 10
""")
```

## Error Handling

```kotlin
try {
    val results = repo.query("SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10")
    results.forEach { println(it) }
} catch (e: SparqlException) {
    when (e) {
        is TimeoutException -> println("Query timed out")
        is AuthenticationException -> println("Authentication failed")
        is NetworkException -> println("Network error: ${e.message}")
        else -> println("SPARQL error: ${e.message}")
    }
}
```

## Performance Tips

1. **Use LIMIT**: Always limit result sets for large queries
2. **Optimize Queries**: Use selective patterns and filters
3. **Connection Pooling**: Reuse connections when possible
4. **Timeout Configuration**: Set appropriate timeouts
5. **Batch Operations**: Group multiple queries when possible

## Best Practices

- Always handle network errors gracefully
- Use appropriate timeouts for your use case
- Cache frequently accessed data locally when possible
- Monitor endpoint performance and availability
- Use federation judiciously to avoid performance issues
