# Error Handling Guide

{% include version-banner.md %}

## Overview

Kastor provides comprehensive error handling with:
- **Specific exception types** for different error categories
- **Error codes** for programmatic error handling
- **Rich context** for debugging
- **Type-safe error handling** with sealed classes

## Error Codes

All RDF exceptions include an `errorCode` property that provides a stable, machine-readable identifier for the error condition. This enables:

- **Internationalization**: Error messages can be translated while codes remain stable
- **Automated handling**: Code can check for specific error conditions
- **Error categorization**: Errors can be grouped by code patterns
- **API stability**: Codes don't change even if messages do

### Error Code Categories

Error codes follow a hierarchical pattern:

- `QUERY_*` - SPARQL query-related errors
- `FORMAT_*` - Format parsing/serialization errors
- `TRANSACTION_*` - Transaction-related errors
- `PROVIDER_*` - Provider-related errors
- `REPOSITORY_*` - Repository operation errors
- `GRAPH_*` - Graph operation errors
- `VALIDATION_*` - Validation errors
- `CONFIGURATION_*` - Configuration errors

### Using Error Codes

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.RdfErrorCode

try {
    val result = repo.select(query)
} catch (e: RdfQueryException) {
    when (e.errorCode) {
        RdfErrorCode.QUERY_SYNTAX_ERROR -> {
            // Handle syntax errors - show user-friendly message
            println("Invalid query syntax: ${e.message}")
        }
        RdfErrorCode.QUERY_TIMEOUT -> {
            // Handle timeout - retry or show timeout message
            println("Query timed out. Please try again.")
        }
        RdfErrorCode.QUERY_EXECUTION_ERROR -> {
            // Handle execution errors - log details
            println("Query execution failed: ${e.message}")
            e.query?.let { println("Query: $it") }
        }
        else -> {
            // Handle other query errors
            println("Query error: ${e.message}")
        }
    }
}
```

## Exception Types

### RdfQueryException

Thrown when SPARQL query operations fail.

```kotlin
try {
    val results = repo.select(query)
} catch (e: RdfQueryException) {
    println("Error code: ${e.errorCode.code}")
    println("Query: ${e.query}")
    println("Bindings: ${e.bindings}")
}
```

**Error Codes:**
- `QUERY_SYNTAX_ERROR` - Query syntax is invalid
- `QUERY_EXECUTION_ERROR` - Query execution failed
- `QUERY_TIMEOUT` - Query execution timed out
- `QUERY_UNSUPPORTED_FEATURE` - Query uses unsupported SPARQL feature
- `QUERY_INVALID_BINDINGS` - Invalid variable bindings

### RdfFormatException

Thrown when format operations fail. This is a sealed class with specific variants:

```kotlin
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

**Error Codes:**
- `FORMAT_PARSE_ERROR` - Failed to parse RDF data
- `FORMAT_UNSUPPORTED` - Format not supported
- `FORMAT_SERIALIZATION_ERROR` - Failed to serialize RDF data
- `FORMAT_ENCODING_ERROR` - Character encoding error

### RdfTransactionException

Thrown when transaction operations fail.

```kotlin
try {
    repo.transaction {
        // Operations
    }
} catch (e: RdfTransactionException) {
    when (e.errorCode) {
        RdfErrorCode.TRANSACTION_NOT_STARTED -> {
            // Transaction was not started
        }
        RdfErrorCode.TRANSACTION_COMMIT_FAILED -> {
            // Commit failed - data may be inconsistent
        }
        RdfErrorCode.TRANSACTION_CONFLICT -> {
            // Transaction conflict - retry may be needed
        }
        else -> {
            // Other transaction errors
        }
    }
}
```

**Error Codes:**
- `TRANSACTION_NOT_STARTED` - Transaction not started
- `TRANSACTION_ALREADY_STARTED` - Transaction already started
- `TRANSACTION_COMMIT_FAILED` - Commit failed
- `TRANSACTION_ROLLBACK_FAILED` - Rollback failed
- `TRANSACTION_CONFLICT` - Transaction conflict detected

### Other Exception Types

- **RdfProviderException** - Provider operations failed
- **RdfRepositoryException** - Repository operations failed
- **RdfGraphException** - Graph operations failed
- **RdfValidationException** - Validation operations failed
- **RdfConfigurationException** - Configuration errors
- **RdfFederationException** - Federation operations failed
- **RdfInferenceException** - Inference operations failed

## Error Context

All exceptions provide a `context` property with additional debugging information:

```kotlin
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

## Best Practices

### 1. Use Error Codes for Programmatic Handling

```kotlin
fun handleError(exception: RdfException): String {
    return when (exception.errorCode) {
        RdfErrorCode.QUERY_SYNTAX_ERROR -> "Please check your query syntax"
        RdfErrorCode.QUERY_TIMEOUT -> "Query took too long. Please simplify your query."
        RdfErrorCode.FORMAT_UNSUPPORTED -> "This format is not supported"
        else -> exception.message ?: "An error occurred"
    }
}
```

### 2. Check Error Categories

```kotlin
fun isQueryError(exception: RdfException): Boolean {
    return exception.errorCode.category == "QUERY"
}

fun isFormatError(exception: RdfException): Boolean {
    return exception.errorCode.category == "FORMAT"
}
```

### 3. Use Sealed Classes for Type Safety

```kotlin
when (val e = catchException()) {
    is RdfQueryException -> handleQueryError(e)
    is RdfFormatException -> handleFormatError(e)
    is RdfTransactionException -> handleTransactionError(e)
    // Compiler ensures all cases are handled
}
```

### 4. Access Rich Context

```kotlin
catch (e: RdfQueryException) {
    val query = e.query ?: "Unknown"
    val bindings = e.bindings ?: emptyMap()
    logger.error("Query failed: $query with bindings: $bindings", e)
}
```

## Error Code Reference

See [RdfErrorCode](../reference/error-codes.md) for a complete list of all error codes.

## Related Documentation

- [Exception API Reference](../api/core-api.md#exceptions)
- [Error Codes Reference](../reference/error-codes.md)
- [Best Practices](best-practices.md#error-handling)

