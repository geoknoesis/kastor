package com.geoknoesis.kastor.rdf.shacl

/**
 * Base type for SHACL setup / registry failures (not normal validation outcomes).
 *
 * Violations of shapes are reported via [ValidationReport]; this hierarchy is for
 * misconfiguration, missing providers, compile failures, or dataset wiring errors.
 */
open class ShaclValidationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class ProviderNotFoundException(val providerId: String) :
    ShaclValidationException("No SHACL validator provider registered for id: $providerId")

class UnsupportedProfileException(
    message: String,
    cause: Throwable? = null,
) : ShaclValidationException(message, cause)

class ShapeCompileException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)

class ShapesGraphNotFoundException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)

class ShapesGraphAccessException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)

class StaleShapesGraphTagException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)
