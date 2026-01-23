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

// Create a SPARQL repository
val repo = RdfProviderRegistry.create(
    RdfConfig.of(
        providerId = ProviderId("sparql"),
        variantId = VariantId("sparql"),
        options = mapOf("location" to "https://dbpedia.org/sparql")
    )
)

// Query remote data
val results = repo.select(SparqlSelectQuery("""
    SELECT ?name ?birthDate WHERE {
        ?person rdfs:label ?name .
        ?person dbo:birthDate ?birthDate .
        FILTER(LANG(?name) = "en")
    } LIMIT 10
"""))

results.forEach { binding ->
    val name = binding.getString("name")
    val birthDate = binding.getString("birthDate")
    println("$name was born on $birthDate")
}
```

## Configuration Options

The SPARQL provider currently accepts a single required option:

```kotlin
val repo = RdfProviderRegistry.create(
    RdfConfig.of(
        providerId = ProviderId("sparql"),
        variantId = VariantId("sparql"),
        options = mapOf("location" to "https://dbpedia.org/sparql")
    )
)
```

## Federation

Federation is performed via SPARQL `SERVICE` clauses against endpoints that support it.
Use a SPARQL-capable provider and write federated queries explicitly.

## Error Handling

```kotlin
try {
    val results = repo.select(SparqlSelectQuery("SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10"))
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



