package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.LiteralValidation
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.sparql.engine.binding.Binding
import org.apache.jena.sparql.expr.E_Function
import org.apache.jena.sparql.expr.E_StrDatatype
import org.apache.jena.sparql.expr.Expr
import org.apache.jena.sparql.expr.ExprFunction2
import org.apache.jena.sparql.expr.ExprFunctionN
import org.apache.jena.sparql.expr.ExprList
import org.apache.jena.sparql.expr.ExprTransformCopy
import org.apache.jena.sparql.expr.NodeValue
import org.apache.jena.sparql.function.FunctionEnv
import org.apache.jena.sparql.syntax.ElementService
import org.apache.jena.sparql.syntax.Element
import org.apache.jena.sparql.syntax.syntaxtransform.ElementTransformCopyBase
import org.apache.jena.sparql.syntax.syntaxtransform.UpdateTransformOps
import org.apache.jena.update.UpdateException
import org.apache.jena.update.UpdateRequest

/**
 * Bounds the numeric and temporal literals a SPARQL UPDATE **computes** in its `WHERE` clause: `STRDT(?text,
 * xsd:integer)` and the XSD casts (`xsd:integer(?text)`, `xsd:dateTime(?text)`, ...) make Jena compute the value of the
 * literal they build, in time quadratic in its length (1.5 s for 160,000 digits, about 100 s for 1,300,000), before
 * anything is written; the text of the update ([JenaLiteralBounds.requireBoundedQuery]) does not hold that literal.
 * [bounded] makes each of them refuse a lexical form that [LiteralValidation.numericLengthProblem] refuses, before Jena
 * builds the literal, also inside a `FILTER` (reported as `RdfQueryException`, as the RDF4J stores refuse the same
 * write). What the update then writes is checked again by [JenaLiteralBounds.boundedWrites].
 *
 * The expressions of a `SERVICE` pattern run on the remote endpoint and are left as they are.
 */
internal object JenaUpdateBounds {
    /** False only when [text] certainly has no `WHERE` clause, so no expression to bound (a cheap look at the text). */
    fun mayCompute(text: String): Boolean = text.contains("WHERE", ignoreCase = true)

    /** [request] with every `STRDT` and XSD cast of its `WHERE` clauses bounded. */
    fun bounded(request: UpdateRequest): UpdateRequest = UpdateTransformOps.transform(request, LocalPatterns, Exprs)

    private object LocalPatterns : ElementTransformCopyBase() {
        override fun transform(el: ElementService, service: org.apache.jena.graph.Node, subElt: Element): Element = el
    }

    private object Exprs : ExprTransformCopy() {
        override fun transform(func: ExprFunction2, expr1: Expr, expr2: Expr): Expr =
            if (func is E_StrDatatype && func !is BoundedStrDatatype) BoundedStrDatatype(expr1, expr2)
            else super.transform(func, expr1, expr2)

        override fun transform(func: ExprFunctionN, args: ExprList): Expr {
            val datatype = (func as? E_Function)?.takeIf { it !is BoundedCast && args.size() == 1 }?.functionIRI?.let(::xsdDatatype)
            return if (datatype != null) BoundedCast(datatype, args) else super.transform(func, args)
        }
    }

    private fun xsdDatatype(iri: String): XSDDatatype? =
        if (iri.startsWith(XSDDatatype.XSD)) org.apache.jena.datatypes.TypeMapper.getInstance().getSafeTypeByName(iri) as? XSDDatatype else null

    /** Fails with [UpdateException] when [value], as a literal of [datatype], is one Kastor refuses for its length. */
    private fun requireBounded(value: NodeValue, datatype: String) {
        val node = value.asNode()
        if (!node.isLiteral) return
        LiteralValidation.numericLengthProblem(datatype, node.literalLexicalForm)?.let { throw LiteralTooLong(UpdateException(it)) }
    }

    /**
     * The refusal, as a cancellation: the one exception a `FILTER` lets through (it turns every other one into
     * `false`, so the refusal would become a silent non-match), as for the sandbox's limits.
     */
    private class LiteralTooLong(cause: UpdateException) : org.apache.jena.query.QueryCancelledException(cause) {
        override val message: String? get() = cause?.message
    }

    /** `STRDT`, refusing an over-long lexical form for a bounded datatype before it builds the literal. */
    private class BoundedStrDatatype(expr1: Expr, expr2: Expr) : E_StrDatatype(expr1, expr2) {
        override fun eval(v1: NodeValue, v2: NodeValue): NodeValue {
            if (v2.isIRI) requireBounded(v1, v2.asNode().uri)
            return super.eval(v1, v2)
        }

        override fun copy(e1: Expr, e2: Expr): Expr = BoundedStrDatatype(e1, e2)
    }

    /** An XSD cast (`xsd:integer(?x)`), evaluated as Jena's cast does, after the same check. */
    private class BoundedCast(private val datatype: XSDDatatype, args: ExprList) : E_Function(datatype.uri, args) {
        override fun evalSpecial(binding: Binding, env: FunctionEnv): NodeValue {
            val value = args[0].eval(binding, env)
            requireBounded(value, datatype.uri)
            return org.apache.jena.sparql.function.CastXSD.cast(value, datatype)
        }

        override fun copy(newArgs: ExprList): Expr = BoundedCast(datatype, newArgs)
    }
}
