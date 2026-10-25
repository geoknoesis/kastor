package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression

/**
 * A shape-based function read a provisional "undefined" answer of the recursion solver
 * ([NativeValidationContext.isProvisional]): the expression has no value yet. It propagates out of the evaluation (it
 * is no failure) to the constraint that evaluates the expression, which reports it as undefined
 * ([Sink.undefinedAnswer]) so that the solver evaluates the question again.
 */
internal class ProvisionalUndefined(val node: RdfTerm, val shape: RdfResource) : RuntimeException(null, null, false, false)

/**
 * The [ProvisionalUndefined] answers that function bodies read during a SPARQL query, which cannot carry them, and
 * the validation failures of bodies that failed on a conformance check the engine could not decide ([stashFailure]):
 * each query runs in its own [scoped] block, so that nested queries keep their own, a query that returns rethrows
 * the first one (a failure before a provisional answer), and a query that throws discards a provisional answer (it
 * never reaches a later, unrelated query) but not a failure: the failure is thrown instead, with the query's own error
 * suppressed, so that a provider error cannot hide an undecided check.
 */
internal class ProvisionalStash {
    private var pending: RuntimeException? = null

    /** Records [e] for the query under way (the first one wins). */
    fun stash(e: ProvisionalUndefined) {
        if (pending == null) pending = e
    }

    /**
     * Records [e], the validation failure of a function body that failed on a conformance check the engine could not
     * decide (a final undefined answer, R16; an unsupported or ill-formed candidate shape, R8), for the query under
     * way: the query's answer cannot be decided either, whatever SPARQL makes of the body's error. It replaces a
     * provisional answer, which the solver would only evaluate again.
     */
    fun stashFailure(e: ShaclValidationException) {
        if (pending !is ShaclValidationException) pending = e
    }

    inline fun <T> scoped(query: () -> T): T {
        val outer = swap(null)
        try {
            val result = try {
                query()
            } catch (e: Exception) {
                val pending = swap(null)
                if (pending is ShaclValidationException && pending !== e) {
                    pending.addSuppressed(e)
                    throw pending
                }
                throw e
            }
            swap(null)?.let { throw it }
            return result
        } finally {
            swap(outer)
        }
    }

    /** Replaces the pending answer with [value], returning the previous one. */
    fun swap(value: RuntimeException?): RuntimeException? = pending.also { pending = value }
}

/** The outcome of `evalExpr` (Node Expressions §3): a list of output nodes, or an evaluation failure (§3.3). */
internal sealed interface NodeExprOutcome {
    data class Nodes(val nodes: List<RdfTerm>) : NodeExprOutcome
    /**
     * [cause] is the error behind the failure, such as the provider's SPARQL error, when there is one. [undecided] is
     * true for a failure of a conformance check the engine could not decide ([UndecidedConformanceException]), which
     * no function absorbs.
     */
    data class Failure(val reason: String, val cause: Throwable? = null, val undecided: Boolean = false) : NodeExprOutcome
}

/**
 * The validation failure of a conformance check of a node expression that the engine could not decide: a final
 * undefined answer (R16), a validation error, an ill-formed or unsupported candidate shape (R8). A deliberate deviation
 * from Node Expressions §3.3 / §5 ("fail closed everywhere"): it is no SPARQL error that `sparql:coalesce`, the
 * unselected branch of `sparql:if`, `sparql:logical-or` or a SPARQL query calling a function body could absorb, but
 * stops validation wherever the expression is evaluated. A provisional answer of the recursion solver is no such
 * failure ([ProvisionalUndefined]).
 */
internal class UndecidedConformanceException(message: String, cause: Throwable? = null) : ShaclValidationException(message, cause)

/**
 * The evaluation context of a node expression besides the focus graph: the focus node (null when there is none), the
 * scope variables, the arguments of the custom function being evaluated, and the evaluation nesting [depth]. The
 * arguments of a custom list parameter function (§6.2) are nodes by position ([args]; null for an unbound optional
 * argument of a SPARQL call); those of a custom named parameter function (§6.1) are the argument node expressions by
 * parameter `sh:path` ([namedArgs]).
 */
internal class NodeExprScope(
    val focus: RdfTerm?,
    val variables: Map<String, RdfTerm>,
    val args: List<RdfTerm?>,
    val depth: Int,
    val namedArgs: Map<Iri, NodeExpression> = emptyMap(),
) {
    fun nested(focus: RdfTerm? = this.focus): NodeExprScope = NodeExprScope(focus, variables, args, depth + 1, namedArgs)
}
