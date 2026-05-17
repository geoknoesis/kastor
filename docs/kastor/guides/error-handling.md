# Error Handling Guide

{% include version-banner.md %}

> **Documentation mode: Reference-oriented guide.** **Explanation:** resilience patterns → [Best Practices](best-practices.md#error-handling). **Reference:** full code list → [Error Codes](../reference/error-codes.md), exception types → [Core API](../api/core-api.md#exceptions).

## Problem

- Inspect failures from **SPARQL**, **parsing/serialization**, **transactions**, and **providers** using stable **`errorCode`** values, **`RdfFormatException`** variants, and **`context`** maps for logs.

## Prerequisites

- **`rdf-core`** (exceptions and **`RdfErrorCode`** ship with the RDF API).

## Steps

### Step 1: Branch on query failures with `errorCode`

```kotlin
import com.geoknoesis.kastor.rdf.RdfQueryException
import com.geoknoesis.kastor.rdf.RdfErrorCode

try {
    val result = repo.select(query)
} catch (e: RdfQueryException) {
    when (e.errorCode) {
        RdfErrorCode.QUERY_SYNTAX_ERROR -> {
            println("Invalid query syntax: ${e.message}")
        }
        RdfErrorCode.QUERY_TIMEOUT -> {
            println("Query timed out. Please try again.")
        }
        RdfErrorCode.QUERY_EXECUTION_ERROR -> {
            println("Query execution failed: ${e.message}")
            e.query?.let { println("Query: $it") }
        }
        else -> {
            println("Query error: ${e.message}")
        }
    }
}
```

Typical **`RdfQueryException`** codes:

| Code | Meaning |
|------|---------|
| `QUERY_SYNTAX_ERROR` | SPARQL parse failure |
| `QUERY_EXECUTION_ERROR` | Runtime execution failure |
| `QUERY_TIMEOUT` | Server or client timeout |
| `QUERY_UNSUPPORTED_FEATURE` | Feature not implemented on provider |
| `QUERY_INVALID_BINDINGS` | Bindings inconsistent with query |

### Step 2: Handle format errors as a sealed class

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfFormatException

try {
    val graph = Rdf.parse(data, RdfFormat.TURTLE)
} catch (e: RdfFormatException) {
    when (e) {
        is RdfFormatException.ParseError -> {
            println("Parse error: ${e.errorCode.code}")
            println("Line: ${e.parseError.line}, Column: ${e.parseError.column}")
            println("Snippet: ${e.parseError.snippet}")
        }
        is RdfFormatException.UnsupportedFormat -> {
            println("Unsupported format: ${e.format}")
            println("Available formats: ${e.availableFormats}")
        }
        is RdfFormatException.Generic -> {
            println("Format error: ${e.message}")
        }
    }
}
```

Representative **`RdfFormatException`** codes:

| Code | Meaning |
|------|---------|
| `FORMAT_PARSE_ERROR` | RDF parse failure |
| `FORMAT_UNSUPPORTED` | Serializer/parser not available |
| `FORMAT_SERIALIZATION_ERROR` | Write path failed |
| `FORMAT_ENCODING_ERROR` | Character encoding problem |

### Step 3: React to transaction failures

```kotlin
import com.geoknoesis.kastor.rdf.RdfErrorCode
import com.geoknoesis.kastor.rdf.RdfTransactionException

try {
    repo.transaction {
        // operations
    }
} catch (e: RdfTransactionException) {
    when (e.errorCode) {
        RdfErrorCode.TRANSACTION_NOT_STARTED -> { /* … */ }
        RdfErrorCode.TRANSACTION_COMMIT_FAILED -> { /* … */ }
        RdfErrorCode.TRANSACTION_CONFLICT -> { /* … */ }
        else -> { /* … */ }
    }
}
```

Other **`RdfException`** subtypes you may see:

- **`RdfProviderException`** — provider wiring or capability failures
- **`RdfRepositoryException`** — dataset lifecycle issues
- **`RdfGraphException`** — graph mutation problems
- **`RdfValidationException`** — validation pipelines (SHACL, etc.)
- **`RdfConfigurationException`** — builder/registry misconfiguration
- **`RdfFederationException`** — federated query failures
- **`RdfInferenceException`** — reasoning failures

### Step 4: Log structured context

All RDF exceptions expose **`context`** for diagnostics:

```kotlin
import com.geoknoesis.kastor.rdf.RdfException

try {
    val result = repo.select(query)
} catch (e: RdfException) {
    println("Error code: ${e.errorCode.code}")
    println("Category: ${e.errorCode.category}")
    e.context?.forEach { (key, value) ->
        println("$key: $value")
    }
}
```

For **`RdfQueryException`**, prefer **`query`** and **`bindings`** when present:

```kotlin
catch (e: RdfQueryException) {
    val queryText = e.query ?: "Unknown"
    val bindings = e.bindings ?: emptyMap()
    logger.error("Query failed: $queryText with bindings: $bindings", e)
}
```

### Step 5: Centralize user-facing messages

```kotlin
import com.geoknoesis.kastor.rdf.RdfException

fun handleError(exception: RdfException): String {
    return when (exception.errorCode) {
        RdfErrorCode.QUERY_SYNTAX_ERROR -> "Please check your query syntax"
        RdfErrorCode.QUERY_TIMEOUT -> "Query took too long. Please simplify your query."
        RdfErrorCode.FORMAT_UNSUPPORTED -> "This format is not supported"
        else -> exception.message ?: "An error occurred"
    }
}
```

Optional helpers:

```kotlin
fun isQueryError(exception: RdfException): Boolean =
    exception.errorCode.category == "QUERY"

fun isFormatError(exception: RdfException): Boolean =
    exception.errorCode.category == "FORMAT"
```

Prefer exhaustive **`when`** branches on sealed **`RdfFormatException`** and concrete **`RdfQueryException`** / **`RdfTransactionException`** handlers where the compiler can enforce coverage.

## Error code categories

Prefixes group related failures:

- **`QUERY_*`** — SPARQL
- **`FORMAT_*`** — parsing and serialization
- **`TRANSACTION_*`** — transactional semantics
- **`PROVIDER_*`** / **`REPOSITORY_*`** / **`GRAPH_*`** — storage layers
- **`VALIDATION_*`** — validation pipelines
- **`CONFIGURATION_*`** — registry and builder errors

Canonical definitions → **[Error Codes](../reference/error-codes.md)**.

## Validation

- Reproduce the failing query or parse in a minimal Kotlin snippet.
- Assert **`errorCode`** (and **`when`** exhaustiveness on **`RdfFormatException`**) in unit tests for stability across message tweaks.

## Troubleshooting

- **Opaque `else` branches:** log **`exception.errorCode.code`** and **`context`** before collapsing to a generic message.
- **Changing messages:** depend on **`errorCode`**, not substring matches on **`message`**.

## Related

- [Error Codes Reference](../reference/error-codes.md)
- [Exception API Reference](../api/core-api.md#exceptions)
- [Best Practices — Error handling](best-practices.md#error-handling)
