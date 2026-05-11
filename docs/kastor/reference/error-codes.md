# Error Codes Reference

{% include version-banner.md %}

Complete reference of all error codes in Kastor.

## Query Errors (QUERY_*)

| Code | Description |
|------|-------------|
| `QUERY_SYNTAX_ERROR` | SPARQL query syntax error |
| `QUERY_EXECUTION_ERROR` | SPARQL query execution failed |
| `QUERY_TIMEOUT` | SPARQL query execution timed out |
| `QUERY_UNSUPPORTED_FEATURE` | SPARQL feature not supported by provider |
| `QUERY_INVALID_BINDINGS` | Invalid variable bindings for query |

## Format Errors (FORMAT_*)

| Code | Description |
|------|-------------|
| `FORMAT_PARSE_ERROR` | Failed to parse RDF data |
| `FORMAT_UNSUPPORTED` | RDF format not supported |
| `FORMAT_SERIALIZATION_ERROR` | Failed to serialize RDF data |
| `FORMAT_ENCODING_ERROR` | Character encoding error |

## Transaction Errors (TRANSACTION_*)

| Code | Description |
|------|-------------|
| `TRANSACTION_NOT_STARTED` | Transaction not started |
| `TRANSACTION_ALREADY_STARTED` | Transaction already started |
| `TRANSACTION_COMMIT_FAILED` | Transaction commit failed |
| `TRANSACTION_ROLLBACK_FAILED` | Transaction rollback failed |
| `TRANSACTION_CONFLICT` | Transaction conflict detected |

## Provider Errors (PROVIDER_*)

| Code | Description |
|------|-------------|
| `PROVIDER_NOT_FOUND` | RDF provider not found |
| `PROVIDER_NOT_SUPPORTED` | Provider does not support requested operation |
| `PROVIDER_INITIALIZATION_ERROR` | Provider initialization failed |
| `PROVIDER_CONNECTION_ERROR` | Provider connection failed |

## Repository Errors (REPOSITORY_*)

| Code | Description |
|------|-------------|
| `REPOSITORY_CLOSED` | Repository is closed |
| `REPOSITORY_ALREADY_OPEN` | Repository is already open |
| `REPOSITORY_OPERATION_FAILED` | Repository operation failed |
| `REPOSITORY_NOT_FOUND` | Repository not found |

## Graph Errors (GRAPH_*)

| Code | Description |
|------|-------------|
| `GRAPH_OPERATION_FAILED` | Graph operation failed |
| `GRAPH_READ_ONLY` | Graph is read-only |

## Validation Errors (VALIDATION_*)

| Code | Description |
|------|-------------|
| `VALIDATION_FAILED` | SHACL validation failed |
| `VALIDATION_SHAPE_NOT_FOUND` | SHACL shape not found |
| `VALIDATION_INVALID_SHAPE` | Invalid SHACL shape |

## Configuration Errors (CONFIGURATION_*)

| Code | Description |
|------|-------------|
| `CONFIGURATION_INVALID` | Invalid configuration |
| `CONFIGURATION_MISSING_REQUIRED` | Required configuration missing |
| `CONFIGURATION_UNSUPPORTED` | Configuration not supported |

## Federation Errors (FEDERATION_*)

| Code | Description |
|------|-------------|
| `FEDERATION_ERROR` | Federation operation failed |
| `FEDERATION_ENDPOINT_ERROR` | Federation endpoint error |

## Inference Errors (INFERENCE_*)

| Code | Description |
|------|-------------|
| `INFERENCE_ERROR` | Inference operation failed |
| `INFERENCE_REASONER_NOT_FOUND` | Reasoner not found |
| `INFERENCE_UNSUPPORTED` | Inference not supported |

## Generic Errors

| Code | Description |
|------|-------------|
| `UNKNOWN_ERROR` | Unknown error occurred |

## Usage Examples

### Get Error Code by String

```kotlin
val code = RdfErrorCode.fromCode("QUERY_SYNTAX_ERROR")
// Returns: RdfErrorCode.QUERY_SYNTAX_ERROR
```

### Get All Error Codes in a Category

```kotlin
val queryErrors = RdfErrorCode.byCategory("QUERY")
// Returns: [QUERY_SYNTAX_ERROR, QUERY_EXECUTION_ERROR, QUERY_TIMEOUT, ...]
```

### Check Error Code in Exception

```kotlin
try {
    // Operation
} catch (e: RdfException) {
    if (e.errorCode == RdfErrorCode.QUERY_SYNTAX_ERROR) {
        // Handle syntax error
    }
}
```

