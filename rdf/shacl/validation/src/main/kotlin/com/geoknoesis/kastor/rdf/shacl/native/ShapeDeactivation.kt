package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.native.ShapesCompiler.isParameterPredicate

/** `sh:deactivated` of shapes, SPARQL-based constraints and the reifiers of parameter triples (SHACL 1.2 Core §3.1.6). */
internal object ShapeDeactivation {
    /**
     * One warning per reifier with `sh:deactivated` of a triple of one of [shapes] that is no constraint parameter
     * ([isParameterPredicate]): the spec gives it no meaning there, so it is ignored.
     */
    internal fun ineffectiveReifierDeactivations(index: ShapeGraphIndex, shapes: Collection<RdfResource>, budget: ValidationBudget): List<String> {
        val out = ArrayList<String>()
        for (shape in shapes) {
            for (p in index.predicates(shape)) {
                if (isParameterPredicate(p, index)) continue
                for (o in index.objects(shape, p)) {
                    budget.tick("shape compilation")
                    for (r in index.reifiersForClaim(RdfTriple(shape, p, o))) {
                        if (index.objects(r, SHACL.deactivated).isNotEmpty()) {
                            out.add("sh:deactivated on a reifier of non-parameter triple $shape $p $o has no effect")
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * Whether the `sh:property`, `sh:node` or `sh:nodeByExpression` triple ([subject] [predicate] [target]) declares a
     * constraint, that is, is not deactivated by the constant `true` at one of its reifiers (SHACL 1.2 Core §3.1.6). A
     * non-constant deactivation is recorded in [links].
     */
    internal fun activeLink(
        subject: RdfResource,
        predicate: Iri,
        target: RdfResource,
        index: ShapeGraphIndex,
        links: MutableMap<Pair<Iri, RdfResource>, List<NodeExpression>>,
    ): Boolean {
        val conditions = reifierDeactivation(subject, listOf(predicate to target), index) ?: return false
        if (conditions.isNotEmpty()) links[predicate to target] = conditions
        return true
    }

    /**
     * How the reifiers of the parameter triples [triples] (predicate, object) of [subject] deactivate the constraint
     * that uses them (SHACL 1.2 Core §3.1.6): null when the `sh:deactivated` of one of them is the constant `true` (the
     * constraint is deactivated for every focus node), else the node expressions of the non-constant ones, which
     * deactivate it for the focus nodes one of them produces exactly `true` for (none: the constraint is active). A
     * triple has at most one reifier with a value for `sh:deactivated`, and that reifier at most one value: more is a
     * compile error. A blank node that is no node expression is reported by feature detection and deactivates nothing.
     */
    internal fun reifierDeactivation(subject: RdfResource, triples: List<Pair<Iri, RdfTerm>>, index: ShapeGraphIndex): List<NodeExpression>? {
        // Every triple is checked before a constant true leaves the constraint out.
        val deactivating = triples.mapNotNull { (predicate, obj) ->
            val reifiers = index.reifiersForClaim(RdfTriple(subject, predicate, obj)).filter { index.objects(it, SHACL.deactivated).isNotEmpty() }
            if (reifiers.size > 1) {
                throw ShapeCompileException(
                    "The triple $subject $predicate $obj has ${reifiers.size} reifiers with sh:deactivated; it can have at most one reifier with a value for sh:deactivated",
                )
            }
            reifiers.singleOrNull()?.also { deactivatedValues(it, index) }
        }
        val out = ArrayList<NodeExpression>()
        for (reifier in deactivating) {
            if (isDeactivated(reifier, index)) return null
            deactivationExpression(reifier, index)?.let { out.add(it) }
        }
        return out
    }

    /**
     * The node expression of a non-constant `sh:deactivated` of [subject], a shape, a SPARQL-based constraint or a
     * reifier (SHACL 1.2 Core §3.1.6 and SHACL 1.2 SPARQL: at most one value), or null for none or a constant (the constant
     * `true` makes it [isDeactivated]; any other constant never produces `true`). A blank node that is no node
     * expression is reported by feature detection and gives null; an ill-formed one is a compile error, and so is a
     * second value ([deactivatedValues]).
     */
    internal fun deactivationExpression(subject: RdfResource, index: ShapeGraphIndex): NodeExpression? {
        val expression = deactivatedValues(subject, index).firstOrNull { it is BlankNode } ?: return null
        return NodeExpressions.compile(expression, index)
    }

    /** True for a shape, SPARQL-based constraint or reifier whose `sh:deactivated` is the constant `true`. */
    internal fun isDeactivated(subject: RdfResource, index: ShapeGraphIndex): Boolean =
        deactivatedValues(subject, index).any { isLexicallyTrue(it) }

    /**
     * The `sh:deactivated` values of [subject]. More than one is a compile error, checked before any of them is
     * read, so that `sh:deactivated true , [ … ]` is not taken for a deactivated shape.
     */
    private fun deactivatedValues(subject: RdfResource, index: ShapeGraphIndex): List<RdfTerm> {
        val values = index.objects(subject, SHACL.deactivated)
        if (values.size > 1) {
            throw ShapeCompileException("$subject has ${values.size} values for sh:deactivated; it can have at most one value")
        }
        return values
    }
}
