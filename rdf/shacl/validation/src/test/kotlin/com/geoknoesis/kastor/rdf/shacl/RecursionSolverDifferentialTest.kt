package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.RDF
import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the incremental recursion solver (explicit worklists, recorded dependencies, optimistic first
 * iteration, incremental refinement, memo shared across targets) against a naive oracle that implements the
 * documented semantics by brute force: every round it rebuilds the dependency graph of the unsettled questions and
 * re-evaluates every member of a bottom group until nothing changes.
 *
 * The cases are small random shape graphs whose shapes reference each other through monotone (`sh:node`, `sh:and`,
 * `sh:or`, `sh:qualifiedMinCount`) and non-monotone (`sh:not`, `sh:xone`, `sh:qualifiedMaxCount`, a minimum and a
 * maximum on the same qualified shape, `sh:qualifiedValueShapesDisjoint`) operators over small random data graphs
 * whose nodes are IRIs, blank nodes and literals. The comparison covers the **content** of the report — for every
 * target, each result's source shape, constraint component, value and whether it is definite or undefined — and not
 * only the three-valued answer. Seeds are fixed, so a failure is reproducible from its message.
 */
class RecursionSolverDifferentialTest {

    /** Three-valued answer in truth order. */
    private enum class V { F, U, C }

    private enum class Kind { IRI, BLANK, LITERAL }

    private sealed class Conjunct {
        object HasName : Conjunct()
        data class All(val pred: String, val shape: Int) : Conjunct()
        data class None(val pred: String, val shape: Int) : Conjunct()
        /** `sh:qualifiedValueShape` with an optional minimum, an optional maximum (at least one) and optional sibling exclusion. */
        data class Qualified(val pred: String, val shape: Int, val min: Int?, val max: Int?, val disjoint: Boolean) : Conjunct()
        data class And(val a: Int, val b: Int) : Conjunct()
        data class Or(val a: Int, val b: Int) : Conjunct()
        data class Xone(val a: Int, val b: Int) : Conjunct()
        data class Not(val shape: Int) : Conjunct()
        data class Node(val shape: Int) : Conjunct()
    }

    private data class Atom(val node: Int, val shape: Int)
    private data class Edge(val to: Atom, val negative: Boolean)

    /** One validation result: its focus node, source shape, constraint component, value and definiteness. */
    private data class Row(val node: Int, val source: String, val type: ConstraintType, val value: Int?, val undefined: Boolean)

    private class Case(
        val seed: Long,
        val kinds: List<Kind>,
        val named: Set<Int>,
        val edges: Map<Pair<Int, String>, List<Int>>,
        val shapes: List<List<Conjunct>>,
    ) {
        val nodes: Int get() = kinds.size

        fun values(node: Int, pred: String): List<Int> = edges[node to pred].orEmpty()

        /** SHACL 4.7.3: the other `sh:qualifiedValueShape` values of the parent shape's properties, minus the own one. */
        fun siblings(shape: Int, c: Conjunct.Qualified): List<Int> =
            if (!c.disjoint) emptyList() else shapes[shape].filterIsInstance<Conjunct.Qualified>().map { it.shape }.distinct().filter { it != c.shape }

        fun dependencies(atom: Atom): List<Edge> =
            shapes[atom.shape].flatMap { c ->
                when (c) {
                    Conjunct.HasName -> emptyList()
                    is Conjunct.All -> values(atom.node, c.pred).map { Edge(Atom(it, c.shape), false) }
                    is Conjunct.None -> values(atom.node, c.pred).map { Edge(Atom(it, c.shape), true) }
                    // A maximum makes the qualified shape a negative dependency; sibling exclusion always is one.
                    is Conjunct.Qualified -> values(atom.node, c.pred).flatMap { v ->
                        listOf(Edge(Atom(v, c.shape), c.max != null)) + siblings(atom.shape, c).map { Edge(Atom(v, it), true) }
                    }
                    is Conjunct.And -> listOf(Edge(Atom(atom.node, c.a), false), Edge(Atom(atom.node, c.b), false))
                    is Conjunct.Or -> listOf(Edge(Atom(atom.node, c.a), false), Edge(Atom(atom.node, c.b), false))
                    is Conjunct.Xone -> listOf(Edge(Atom(atom.node, c.a), true), Edge(Atom(atom.node, c.b), true))
                    is Conjunct.Not -> listOf(Edge(Atom(atom.node, c.shape), true))
                    is Conjunct.Node -> listOf(Edge(Atom(atom.node, c.shape), false))
                }
            }

        /**
         * The results of conjunct [index] of [atom]'s shape given the answers of the questions it reads (Kleene
         * logic). No result: the conjunct conforms. An undefined result: its outcome depends on an undefined answer.
         */
        fun rows(atom: Atom, index: Int, answer: (Atom) -> V): List<Row> {
            val node = atom.node
            val shape = "S${atom.shape}"
            val property = "S${atom.shape}_c$index"
            fun row(source: String, type: ConstraintType, value: Int?, outcome: V): List<Row> =
                if (outcome == V.C) emptyList() else listOf(Row(node, source, type, value, outcome == V.U))
            return when (val c = shapes[atom.shape][index]) {
                Conjunct.HasName -> row(property, ConstraintType.MIN_COUNT, null, if (node in named) V.C else V.F)
                is Conjunct.All -> values(node, c.pred).flatMap { v -> row(property, ConstraintType.NODE, v, answer(Atom(v, c.shape))) }
                is Conjunct.None -> values(node, c.pred).flatMap { v -> row(property, ConstraintType.NOT, v, invert(answer(Atom(v, c.shape)))) }
                is Conjunct.Qualified -> {
                    val siblings = siblings(atom.shape, c)
                    val counted = values(node, c.pred).map { v ->
                        val own = answer(Atom(v, c.shape))
                        val others = siblings.map { answer(Atom(v, it)) }
                        when {
                            own == V.F || others.any { it == V.C } -> V.F
                            own == V.U || others.any { it == V.U } -> V.U
                            else -> V.C
                        }
                    }
                    // The count lies between the values that definitely count and those that possibly count.
                    val definite = counted.count { it == V.C }
                    val possible = counted.count { it != V.F }
                    val minimum = when {
                        c.min == null -> V.C
                        possible < c.min -> V.F
                        definite < c.min -> V.U
                        else -> V.C
                    }
                    val maximum = when {
                        c.max == null -> V.C
                        definite > c.max -> V.F
                        possible > c.max -> V.U
                        else -> V.C
                    }
                    row(property, ConstraintType.QUALIFIED_MIN_COUNT, null, minimum) + row(property, ConstraintType.QUALIFIED_MAX_COUNT, null, maximum)
                }
                is Conjunct.And -> row(shape, ConstraintType.AND, node, minOf(answer(Atom(node, c.a)), answer(Atom(node, c.b))))
                is Conjunct.Or -> row(shape, ConstraintType.OR, node, maxOf(answer(Atom(node, c.a)), answer(Atom(node, c.b))))
                is Conjunct.Xone -> {
                    val answers = listOf(answer(Atom(node, c.a)), answer(Atom(node, c.b)))
                    val outcome = when {
                        answers.count { it == V.C } > 1 -> V.F
                        answers.any { it == V.U } -> V.U
                        answers.none { it == V.C } -> V.F
                        else -> V.C
                    }
                    row(shape, ConstraintType.XONE, node, outcome)
                }
                is Conjunct.Not -> row(shape, ConstraintType.NOT, node, invert(answer(Atom(node, c.shape))))
                is Conjunct.Node -> row(shape, ConstraintType.NODE, node, answer(Atom(node, c.shape)))
            }
        }

        fun rows(atom: Atom, answer: (Atom) -> V): List<Row> = shapes[atom.shape].indices.flatMap { rows(atom, it, answer) }

        /** Kleene evaluation of one question: fails with a definite result, undefined with only undefined ones. */
        fun evaluate(atom: Atom, answer: (Atom) -> V): V {
            val rows = rows(atom, answer)
            return when {
                rows.any { !it.undefined } -> V.F
                rows.isNotEmpty() -> V.U
                else -> V.C
            }
        }

        // --- naive oracle ------------------------------------------------------------------------------------------

        fun oracle(): Map<Atom, V> {
            val settled = HashMap<Atom, V>()
            val all = (0 until nodes).flatMap { n -> shapes.indices.map { Atom(n, it) } }
            while (settled.size < all.size) {
                val unsettled = all.filter { it !in settled }
                val group = bottomGroup(unsettled) { a -> dependencies(a).map { it.to }.filter { it !in settled } }
                val members = group.toSet()
                val negativeInside = group.any { a -> dependencies(a).any { it.negative && it.to in members } }
                if (!negativeInside) {
                    // Greatest fixpoint: assume conformance and lower the answers until nothing changes.
                    val current = group.associateWithTo(HashMap()) { V.C }
                    do {
                        var changed = false
                        for (a in group) {
                            val v = evaluate(a) { settled[it] ?: current.getValue(it) }
                            if (v < current.getValue(a)) { current[a] = v; changed = true }
                        }
                    } while (changed)
                    settled.putAll(current)
                    continue
                }
                // Negative dependency inside the group: settle what does not depend on the unsettled members.
                var settledAny = false
                do {
                    var changed = false
                    for (a in group) {
                        if (a in settled) continue
                        val v = evaluate(a) { settled[it] ?: V.U }
                        if (v != V.U) { settled[a] = v; changed = true; settledAny = true }
                    }
                } while (changed)
                // Nothing could be settled: every member lies on a cycle through a negative dependency.
                if (!settledAny) group.forEach { settled[it] = V.U }
                // Otherwise the remainder is split into groups again by the next round.
            }
            return settled
        }

        /** A strongly connected component of [vertices] without dependencies outside itself (Tarjan: first emitted). */
        private fun bottomGroup(vertices: List<Atom>, successors: (Atom) -> List<Atom>): List<Atom> {
            val index = HashMap<Atom, Int>()
            val low = HashMap<Atom, Int>()
            val stack = ArrayDeque<Atom>()
            val onStack = HashSet<Atom>()
            var counter = 0
            var found: List<Atom>? = null
            fun visit(v: Atom) {
                index[v] = counter; low[v] = counter; counter++
                stack.addLast(v); onStack.add(v)
                for (w in successors(v)) {
                    if (found != null) return
                    if (w !in index) { visit(w); low[v] = minOf(low.getValue(v), low.getValue(w)) }
                    else if (w in onStack) low[v] = minOf(low.getValue(v), index.getValue(w))
                }
                if (found == null && low[v] == index[v]) {
                    val component = ArrayList<Atom>()
                    while (true) {
                        val w = stack.removeLast(); onStack.remove(w); component.add(w)
                        if (w == v) break
                    }
                    found = component
                }
            }
            visit(vertices.first())
            return found!!
        }

        // --- RDF --------------------------------------------------------------------------------------------------

        private fun term(node: Int): String =
            when (kinds[node]) {
                Kind.IRI -> "ex:n$node"
                Kind.BLANK -> "_:n$node"
                Kind.LITERAL -> "'n$node'"
            }

        fun dataGraph(): RdfGraph {
            val sb = StringBuilder(PREFIXES)
            for (n in 0 until nodes) {
                // Every resource is declared, so that a node without edges still exists in the data graph. A blank
                // node is typed with a class of its own, which is how a shape targets it.
                when (kinds[n]) {
                    Kind.IRI -> sb.append("ex:n$n a ex:Thing .\n")
                    Kind.BLANK -> sb.append("_:n$n a ex:C$n .\n")
                    Kind.LITERAL -> Unit
                }
                if (n in named) sb.append("${term(n)} ex:name 'name of n$n' .\n")
            }
            for ((key, targets) in edges) for (t in targets) sb.append("${term(key.first)} ex:${key.second} ${term(t)} .\n")
            return Rdf.parse(sb.toString(), RdfFormat.TURTLE)
        }

        /** Shapes graph in which [targets] maps a shape to the nodes it targets. */
        fun shapesGraph(targets: Map<Int, List<Int>>): RdfGraph {
            val sb = StringBuilder(PREFIXES)
            shapes.forEachIndexed { s, conjuncts ->
                sb.append("ex:S$s a sh:NodeShape .\n")
                for (n in targets[s].orEmpty()) {
                    // IRIs and literals are sh:targetNode values; a blank node of the data graph is targeted by its class.
                    if (kinds[n] == Kind.BLANK) sb.append("ex:S$s sh:targetClass ex:C$n .\n") else sb.append("ex:S$s sh:targetNode ${term(n)} .\n")
                }
                conjuncts.forEachIndexed { i, c ->
                    val ps = "ex:S${s}_c$i"
                    when (c) {
                        Conjunct.HasName -> sb.append("ex:S$s sh:property $ps . $ps sh:path ex:name ; sh:minCount 1 .\n")
                        is Conjunct.All -> sb.append("ex:S$s sh:property $ps . $ps sh:path ex:${c.pred} ; sh:node ex:S${c.shape} .\n")
                        is Conjunct.None -> sb.append("ex:S$s sh:property $ps . $ps sh:path ex:${c.pred} ; sh:not ex:S${c.shape} .\n")
                        is Conjunct.Qualified -> {
                            sb.append("ex:S$s sh:property $ps . $ps sh:path ex:${c.pred} ; sh:qualifiedValueShape ex:S${c.shape}")
                            c.min?.let { sb.append(" ; sh:qualifiedMinCount $it") }
                            c.max?.let { sb.append(" ; sh:qualifiedMaxCount $it") }
                            if (c.disjoint) sb.append(" ; sh:qualifiedValueShapesDisjoint true")
                            sb.append(" .\n")
                        }
                        is Conjunct.And -> sb.append("ex:S$s sh:and ( ex:S${c.a} ex:S${c.b} ) .\n")
                        is Conjunct.Or -> sb.append("ex:S$s sh:or ( ex:S${c.a} ex:S${c.b} ) .\n")
                        is Conjunct.Xone -> sb.append("ex:S$s sh:xone ( ex:S${c.a} ex:S${c.b} ) .\n")
                        is Conjunct.Not -> sb.append("ex:S$s sh:not ex:S${c.shape} .\n")
                        is Conjunct.Node -> sb.append("ex:S$s sh:node ex:S${c.shape} .\n")
                    }
                }
            }
            return Rdf.parse(sb.toString(), RdfFormat.TURTLE)
        }

        fun describe(): String = "seed=$seed kinds=$kinds named=$named edges=$edges shapes=$shapes"
    }

    private fun generate(seed: Long): Case {
        val random = Random(seed)
        val nodes = 1 + random.nextInt(6)
        val shapeCount = 1 + random.nextInt(4)
        val kinds = List(nodes) {
            when (random.nextInt(10)) {
                in 0..5 -> Kind.IRI
                in 6..7 -> Kind.BLANK
                else -> Kind.LITERAL
            }
        }
        // A literal is never a subject: it has no name and no outgoing edge.
        val named = (0 until nodes).filter { kinds[it] != Kind.LITERAL && random.nextInt(3) != 0 }.toSet()
        val predicates = listOf("p", "q")
        val edges = LinkedHashMap<Pair<Int, String>, List<Int>>()
        for (n in 0 until nodes) for (pred in predicates) {
            val targets = (0 until nodes).filter { random.nextInt(100) < 30 }
            if (targets.isNotEmpty() && kinds[n] != Kind.LITERAL) edges[n to pred] = targets
        }
        fun shape() = random.nextInt(shapeCount)
        fun pred() = predicates[random.nextInt(predicates.size)]
        val shapes = List(shapeCount) {
            List(1 + random.nextInt(3)) {
                when (random.nextInt(16)) {
                    0, 1 -> Conjunct.HasName
                    2, 3 -> Conjunct.All(pred(), shape())
                    4, 5 -> Conjunct.None(pred(), shape())
                    6 -> Conjunct.Qualified(pred(), shape(), min = 1 + random.nextInt(2), max = null, disjoint = false)
                    7 -> Conjunct.Qualified(pred(), shape(), min = null, max = random.nextInt(2), disjoint = false)
                    // A minimum and a maximum on the same qualified shape.
                    8 -> Conjunct.Qualified(pred(), shape(), min = 1, max = 1 + random.nextInt(2), disjoint = false)
                    // Sibling exclusion (it only has siblings when the shape has another qualified conjunct).
                    9, 10 -> {
                        val min = if (random.nextBoolean()) 1 else null
                        Conjunct.Qualified(pred(), shape(), min = min, max = if (min == null || random.nextBoolean()) random.nextInt(2) else null, disjoint = true)
                    }
                    11 -> Conjunct.And(shape(), shape())
                    12 -> Conjunct.Or(shape(), shape())
                    13 -> Conjunct.Xone(shape(), shape())
                    14 -> Conjunct.Not(shape())
                    else -> Conjunct.Node(shape())
                }
            }.distinct()
        }
        return Case(seed, kinds, named, edges, shapes)
    }

    /** The engine's results as rows. Blank nodes are identified through the class the data graph gives each of them. */
    private fun engineRows(report: ValidationReport, data: RdfGraph): List<Row> {
        val blankNodes = HashMap<RdfTerm, Int>()
        for (t in data.getTriples()) {
            val type = t.obj as? Iri ?: continue
            if (t.subject is BlankNode && t.predicate == RDF.type) blankNodes[t.subject] = type.value.substringAfterLast("/C").toInt()
        }
        fun node(term: RdfTerm): Int =
            when (term) {
                is Iri -> term.value.substringAfterLast("/n").toInt()
                is Literal -> term.lexical.removePrefix("n").toInt()
                else -> blankNodes.getValue(term)
            }
        return report.violations.map { v ->
            Row(node(v.focusNode), v.shapeUri!!.substringAfterLast("/"), v.constraint.constraintType, v.value?.let { node(it) }, v.isUndefinedRecursion)
        }
    }

    private fun sorted(rows: List<Row>): List<String> = rows.map { it.toString() }.sorted()

    private fun validator() = NativeShaclValidator(ValidationConfig(maxViolations = 100_000))

    @Test
    fun `incremental solver agrees with the naive oracle on random recursive shape graphs with negation`() {
        var undefined = 0
        var failing = 0
        var questions = 0
        val rowsByType = HashMap<Pair<ConstraintType, Boolean>, Int>()
        val valueKinds = HashMap<Kind, Int>()
        val focusKinds = HashMap<Kind, Int>()
        var disjointWithSiblings = 0
        for (seed in 1L..400L) {
            val case = generate(seed)
            val expected = case.oracle()
            val answer = { atom: Atom -> expected.getValue(atom) }
            val data = case.dataGraph()
            val atoms = expected.keys
            // 1. Every shape targets every node: the questions are answered in one run and share the memo.
            val allTargets = case.shapes.indices.associateWith { (0 until case.nodes).toList() }
            val expectedRows = atoms.flatMap { case.rows(it, answer) }
            val together = engineRows(validator().validate(data, case.shapesGraph(allTargets)), data)
            assertEquals(sorted(expectedRows), sorted(together), "all targets in one run: ${case.describe()}")
            // 2. Questions alone, solved from a cold start: all of them for one case in four, a seeded sample otherwise.
            val sample = if (seed % 4 == 0L) atoms.toList() else atoms.toList().shuffled(Random(seed)).take(3)
            for (atom in sample) {
                val alone = engineRows(validator().validate(data, case.shapesGraph(mapOf(atom.shape to listOf(atom.node)))), data)
                assertEquals(sorted(case.rows(atom, answer)), sorted(alone), "single target $atom: ${case.describe()}")
            }
            questions += atoms.size
            undefined += expected.values.count { it == V.U }
            failing += expected.values.count { it == V.F }
            for (row in expectedRows) {
                rowsByType.merge(row.type to row.undefined, 1, Int::plus)
                focusKinds.merge(case.kinds[row.node], 1, Int::plus)
                row.value?.let { valueKinds.merge(case.kinds[it], 1, Int::plus) }
            }
            case.shapes.forEachIndexed { s, conjuncts ->
                disjointWithSiblings += conjuncts.filterIsInstance<Conjunct.Qualified>().count { case.siblings(s, it).isNotEmpty() }
            }
        }
        // The generator must actually reach the interesting regions (deterministic for the fixed seeds).
        val coverage = "questions=$questions undefined=$undefined failing=$failing rows=$rowsByType focus=$focusKinds values=$valueKinds " +
            "disjointWithSiblings=$disjointWithSiblings"
        assertTrue(undefined >= 100, coverage)
        assertTrue(failing >= 500, coverage)
        assertTrue(questions - undefined - failing >= 500, coverage)
        // Every operator yields definite and undefined results somewhere, in particular the qualified counts and sh:and.
        val operators = listOf(
            ConstraintType.NODE, ConstraintType.NOT, ConstraintType.AND, ConstraintType.OR, ConstraintType.XONE,
            ConstraintType.QUALIFIED_MIN_COUNT, ConstraintType.QUALIFIED_MAX_COUNT,
        )
        for (type in operators) {
            assertTrue((rowsByType[type to false] ?: 0) >= 5, "definite $type results: $coverage")
            assertTrue((rowsByType[type to true] ?: 0) >= 5, "undefined $type results: $coverage")
        }
        assertTrue((rowsByType[ConstraintType.MIN_COUNT to false] ?: 0) >= 50, coverage)
        // Focus nodes and value nodes of every kind occur in results.
        for (kind in Kind.values()) {
            assertTrue((focusKinds[kind] ?: 0) >= 20, "$kind focus nodes: $coverage")
            assertTrue((valueKinds[kind] ?: 0) >= 20, "$kind values: $coverage")
        }
        assertTrue(disjointWithSiblings >= 20, coverage)
    }

    // --- results from recorded evaluations -----------------------------------------------------------------------------

    /**
     * The results of failing and undefined focus nodes come from the solver's recorded evaluations
     * ([NativeShaclValidator.reportRecordingCapacity]). They must be the very results a separate report evaluation
     * produces: the same list, in the same order, with the same messages, for every random case — also when the bound
     * on recordings makes the two ways alternate within one run.
     */
    @Test
    fun `results from recorded solver evaluations are those of a separate report evaluation`() {
        var fromRecordings = 0L
        var mixed = 0L
        for (seed in 1L..400L) {
            val case = generate(seed)
            val data = case.dataGraph()
            val shapes = case.shapesGraph(case.shapes.indices.associateWith { (0 until case.nodes).toList() })
            val separate = validator().also { it.reportRecordingCapacity = 0 }
            val expected = separate.validate(data, shapes).violations
            assertEquals(0L, separate.recordedReports, "recording is disabled: ${case.describe()}")

            val recording = validator()
            assertEquals(expected, recording.validate(data, shapes).violations, "recorded results: ${case.describe()}")
            // Every failing or undefined focus node of a recursive shape was reported without another evaluation.
            assertEquals(0L, recording.separateReportEvaluations, case.describe())
            assertEquals(separate.separateReportEvaluations, recording.recordedReports, case.describe())
            fromRecordings += recording.recordedReports

            // A bound so small that recording stops and resumes as recordings are reported.
            val bounded = validator().also { it.reportRecordingCapacity = 3 }
            assertEquals(expected, bounded.validate(data, shapes).violations, "bounded recording: ${case.describe()}")
            if (bounded.recordedReports > 0 && bounded.separateReportEvaluations > 0) mixed++
        }
        assertTrue(fromRecordings >= 300, "focus nodes reported from recordings: $fromRecordings")
        assertTrue(mixed >= 10, "runs that used both ways: $mixed")
    }

    // --- restart recovery --------------------------------------------------------------------------------------------

    private val notNext = """
        ex:S a sh:NodeShape ; sh:targetNode ex:a ;
          sh:property [ sh:path ex:next ; sh:not ex:S ] .
    """

    /** Whether `ex:a` conforms to "no `ex:next` value conforms to this shape", and the solver restarts it took. */
    private fun solveForA(data: String, dropReadOfB: Boolean): Pair<Boolean, Long> {
        val validator = validator()
        // Seam: the recording pass answers the read of (ex:b, ex:S) but does not register it, as an incomplete
        // recording would.
        if (dropReadOfB) validator.dropRecordedRead = { node, _ -> node == Iri("http://example.org/b") }
        val report = validator.validate(Rdf.parse(PREFIXES + data, RdfFormat.TURTLE), Rdf.parse(PREFIXES + notNext, RdfFormat.TURTLE))
        assertTrue(report.violations.none { it.isUndefinedRecursion }, report.violations.toString())
        return report.isValid to validator.solverRestarts
    }

    @Test
    fun `a read the recording missed restarts the solve instead of being answered by default`() {
        // a -> b -> c and a -> d -> e: c and e conform, so b and d fail and a conforms. Answering the unrecorded
        // read of (b, S) with the default "conforms" would make a fail.
        val fork = "ex:a ex:next ex:b , ex:d . ex:b ex:next ex:c . ex:d ex:next ex:e ."
        assertEquals(true to 0L, solveForA(fork, dropReadOfB = false), "a complete recording never restarts")
        // The evaluation of (a, S) first reads a question that is not registered at all (restart 1), then a
        // registered question that no recorded dependency orders before (a, S), so it is not settled (restart 2).
        assertEquals(true to 2L, solveForA(fork, dropReadOfB = true))

        // b is a leaf here: it conforms, so a fails. Once registered, b is settled by its own recording.
        val leaf = "ex:a ex:next ex:b , ex:d . ex:d ex:next ex:e ."
        assertEquals(false to 0L, solveForA(leaf, dropReadOfB = false))
        assertEquals(false to 1L, solveForA(leaf, dropReadOfB = true))
    }

    private companion object {
        const val PREFIXES = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n@prefix ex: <http://example.org/> .\n"

        fun invert(v: V) = when (v) { V.F -> V.C; V.C -> V.F; V.U -> V.U }
    }
}
