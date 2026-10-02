package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm

enum class ShaclSeverity {
    Violation,
    Warning,
    Info
}

/**
 * Represents a SHACL validation violation with structured information.
 * 
 * @property focusNode The resource that violated the constraint
 * @property shapeIri The IRI of the SHACL shape that was violated
 * @property constraintIri The IRI of the constraint component that failed (e.g., sh:minCount)
 * @property path The property path that was validated (null for node-level constraints)
 * @property actualValue The actual value that violated the constraint (if applicable)
 * @property expectedValue The expected value or constraint description (if applicable)
 * @property message Human-readable error message
 * @property severity The severity level of the violation
 */
data class ShaclViolation(
    val focusNode: RdfResource,
    val shapeIri: Iri,
    val constraintIri: Iri,
    val path: Iri? = null,
    val actualValue: RdfTerm? = null,
    val expectedValue: RdfTerm? = null,
    val message: String,
    val severity: ShaclSeverity = ShaclSeverity.Violation
)

sealed interface ValidationResult {
    data object Ok : ValidationResult
    data class Violations(val items: List<ShaclViolation>) : ValidationResult
}

/**
 * A SHACL validator for a focus node in a data graph.
 *
 * Validators may hold resources (an RDF4J repository, parsed shapes); callers that create one should close it
 * (`use { }`). The default [close] does nothing.
 */
interface ValidationContext : AutoCloseable {
    fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult

    /**
     * Validates every node of [focuses] against one state of [data] and returns the result of each, in the order of
     * [focuses] (a node listed twice is validated once).
     *
     * Prefer this to calling [validate] in a loop: a validator that keeps its own copy of the data graph (the RDF4J
     * and Jena adapters) checks whether the graph changed once per call, and for a graph without a modification
     * stamp that check is a full read of the graph. `validateAll` reads such a graph once for all the nodes, where a
     * loop over N nodes reads it N times. All results describe the same content of the graph.
     *
     * The default implementation calls [validate] for each node.
     */
    fun validateAll(data: RdfGraph, focuses: Collection<RdfTerm>): Map<RdfTerm, ValidationResult> {
        val results = LinkedHashMap<RdfTerm, ValidationResult>()
        for (focus in focuses) {
            if (focus !in results) results[focus] = validate(data, focus)
        }
        return results
    }

    /** Releases resources held by this validator. The default does nothing. */
    override fun close() {}
}

/**
 * Validates the nodes of [instances] (generated wrappers, or anything [RdfBacked]) with this validator, one
 * [ValidationContext.validateAll] call per data graph instead of one [ValidationContext.validate] call per instance,
 * so each graph is checked for changes (and, without a modification stamp, read) once. Returns the result of every
 * instance in the order of [instances].
 *
 * Instances are grouped by the graph object their handle refers to: materialize the instances of one graph from one
 * graph handle (`val graph = repository.getGraph(name)`) to get one group.
 */
fun <T : RdfBacked> ValidationContext.validateAll(instances: Iterable<T>): List<Pair<T, ValidationResult>> {
    val all = instances.toList()
    val byGraph = java.util.IdentityHashMap<RdfGraph, MutableList<RdfTerm>>()
    all.forEach { byGraph.getOrPut(it.rdf.graph) { ArrayList() } += it.rdf.node }
    val results = java.util.IdentityHashMap<RdfGraph, Map<RdfTerm, ValidationResult>>()
    byGraph.forEach { (graph, nodes) -> results[graph] = validateAll(graph, nodes) }
    return all.map { it to results.getValue(it.rdf.graph).getValue(it.rdf.node) }
}

/**
 * Process-wide validators shared by generated wrappers, one per validator implementation.
 *
 * Wrappers generated with `ValidationMode.EXTERNAL` name a [ValidationContext] class with a no-argument constructor;
 * the class alone determines the shapes it validates against. Validators can be expensive (the RDF4J adapter keeps
 * in-memory copies of recently validated graphs, one store and lock per graph, so validations of different graphs
 * run concurrently), so all wrapper types that name the same class use one
 * instance obtained from [get] instead of one instance each.
 *
 * ## Lifecycle
 * A shared validator is created on first use and lives until [close] (for its class) or [closeAll] is called, which
 * closes it and removes it; the next [get] creates a fresh one. Call [closeAll] when the application (or a test, or a
 * reloadable module) shuts down to release the resources held by the validators. Do not close a shared validator
 * directly while wrappers may still use it.
 */
object SharedValidators {
    private val validators = java.util.concurrent.ConcurrentHashMap<Class<*>, ValidationContext>()

    /** The shared validator of [type], created with [create] on first use. */
    @JvmStatic
    fun <V : ValidationContext> get(type: Class<V>, create: () -> V): V {
        @Suppress("UNCHECKED_CAST")
        return validators.computeIfAbsent(type) { create() } as V
    }

    /** Closes and removes the shared validator of [type]; returns false when there was none. */
    @JvmStatic
    fun close(type: Class<out ValidationContext>): Boolean {
        val validator = validators.remove(type) ?: return false
        validator.close()
        return true
    }

    /** Closes and removes every shared validator. A failure to close one does not prevent closing the others. */
    @JvmStatic
    fun closeAll() {
        var failure: Exception? = null
        validators.keys.toList().forEach { type ->
            try {
                validators.remove(type)?.close()
            } catch (e: Exception) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }
}

class ValidationException(
    message: String,
    val violations: List<ShaclViolation> = emptyList(),
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Throws [ValidationException] when the result contains an `sh:Violation`. Results with severity `sh:Warning` or
 * `sh:Info` do not make data invalid (SHACL `sh:conforms` is still false, but they are advisory) and are ignored;
 * use the overload with a minimum severity to fail on them too.
 */
fun ValidationResult.orThrow(): Unit = orThrow(ShaclSeverity.Violation)

/**
 * Throws [ValidationException] carrying the items at least as severe as [minimumSeverity]
 * (Violation > Warning > Info); does nothing when there are none.
 */
fun ValidationResult.orThrow(minimumSeverity: ShaclSeverity) {
    if (this !is ValidationResult.Violations) return
    val failing = items.filter { it.severity.ordinal <= minimumSeverity.ordinal }
    if (failing.isEmpty()) return
    val message = failing.joinToString("; ") { it.message }
    throw ValidationException(message.ifBlank { "SHACL validation failed" }, failing)
}
