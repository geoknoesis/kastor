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
 * - Use [RdfQueryException.query] to access the failed query string
 * - Use [RdfException.context] for additional debugging information
 * - Consider using [selectOrNull] or [selectResult] for functional error handling
 */

/**
 * Base exception class for all RDF-related errors.
 * 
 * All RDF exceptions provide additional context through the [context] property
 * to aid in debugging and error recovery.
 */
sealed class RdfException(
    message: String,
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
 * @param query The SPARQL query that failed (if available)
 * @param bindings Variable bindings at the time of failure (if available)
 * @param cause The underlying exception that caused this error
 */
class RdfQueryException(
    message: String,
    val query: String? = null,
    val bindings: Map<String, RdfTerm>? = null,
    cause: Throwable? = null
) : RdfException(message, cause) {
    override val context: Map<String, Any>? = buildMap {
        query?.let { put("query", it) }
        bindings?.let { put("bindings", it) }
    }
}

/**
 * Exception thrown when transaction operations fail.
 */
class RdfTransactionException(message: String, cause: Throwable? = null) : RdfException(message, cause)

/**
 * Exception thrown when provider operations fail.
 */
class RdfProviderException(message: String, cause: Throwable? = null) : RdfException(message, cause)

/**
 * Exception thrown when validation operations fail.
 */
class RdfValidationException(message: String, cause: Throwable? = null) : RdfException(message, cause)

/**
 * Exception thrown when data format operations fail.
 */
class RdfFormatException(message: String, cause: Throwable? = null) : RdfException(message, cause)

/**
 * Exception thrown when repository operations fail.
 */
class RdfRepositoryException(message: String, cause: Throwable? = null) : RdfException(message, cause)

/**
 * Exception thrown when graph operations fail.
 */
class RdfGraphException(message: String, cause: Throwable? = null) : RdfException(message, cause)

/**
 * Exception thrown when federation operations fail.
 */
class RdfFederationException(message: String, cause: Throwable? = null) : RdfException(message, cause)

/**
 * Exception thrown when inference operations fail.
 */
class RdfInferenceException(message: String, cause: Throwable? = null) : RdfException(message, cause)

/**
 * Exception thrown when configuration operations fail.
 */
class RdfConfigurationException(message: String, cause: Throwable? = null) : RdfException(message, cause)









