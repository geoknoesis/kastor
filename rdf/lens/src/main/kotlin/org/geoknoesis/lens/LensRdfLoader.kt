package org.geoknoesis.lens

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm

/**
 * Conservative, bounded reader for the Semantic Lens ontology v0.2 RDF representation.
 *
 * Only loads supported expressions, with strict cardinalities and RDF-list validity.
 * The source graph is supplied by the caller: imports are NEVER dereferenced.
 * Unsupported expression kinds or constraints fail closed.
 */
class LensRdfLoader(
    private val graph: RdfGraph,
    private val maxDepth: Int = 64,
    private val maxListSize: Int = 256
) {
    init { require(maxDepth in 1..1024 && maxListSize in 1..100_000) }

    private val ns = "https://example.org/semantic-lens/1.0#"
    private val rdf = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val xsd = "http://www.w3.org/2001/XMLSchema#"
    private fun term(ns: String, local: String) = Iri(ns + local)
    private fun lens(local: String) = term(ns, local)
    private fun rdf(local: String) = term(rdf, local)


    /** Unknown executable lens properties must never be silently ignored. */
    private fun rejectUnknown(ref: RdfResource, allowed: Set<String>) {
        val unsupported = graph.find(ref).map { it.predicate.value }
            .filter { it.startsWith(ns) && it.removePrefix(ns) !in allowed }
            .distinct()
        require(unsupported.isEmpty()) {
            "Unsupported lens execution properties at $ref: $unsupported"
        }
    }

    sealed interface Loaded {
        data class Node(val expression: Lens) : Loaded
        data class Network(val expression: NetworkLens) : Loaded
    }

    fun read(definition: RdfResource): Loaded {
        val types = graph.find(definition, rdf("type"), null)
            .mapNotNull { it.obj as? Iri }.toSet()
        val concrete = listOf("ClassLens", "IntersectionLens", "UnionLens",
            "DifferenceLens", "FilterLens", "NetworkLens", "EgoNetworkLens")
            .count { lens(it) in types }
        require(concrete == 1) { "Exactly one supported concrete lens type required" }
        return if (lens("NetworkLens") in types || lens("EgoNetworkLens") in types)
            Loaded.Network(network(definition, types))
        else Loaded.Node(node(definition, LinkedHashSet(), 0))
    }

    private fun objects(subject: RdfResource, predicate: Iri): List<RdfTerm> =
        graph.find(subject, predicate, null).map { it.obj }

    private fun only(subject: RdfResource, predicate: Iri, required: Boolean = true): RdfTerm? {
        val vals = objects(subject, predicate)
        require(vals.size <= 1) { "Multiple values for $predicate at $subject" }
        if (required) require(vals.isNotEmpty()) { "Missing $predicate at $subject" }
        return vals.firstOrNull()
    }

    private fun resource(subject: RdfResource, predicate: Iri, required: Boolean = true): RdfResource? {
        val v = only(subject, predicate, required) ?: return null
        return v as? RdfResource ?: error("$predicate must point to an RDF resource")
    }

    private fun iri(subject: RdfResource, predicate: Iri, required: Boolean = true): Iri? {
        val v = only(subject, predicate, required) ?: return null
        return v as? Iri ?: error("$predicate must point to an IRI")
    }

    private fun integer(subject: RdfResource, predicate: Iri, required: Boolean = true): Int? {
        val v = only(subject, predicate, required) ?: return null
        val literal = v as? Literal ?: error("$predicate must be an integer literal")
        require(literal.datatype == term(xsd, "integer")) { "$predicate must use xsd:integer" }
        return literal.lexical.toIntOrNull() ?: error("Invalid integer for $predicate")
    }

    private fun list(head: RdfResource): List<RdfResource> {
        val result = ArrayList<RdfResource>()
        val visited = HashSet<RdfResource>()
        var current = head
        val nil = rdf("nil")
        while (current != nil) {
            require(visited.add(current)) { "Cycle in RDF list" }
            require(result.size < maxListSize) { "Lens operand list too large" }
            val first = only(current, rdf("first")) as? RdfResource
                ?: error("RDF list first must identify a lens")
            result.add(first)
            current = resource(current, rdf("rest"))
                ?: error("RDF list missing rest")
        }
        return result
    }

    private fun node(ref: RdfResource, active: MutableSet<RdfResource>, depth: Int): Lens {
        require(depth < maxDepth) { "Lens definition nesting exceeds limit" }
        require(active.add(ref)) { "Cycle among lens definitions at $ref" }
        try {
            val types = objects(ref, rdf("type")).filterIsInstance<Iri>().toSet()
            val relevant = listOf("ClassLens", "IntersectionLens", "UnionLens", "DifferenceLens", "FilterLens")
                .filter { lens(it) in types }
            require(relevant.size == 1) {
                "Expected one supported node lens type at $ref; found $relevant"
            }
            rejectUnknown(ref, when (relevant.single()) {
                "ClassLens" -> setOf("selectClass")
                "FilterLens" -> setOf("inputLens", "condition")
                "IntersectionLens", "UnionLens" -> setOf("operands")
                "DifferenceLens" -> setOf("leftOperand", "rightOperand")
                else -> emptySet()
            })
            return when (relevant.single()) {
                "ClassLens" -> Lens.Type(iri(ref, lens("selectClass"))!!.value)
                "FilterLens" -> LensDsl.filter(
                    node(resource(ref, lens("inputLens"))!!, active, depth + 1),
                    boolean(resource(ref, lens("condition"))!!, LinkedHashSet(), depth + 1)
                )
                "IntersectionLens", "UnionLens" -> {
                    val root = resource(ref, lens("operands"))!!
                    val operands = list(root).map { node(it, active, depth + 1) }
                    require(operands.isNotEmpty()) { "Empty composite at $ref" }
                    if ("IntersectionLens" in relevant) LensDsl.and(*operands.toTypedArray())
                    else LensDsl.or(*operands.toTypedArray())
                }
                "DifferenceLens" -> LensDsl.minus(
                    node(resource(ref, lens("leftOperand"))!!, active, depth + 1),
                    node(resource(ref, lens("rightOperand"))!!, active, depth + 1)
                )
                else -> error("Unreachable type")
            }
        } finally {
            active.remove(ref)
        }
    }


    /**
     * Portable expression profile: ComparisonExpression and BooleanExpression
     * with explicit operator IRIs. Unknown operators fail, never become strings
     * handed to SPARQL.
     */
    private fun boolean(ref: RdfResource, active: MutableSet<RdfResource>, depth: Int): BooleanPredicate {
        require(depth < maxDepth) { "Boolean expression nesting exceeds limit" }
        require(active.add(ref)) { "Cycle in Boolean expression" }
        try {
            val types = objects(ref, rdf("type")).filterIsInstance<Iri>().toSet()
            val op = iri(ref, lens("operator"))!!.value
            if (lens("ComparisonExpression") in types) {
                rejectUnknown(ref, setOf("operator", "leftExpression", "rightExpression"))
                val operation = when(op) {
                    lens("Equal").value -> CompareOperator.EQ
                    lens("NotEqual").value -> CompareOperator.NE
                    lens("LessThan").value -> CompareOperator.LT
                    lens("LessOrEqual").value -> CompareOperator.LE
                    lens("GreaterThan").value -> CompareOperator.GT
                    lens("GreaterOrEqual").value -> CompareOperator.GE
                    else -> error("Unknown comparison operator: " + op)
                }
                require(objects(ref, lens("operands")).isEmpty()) { "Comparison cannot have extra operands" }
                return BooleanPredicate.Compare(
                    expression(resource(ref, lens("leftExpression"))!!),
                    operation,
                    expression(resource(ref, lens("rightExpression"))!!)
                )
            }
            require(lens("BooleanExpression") in types) { "Unknown Boolean expression type" }
            rejectUnknown(ref, setOf("operator", "operands"))
            require(objects(ref, lens("leftExpression")).isEmpty() &&
                objects(ref, lens("rightExpression")).isEmpty()) { "Boolean group uses operands only" }
            val listHead = resource(ref, lens("operands"))!!
            val children = list(listHead).map { boolean(it, active, depth + 1) }
            return when(op) {
                lens("And").value -> BooleanPredicate.All(children)
                lens("Or").value -> BooleanPredicate.Any(children)
                lens("Not").value -> {
                    require(children.size == 1) { "Not requires exactly one operand" }
                    BooleanPredicate.Not(children[0])
                }
                else -> error("Unknown Boolean operator: " + op)
            }
        } finally { active.remove(ref) }
    }

    private fun expression(ref: RdfResource): ValueExpression {
        val types = objects(ref, rdf("type")).filterIsInstance<Iri>().toSet()
        val relevant = listOf("BoundPathExpression", "SequencePathExpression",
            "InversePathExpression", "AlternativePathExpression", "ConstantExpression")
            .filter { lens(it) in types }
        require(relevant.size == 1) { "Expected a path or constant expression" }
        return when(relevant.single()) {
            "BoundPathExpression" -> {
                rejectUnknown(ref, setOf("pathPredicate"))
                require(objects(ref, lens("literalValue")).isEmpty() &&
                    objects(ref, lens("constantIri")).isEmpty()) { "Path expression has unexpected value" }
                ValueExpression.Path(iri(ref, lens("pathPredicate"))!!.value)
            }
            "SequencePathExpression", "AlternativePathExpression" -> {
                rejectUnknown(ref, setOf("pathPredicates"))
                val head = resource(ref, lens("pathPredicates"))!!
                val iriList = list(head).map {
                    it as? Iri ?: error("Property path member must be an IRI")
                }.map { it.value }
                require(iriList.size in 1..8) { "Property path requires 1..8 IRI members" }
                if ("SequencePathExpression" in relevant) ValueExpression.SequencePath(iriList)
                else ValueExpression.AlternativePath(iriList)
            }
            "InversePathExpression" -> {
                rejectUnknown(ref, setOf("pathPredicate"))
                ValueExpression.InversePath(iri(ref, lens("pathPredicate"))!!.value)
            }
            "ConstantExpression" -> {
                rejectUnknown(ref, setOf("literalValue", "constantIri"))
                val literal = only(ref, lens("literalValue"), false)
                val iri = iri(ref, lens("constantIri"), false)
                require((literal == null) != (iri == null)) { "Exactly one constant value is required" }
                val v = if (iri != null) Value.Iri(iri.value) else {
                    val typed = literal as? Literal ?: error("literalValue must be RDF literal")
                    Value.TypedLiteral(typed.lexical, typed.datatype.value)
                }
                ValueExpression.Constant(v)
            }
            else -> error("Unreachable expression class")
        }
    }

    private fun network(ref: RdfResource, types: Set<Iri>): NetworkLens {
        require(!(lens("NetworkLens") in types && lens("EgoNetworkLens") in types)) {
            "Multiple network lens types are not supported"
        }
        val unsupported = listOf("traversalNodeCondition", "traversalEdgeCondition",
            "outputNodeCondition", "outputEdgeCondition").filter {
            objects(ref, lens(it)).isNotEmpty()
        }
        require(unsupported.isEmpty()) {
            "RDF condition expressions not yet compiled: $unsupported"
        }
        require(listOf("minDepth", "maxNodes", "maxEdges", "includeIsolatedNodes",
            "dataset").all { objects(ref, lens(it)).isEmpty() }) {
            "Network configuration contains fields unsupported by this portable profile"
        }

        val ego = lens("EgoNetworkLens") in types
        rejectUnknown(ref, if (ego)
            setOf("seedLens", "edgeLens", "predicate", "maxDepth", "direction", "closureMode")
        else setOf("focusLens", "nodeLens", "edgeLens", "predicate", "maxDepth", "direction", "closureMode"))
        val seed = resource(ref, lens(if (ego) "seedLens" else "focusLens"))
            ?: error("Network must have a seed lens")
        val edgeRef = resource(ref, lens("edgeLens"), false)
        val directPredicate = iri(ref, lens("predicate"), false)
        require((edgeRef == null) != (directPredicate == null)) {
            "Exactly one of edgeLens and predicate is required"
        }
        if (edgeRef != null) rejectUnknown(edgeRef, setOf("predicate"))
        if (edgeRef != null) require(lens("EdgeLens") in objects(edgeRef, rdf("type"))) {
            "edgeLens must reference an EdgeLens"
        }
        val predicate = if (edgeRef != null) {
            require(listOf("condition", "traversalEdgeCondition", "outputEdgeCondition")
                .all { objects(edgeRef, lens(it)).isEmpty() }) {
                "EdgeLens conditions need a supported typed expression compiler"
            }
            iri(edgeRef, lens("predicate")) ?: error("EdgeLens requires a predicate")
        } else directPredicate
        val traversableNode = if (ego) null else
            resource(ref, lens("nodeLens"), false)?.let { node(it, LinkedHashSet(), 0) }
        val direction = when (iri(ref, lens("direction"), false)?.value) {
            null, lens("Outgoing").value -> NetworkDirection.OUT
            lens("Incoming").value -> NetworkDirection.IN
            lens("Both").value -> NetworkDirection.BOTH
            else -> error("Unknown direction")
        }
        val closure = when (iri(ref, lens("closureMode"), !ego)?.value) {
            null, lens("Traversed").value -> NetworkClosure.TRAVERSED
            lens("Induced").value -> NetworkClosure.INDUCED
            else -> error("Unknown closure mode")
        }
        return NetworkLens(
            seeds = node(seed, LinkedHashSet(), 0),
            predicate = predicate!!.value,
            maxDepth = integer(ref, lens("maxDepth"))!!,
            direction = direction,
            traversalNode = traversableNode,
            closure = closure
        )
    }
}
