package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.native.ConstraintInstantiation
import com.geoknoesis.kastor.rdf.shacl.native.DynamicParameters
import com.geoknoesis.kastor.rdf.shacl.native.NodeExpression
import com.geoknoesis.kastor.rdf.shacl.native.ParameterShape
import com.geoknoesis.kastor.rdf.shacl.native.ParameterValue
import com.geoknoesis.kastor.rdf.shacl.native.PropertyConstraint
import com.geoknoesis.kastor.rdf.shacl.native.SparqlQueryTemplate
import java.util.IdentityHashMap

/** The most constraint instances a run keeps of its [PropertyConstraint.Dynamic] constraints ([DynamicInstanceCache]). */
internal const val DYNAMIC_INSTANCE_CACHE_MAX = 4096

/**
 * The constant constraints a run instantiated from its [PropertyConstraint.Dynamic] constraints, per constraint (by
 * identity) and resolved parameter values, so that one instance, and what the run caches per instance (pattern
 * outcomes, say), serves every focus node with the same values. Past [max] entries, instances are built but not stored;
 * entries are never evicted.
 */
internal class DynamicInstanceCache(private val max: Int = DYNAMIC_INSTANCE_CACHE_MAX) {
    private val instances = IdentityHashMap<PropertyConstraint.Dynamic, HashMap<List<List<RdfTerm>>, Instance>>()

    /** A cached instance; [constraint] is null for "no constraint". */
    private class Instance(val constraint: PropertyConstraint?)

    var size: Int = 0
        private set

    fun getOrPut(constraint: PropertyConstraint.Dynamic, values: List<List<RdfTerm>>, build: () -> PropertyConstraint?): PropertyConstraint? {
        instances[constraint]?.get(values)?.let { return it.constraint }
        val built = build()
        if (size < max) {
            instances.getOrPut(constraint) { HashMap() }[values] = Instance(built)
            size++
        }
        return built
    }
}

/**
 * `evalExpr(expr, data graph, focus, {})` of a computed constraint parameter (SHACL 1.2 Node Expressions §8): the focus
 * node of the shape, never a value node, and an empty scope, at the shape nesting [depth]. A failure is a validation
 * failure; outputs are bounded like those of every node expression.
 */
internal fun NativeShaclValidator.evaluateComputed(expr: NodeExpression, focus: RdfTerm, ctx: NativeValidationContext, depth: Int): List<RdfTerm> =
    evalNodeExpression(expr, ctx, focus, emptyMap(), shapeDepth = depth)

/**
 * The constant constraint [c] stands for at [focus], of [shape]: each computed parameter is evaluated ([evaluateComputed])
 * and its outputs checked, by number and type, against the parameter; then the constraint is instantiated from the
 * values ([ConstraintInstantiation.instantiate]), or taken from the run's [NativeValidationContext.dynamicInstances].
 * Null when the values make no constraint. Any output that breaks the parameter's rules is a validation failure
 * ([ShaclValidationException]).
 */
internal fun NativeShaclValidator.resolveDynamic(
    c: PropertyConstraint.Dynamic,
    focus: RdfTerm,
    ctx: NativeValidationContext,
    depth: Int,
    shape: RdfResource,
): PropertyConstraint? {
    val values = c.parameters.map { (parameter, value) ->
        when (value) {
            is ParameterValue.Constant -> listOf(value.term)
            is ParameterValue.Computed -> checkedOutputs(parameter, evaluateComputed(value.expr, focus, ctx, depth), focus, shape)
        }
    }
    ctx.budget.tick("dynamic constraint")
    return ctx.dynamicInstances.getOrPut(c, values) {
        try {
            ConstraintInstantiation.instantiate(c.component, c.parameters.keys.zip(values).toMap(), c.extras, config)
        } catch (e: ShapeCompileException) {
            throw ShaclValidationException("Dynamic SHACL: ${e.message} (shape ${shape.displayId()}, focus node $focus)", e)
        }
    }
}

/** The failure of the computed [parameter] of [shape] at [focus]: its outputs break the parameter's rules (D6). */
internal fun dynamicParameterFailure(parameter: Iri, shape: RdfResource, focus: RdfTerm, problem: String): Nothing =
    throw ShaclValidationException("Dynamic SHACL: ${DynamicParameters.displayName(parameter)} of shape ${shape.displayId()} $problem, for focus node $focus")

/** [outputs] of the computed [parameter], when their number and every node fit it (D6); otherwise a failure. */
internal fun checkedOutputs(parameter: Iri, outputs: List<RdfTerm>, focus: RdfTerm, shape: RdfResource): List<RdfTerm> {
    val name = DynamicParameters.displayName(parameter)
    fun fail(problem: String): Nothing = dynamicParameterFailure(parameter, shape, focus, problem)
    when (DynamicParameters.shapeOf(parameter)) {
        ParameterShape.SINGLE -> when (outputs.size) {
            1 -> Unit
            0 -> fail("produced no nodes; exactly one is required")
            else -> fail("produced ${outputs.size} nodes; exactly one is required")
        }
        ParameterShape.IRI_OR_LIST -> if (outputs.isEmpty()) fail("produced no nodes; at least one is required")
        ParameterShape.LIST -> Unit
        null -> error("$name is not a dynamic parameter")
    }
    for (node in outputs) {
        ConstraintInstantiation.outputError(parameter, node, outputs.size)?.let { fail("produced the invalid value $node: $it") }
    }
    return outputs
}

/**
 * The values of the computed parameters of the SPARQL-based constraint component use [c] at [focus], of [shape], by
 * name (SHACL 1.2 Node Expressions §8): each expression is evaluated once ([evaluateComputed]) and must produce exactly
 * one node (D6), which is no blank node (the declaration rules them out, D10), is allowed by the triple-term setting,
 * and matches the declaration's constant `sh:nodeKind` and `sh:datatype`. Anything else is a validation failure. The
 * values are then pre-bound, rendered into the query text like those of `$this` and `$value`.
 */
internal fun NativeShaclValidator.resolveComponentParameters(
    c: PropertyConstraint.SparqlComponent,
    focus: RdfTerm,
    ctx: NativeValidationContext,
    depth: Int,
    shape: RdfResource,
): Map<String, RdfTerm> {
    if (c.computed.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, RdfTerm>()
    for ((name, parameter) in c.computed) {
        fun fail(problem: String): Nothing = dynamicParameterFailure(parameter.path, shape, focus, problem)
        val outputs = evaluateComputed(parameter.expr, focus, ctx, depth)
        when (outputs.size) {
            1 -> Unit
            0 -> fail("produced no nodes; exactly one is required")
            else -> fail("produced ${outputs.size} nodes; exactly one is required")
        }
        val value = outputs.single()
        val problem = when {
            value is BlankNode -> "a blank node, which the declaration ${parameter.declaration} rules out"
            value is TripleTerm && !config.allowTripleTermsInShapeParameters ->
                "a triple term, which requires ValidationConfig.allowTripleTermsInShapeParameters=true"
            // Iri() already refuses these characters; kept so no provider is ever handed an IRI SPARQL cannot write.
            value is Iri && SparqlQueryTemplate.renderIri(value.value) == null ->
                "an IRI that cannot be written in a SPARQL query"
            else ->
                parameter.nodeKinds.firstOrNull { kinds -> kinds.none { matchesNodeKind(value, it) } }
                    ?.let { kinds -> "not of node kind ${kinds.joinToString { it.value }}, as the declaration ${parameter.declaration} requires" }
                    ?: parameter.datatypes.firstOrNull { !literalMatchesShaclDatatypes(value, it) }
                        ?.let { types -> "not of datatype ${types.joinToString { it.value }}, as the declaration ${parameter.declaration} requires" }
        }
        if (problem != null) fail("produced the invalid value $value: $problem")
        out[name] = value
    }
    ctx.budget.tick("dynamic constraint")
    return out
}
