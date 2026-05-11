package com.geoknoesis.kastor.rdf

/**
 * Error codes for programmatic error handling.
 * 
 * Error codes provide a stable, machine-readable way to identify specific error conditions
 * without relying on error message text. This enables:
 * - Internationalization (i18n) - error messages can be translated while codes remain stable
 * - Automated error handling - code can check for specific error conditions
 * - Error categorization - errors can be grouped by code patterns
 * - API stability - codes don't change even if messages do
 * 
 * Error codes follow a hierarchical pattern:
 * - `QUERY_*` - SPARQL query-related errors
 * - `FORMAT_*` - Format parsing/serialization errors
 * - `TRANSACTION_*` - Transaction-related errors
 * - `PROVIDER_*` - Provider-related errors
 * - `REPOSITORY_*` - Repository operation errors
 * - `GRAPH_*` - Graph operation errors
 * - `VALIDATION_*` - Validation errors
 * - `CONFIGURATION_*` - Configuration errors
 * 
 * @sample com.example.ErrorCodeHandling
 */
enum class RdfErrorCode(
    val code: String,
    val category: String,
    val description: String
) {
    // Query Errors (QUERY_*)
    QUERY_SYNTAX_ERROR("QUERY_SYNTAX_ERROR", "QUERY", "SPARQL query syntax error"),
    QUERY_EXECUTION_ERROR("QUERY_EXECUTION_ERROR", "QUERY", "SPARQL query execution failed"),
    QUERY_TIMEOUT("QUERY_TIMEOUT", "QUERY", "SPARQL query execution timed out"),
    QUERY_UNSUPPORTED_FEATURE("QUERY_UNSUPPORTED_FEATURE", "QUERY", "SPARQL feature not supported by provider"),
    QUERY_INVALID_BINDINGS("QUERY_INVALID_BINDINGS", "QUERY", "Invalid variable bindings for query"),
    
    // Format Errors (FORMAT_*)
    FORMAT_PARSE_ERROR("FORMAT_PARSE_ERROR", "FORMAT", "Failed to parse RDF data"),
    FORMAT_UNSUPPORTED("FORMAT_UNSUPPORTED", "FORMAT", "RDF format not supported"),
    FORMAT_SERIALIZATION_ERROR("FORMAT_SERIALIZATION_ERROR", "FORMAT", "Failed to serialize RDF data"),
    FORMAT_ENCODING_ERROR("FORMAT_ENCODING_ERROR", "FORMAT", "Character encoding error"),
    
    // Transaction Errors (TRANSACTION_*)
    TRANSACTION_NOT_STARTED("TRANSACTION_NOT_STARTED", "TRANSACTION", "Transaction not started"),
    TRANSACTION_ALREADY_STARTED("TRANSACTION_ALREADY_STARTED", "TRANSACTION", "Transaction already started"),
    TRANSACTION_COMMIT_FAILED("TRANSACTION_COMMIT_FAILED", "TRANSACTION", "Transaction commit failed"),
    TRANSACTION_ROLLBACK_FAILED("TRANSACTION_ROLLBACK_FAILED", "TRANSACTION", "Transaction rollback failed"),
    TRANSACTION_CONFLICT("TRANSACTION_CONFLICT", "TRANSACTION", "Transaction conflict detected"),
    
    // Provider Errors (PROVIDER_*)
    PROVIDER_NOT_FOUND("PROVIDER_NOT_FOUND", "PROVIDER", "RDF provider not found"),
    PROVIDER_NOT_SUPPORTED("PROVIDER_NOT_SUPPORTED", "PROVIDER", "Provider does not support requested operation"),
    PROVIDER_INITIALIZATION_ERROR("PROVIDER_INITIALIZATION_ERROR", "PROVIDER", "Provider initialization failed"),
    PROVIDER_CONNECTION_ERROR("PROVIDER_CONNECTION_ERROR", "PROVIDER", "Provider connection failed"),
    
    // Repository Errors (REPOSITORY_*)
    REPOSITORY_CLOSED("REPOSITORY_CLOSED", "REPOSITORY", "Repository is closed"),
    REPOSITORY_ALREADY_OPEN("REPOSITORY_ALREADY_OPEN", "REPOSITORY", "Repository is already open"),
    REPOSITORY_OPERATION_FAILED("REPOSITORY_OPERATION_FAILED", "REPOSITORY", "Repository operation failed"),
    REPOSITORY_NOT_FOUND("REPOSITORY_NOT_FOUND", "REPOSITORY", "Repository not found"),
    
    // Graph Errors (GRAPH_*)
    GRAPH_OPERATION_FAILED("GRAPH_OPERATION_FAILED", "GRAPH", "Graph operation failed"),
    GRAPH_READ_ONLY("GRAPH_READ_ONLY", "GRAPH", "Graph is read-only"),
    
    // Validation Errors (VALIDATION_*)
    VALIDATION_FAILED("VALIDATION_FAILED", "VALIDATION", "SHACL validation failed"),
    VALIDATION_SHAPE_NOT_FOUND("VALIDATION_SHAPE_NOT_FOUND", "VALIDATION", "SHACL shape not found"),
    VALIDATION_INVALID_SHAPE("VALIDATION_INVALID_SHAPE", "VALIDATION", "Invalid SHACL shape"),
    
    // Configuration Errors (CONFIGURATION_*)
    CONFIGURATION_INVALID("CONFIGURATION_INVALID", "CONFIGURATION", "Invalid configuration"),
    CONFIGURATION_MISSING_REQUIRED("CONFIGURATION_MISSING_REQUIRED", "CONFIGURATION", "Required configuration missing"),
    CONFIGURATION_UNSUPPORTED("CONFIGURATION_UNSUPPORTED", "CONFIGURATION", "Configuration not supported"),
    
    // Federation Errors (FEDERATION_*)
    FEDERATION_ERROR("FEDERATION_ERROR", "FEDERATION", "Federation operation failed"),
    FEDERATION_ENDPOINT_ERROR("FEDERATION_ENDPOINT_ERROR", "FEDERATION", "Federation endpoint error"),
    
    // Inference Errors (INFERENCE_*)
    INFERENCE_ERROR("INFERENCE_ERROR", "INFERENCE", "Inference operation failed"),
    INFERENCE_REASONER_NOT_FOUND("INFERENCE_REASONER_NOT_FOUND", "INFERENCE", "Reasoner not found"),
    INFERENCE_UNSUPPORTED("INFERENCE_UNSUPPORTED", "INFERENCE", "Inference not supported"),
    
    // Generic/Unknown
    UNKNOWN_ERROR("UNKNOWN_ERROR", "GENERIC", "Unknown error occurred");
    
    companion object {
        /**
         * Get error code by string code value.
         */
        fun fromCode(code: String): RdfErrorCode? {
            return values().find { it.code == code }
        }
        
        /**
         * Get all error codes for a specific category.
         */
        fun byCategory(category: String): List<RdfErrorCode> {
            return values().filter { it.category == category }
        }
    }
}

