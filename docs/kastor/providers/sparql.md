# SPARQL Provider

The SPARQL provider (artifact `rdf-sparql`) lets Kastor work with remote SPARQL 1.1 Protocol endpoints over HTTP(S). It uses the JDK HTTP client only (no Apache HttpClient dependency).

## Features

- **Remote Query Execution**: SELECT and ASK against remote endpoints (results read as `application/sparql-results+json`); CONSTRUCT/DESCRIBE are not supported
- **Updates**: SPARQL Update against the query endpoint or a separate update URL
- **Authentication**: HTTP Basic (explicit `username`/`password` or credentials embedded in the URL) and custom headers such as API keys
- **Connection Reuse**: connections are not force-closed, so the JDK keep-alive pool reuses them
- **Timeout Handling**: connect, per-read and overall per-request timeouts
- **Response Caps**: size limits for buffered and streamed responses

Only `http` and `https` URLs are accepted.

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

`location` is the only required option. The other provider options map onto `SparqlEndpointConfig`:

| Option | `SparqlEndpointConfig` property | Default |
|--------|--------------------------------|---------|
| `location` | `endpoint` (http/https; URL credentials become Basic auth) | required |
| `updateLocation` | `updateEndpoint` | same as `location` |
| `header.<Name>` | `headers` (extra HTTP headers on every request) | none |
| `username` / `password` | HTTP Basic credentials (set both) | none |
| `queryMethod` | `POST`, `POST_FORM` or `GET` | `POST` (`application/sparql-query`) |
| `updateMethod` | `POST` or `POST_FORM` | `POST` (`application/sparql-update`) |
| `connectTimeoutMillis` | `connectTimeout` | 30 s |
| `readTimeoutMillis` | `readTimeout` (single socket read) | 60 s |
| `requestTimeoutMillis` | `requestTimeout` (overall deadline incl. reading the response; `none` disables) | 5 min |
| `maxResponseBytes` | `maxResponseBytes`: cap for buffered calls (`select`, ASK, update responses) | 32 MiB |
| `maxStreamedResponseBytes` | `maxStreamedResponseBytes`: cap for `withSelectRows` streaming (`none` = unbounded) | unbounded |
| `insertBatchSize` | `insertBatchSize`: triples per `INSERT DATA` / `DELETE DATA` request | 5000 |

```kotlin
val repo = RdfProviderRegistry.create(
    RdfConfig.of(
        providerId = ProviderId("sparql"),
        variantId = VariantId("sparql"),
        options = mapOf(
            "location" to "https://example.org/sparql",
            "updateLocation" to "https://example.org/sparql/update",
            "header.X-API-Key" to "…",
            "queryMethod" to "GET",
            "requestTimeoutMillis" to "120000",
        )
    )
)

// Or construct the repository directly
val direct = SparqlRepository(
    SparqlEndpointConfig(
        endpoint = "https://example.org/sparql",
        username = "reader",
        password = "…",
        requestTimeout = Duration.ofMinutes(2),
    )
)
```

`SparqlEndpointConfig.toString()` never prints passwords or URL-embedded credentials.

## Capabilities

The provider reports what this HTTP adapter itself supports, not what the remote server might: `sparqlVersion = "1.1"`, no RDF-star / triple terms (RDF 1.2 triple terms and directional literals are not decoded from results), no federation, no transactions. Property paths, aggregation, sub-selects, named graphs and updates are reported as supported.

## Federation

The adapter reports `supportsFederation = false`. Queries are sent to the endpoint as written, so `SERVICE` clauses work only if the remote server supports them.

## Writes and Blank Nodes

- `addTriples` sends triples without blank nodes in `INSERT DATA` batches of `insertBatchSize`. A call containing blank nodes is sent as one request (never split across batches) and its labels are re-issued, so each call creates fresh blank nodes on the endpoint.
- `removeTriples` runs one `ASK … VALUES` (to report whether anything existed) plus one `DELETE DATA` per batch. The operation is **not atomic**.
- `hasTriple`, `find`, `removeTriple` and `removeTriples` cannot address an existing blank node by label and fail with `IllegalArgumentException`; use an explicit `DELETE WHERE` pattern instead.

## Error Handling

Transport, HTTP and result-format failures surface as `RdfQueryException` (HTTP error bodies are included). Exceptions thrown by your own `withSelectRows` consumer propagate unchanged.

```kotlin
try {
    val results = repo.select(SparqlSelectQuery("SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10"))
    results.forEach { println(it) }
} catch (e: RdfQueryException) {
    // Transport failures (including timeouts), HTTP error statuses such as 401/403,
    // response-size limits and malformed results. HTTP error bodies are part of the message.
    println("SPARQL error [${e.errorCode.code}]: ${e.message}")
    e.query?.let { println("Query: $it") }
} catch (e: IllegalArgumentException) {
    // Invalid SparqlEndpointConfig values, or blank nodes passed to find/has/remove
    println("Invalid request: ${e.message}")
}
```

`RdfQueryException` (package `com.geoknoesis.kastor.rdf`) is one of the `RdfException` subclasses in `rdf-core`, alongside `RdfFormatException`, `RdfProviderException` and `RdfTransactionException`. Each carries an `errorCode` for programmatic handling. Transactions are not supported by this adapter: `transaction { }` and `readTransaction { }` throw `UnsupportedOperationException`.

## Performance Tips

1. **Use LIMIT**: Always limit result sets for large queries
2. **Optimize Queries**: Use selective patterns and filters
3. **Stream Large Results**: Use `withSelectRows` (optionally capped by `maxStreamedResponseBytes`) instead of buffering with `select`
4. **Timeout Configuration**: Set `connectTimeout`, `readTimeout` and `requestTimeout` for your workload
5. **Batch Operations**: Add or remove triples in collections; `insertBatchSize` controls the request size

## Best Practices

- Always handle network errors gracefully
- Use appropriate timeouts for your use case
- Cache frequently accessed data locally when possible
- Monitor endpoint performance and availability
- Use federation judiciously to avoid performance issues



