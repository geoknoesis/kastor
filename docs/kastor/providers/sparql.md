# SPARQL Provider

The SPARQL provider (artifact `rdf-sparql`) lets Kastor work with remote SPARQL 1.1 Protocol endpoints over HTTP(S). It uses the JDK `java.net.http.HttpClient` only (no Apache HttpClient dependency).

## Features

- **Remote Query Execution**: SELECT and ASK against remote endpoints (results read as `application/sparql-results+json`); CONSTRUCT/DESCRIBE are not supported
- **Updates**: SPARQL Update against the query endpoint or a separate update URL
- **Authentication**: HTTP Basic (explicit `username`/`password` or credentials embedded in the URL) and custom headers such as API keys
- **Connection Reuse**: HTTP/1.1 connections are pooled and reused; requests are never retried automatically, so a failed UPDATE is not re-sent
- **Timeout Handling**: connect, per-read and per-request timeouts, plus an optional deadline for streamed results
- **Safe Redirects**: redirects are followed explicitly, never downgrade a POST and never leak headers or credentials to another origin
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
| `readTimeoutMillis` | `readTimeout` (longest wait for a single read of the response body) | 60 s |
| `requestTimeoutMillis` | `requestTimeout`: whole exchange for buffered calls; only the wait for response headers for streamed rows (`none` disables) | 5 min |
| `streamingRequestTimeoutMillis` | `streamingRequestTimeout`: overall deadline for a streamed `withSelectRows` call, including time spent consuming rows (`none` = no deadline) | none |
| `maxResponseBytes` | `maxResponseBytes`: cap for buffered calls (`select`, ASK, update responses) | 32 MiB |
| `maxStreamedResponseBytes` | `maxStreamedResponseBytes`: cap for `withSelectRows` streaming (`none` = unbounded) | unbounded |
| `insertBatchSize` | `insertBatchSize`: triples per `INSERT DATA` / `DELETE DATA` request | 5000 |
| `maxBlankNodeComponentTriples` | `maxBlankNodeComponentTriples`: largest group of triples connected through blank nodes that `addTriples` accepts | 100000 |
| `maxGetUrlLength` | `maxGetUrlLength`: longest request URL sent with `queryMethod = GET`; longer queries are sent as a form-encoded POST | 2000 |
| `followCrossOriginRedirects` | `followCrossOriginRedirects` (`true`/`false`) | `false` |
| `maxRedirects` | `maxRedirects`: redirects followed per request | 5 |

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
            "streamingRequestTimeoutMillis" to "900000",
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
        streamingRequestTimeout = Duration.ofMinutes(15),
    )
)
```

Configuration is validated when `SparqlEndpointConfig` is created (invalid values throw `IllegalArgumentException`):

- Headers the HTTP client manages itself (`Host`, `Content-Length`, `Connection`, `Expect`, `Upgrade`, in any letter case) cannot be set through `headers` / `header.<Name>`. Header values must not contain line breaks.
- Timeouts, byte caps, `maxGetUrlLength`, `insertBatchSize` and `maxBlankNodeComponentTriples` must be positive; `maxRedirects` must not be negative (`0` disables redirects).
- `SparqlEndpointConfig.toString()` never prints passwords or URL-embedded credentials.

When HTTP Basic credentials would be sent over plain `http` (for the query or the update endpoint), the repository logs a warning once per endpoint through `System.Logger`. Use `https` for authenticated endpoints.

## Timeouts

| Setting | Buffered calls (`select`, ASK, update) | Streamed rows (`withSelectRows`) |
|---------|-----------------------------------------|----------------------------------|
| `connectTimeout` | TCP connect | TCP connect |
| `readTimeout` | each read of the response body; also the wait for the response headers when `requestTimeout` is null | each read of the response body; also the wait for the response headers when `requestTimeout` is null |
| `requestTimeout` | whole exchange, including reading the response | only until the response headers arrive (all redirect hops included) |
| `streamingRequestTimeout` | not used | whole call, including the time your consumer spends on rows |
| per-call `timeout` of `withSelectRows(query, bindings, timeout)` | not applicable | whole call: headers, every read and the time your consumer spends on rows (the shorter of this and `streamingRequestTimeout` applies) |

Because `requestTimeout` stops at the response headers for untimed streams, a slow row consumer is never cut off by it. Set `streamingRequestTimeout` if a stream must finish within a fixed time, or use the timed `withSelectRows` overload. Time between reads counts towards the whole-call deadline and is checked on the next read and before each buffered row is handed to the consumer. With `requestTimeout = null`, a server that accepts the connection but never answers is still bounded by `readTimeout`.

## Redirects

The client follows redirects itself (up to `maxRedirects`):

- `307` and `308` are followed for every request and keep the method and body.
- `301`, `302` and `303` are followed only for GET queries. When a POST gets one of these statuses, the call fails with `RdfQueryException` instead of silently turning into a GET without the query. Configure the redirect target as the endpoint URL instead.
- A redirect to another origin (different scheme, host or port) fails by default. With `followCrossOriginRedirects = true` it is followed, but custom `headers` and credentials are not sent to the other origin.
- A redirect without a `Location` header, or to a non-http(s) URL or a URL with embedded credentials, fails.

## Streaming and Initial Bindings

`withSelectRows(query) { rows -> … }` streams rows to the consumer. Consume them inside the block and do not keep the sequence.

`withSelectRows(query, bindings, timeout) { rows -> … }` runs the query with initial bindings, and `timeout` bounds the whole call, including your consumer. A shorter `streamingRequestTimeout` still applies. The SPARQL 1.1 Protocol has no initial-bindings parameter, so the adapter substitutes the bound constants into the query text. It follows the same rules as the Jena provider, which uses Jena's query substitution:

- Every use of a bound variable in the WHERE clause is replaced by the constant, including FILTER, BIND expressions, OPTIONAL, MINUS, EXISTS / NOT EXISTS and sub-selects. The same applies to HAVING. The binding therefore restricts the query *before* aggregation, LIMIT and filtering.
- A bound variable in the projection stays in the results as `(constant AS ?var)`.
- `GROUP BY ?var` becomes `GROUP BY (constant AS ?var)`, and the projection keeps `?var`.
- `ORDER BY ?var` becomes `ORDER BY (constant)`.
- `BOUND(?var)` becomes `(true)`, because `BOUND(constant)` is not legal SPARQL.
- `SELECT *` does not return bound variables.
- Because the variable is replaced, a `MINUS` whose only shared variable is bound no longer shares a variable and removes nothing (the same as with Jena).
- Comments, string literals and IRIs are never rewritten.

For example, with `bindings = mapOf("s" to Iri("urn:a"))`:

```sparql
# Sent by the caller
SELECT ?s (COUNT(*) AS ?c) WHERE { ?s <urn:p> ?o } GROUP BY ?s

# Sent to the endpoint
SELECT ?s (COUNT(*) AS ?c) WHERE { <urn:a> <urn:p> ?o } GROUP BY (<urn:a> AS ?s)
```

```sparql
# Sent by the caller
SELECT ?s ?o WHERE { ?s <urn:p> ?o } ORDER BY ?s

# Sent to the endpoint
SELECT (<urn:a> AS ?s) ?o WHERE { <urn:a> <urn:p> ?o } ORDER BY (<urn:a>)
```

The following are rejected with `IllegalArgumentException` before any request is sent, because substitution could not keep the query's meaning:

- The query is not a SELECT query.
- The query assigns the bound variable with `BIND(… AS ?var)`, `(expr AS ?var)` or `VALUES` (inline or trailing).
- The bound variable is used inside a sub-select that does not project it. There it is a different, local variable, so an outer binding must not apply to it.
- A binding value is a blank node.

Bindings are not appended as a trailing `VALUES` block.

## Capabilities

The provider reports what this HTTP adapter itself supports, not what the remote server might: `sparqlVersion = "1.1"`, no RDF-star / triple terms (RDF 1.2 triple terms and directional literals are not decoded from results), no federation, no transactions. Property paths, aggregation, sub-selects, named graphs and updates are reported as supported.

## Federation

The adapter reports `supportsFederation = false`. Queries are sent to the endpoint as written, so `SERVICE` clauses work only if the remote server supports them.

## Writes and Blank Nodes

- `addTriples` groups triples that are connected through blank nodes and always sends each group in a single `INSERT DATA` request, because blank-node labels only apply within one request. Groups and triples without blank nodes are packed into requests of up to `insertBatchSize` triples; a group larger than `insertBatchSize` is sent on its own. Labels are re-issued per request, so every call creates fresh blank nodes on the endpoint.
- A group larger than `maxBlankNodeComponentTriples` is rejected with `IllegalArgumentException` before anything is sent.
- `removeTriples` runs one `ASK … VALUES` (to report whether anything existed) plus one `DELETE DATA` per batch. The operation is **not atomic**.
- `hasTriple`, `find`, `removeTriple` and `removeTriples` cannot address an existing blank node by label and fail with `IllegalArgumentException`; use an explicit `DELETE WHERE` pattern instead.

String literals are escaped with the same rules as the query DSL renderer. Among other things, a `u` or `U` directly after a backslash in the text is sent as `u` / `U`, so servers that decode `\uXXXX` escapes before parsing cannot change the value (see [SPARQL fundamentals](../concepts/sparql-fundamentals.md#literal-escaping)).

## Error Handling

Transport, HTTP, redirect and result-format failures surface as `RdfQueryException` (HTTP error bodies are included). Exceptions thrown by your own `withSelectRows` consumer propagate unchanged. After `close()`, every operation throws `IllegalStateException("Repository is closed")`.

```kotlin
try {
    val results = repo.select(SparqlSelectQuery("SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10"))
    results.forEach { println(it) }
} catch (e: RdfQueryException) {
    // Transport failures (including timeouts), HTTP error statuses such as 401/403,
    // refused redirects, response-size limits and malformed results. HTTP error bodies are part of the message.
    println("SPARQL error [${e.errorCode.code}]: ${e.message}")
    e.query?.let { println("Query: $it") }
} catch (e: IllegalArgumentException) {
    // Invalid SparqlEndpointConfig values, blank nodes passed to find/has/remove,
    // or initial bindings that cannot be substituted
    println("Invalid request: ${e.message}")
}
```

`RdfQueryException` (package `com.geoknoesis.kastor.rdf`) is one of the `RdfException` subclasses in `rdf-core`, alongside `RdfFormatException`, `RdfProviderException` and `RdfTransactionException`. Each carries an `errorCode` for programmatic handling. Transactions are not supported by this adapter: `transaction { }` and `readTransaction { }` throw `UnsupportedOperationException`.

## Performance Tips

1. **Use LIMIT**: Always limit result sets for large queries
2. **Optimize Queries**: Use selective patterns and filters
3. **Stream Large Results**: Use `withSelectRows` (optionally capped by `maxStreamedResponseBytes` and `streamingRequestTimeout`) instead of buffering with `select`
4. **Timeout Configuration**: Set `connectTimeout`, `readTimeout`, `requestTimeout` and, for streams, `streamingRequestTimeout` for your workload
5. **Batch Operations**: Add or remove triples in collections; `insertBatchSize` controls the request size
6. **Long GET queries**: With `queryMethod = GET`, queries whose URL exceeds `maxGetUrlLength` are sent as a form POST automatically

## Best Practices

- Always handle network errors gracefully
- Use appropriate timeouts for your use case
- Use `https` when sending credentials
- Cache frequently accessed data locally when possible
- Monitor endpoint performance and availability
- Use federation judiciously to avoid performance issues
