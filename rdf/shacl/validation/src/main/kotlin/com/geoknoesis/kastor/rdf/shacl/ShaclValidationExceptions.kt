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

open class ShapeCompileException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)

/** Categories of recognised SHACL features that the native engine does not implement. */
enum class UnsupportedShaclFeature {
    /** SHACL-SPARQL constraint components (`sh:validator`, `sh:nodeValidator`, `sh:propertyValidator`). */
    SPARQL_CONSTRAINT_COMPONENT,

    /** SHACL 1.2 node expressions (`sh:values`, `sh:expression`, computed `sh:targetNode` / `sh:nodeByExpression`). */
    NODE_EXPRESSION,

    /** SHACL 1.2 SPARQL node expressions (`sh:select` / `sh:sparqlExpr` expressions, SPARQL `sh:targetWhere`). */
    SPARQL_NODE_EXPRESSION,

    /** SHACL 1.2 functions (`sh:bodyExpression`) called from SPARQL queries. */
    SHACL_FUNCTION,

    /** SPARQL-based or custom targets (`sh:target`). */
    CUSTOM_TARGET,
}

/**
 * The shapes graph uses SHACL features the engine recognises but cannot evaluate ([features]), and
 * [ValidationConfig.unsupportedFeatures] asks for failure: nothing was validated.
 */
class UnsupportedShaclFeatureException(
    message: String,
    val features: Set<UnsupportedShaclFeature>,
    cause: Throwable? = null,
) : ShapeCompileException(message, cause)

/**
 * A SHACL-SPARQL query breaks the pre-binding restrictions (`MINUS`, `SERVICE`, `VALUES`, re-binding a pre-bound
 * variable, or a sub-query that does not project `$this`).
 */
class SparqlPreBindingRestrictionException(message: String, cause: Throwable? = null) :
    ShapeCompileException(message, cause)

class ShapesGraphNotFoundException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)

class ShapesGraphAccessException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)

class StaleShapesGraphTagException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)
