package com.geoknoesis.kastor.rdf

/**
 * Enhanced error handling for the Kastor RDF API.
 * 
 * This module provides specific exception types for different RDF operations,
 * making debugging and error handling more precise and informative.
 * 
 * ## Error Handling Patterns
 * 
 * Kastor uses a consistent error handling strategy:
 * 
 * 1. **Technical Failures** → Exceptions ([RdfQueryException], [RdfTransactionException], etc.)
 *    - Operations that can fail due to technical issues (parsing, network, I/O)
 *    - Examples: SPARQL query execution, transaction operations
 *    - Always include context (query strings, operation details) for debugging
 * 
 * 2. **Semantic Failures** → Sealed Results ([ValidationResult])
 *    - Operations that can fail semantically but are expected outcomes
 *    - Examples: SHACL validation (violations are data issues, not technical errors)
 *    - Use sealed classes to represent success/failure states
 * 
 * 3. **Operations That Should Never Fail** → Direct Return Types
 *    - Operations that are guaranteed to succeed in normal operation
 *    - Examples: Getting graph size, checking if repository is closed
 *    - May throw exceptions only in exceptional circumstances (e.g., repository closed)
 * 
 * ## Best Practices
 * 
 * - Always catch specific exception types when possible
 * - Use [RdfException.errorCode] for programmatic error handling
 * - Use [RdfQueryException.query] to access the failed query string
 * - Use [RdfException.context] for additional debugging information
 * - Consider using [selectOrNull] or [selectResult] for functional error handling
 * 
 * ## Error Codes
 * 
 * All exceptions include an [errorCode] property for programmatic error handling.
 * See [RdfErrorCode] for a complete list of error codes.
 */

/**
 * Base exception class for all RDF-related errors.
 * 
 * All RDF exceptions provide additional context through the [context] property
 * to aid in debugging and error recovery, and an [errorCode] for programmatic error handling.
 */
sealed class RdfException(
    message: String,
    val errorCode: RdfErrorCode = RdfErrorCode.UNKNOWN_ERROR,
    cause: Throwable? = null
) : Exception(message, cause) {
    /**
     * Additional context for debugging.
     * Subclasses should populate this with relevant information (query strings, bindings, etc.).
     */
    open val context: Map<String, Any>? = null
}

/**
 * Exception thrown when SPARQL query operations fail.
 * 
 * @param message Human-readable error message
 * @param errorCode Error code for programmatic handling (defaults to QUERY_EXECUTION_ERROR)
 * @param query The SPARQL query that failed (if available)
 * @param bindings Variable bindings at the time of failure (if available)
 * @param cause The underlying exception that caused this error
 */
class RdfQueryException(
    message: String,
    errorCode: RdfErrorCode = RdfErrorCode.QUERY_EXECUTION_ERROR,
    val query: String? = null,
    val bindings: Map<String, RdfTerm>? = null,
    cause: Throwable? = null
) : RdfException(message, errorCode, cause) {
    override val context: Map<String, Any>? = buildMap {
        put("errorCode", errorCode.code)
        query?.let { put("query", it) }
        bindings?.let { put("bindings", it) }
    }
}

/**
 * Exception thrown when transaction operations fail.
 * 
 * @param message Human-readable error message
 * @param errorCode Error code for programmatic handling (defaults to TRANSACTION_COMMIT_FAILED)
 * @param cause The underlying exception that caused this error
 */
class RdfTransactionException(
    message: String,
    errorCode: RdfErrorCode = RdfErrorCode.TRANSACTION_COMMIT_FAILED,
    cause: Throwable? = null
) : RdfException(message, errorCode, cause)

/**
 * Exception thrown when provider operations fail.
 * 
 * @param message Human-readable error message
 * @param errorCode Error code for programmatic handling (defaults to PROVIDER_OPERATION_FAILED)
 * @param cause The underlying exception that caused this error
 */
class RdfProviderException(
    message: String,
    errorCode: RdfErrorCode = RdfErrorCode.PROVIDER_NOT_SUPPORTED,
    cause: Throwable? = null
) : RdfException(message, errorCode, cause)

/**
 * Exception thrown when validation operations fail.
 * 
 * @param message Human-readable error message
 * @param errorCode Error code for programmatic handling (defaults to VALIDATION_FAILED)
 * @param cause The underlying exception that caused this error
 */
class RdfValidationException(
    message: String,
    errorCode: RdfErrorCode = RdfErrorCode.VALIDATION_FAILED,
    cause: Throwable? = null
) : RdfException(message, errorCode, cause)

/**
 * Detailed parsing error information including line/column context when available.
 * 
 * This data class captures parsing error details that help developers debug
 * malformed RDF files by providing precise location information.
 * 
 * @param message Human-readable error message
 * @param line Line number where the error occurred (1-based, null if unknown)
 * @param column Column number where the error occurred (1-based, null if unknown)
 * @param snippet Code snippet around the error location (null if unavailable)
 * @param format The RDF format being parsed
 * @param cause The underlying exception that caused this error
 */
data class ParseErrorDetails(
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
    val snippet: String? = null,
    val format: String,
    val cause: Throwable? = null
) {
    override fun toString(): String = buildString {
        append("Parse error in $format")
        if (line != null) append(" at line $line")
        if (column != null) append(", column $column")
        append(": $message")
        if (snippet != null) append("\nSnippet: $snippet")
    }
}

/**
 * Exception thrown when data format operations fail.
 * 
 * This is a sealed class that can represent different types of format-related errors:
 * - [ParseError] - Detailed parsing errors with line/column context
 * - [UnsupportedFormat] - Format not supported by any provider
 */
sealed class RdfFormatException(
    message: String,
    errorCode: RdfErrorCode = RdfErrorCode.FORMAT_SERIALIZATION_ERROR,
    cause: Throwable? = null
) : RdfException(message, errorCode, cause) {
    /**
     * Detailed parsing error with line/column context.
     */
    data class ParseError(
        val parseError: ParseErrorDetails
    ) : RdfFormatException(parseError.toString(), RdfErrorCode.FORMAT_PARSE_ERROR, parseError.cause) {
        override val context: Map<String, Any>? = buildMap {
            put("errorCode", errorCode.code)
            put("format", parseError.format)
            parseError.line?.let { put("line", it) }
            parseError.column?.let { put("column", it) }
            parseError.snippet?.let { put("snippet", it) }
        }
    }
    
    /**
     * Format is not supported by any available provider.
     */
    data class UnsupportedFormat(
        val format: String,
        val availableFormats: List<String>
    ) : RdfFormatException(
        "Unsupported format: $format. Available: ${availableFormats.joinToString()}",
        RdfErrorCode.FORMAT_UNSUPPORTED
    ) {
        override val context: Map<String, Any>? = mapOf(
            "errorCode" to errorCode.code,
            "format" to format,
            "availableFormats" to availableFormats
        )
    }
    
    /**
     * Generic format exception for backward compatibility.
     * Prefer using [ParseError] or [UnsupportedFormat] when possible.
     */
    class Generic(
        message: String,
        errorCode: RdfErrorCode = RdfErrorCode.FORMAT_SERIALIZATION_ERROR,
        cause: Throwable? = null
    ) : RdfFormatException(message, errorCode, cause)
}

/**
 * Exception thrown when repository operations fail.
 * 
 * @param message Human-readable error message
 * @param errorCode Error code for programmatic handling (defaults to REPOSITORY_OPERATION_FAILED)
 * @param cause The underlying exception that caused this error
 */
class RdfRepositoryException(
    message: String,
    errorCode: RdfErrorCode = RdfErrorCode.REPOSITORY_OPERATION_FAILED,
    cause: Throwable? = null
) : RdfException(message, errorCode, cause)

/**
 * Exception thrown when graph operations fail.
 * 
 * @param message Human-readable error message
 * @param errorCode Error code for programmatic handling (defaults to GRAPH_OPERATION_FAILED)
 * @param cause The underlying exception that caused this error
 */
class RdfGraphException(
    message: String,
    errorCode: RdfErrorCode = RdfErrorCode.GRAPH_OPERATION_FAILED,
    cause: Throwable? = null
) : RdfException(message, errorCode, cause)

/**
 * Exception thrown when federation operations fail.
 * 
 * @param message Human-readable error message
 * @param errorCode Error code for programmatic handling (defaults to FEDERATION_ERROR)
 * @param cause The underlying exception that caused this error
 */
class RdfFederationException(
    message: String,
    errorCode: RdfErrorCode = RdfErrorCode.FEDERATION_ERROR,
    cause: Throwable? = null
) : RdfException(message, errorCode, cause)

/**
 * Exception thrown when inference operations fail.
 * 
 * @param message Human-readable error message
 * @param errorCode Error code for programmatic handling (defaults to INFERENCE_ERROR)
 * @param cause The underlying exception that caused this error
 */
class RdfInferenceException(
    message: String,
    errorCode: RdfErrorCode = RdfErrorCode.INFERENCE_ERROR,
    cause: Throwable? = null
) : RdfException(message, errorCode, cause)

/**
 * Exception thrown when configuration operations fail.
 * 
 * @param message Human-readable error message
 * @param errorCode Error code for programmatic handling (defaults to CONFIGURATION_INVALID)
 * @param cause The underlying exception that caused this error
 */
class RdfConfigurationException(
    message: String,
    errorCode: RdfErrorCode = RdfErrorCode.CONFIGURATION_INVALID,
    cause: Throwable? = null
) : RdfException(message, errorCode, cause)









