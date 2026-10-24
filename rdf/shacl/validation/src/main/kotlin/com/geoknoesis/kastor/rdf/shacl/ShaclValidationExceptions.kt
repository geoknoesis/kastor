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

/**
 * The engine does not implement the requested operation or execution option: `validate(graph, List<ShaclShape>)` /
 * `validateConstraints` with a non-empty list, [ValidationConfig.parallelValidation] or
 * [ValidationConfig.streamingMode] on the Kastor native engine (provider ids `kastor` and `memory`). Nothing was
 * validated; the message says what to use instead.
 */
class UnsupportedShaclOperationException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)

open class ShapeCompileException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)

/** Categories of recognised SHACL features that the native engine does not implement. */
enum class UnsupportedShaclFeature {
    /**
     * SHACL-SPARQL constraint components (`sh:validator`, `sh:nodeValidator`, `sh:propertyValidator`). Kept for
     * compatibility: these components are now evaluated, so the engine no longer reports this category.
     */
    SPARQL_CONSTRAINT_COMPONENT,

    /**
     * SHACL 1.2 node expressions the engine cannot evaluate: a non-constant `sh:deactivated` on a reifier, and a blank
     * node at a node expression position (`sh:values`, `sh:defaultValue`, `sh:targetNode`, `sh:deactivated`,
     * `sh:expression`, `sh:nodeByExpression`) that is no node expression of the library, such as a call of a function the shapes graph
     * does not declare. Every well-formed node expression of SHACL 1.2 Node Expressions is evaluated at these
     * positions.
     */
    NODE_EXPRESSION,

    /**
     * SHACL 1.2 SPARQL node expressions (`sh:select` / `sh:sparqlExpr`) the engine cannot evaluate: those used as
     * `sh:targetWhere` values. At every node expression position (`sh:targetNode`, `sh:values`, `sh:defaultValue`,
     * `sh:expression`, `sh:nodeByExpression`) they are supported.
     */
    SPARQL_NODE_EXPRESSION,

    /**
     * SHACL 1.2 functions that SPARQL queries call but the engine cannot compile: not a
     * `sh:ListParameterExpressionFunction`, or a `sh:bodyExpression` that is no node expression at all. Functions
     * with a `sh:select` / `sh:sparqlExpr` or `shnex:` body are supported.
     */
    SHACL_FUNCTION,

    /** SPARQL-based or custom targets (`sh:target`). */
    CUSTOM_TARGET,

    /**
     * SHACL 1.2 `sh:reifierShape` / `sh:reificationRequired true` on a property shape whose `sh:path` is not a
     * predicate IRI: the engine only knows which triples a predicate path traverses.
     */
    REIFIER_CONSTRAINT_ON_COMPLEX_PATH,
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
 * variable).
 */
class SparqlPreBindingRestrictionException(message: String, cause: Throwable? = null) :
    ShapeCompileException(message, cause)

class ShapesGraphNotFoundException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)

class ShapesGraphAccessException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)

class StaleShapesGraphTagException(message: String, cause: Throwable? = null) :
    ShaclValidationException(message, cause)
