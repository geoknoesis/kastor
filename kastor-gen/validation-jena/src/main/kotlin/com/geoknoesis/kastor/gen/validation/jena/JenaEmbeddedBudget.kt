package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.EmbeddedShapesBounds
import com.geoknoesis.kastor.gen.runtime.EmbeddedShapesRefusedException
import com.geoknoesis.kastor.gen.runtime.KastorGenInternalApi
import org.apache.jena.graph.Graph
import org.apache.jena.graph.Node
import org.apache.jena.graph.Triple
import org.apache.jena.graph.impl.WrappedGraph
import org.apache.jena.shacl.Shapes
import org.apache.jena.shacl.ValidationReport
import org.apache.jena.shacl.engine.ValidationContext
import org.apache.jena.shacl.validation.VLib
import org.apache.jena.shacl.validation.ValidationListener
import org.apache.jena.util.iterator.ExtendedIterator

/**
 * The bounds `JenaValidation` puts on shapes embedded in the data graph, which are untrusted: the structural bound of
 * [EmbeddedShapesBounds] before the engine runs, and a step budget of [MAX_STEPS] while it validates one focus node.
 *
 * The structural bound limits what the shapes cost per value node; the budget limits how many value nodes the data
 * makes them visit (`jena-shacl` evaluates a nested shape once per value node and per reference, so a data graph
 * whose nodes each have `k` values makes `d` nested property shapes cost `k^d`). One step is charged for each
 * validation event of the engine (one per shape and constraint evaluated, nested evaluations included), for each
 * lookup in the data graph and for each statement a lookup returns. The count is deterministic: it does not depend
 * on timing or on the machine.
 */
@OptIn(KastorGenInternalApi::class)
internal object JenaEmbeddedBudget {
    /** Steps (see the object documentation) one validation of a focus node against embedded shapes may take. */
    const val MAX_STEPS: Long = 500_000

    /** Throws [EmbeddedShapesRefusedException] when the shapes in [graph] exceed a structural cap. */
    fun requireBoundedShapes(graph: Graph) {
        val it = graph.find()
        try {
            EmbeddedShapesBounds.requireWithin(it.asSequence().map { t -> Triple(key(t.subject), key(t.predicate), key(t.`object`)) }, "JenaValidation")
        } finally {
            it.close()
        }
    }

    private fun key(node: Node): String = when {
        node.isURI -> node.uri
        node.isBlank -> "_:" + node.blankNodeLabel
        // No IRI starts with a quote or a space: literals and triple terms never collide with a node key.
        node.isLiteral -> "\"" + node.literalLexicalForm
        else -> " $node"
    }

    /**
     * Validates [focus] against [shapes] like `ShaclValidator.validate(shapes, data, focus)` (the shapes that target
     * [focus], with the engine's own procedure), charging the work to a budget of [maxSteps] steps.
     *
     * @throws EmbeddedShapesRefusedException when the budget runs out
     */
    fun validate(shapes: Shapes, data: Graph, focus: Node, maxSteps: Long = MAX_STEPS): ValidationReport {
        val budget = Budget(maxSteps)
        val graph = BudgetedGraph(data, budget)
        val context = ValidationContext.create(shapes, graph, ValidationListener { budget.charge() })
        for (shape in shapes.targetShapes) {
            if (VLib.isFocusNode(shape, focus, graph)) VLib.validateShape(context, graph, shape, focus)
        }
        return context.generateReport()
    }

    private class Budget(private val maxSteps: Long) {
        private var steps = 0L

        fun charge() {
            if (++steps > maxSteps) {
                throw EmbeddedShapesRefusedException(
                    "JenaValidation stopped validating against the SHACL shapes embedded in the data graph after " +
                        "$maxSteps steps (shape and constraint evaluations, data lookups and the statements they " +
                        "return): with the data of this graph, the shapes cost more than embedded shapes may. " +
                        "Embedded shapes come from the data and are untrusted: pass the shapes to the constructor, " +
                        "validate with the Kastor SHACL validator, or, only when the data graph is trusted, create " +
                        "the validator with JenaValidation.trustingEmbeddedShapes().",
                )
            }
        }
    }

    /** [base], read only through lookups that are charged to [budget]. */
    private class BudgetedGraph(base: Graph, private val budget: Budget) : WrappedGraph(base) {
        override fun find(triple: Triple): ExtendedIterator<Triple> = charged(base.find(triple))

        override fun find(s: Node?, p: Node?, o: Node?): ExtendedIterator<Triple> = charged(base.find(s, p, o))

        override fun contains(s: Node?, p: Node?, o: Node?): Boolean {
            budget.charge()
            return base.contains(s, p, o)
        }

        override fun contains(t: Triple): Boolean {
            budget.charge()
            return base.contains(t)
        }

        private fun charged(found: ExtendedIterator<Triple>): ExtendedIterator<Triple> {
            budget.charge()
            return found.mapWith { triple -> budget.charge(); triple }
        }
    }
}
