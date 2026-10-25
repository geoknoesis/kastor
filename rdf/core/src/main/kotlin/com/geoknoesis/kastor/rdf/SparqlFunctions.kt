package com.geoknoesis.kastor.rdf

/**
 * A SELECT query whose text comes from an untrusted source, such as a SHACL shapes graph. A provider evaluates it only
 * after parsing it with the parser that executes it, and refuses it with [IllegalArgumentException] when the parsed
 * query would contact another service (`SERVICE`) or load code by name (Jena's `java:` functions and property
 * functions). A provider that cannot check the query itself, such as a remote endpoint, refuses it with
 * [UnsupportedOperationException].
 *
 * [functions] are custom SPARQL functions that are visible to this one execution only; a provider never registers
 * them globally. The same refusal of `SERVICE` and `java:` code applies to every query a function body runs.
 *
 * Two queries are equal when their [sparql] is equal and their [functions] are the same library instance.
 */
class SandboxedSelectQuery @JvmOverloads constructor(
    override val sparql: String,
    val functions: SparqlFunctionLibrary = SparqlFunctionLibrary.EMPTY,
) : SparqlSelect {
    override fun equals(other: Any?): Boolean =
        other is SandboxedSelectQuery && sparql == other.sparql && functions === other.functions

    override fun hashCode(): Int = 31 * sparql.hashCode() + System.identityHashCode(functions)

    override fun toString(): String = "SandboxedSelectQuery(sparql=$sparql)"
}

/**
 * Custom functions visible to one sandboxed query and to every query its function bodies run.
 *
 * Not to be confused with [SparqlExtensionFunction] and the SPARQL DSL's `SparqlExtensionFunctionRegistry`: those
 * describe functions an engine already has (metadata for service descriptions and the DSL), are global, and are never
 * executed by Kastor. A [SparqlFunction] is executable, and exists only for the query that carries its library.
 */
fun interface SparqlFunctionLibrary {
    /** The custom function with [iri], or null when this library has none. */
    fun function(iri: String): SparqlFunction?

    companion object {
        /** A library without functions. */
        @JvmField
        val EMPTY: SparqlFunctionLibrary = SparqlFunctionLibrary { null }

        /** A library of [functions]; when several have the same IRI, the later one replaces the earlier ones. */
        @JvmStatic
        fun of(functions: Collection<SparqlFunction>): SparqlFunctionLibrary {
            val byIri = LinkedHashMap<String, SparqlFunction>()
            functions.forEach { byIri[it.iri] = it }
            return SparqlFunctionLibrary { byIri[it] }
        }
    }
}

/**
 * A custom SPARQL function (SHACL 1.2 SPARQL Extensions, section 7).
 *
 * Per section 7.3, a provider ignores a custom function whose IRI is already a built-in or other non-custom function
 * ("SPARQL engines MUST ignore the attempt to redefine it"), and it never modifies registered functions while a query
 * runs.
 */
class SparqlFunction(
    val iri: String,
    optional: List<Boolean>,
    /** Returns the single result node, or null for an error. Never called with a blank node argument. */
    val body: (SparqlFunctionCall) -> RdfTerm?,
) {
    /** One entry per declared parameter, in argument order: true when the parameter is optional (a private copy). */
    val optional: List<Boolean> = optional.toList()
}

/** The context a [SparqlFunction.body] runs in. */
interface SparqlFunctionCall {
    /** Evaluated arguments, one per declared parameter; null for an unbound optional argument. */
    val arguments: List<RdfTerm?>

    /**
     * Runs [query] on the dataset of the calling query, within its remaining time, one nesting level deeper than the
     * calling query. The nested query sees exactly its own [SandboxedSelectQuery.functions]; there is no implicit
     * inheritance from the calling query. A call deeper than [MAX_FUNCTION_DEPTH] is an error.
     */
    fun <T> select(query: SandboxedSelectQuery, bindings: Map<String, RdfTerm>, consume: (Sequence<BindingSet>) -> T): T
}

/** The deepest nesting of function calls a provider evaluates; a deeper call is an error, so recursion terminates. */
const val MAX_FUNCTION_DEPTH: Int = 16

/**
 * Provider helper (a provider calls it before [SparqlFunction.body]; applications do not need it).
 *
 * The arguments a provider passes to [SparqlFunction.body]: [evaluated] holds one entry per actual argument
 * (null = evaluation error).
 *
 * Section 7.3: "Where the evaluation has caused an error, the result of the SPARQL function call is also an error,
 * unless the corresponding sh:parameter for the argument has sh:optional true. In that case, the argument will be
 * unbound in the scope." and "blank nodes cannot be passed as arguments ... an argument that evaluates to a blank
 * node leads to an evaluation failure and thus an error."
 *
 * Returns null when the call is an error: more arguments than parameters; an error, a missing argument or a blank
 * node for a non-optional parameter. Optional parameters with an error, a blank node or no argument become null.
 */
fun SparqlFunction.bindArguments(evaluated: List<RdfTerm?>): List<RdfTerm?>? {
    if (evaluated.size > optional.size) return null
    return optional.mapIndexed { i, isOptional ->
        val arg = evaluated.getOrNull(i)?.takeUnless { it is BlankNode }
        if (arg == null && !isOptional) return null
        arg
    }
}
