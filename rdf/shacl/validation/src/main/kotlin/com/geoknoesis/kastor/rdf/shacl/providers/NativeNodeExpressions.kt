package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression
import com.geoknoesis.kastor.rdf.shacl.native.distinctShaclTerms

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
            val outputs = ctx.select(query, bindings) { rows ->
                val out = ArrayList<RdfTerm>()
                for (row in rows) {
                    ctx.budget.tick("node expression")
                    row.get(expr.variable)?.let { out.add(it) }
                }
                out
            }
            distinctShaclTerms(outputs)
        }
    }
