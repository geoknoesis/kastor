package com.geoknoesis.kastor.rdf

/**
 * Enhanced error handling for the Kastor RDF API.
 * 
 * This module provides specific exception types for different RDF operations,
 * making debugging and error handling more precise and informative.
 */

/**
 * Base exception class for all RDF-related errors.
 */
sealed class RdfException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when SPARQL query operations fail.
 */
class RdfQueryException(message: String, cause: Throwable? = null) : RdfException(message, cause)

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









