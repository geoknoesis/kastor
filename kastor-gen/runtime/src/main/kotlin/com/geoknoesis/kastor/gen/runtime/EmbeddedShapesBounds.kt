package com.geoknoesis.kastor.gen.runtime

/**
 * **Not public API.** The structural cost bound of the validation adapters (`JenaValidation`, `Rdf4jValidation`)
 * for shapes embedded in the data graph, checked before the engine sees them (after [EmbeddedShapes.refused]).
 *
 * Neither engine bounds its own work: `jena-shacl` re-evaluates a shape reached through `sh:or`, `sh:and`,
 * `sh:xone`, `sh:not`, `sh:node`, `sh:property` or `sh:qualifiedValueShape` once per reference, so a chain of `n`
 * shapes that each reference the next twice costs `2^n`; RDF4J's `ShaclSail` builds a validation plan of the same
 * unfolded size before it reads any data (and runs out of memory at `n = 14` in a 256 MiB heap), hangs on a cyclic
 * RDF list and overflows its stack on long lists. So the shapes are measured as the engines evaluate them, **unfolded
 * into a tree** (every reference to a shared shape counted again), and refused with [EmbeddedShapesRefusedException]
 * beyond these caps:
 *
 * - [MAX_SHAPE_TRIPLES] statements in the SHACL namespace;
 * - [MAX_COST] unfolded cost, summed over every shape that has a target or a shape type: one per shape visit, one per
 *   parameter statement of the visited shape, one per member of a list it uses (`sh:in`, `sh:languageIn`,
 *   `sh:ignoredProperties`, the operands of the logical constraints, the members of a path) and one per path node;
 *   a property shape with `sh:qualifiedValueShapesDisjoint` also pays for the qualified value shapes of its siblings,
 *   which the engines evaluate for every value;
 * - [MAX_DEPTH] levels of shape references along any branch of the unfolding, and of path nesting;
 * - [MAX_OPERANDS] operands of one `sh:or`, `sh:and` or `sh:xone`;
 * - [MAX_LIST_LENGTH] members of any other list;
 * - a list must be well formed: every node has exactly one `rdf:first` and one `rdf:rest`, and it ends in `rdf:nil`
 *   without a cycle.
 *
 * A shape that references itself (directly or through others) is unfolded until the reference closes the cycle,
 * which counts as one visit: `jena-shacl` stops there too, and `ShaclSail` refuses recursive shapes.
 *
 * The bound is on the shapes alone. Work that depends on the size of the data (the number of value nodes each path
 * reaches) is not bounded here; `JenaValidation` charges it to a step budget while it validates.
 */
@KastorGenInternalApi
object EmbeddedShapesBounds {
    /** Most statements in the SHACL namespace embedded shapes may have. */
    const val MAX_SHAPE_TRIPLES: Int = 20_000

    /** Most unfolded cost (see the object documentation) of all the embedded shapes together. */
    const val MAX_COST: Int = 5_000

    /** Most levels of nested shape references, and of nested paths. */
    const val MAX_DEPTH: Int = 32

    /** Most operands of one `sh:or`, `sh:and` or `sh:xone`. */
    const val MAX_OPERANDS: Int = 64

    /** Most members of one list. */
    const val MAX_LIST_LENGTH: Int = 1_000

    private const val SH = "http://www.w3.org/ns/shacl#"
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    internal const val RDF_FIRST = RDF + "first"
    internal const val RDF_REST = RDF + "rest"
    internal const val RDF_NIL = RDF + "nil"
    private const val RDF_TYPE = RDF + "type"

    private val SHAPE_TYPES = setOf(SH + "NodeShape", SH + "PropertyShape", SH + "Shape")
    private val TARGETS = setOf(SH + "targetClass", SH + "targetNode", SH + "targetSubjectsOf", SH + "targetObjectsOf")
    private val SINGLE_SHAPE = setOf(SH + "not", SH + "node", SH + "property", SH + "qualifiedValueShape")
    private val LOGICAL = setOf(SH + "or", SH + "and", SH + "xone")
    private val VALUE_LISTS = setOf(SH + "in", SH + "languageIn", SH + "ignoredProperties")
    private val UNARY_PATHS = setOf(SH + "inversePath", SH + "zeroOrMorePath", SH + "oneOrMorePath", SH + "zeroOrOnePath")
    internal const val PATH = SH + "path"
    internal const val PROPERTY = SH + "property"
    internal const val QUALIFIED_VALUE_SHAPE = SH + "qualifiedValueShape"
    internal const val QUALIFIED_DISJOINT = SH + "qualifiedValueShapesDisjoint"
    internal const val ALTERNATIVE_PATH = SH + "alternativePath"

    /**
     * Throws [EmbeddedShapesRefusedException] when the shapes in [statements] exceed a cap (see the object
     * documentation). Each statement is (subject, predicate IRI, object), the nodes written as keys that are equal
     * exactly when the nodes are: an IRI as itself, a blank node as `_:` and its label, any other term starting with
     * a character no IRI starts with. Statements outside the SHACL namespace other than `rdf:type`, `rdf:first` and
     * `rdf:rest` are ignored, so the whole data graph may be passed. [adapter] names the validator in the message.
     */
    fun requireWithin(statements: Sequence<Triple<String, String, String>>, adapter: String) {
        val graph = ShapeStatements(adapter)
        statements.forEach(graph::add)
        Unfolding(graph).measure()
    }

    internal fun refuse(adapter: String, problem: String): Nothing =
        throw EmbeddedShapesRefusedException(
            "$adapter refuses the SHACL shapes embedded in the data graph: $problem. The engine evaluates embedded " +
                "shapes without a bound on their cost, so they are limited to $MAX_COST unfolded constraints, " +
                "$MAX_DEPTH levels of nesting, $MAX_OPERANDS operands per logical constraint, lists of " +
                "$MAX_LIST_LENGTH members and $MAX_SHAPE_TRIPLES SHACL statements. Embedded shapes come from the data " +
                "and are untrusted: pass the shapes to the constructor, validate with the Kastor SHACL validator, or, " +
                "only when the data graph is trusted, create the validator with $adapter.trustingEmbeddedShapes().",
        )

    /** The statements the measure needs, indexed by subject. */
    internal class ShapeStatements(val adapter: String) {
        val bySubject = HashMap<String, MutableList<Pair<String, String>>>()
        val listNodes = HashMap<String, MutableList<Pair<String, String>>>()
        val parents = HashMap<String, MutableList<String>>()
        val roots = LinkedHashSet<String>()
        private var shapeTriples = 0

        fun add(statement: Triple<String, String, String>) {
            val (subject, predicate, obj) = statement
            when {
                predicate == RDF_FIRST || predicate == RDF_REST -> listNodes.getOrPut(subject) { ArrayList(2) } += predicate to obj
                predicate == RDF_TYPE -> if (obj in SHAPE_TYPES) roots += subject
                predicate.startsWith(SH) -> addShapeStatement(subject, predicate, obj)
            }
        }

        private fun addShapeStatement(subject: String, predicate: String, obj: String) {
            if (++shapeTriples > MAX_SHAPE_TRIPLES) refuse(adapter, "they have more than $MAX_SHAPE_TRIPLES statements in the SHACL namespace")
            bySubject.getOrPut(subject) { ArrayList() } += predicate to obj
            if (predicate in TARGETS) roots += subject
            if (predicate == PROPERTY) parents.getOrPut(obj) { ArrayList(1) } += subject
        }
    }

    /** One measure of the unfolded shapes; aborts as soon as a cap is passed, so its own work is bounded too. */
    private class Unfolding(private val graph: ShapeStatements) {
        private var cost = 0
        private val onPath = HashSet<String>()
        private val lists = HashMap<String, List<String>>()

        fun measure() {
            for (root in graph.roots) visitShape(root, 0)
        }

        private fun charge(amount: Int) {
            cost += amount
            if (cost > MAX_COST) refuse(graph.adapter, "unfolded into a tree, as the engine evaluates them, they cost more than $MAX_COST")
        }

        private fun deeper(depth: Int): Int {
            if (depth >= MAX_DEPTH) refuse(graph.adapter, "they nest shape references or paths more than $MAX_DEPTH levels deep")
            return depth + 1
        }

        private fun visitShape(shape: String, depth: Int) {
            charge(1)
            if (!onPath.add(shape)) return
            try {
                for ((predicate, obj) in graph.bySubject[shape].orEmpty()) visitParameter(shape, predicate, obj, depth)
            } finally {
                onPath.remove(shape)
            }
        }

        private fun visitParameter(shape: String, predicate: String, obj: String, depth: Int) {
            charge(1)
            when (predicate) {
                in SINGLE_SHAPE -> visitShape(obj, deeper(depth))
                in LOGICAL -> visitOperands(obj, depth)
                in VALUE_LISTS -> charge(list(obj, MAX_LIST_LENGTH).size)
                PATH -> visitPath(obj, deeper(depth))
                QUALIFIED_DISJOINT -> visitSiblings(shape, depth)
            }
        }

        private fun visitOperands(head: String, depth: Int) {
            val operands = list(head, MAX_OPERANDS)
            charge(operands.size)
            val next = deeper(depth)
            for (operand in operands) visitShape(operand, next)
        }

        /** The qualified value shapes of the other property shapes of [shape]'s parents, evaluated for each value. */
        private fun visitSiblings(shape: String, depth: Int) {
            val next = deeper(depth)
            for (parent in graph.parents[shape].orEmpty()) {
                for ((predicate, sibling) in graph.bySubject[parent].orEmpty()) {
                    if (predicate != PROPERTY || sibling == shape) continue
                    for ((p, qualified) in graph.bySubject[sibling].orEmpty()) if (p == QUALIFIED_VALUE_SHAPE) visitShape(qualified, next)
                }
            }
        }

        private fun visitPath(path: String, depth: Int) {
            charge(1)
            if (path in graph.listNodes) {
                for (member in list(path, MAX_LIST_LENGTH)) visitPath(member, deeper(depth))
                return
            }
            for ((predicate, obj) in graph.bySubject[path].orEmpty()) {
                charge(1)
                when (predicate) {
                    in UNARY_PATHS -> visitPath(obj, deeper(depth))
                    ALTERNATIVE_PATH -> for (member in list(obj, MAX_LIST_LENGTH)) visitPath(member, deeper(depth))
                }
            }
        }

        /** The members of the list at [head]; refused when it is malformed or longer than [max]. */
        private fun list(head: String, max: Int): List<String> = lists[head]?.also { checkLength(it, max) }
            ?: readList(head, max).also { lists[head] = it }

        private fun checkLength(members: List<String>, max: Int) {
            if (members.size > max) refuse(graph.adapter, "a list has more than $max members")
        }

        private fun readList(head: String, max: Int): List<String> {
            val members = ArrayList<String>()
            val seen = HashSet<String>()
            var node = head
            while (node != RDF_NIL) {
                if (!seen.add(node)) refuse(graph.adapter, "an RDF list is cyclic")
                val entries = graph.listNodes[node].orEmpty()
                val first = entries.filter { it.first == RDF_FIRST }
                val rest = entries.filter { it.first == RDF_REST }
                if (first.size != 1 || rest.size != 1) refuse(graph.adapter, "an RDF list node does not have exactly one rdf:first and one rdf:rest")
                members += first[0].second
                checkLength(members, max)
                node = rest[0].second
            }
            return members
        }
    }
}
