package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
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
 * The cases are small random shape graphs whose shapes reference each other through monotone (`sh:node`, `sh:or`,
 * `sh:qualifiedMinCount`) and non-monotone (`sh:not`, `sh:xone`, `sh:qualifiedMaxCount`) operators over small random
 * data graphs. Seeds are fixed, so a failure is reproducible from its message.
 */
class RecursionSolverDifferentialTest {

    /** Three-valued answer in truth order. */
    private enum class V { F, U, C }

    private sealed class Conjunct {
        object HasName : Conjunct()
        data class All(val pred: String, val shape: Int) : Conjunct()
        data class None(val pred: String, val shape: Int) : Conjunct()
        data class Some(val pred: String, val shape: Int) : Conjunct()
        data class AtMost(val pred: String, val shape: Int, val max: Int) : Conjunct()
        data class Or(val a: Int, val b: Int) : Conjunct()
        data class Xone(val a: Int, val b: Int) : Conjunct()
        data class Not(val shape: Int) : Conjunct()
        data class Node(val shape: Int) : Conjunct()
    }

    private data class Atom(val node: Int, val shape: Int)
    private data class Edge(val to: Atom, val negative: Boolean)

    private class Case(val seed: Long, val nodes: Int, val named: Set<Int>, val edges: Map<Pair<Int, String>, List<Int>>, val shapes: List<List<Conjunct>>) {
        fun values(node: Int, pred: String): List<Int> = edges[node to pred].orEmpty()

        fun dependencies(atom: Atom): List<Edge> =
            shapes[atom.shape].flatMap { c ->
                when (c) {
                    Conjunct.HasName -> emptyList()
                    is Conjunct.All -> values(atom.node, c.pred).map { Edge(Atom(it, c.shape), false) }
                    is Conjunct.Some -> values(atom.node, c.pred).map { Edge(Atom(it, c.shape), false) }
                    is Conjunct.None -> values(atom.node, c.pred).map { Edge(Atom(it, c.shape), true) }
                    is Conjunct.AtMost -> values(atom.node, c.pred).map { Edge(Atom(it, c.shape), true) }
                    is Conjunct.Or -> listOf(Edge(Atom(atom.node, c.a), false), Edge(Atom(atom.node, c.b), false))
                    is Conjunct.Xone -> listOf(Edge(Atom(atom.node, c.a), true), Edge(Atom(atom.node, c.b), true))
                    is Conjunct.Not -> listOf(Edge(Atom(atom.node, c.shape), true))
                    is Conjunct.Node -> listOf(Edge(Atom(atom.node, c.shape), false))
                }
            }

        /** Kleene evaluation of one question given the answers of the questions it reads. */
        fun evaluate(atom: Atom, answer: (Atom) -> V): V =
            shapes[atom.shape].minOf { c ->
                when (c) {
                    Conjunct.HasName -> if (atom.node in named) V.C else V.F
                    is Conjunct.All -> values(atom.node, c.pred).minOfOrNull { answer(Atom(it, c.shape)) } ?: V.C
                    is Conjunct.None -> values(atom.node, c.pred).minOfOrNull { invert(answer(Atom(it, c.shape))) } ?: V.C
                    is Conjunct.Some -> {
                        val answers = values(atom.node, c.pred).map { answer(Atom(it, c.shape)) }
                        when {
                            answers.none { it != V.F } -> V.F
                            answers.none { it == V.C } -> V.U
                            else -> V.C
                        }
                    }
                    is Conjunct.AtMost -> {
                        val answers = values(atom.node, c.pred).map { answer(Atom(it, c.shape)) }
                        when {
                            answers.count { it == V.C } > c.max -> V.F
                            answers.count { it != V.F } > c.max -> V.U
                            else -> V.C
                        }
                    }
                    is Conjunct.Or -> maxOf(answer(Atom(atom.node, c.a)), answer(Atom(atom.node, c.b)))
                    is Conjunct.Xone -> {
                        val answers = listOf(answer(Atom(atom.node, c.a)), answer(Atom(atom.node, c.b)))
                        when {
                            answers.count { it == V.C } > 1 -> V.F
                            answers.any { it == V.U } -> V.U
                            answers.none { it == V.C } -> V.F
                            else -> V.C
                        }
                    }
                    is Conjunct.Not -> invert(answer(Atom(atom.node, c.shape)))
                    is Conjunct.Node -> answer(Atom(atom.node, c.shape))
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

        fun dataGraph(): RdfGraph {
            val sb = StringBuilder(PREFIXES)
            for (n in 0 until nodes) {
                // Every node is declared, so that a node without edges still exists in the data graph.
                sb.append("ex:n$n a ex:Thing .\n")
                if (n in named) sb.append("ex:n$n ex:name 'n$n' .\n")
            }
            for ((key, targets) in edges) for (t in targets) sb.append("ex:n${key.first} ex:${key.second} ex:n$t .\n")
            return Rdf.parse(sb.toString(), RdfFormat.TURTLE)
        }

        /** Shapes graph in which [targets] maps a shape to the nodes it targets (`sh:targetNode`). */
        fun shapesGraph(targets: Map<Int, List<Int>>): RdfGraph {
            val sb = StringBuilder(PREFIXES)
            shapes.forEachIndexed { s, conjuncts ->
                sb.append("ex:S$s a sh:NodeShape .\n")
                targets[s].orEmpty().forEach { sb.append("ex:S$s sh:targetNode ex:n$it .\n") }
                conjuncts.forEachIndexed { i, c ->
                    val ps = "ex:S${s}_c$i"
                    when (c) {
                        Conjunct.HasName -> sb.append("ex:S$s sh:property $ps . $ps sh:path ex:name ; sh:minCount 1 .\n")
                        is Conjunct.All -> sb.append("ex:S$s sh:property $ps . $ps sh:path ex:${c.pred} ; sh:node ex:S${c.shape} .\n")
                        is Conjunct.None -> sb.append("ex:S$s sh:property $ps . $ps sh:path ex:${c.pred} ; sh:not ex:S${c.shape} .\n")
                        is Conjunct.Some ->
                            sb.append("ex:S$s sh:property $ps . $ps sh:path ex:${c.pred} ; sh:qualifiedValueShape ex:S${c.shape} ; sh:qualifiedMinCount 1 .\n")
                        is Conjunct.AtMost ->
                            sb.append("ex:S$s sh:property $ps . $ps sh:path ex:${c.pred} ; sh:qualifiedValueShape ex:S${c.shape} ; sh:qualifiedMaxCount ${c.max} .\n")
                        is Conjunct.Or -> sb.append("ex:S$s sh:or ( ex:S${c.a} ex:S${c.b} ) .\n")
                        is Conjunct.Xone -> sb.append("ex:S$s sh:xone ( ex:S${c.a} ex:S${c.b} ) .\n")
                        is Conjunct.Not -> sb.append("ex:S$s sh:not ex:S${c.shape} .\n")
                        is Conjunct.Node -> sb.append("ex:S$s sh:node ex:S${c.shape} .\n")
                    }
                }
            }
            return Rdf.parse(sb.toString(), RdfFormat.TURTLE)
        }

        fun describe(): String = "seed=$seed nodes=$nodes named=$named edges=$edges shapes=$shapes"
    }

    private fun generate(seed: Long): Case {
        val random = Random(seed)
        val nodes = 1 + random.nextInt(4)
        val shapeCount = 1 + random.nextInt(3)
        val named = (0 until nodes).filter { random.nextInt(3) != 0 }.toSet()
        val predicates = listOf("p", "q")
        val edges = LinkedHashMap<Pair<Int, String>, List<Int>>()
        for (n in 0 until nodes) for (pred in predicates) {
            val targets = (0 until nodes).filter { random.nextInt(100) < 35 }
            if (targets.isNotEmpty()) edges[n to pred] = targets
        }
        fun shape() = random.nextInt(shapeCount)
        fun pred() = predicates[random.nextInt(predicates.size)]
        val shapes = List(shapeCount) {
            List(1 + random.nextInt(3)) {
                when (random.nextInt(11)) {
                    0, 1 -> Conjunct.HasName
                    2, 3 -> Conjunct.All(pred(), shape())
                    4, 5 -> Conjunct.None(pred(), shape())
                    6 -> Conjunct.Some(pred(), shape())
                    7 -> Conjunct.AtMost(pred(), shape(), random.nextInt(2))
                    8 -> Conjunct.Or(shape(), shape())
                    9 -> Conjunct.Xone(shape(), shape())
                    else -> if (random.nextBoolean()) Conjunct.Not(shape()) else Conjunct.Node(shape())
                }
            }.distinct()
        }
        return Case(seed, nodes, named, edges, shapes)
    }

    /** The engine's three-valued answer for every (focus node, top-level shape) of [report]. */
    private fun engineAnswers(report: ValidationReport, questions: Collection<Atom>): Map<Atom, V> {
        fun node(term: RdfTerm) = (term as Iri).value.substringAfterLast("/n").toInt()
        fun shape(uri: String) = uri.substringAfterLast("/S").substringBefore("_").toInt()
        val results = report.violations.groupBy { Atom(node(it.focusNode), shape(it.shapeUri!!)) }
        return questions.associateWith { q ->
            val rows = results[q].orEmpty()
            when {
                rows.isEmpty() -> V.C
                rows.all { it.isUndefinedRecursion } -> V.U
                else -> V.F
            }
        }
    }

    private fun validator() = NativeShaclValidator(ValidationConfig(maxViolations = 100_000))

    @Test
    fun `incremental solver agrees with the naive oracle on random recursive shape graphs with negation`() {
        var undefined = 0
        var failing = 0
        var questions = 0
        for (seed in 1L..400L) {
            val case = generate(seed)
            val expected = case.oracle()
            val data = case.dataGraph()
            val atoms = expected.keys
            // 1. Every shape targets every node: the questions are answered in one run and share the memo.
            val allTargets = case.shapes.indices.associateWith { (0 until case.nodes).toList() }
            val together = engineAnswers(validator().validate(data, case.shapesGraph(allTargets)), atoms)
            assertEquals(expected, together, "all targets in one run: ${case.describe()}")
            // 2. Each question alone, solved from a cold start.
            for (atom in atoms) {
                val alone = engineAnswers(validator().validate(data, case.shapesGraph(mapOf(atom.shape to listOf(atom.node)))), listOf(atom))
                assertEquals(expected.getValue(atom), alone.getValue(atom), "single target $atom: ${case.describe()}")
            }
            questions += atoms.size
            undefined += expected.values.count { it == V.U }
            failing += expected.values.count { it == V.F }
        }
        // The generator must actually reach the interesting regions (deterministic for the fixed seeds).
        assertTrue(undefined >= 50, "undefined answers: $undefined of $questions")
        assertTrue(failing >= 200, "failing answers: $failing of $questions")
        assertTrue(questions - undefined - failing >= 200, "conforming answers: ${questions - undefined - failing} of $questions")
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
