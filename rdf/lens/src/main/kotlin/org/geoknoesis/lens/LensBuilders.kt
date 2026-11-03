package org.geoknoesis.lens

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal as RdfLiteral
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Type-safe builders for [Lens] and [NetworkLens] definitions.
 *
 * ```kotlin
 * val result = lens {
 *     type("urn:ex:Company")
 *     where {
 *         sequence("urn:ex:locatedIn", "urn:ex:regionName") eq "US"
 *         not { path("urn:ex:age") lt 18 }
 *     }
 *     exclude { type("urn:ex:Restricted") }
 * }
 * ```
 *
 * A [lens] block is the intersection of its selectors; `has` and `where` then
 * restrict it, and each `exclude` subtracts from it. The builders only produce
 * the existing model: the compilers stay the single source of SPARQL semantics.
 */
@DslMarker
annotation class LensDslMarker

fun lens(block: LensBuilder.() -> Unit): Lens = LensBuilder().apply(block).build()

fun network(block: NetworkLensBuilder.() -> Unit): NetworkLens = NetworkLensBuilder().apply(block).build()

/** Operands shared by intersection and union scopes. */
@LensDslMarker
sealed class LensOperands {
    protected val parts = ArrayList<Lens>()

    fun type(classIri: String) { parts.add(Lens.Type(classIri)) }
    fun type(classIri: Iri) = type(classIri.value)

    /** Embed a lens built elsewhere, e.g. one shared by several definitions. */
    fun include(lens: Lens) { parts.add(lens) }

    fun allOf(block: LensBuilder.() -> Unit) { parts.add(lens(block)) }

    fun anyOf(block: UnionBuilder.() -> Unit) { parts.add(UnionBuilder().apply(block).build()) }
}

class UnionBuilder internal constructor() : LensOperands() {
    internal fun build(): Lens {
        require(parts.isNotEmpty()) { "anyOf requires at least one operand" }
        return if (parts.size == 1) parts.single() else Lens.Union(parts.toList())
    }
}

class LensBuilder internal constructor() : LensOperands() {
    private val restrictions = ArrayList<(Lens) -> Lens>()
    private val excluded = ArrayList<Lens>()

    /** Nodes with this exact property value (a triple pattern, not a value comparison). */
    fun has(property: String, value: Value) { restrictions.add { Lens.Equals(it, property, value) } }
    fun has(property: String, value: RdfTerm) = has(property, value.toLensValue())
    fun has(property: Iri, value: RdfTerm) = has(property.value, value.toLensValue())

    /** Nodes with at least one [property] value selected by the [target] lens. */
    fun has(property: String, target: LensBuilder.() -> Unit) {
        val selected = lens(target)
        restrictions.add { Lens.Exists(it, property, selected) }
    }
    fun has(property: Iri, target: LensBuilder.() -> Unit) = has(property.value, target)

    /** Typed filter; several conditions in one block must all hold. */
    fun where(block: ConditionBuilder.() -> Unit) {
        val condition = ConditionBuilder().apply(block).all()
        restrictions.add { Lens.Filter(it, condition) }
    }

    /** Subtract the nodes selected by [block]; scoped to this lens, never open-world. */
    fun exclude(block: LensBuilder.() -> Unit) { excluded.add(lens(block)) }

    internal fun build(): Lens {
        require(parts.isNotEmpty()) {
            "A lens needs at least one selector (type, include, allOf or anyOf); there is no universe lens"
        }
        var result = if (parts.size == 1) parts.single() else Lens.Intersect(parts.toList())
        for (restrict in restrictions) result = restrict(result)
        for (right in excluded) result = Lens.Difference(result, right)
        return result
    }
}

/** Builds a [BooleanPredicate]; each statement is one clause of the enclosing group. */
@LensDslMarker
class ConditionBuilder internal constructor() {
    private val clauses = ArrayList<BooleanPredicate>()

    fun path(predicate: String): ValueExpression = ValueExpression.Path(predicate)
    fun path(predicate: Iri): ValueExpression = path(predicate.value)
    fun sequence(vararg predicates: String): ValueExpression = ValueExpression.SequencePath(predicates.toList())
    fun inverse(predicate: String): ValueExpression = ValueExpression.InversePath(predicate)
    fun alternative(vararg predicates: String): ValueExpression = ValueExpression.AlternativePath(predicates.toList())
    fun iri(value: String): ValueExpression = ValueExpression.Constant(Value.Iri(value))
    fun literal(lexical: String, datatype: String): ValueExpression =
        ValueExpression.Constant(Value.TypedLiteral(lexical, datatype))

    infix fun ValueExpression.eq(right: ValueExpression) = compare(this, CompareOperator.EQ, right)
    infix fun ValueExpression.eq(right: String) = eq(constant(right))
    infix fun ValueExpression.eq(right: Number) = eq(constant(right))
    infix fun ValueExpression.eq(right: Boolean) = eq(constant(right))
    infix fun ValueExpression.eq(right: RdfTerm) = eq(constant(right))

    infix fun ValueExpression.ne(right: ValueExpression) = compare(this, CompareOperator.NE, right)
    infix fun ValueExpression.ne(right: String) = ne(constant(right))
    infix fun ValueExpression.ne(right: Number) = ne(constant(right))
    infix fun ValueExpression.ne(right: Boolean) = ne(constant(right))
    infix fun ValueExpression.ne(right: RdfTerm) = ne(constant(right))

    infix fun ValueExpression.lt(right: ValueExpression) = compare(this, CompareOperator.LT, right)
    infix fun ValueExpression.lt(right: String) = lt(constant(right))
    infix fun ValueExpression.lt(right: Number) = lt(constant(right))
    infix fun ValueExpression.lt(right: RdfTerm) = lt(constant(right))

    infix fun ValueExpression.le(right: ValueExpression) = compare(this, CompareOperator.LE, right)
    infix fun ValueExpression.le(right: String) = le(constant(right))
    infix fun ValueExpression.le(right: Number) = le(constant(right))
    infix fun ValueExpression.le(right: RdfTerm) = le(constant(right))

    infix fun ValueExpression.gt(right: ValueExpression) = compare(this, CompareOperator.GT, right)
    infix fun ValueExpression.gt(right: String) = gt(constant(right))
    infix fun ValueExpression.gt(right: Number) = gt(constant(right))
    infix fun ValueExpression.gt(right: RdfTerm) = gt(constant(right))

    infix fun ValueExpression.ge(right: ValueExpression) = compare(this, CompareOperator.GE, right)
    infix fun ValueExpression.ge(right: String) = ge(constant(right))
    infix fun ValueExpression.ge(right: Number) = ge(constant(right))
    infix fun ValueExpression.ge(right: RdfTerm) = ge(constant(right))

    fun allOf(block: ConditionBuilder.() -> Unit) { clauses.add(ConditionBuilder().apply(block).all()) }
    fun anyOf(block: ConditionBuilder.() -> Unit) { clauses.add(ConditionBuilder().apply(block).any()) }

    /** Negates the conjunction of the block; missing values make NOT(comparison) true. */
    fun not(block: ConditionBuilder.() -> Unit) { clauses.add(BooleanPredicate.Not(ConditionBuilder().apply(block).all())) }

    /** Add a predicate built elsewhere. */
    fun include(predicate: BooleanPredicate) { clauses.add(predicate) }

    private fun compare(left: ValueExpression, operator: CompareOperator, right: ValueExpression) {
        if (operator != CompareOperator.EQ && operator != CompareOperator.NE) {
            require(listOf(left, right).none { it is ValueExpression.Constant && it.value is Value.Iri }) {
                "Ordered comparisons cannot use IRI constants"
            }
        }
        clauses.add(BooleanPredicate.Compare(left, operator, right))
    }

    internal fun all(): BooleanPredicate {
        require(clauses.isNotEmpty()) { "A condition block needs at least one clause" }
        return if (clauses.size == 1) clauses.single() else BooleanPredicate.All(clauses.toList())
    }

    internal fun any(): BooleanPredicate {
        require(clauses.isNotEmpty()) { "anyOf requires at least one clause" }
        return if (clauses.size == 1) clauses.single() else BooleanPredicate.Any(clauses.toList())
    }
}

@LensDslMarker
class NetworkLensBuilder internal constructor() {
    var predicate: String? = null
    var maxDepth: Int? = null
    var direction: NetworkDirection = NetworkDirection.OUT
    var closure: NetworkClosure = NetworkClosure.TRAVERSED
    var datasetGraph: String? = null

    private var seeds: Lens? = null
    private var traversalNode: Lens? = null
    private var outputNode: Lens? = null
    private val traversalEdge = ArrayList<EdgeCondition>()
    private val outputEdge = ArrayList<EdgeCondition>()

    fun seeds(block: LensBuilder.() -> Unit) { seeds = lens(block) }

    /** Nodes the walk may pass through (seeds included). */
    fun traversalNode(block: LensBuilder.() -> Unit) { traversalNode = lens(block) }

    /** Nodes kept in the final graph view; traversal is unaffected. */
    fun outputNode(block: LensBuilder.() -> Unit) { outputNode = lens(block) }

    /** Reification constraint every traversed edge statement must satisfy. */
    fun traversalEdge(property: String, value: Value) { traversalEdge.add(EdgeCondition(property, value)) }
    fun traversalEdge(property: String, value: RdfTerm) = traversalEdge(property, value.toLensValue())

    /** Reification constraint on edges emitted in the result. */
    fun outputEdge(property: String, value: Value) { outputEdge.add(EdgeCondition(property, value)) }
    fun outputEdge(property: String, value: RdfTerm) = outputEdge(property, value.toLensValue())

    internal fun build(): NetworkLens = NetworkLens(
        seeds = requireNotNull(seeds) { "A network lens needs seeds { }" },
        predicate = requireNotNull(predicate) { "A network lens needs a predicate" },
        maxDepth = requireNotNull(maxDepth) { "A network lens needs maxDepth" },
        direction = direction,
        traversalNode = traversalNode,
        outputNode = outputNode,
        traversalEdge = traversalEdge.toList(),
        outputEdge = outputEdge.toList(),
        closure = closure,
        datasetGraph = datasetGraph
    )
}

private const val XSD = "http://www.w3.org/2001/XMLSchema#"

private fun constant(value: String) = ValueExpression.Constant(Value.TypedLiteral(value, XSD + "string"))
private fun constant(value: Boolean) = ValueExpression.Constant(Value.TypedLiteral(value.toString(), XSD + "boolean"))
private fun constant(value: RdfTerm) = ValueExpression.Constant(value.toLensValue())
private fun constant(value: Number): ValueExpression {
    val datatype = when (value) {
        is Int, is Long, is Short, is Byte, is BigInteger -> "integer"
        is BigDecimal -> "decimal"
        is Double -> "double"
        is Float -> "float"
        else -> throw IllegalArgumentException("Unsupported numeric constant type: ${value::class}")
    }
    val lexical = if (value is BigDecimal) value.toPlainString() else value.toString()
    return ValueExpression.Constant(Value.TypedLiteral(lexical, XSD + datatype))
}

private fun RdfTerm.toLensValue(): Value = when (this) {
    is Iri -> Value.Iri(value)
    is RdfLiteral -> Value.TypedLiteral(lexical, datatype.value)
    else -> throw IllegalArgumentException("Lens constants must be IRIs or literals, not $this")
}

/** Build a definitions graph readable by [LensRdfLoader] and the bundled SHACL profile. */
fun lensDefinitions(block: LensRdfWriter.() -> Unit): RdfGraph = LensRdfWriter().apply(block).graph

/**
 * Writes lenses as Semantic Lens v0.2 RDF, the strict profile [LensRdfLoader] reads.
 *
 * Nested lenses, expressions and lists become blank nodes. Model features with no
 * RDF form in that profile (Equals, Exists, output node/edge guards, traversal edge
 * guards, dataset graphs) are rejected rather than silently dropped.
 */
@LensDslMarker
class LensRdfWriter {
    private val ns = "https://example.org/semantic-lens/1.0#"
    private val rdfNs = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val out: MutableRdfGraph = Rdf.graph { }
    private var serial = 0

    val graph: RdfGraph get() = out

    fun define(id: String, lens: Lens) = define(Iri(id), lens)
    fun define(id: Iri, lens: Lens) { node(id, lens) }
    fun define(id: String, block: LensBuilder.() -> Unit) = define(Iri(id), org.geoknoesis.lens.lens(block))

    fun define(id: String, network: NetworkLens) = define(Iri(id), network)
    fun define(id: Iri, network: NetworkLens) { writeNetwork(id, network) }
    fun network(id: String, block: NetworkLensBuilder.() -> Unit) = define(Iri(id), org.geoknoesis.lens.network(block))

    private fun term(local: String) = Iri(ns + local)
    private fun rdf(local: String) = Iri(rdfNs + local)
    private fun fresh(): BlankNode = BlankNode("lens" + serial++)
    private fun add(s: RdfResource, p: Iri, o: RdfTerm) = out.addTriple(RdfTriple(s, p, o))
    private fun typed(s: RdfResource, local: String) = add(s, rdf("type"), term(local))

    private fun list(items: List<RdfResource>): RdfResource {
        var rest: RdfResource = rdf("nil")
        for (item in items.asReversed()) {
            val cell = fresh()
            add(cell, rdf("first"), item)
            add(cell, rdf("rest"), rest)
            rest = cell
        }
        return rest
    }

    private fun node(id: RdfResource, lens: Lens) {
        when (lens) {
            is Lens.Type -> {
                typed(id, "ClassLens")
                add(id, term("selectClass"), Iri(lens.classIri))
            }
            is Lens.Intersect, is Lens.Union -> {
                val parts = if (lens is Lens.Intersect) lens.parts else (lens as Lens.Union).parts
                typed(id, if (lens is Lens.Intersect) "IntersectionLens" else "UnionLens")
                add(id, term("operands"), list(parts.map { child(it) }))
            }
            is Lens.Difference -> {
                typed(id, "DifferenceLens")
                add(id, term("leftOperand"), child(lens.left))
                add(id, term("rightOperand"), child(lens.right))
            }
            is Lens.Filter -> {
                typed(id, "FilterLens")
                add(id, term("inputLens"), child(lens.input))
                add(id, term("condition"), condition(lens.condition))
            }
            is Lens.Equals, is Lens.Exists -> throw IllegalArgumentException(
                "The RDF v0.2 profile has no form for has(...); use where { path(p) eq value } instead"
            )
        }
    }

    private fun child(lens: Lens): RdfResource = fresh().also { node(it, lens) }

    private fun condition(p: BooleanPredicate): RdfResource {
        val id = fresh()
        when (p) {
            is BooleanPredicate.Compare -> {
                typed(id, "ComparisonExpression")
                add(id, term("operator"), term(when (p.operator) {
                    CompareOperator.EQ -> "Equal"
                    CompareOperator.NE -> "NotEqual"
                    CompareOperator.LT -> "LessThan"
                    CompareOperator.LE -> "LessOrEqual"
                    CompareOperator.GT -> "GreaterThan"
                    CompareOperator.GE -> "GreaterOrEqual"
                }))
                add(id, term("leftExpression"), expression(p.left))
                add(id, term("rightExpression"), expression(p.right))
            }
            is BooleanPredicate.All -> group(id, "And", p.clauses)
            is BooleanPredicate.Any -> group(id, "Or", p.clauses)
            is BooleanPredicate.Not -> group(id, "Not", listOf(p.inner))
        }
        return id
    }

    private fun group(id: RdfResource, operator: String, children: List<BooleanPredicate>) {
        typed(id, "BooleanExpression")
        add(id, term("operator"), term(operator))
        add(id, term("operands"), list(children.map { condition(it) }))
    }

    private fun expression(e: ValueExpression): RdfResource {
        val id = fresh()
        when (e) {
            is ValueExpression.Path -> {
                typed(id, "BoundPathExpression")
                add(id, term("pathPredicate"), Iri(e.predicate))
            }
            is ValueExpression.InversePath -> {
                typed(id, "InversePathExpression")
                add(id, term("pathPredicate"), Iri(e.predicate))
            }
            is ValueExpression.SequencePath -> {
                typed(id, "SequencePathExpression")
                add(id, term("pathPredicates"), list(e.predicates.map { Iri(it) }))
            }
            is ValueExpression.AlternativePath -> {
                typed(id, "AlternativePathExpression")
                add(id, term("pathPredicates"), list(e.predicates.map { Iri(it) }))
            }
            is ValueExpression.Constant -> {
                typed(id, "ConstantExpression")
                when (val v = e.value) {
                    is Value.Iri -> add(id, term("constantIri"), Iri(v.value))
                    is Value.TypedLiteral -> add(id, term("literalValue"), RdfLiteral(v.lexical, Iri(v.datatype)))
                    is Value.Literal -> add(id, term("literalValue"), RdfLiteral(v.lexical))
                }
            }
        }
        return id
    }

    private fun writeNetwork(id: RdfResource, n: NetworkLens) {
        val unsupported = listOfNotNull(
            "outputNode".takeIf { n.outputNode != null },
            "traversalEdge".takeIf { n.traversalEdge.isNotEmpty() },
            "outputEdge".takeIf { n.outputEdge.isNotEmpty() },
            "datasetGraph".takeIf { n.datasetGraph != null }
        )
        require(unsupported.isEmpty()) {
            "The RDF v0.2 network profile cannot express $unsupported; compile this lens directly instead"
        }
        typed(id, "NetworkLens")
        add(id, term("focusLens"), child(n.seeds))
        n.traversalNode?.let { add(id, term("nodeLens"), child(it)) }
        add(id, term("predicate"), Iri(n.predicate))
        add(id, term("maxDepth"), RdfLiteral(n.maxDepth.toString(), Iri(XSD + "integer")))
        add(id, term("direction"), term(when (n.direction) {
            NetworkDirection.OUT -> "Outgoing"
            NetworkDirection.IN -> "Incoming"
            NetworkDirection.BOTH -> "Both"
        }))
        add(id, term("closureMode"), term(when (n.closure) {
            NetworkClosure.TRAVERSED -> "Traversed"
            NetworkClosure.INDUCED -> "Induced"
        }))
    }
}
