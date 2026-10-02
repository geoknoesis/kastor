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
| `maxResultRowChars` | `maxResultRowChars`: longest single result row (or skipped JSON value such as `head`), in characters of JSON text; see [Result decoding](#result-decoding) | 4194304 (4 Mi; was 16 Mi) |
| `strictContentType` | `strictContentType` (`true`/`false`): a successful SELECT/ASK response must declare a JSON media type; see [Result decoding](#result-decoding) | `true` |

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

- Headers the HTTP client manages itself (`Host`, `Content-Length`, `Connection`, `Expect`, `Upgrade`, in any letter case) cannot be set through `headers` / `header.<Name>`.
- Header names must be RFC 9110 tokens. Header values may contain visible ASCII, ISO-8859-1 characters, and spaces or tabs between them: no line breaks or other control characters, no characters beyond ISO-8859-1, and no leading or trailing white space. Because this is checked up front, a request is never refused later, when it is built.
- Timeouts, byte caps, `maxResultRowChars`, `maxGetUrlLength`, `insertBatchSize` and `maxBlankNodeComponentTriples` must be positive; `maxRedirects` must not be negative (`0` disables redirects).
- A timeout too long to be counted in nanoseconds (about 292 years: `Duration.ofMillis(Long.MAX_VALUE)`, `ChronoUnit.FOREVER.duration`, or `Long.MAX_VALUE` as a `...Millis` option) means "no limit". This is the way to switch off `connectTimeout` and `readTimeout`, which cannot be `null`.
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

Which option bounds what:

- **Connecting**: `connectTimeout`.
- **Waiting for the response headers**: the earliest of `requestTimeout` and, for streams, `streamingRequestTimeout` or the per-call `timeout`; `readTimeout` when none of them is set. It is one budget for the whole call, measured from its start, however many redirects are followed.
- **Each read of the response body**: `readTimeout`, cut short by a whole-call deadline that ends earlier.
- **The whole call**: `requestTimeout` for buffered calls; `streamingRequestTimeout` and the per-call `timeout` for streams. An untimed stream has no whole-call deadline.

The adapter enforces all of these itself and sets no timeout on the HTTP request, so the behaviour is the same on every JDK. A deadline that is "unbounded" (see above) is simply not applied; the other limits still are. A call that hits a limit fails with `RdfQueryException` naming the limit, and the response is closed.

Because `requestTimeout` stops at the response headers for untimed streams, a slow row consumer is never cut off by it. Set `streamingRequestTimeout` if a stream must finish within a fixed time, or use the timed `withSelectRows` overload. Time between reads counts towards the whole-call deadline and is checked on the next read and before each buffered row is handed to the consumer. With `requestTimeout = null`, a server that accepts the connection but never answers is still bounded by `readTimeout`.

## Redirects

The client follows redirects itself (up to `maxRedirects`):

- `307` and `308` are followed for every request and keep the method and body.
- `301`, `302` and `303` are followed only for GET queries. When a POST gets one of these statuses, the call fails with `RdfQueryException` instead of silently turning into a GET without the query. Configure the redirect target as the endpoint URL instead.
- A redirect to another origin (different scheme, host or port) fails by default. With `followCrossOriginRedirects = true` it is followed, but custom `headers` and credentials are not sent to the other origin (a warning is logged when they are dropped).
- A redirect from `https` to plain `http` is always refused, also with `followCrossOriginRedirects = true`: the query, and for `307`/`308` the request body, would be re-sent unencrypted. Configure the `http` URL as the endpoint if cleartext is intended.
- All hops share the one budget for the wait for response headers (see [Timeouts](#timeouts)); a redirect does not restart it.
- A redirect without a `Location` header, or to a non-http(s) URL or a URL with embedded credentials, fails.

## Result decoding

SELECT and ASK results are read as SPARQL 1.1 Query Results JSON (`application/sparql-results+json`), by a streaming decoder that holds one row at a time.

- **Content-Type**: with `strictContentType = true` (default) a successful response must declare `application/sparql-results+json`, `application/json` or another `+json` type (`text/plain` is also accepted for ASK, for endpoints that answer a bare `true`/`false`); a response without a Content-Type is parsed. Anything else, such as the HTML of a login page, fails with `RdfQueryException` naming the type. Set `strictContentType = false` for a legacy server that labels its JSON `text/json` or similar; the body must still be SPARQL JSON.
- **Encoding**: UTF-8. One leading byte order mark is ignored; malformed UTF-8 is an error and is never replaced with U+FFFD.
- **Strict JSON**: only space, tab, line feed and carriage return are white space; numbers, `true`, `false`, `null`, strings and escapes must be valid JSON, also in the parts the adapter does not use. A `results` or `bindings` member repeated after the one that was read is an error.
- **Row size**: no single row, and no single skipped value such as `head`, may be longer than `maxResultRowChars` characters of JSON text (UTF-16 code units; quotes, escapes and inner white space count). This also holds for streamed responses with `maxStreamedResponseBytes` unbounded.
- **Nesting**: JSON values may be nested at most 128 levels deep.
- **Memory**: only what a binding needs is kept: the variable names of a row and each term's `type`, `value`, `xml:lang` and `datatype`. `head`, unknown members and nested values are checked and skipped without being stored. A row therefore needs at most two bytes of heap per character for its strings (8 MB at the default limit) plus roughly 200 bytes per variable it binds, and a scratch buffer of up to twice the size of its largest string while that string is read.
- **ASK** uses the same decoder: `{"boolean": true}` (other members are skipped) or plain `true`/`false`.
- Error messages never quote the response body beyond a short, printable excerpt of a term's type.

### RDF 1.2 results are not supported

The adapter decodes SPARQL 1.1 terms only: `uri`, `bnode`, `literal` (and the legacy `typed-literal`). Results of a SPARQL 1.2 server that contain

- a triple term (`"type": "triple"`), or
- a literal with a base direction (`"its:dir"`, or `"direction"` in older drafts)

fail with `RdfQueryException`, and the message says that RDF 1.2 result terms are not supported. They are never silently dropped or turned into something else. Queries can still *use* SPARQL 1.2 syntax if the server accepts it, as long as the result rows hold RDF 1.1 terms; project the parts of a triple term (`SUBJECT(?t)`, `PREDICATE(?t)`, `OBJECT(?t)`) or use a Jena- or RDF4J-backed repository to read such results.

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
- A literal (or a triple term) is bound to a variable that stands where only an IRI is legal: the predicate of a triple pattern, the name of a `GRAPH` or `SERVICE`, and in SPARQL 1.2 syntax the predicate inside a reified triple `<< s p o >>` or a triple term `<<( s p o )>>`, a predicate of an annotation block `{| p o |}`, a reifier (`~ r`), or the subject of a triple term in an expression. The substituted text would not parse, so it is rejected up front (the Jena and RDF4J providers reject it the same way).

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

Transport, HTTP, redirect and result-format failures surface as `RdfQueryException` (HTTP error bodies are included), from `select`, `withSelectRows`, `ask` and `update` alike; this includes a request that cannot be built or sent at all, an interrupted call (the thread's interrupt flag stays set) and a cancelled exchange. Exceptions thrown by your own `withSelectRows` consumer propagate unchanged. After `close()`, every operation throws `IllegalStateException("Repository is closed")`.

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
