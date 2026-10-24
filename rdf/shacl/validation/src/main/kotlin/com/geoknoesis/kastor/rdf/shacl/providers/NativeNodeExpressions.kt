package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression
import com.geoknoesis.kastor.rdf.shacl.native.shaclRdfTermFingerprint

/**
 * Evaluates [expr] (SHACL 1.2 SPARQL §6, `evalExpr(expr, data graph, focus, {})`) and returns its distinct output
 * nodes in solution order. A [NodeExpression.Constant] yields its term without a query. For a
 * [NodeExpression.Select], `this` is pre-bound to [focus] when it is not null (and left unbound otherwise), and solutions
 * in which the expression variable is unbound are skipped. SPARQL errors surface as `ShaclValidationException`.
 */
internal fun evalNodeExpression(expr: NodeExpression, ctx: NativeValidationContext, focus: RdfTerm?): List<RdfTerm> =
    when (expr) {
        is NodeExpression.Constant -> listOf(expr.term)
        is NodeExpression.Select -> {
            val (query, bindings) = expr.template.bind(if (focus == null) emptyMap() else mapOf("this" to focus))
            ctx.select(query, bindings) { rows ->
                // Distinct under the same equality as distinctShaclTerms, capped while streaming.
                val out = LinkedHashMap<String, RdfTerm>()
                for (row in rows) {
                    ctx.budget.tick("node expression")
                    val term = row.get(expr.variable) ?: continue
                    if (out.putIfAbsent(shaclRdfTermFingerprint(term), term) == null) {
                        checkNodeExpressionSize(out.size, ctx, "node expression evaluation")
                    }
                }
                out.values.toList()
            }
        }
        // Compilation keeps only supportedInValidation expressions at validation positions.
        else -> throw IllegalStateException("Node expression ${expr::class.simpleName} is not evaluated by validation")
    }

/** Fails when [size] output nodes exceed [com.geoknoesis.kastor.rdf.shacl.ValidationConfig.maxPathValueNodes], as path evaluation does. */
internal fun checkNodeExpressionSize(size: Int, ctx: NativeValidationContext, what: String) {
    if (size > ctx.data.maxPathValueNodes) {
        throw ShaclValidationException("SHACL $what exceeded ValidationConfig.maxPathValueNodes=${ctx.data.maxPathValueNodes}")
    }
}
