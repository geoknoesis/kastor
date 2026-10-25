package com.geoknoesis.kastor.rdf.cli

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.GraphIsomorphismLimitException
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.serialize
import java.io.PrintStream
import java.nio.file.Path
import java.util.PriorityQueue
import kotlin.io.path.name

/**
 * `diff`: exit [EXIT_OK] when the inputs are isomorphic (datasets: the default graph and all named graphs together,
 * graph names included, up to blank node relabelling), [EXIT_NOT_ISOMORPHIC] with an explanation on [err] otherwise.
 */
internal fun cmdDiff(rest: List<String>, out: PrintStream, err: PrintStream, runtime: CliRuntime): Int {
    val arguments = DiffArguments(rest)
    val fmt = arguments.positional.getOrNull(2)
    val p1 = requireRegular(Path.of(arguments.positional[0]))
    val p2 = requireRegular(Path.of(arguments.positional[1]))
    val format1 = formatOrInfer(p1, fmt)
    val format2 = formatOrInfer(p2, fmt)
    val first = read(p1, format1, runtime, arguments.baseIri)
    val second = read(p2, format2, runtime, arguments.baseIri)
    val timeout = arguments.timeout
    val maxSearchStates = arguments.maxSearchStates
    // One deadline for the whole comparison: a dataset is compared as a whole and then, to say where it differs,
    // graph by graph. Each of those checks gets what is left of --timeout, not the full limit again.
    val started = runtime.nanoTime()

    fun stopped(reason: GraphIsomorphismLimitException.Reason, message: String?): CliError {
        val advice =
            when (reason) {
                GraphIsomorphismLimitException.Reason.TIME ->
                    " (limit: ${timeout?.seconds ?: 0} s for the whole comparison; raise it with --timeout <seconds>, 0 = no limit)"
                GraphIsomorphismLimitException.Reason.SEARCH_STATES ->
                    " (limit: ${maxSearchStates ?: 0} search states; raise it with --max-search-states <n>, 0 = no limit; --timeout does not change it)"
                GraphIsomorphismLimitException.Reason.WORK ->
                    " (work limit, which scales with the size of the inputs; --max-search-states 0 lifts it, --timeout does not change it)"
                else -> " (${reason.name.lowercase().replace('_', ' ')} limit; --timeout and --max-search-states do not change it)"
            }
        return CliError("kastor-rdf: error: diff stopped without an answer: ${oneLine(message)}$advice", EXIT_RUNTIME_ERROR)
    }

    fun isomorphic(a: RdfGraph, b: RdfGraph): Boolean {
        val remaining =
            timeout?.let {
                val left = it.minusNanos(runtime.nanoTime() - started)
                if (left.isZero || left.isNegative) throw stopped(GraphIsomorphismLimitException.Reason.TIME, "Graph isomorphism time limit exceeded")
                left
            }
        return try {
            runtime.isomorphic(a, b, IsomorphismLimits(remaining, maxSearchStates))
        } catch (e: GraphIsomorphismLimitException) {
            throw stopped(e.reason, e.message)
        }
    }

    // One isomorphism check decides: on the graphs themselves, or (datasets) on the quads, so that blank nodes shared
    // across graphs, those that name graphs among them, must correspond. Graphs named by IRIs must have the same IRIs;
    // graphs named by blank nodes have no name two inputs can share, only their number must agree before the check.
    val sameIris = first.iriGraphs.keys == second.iriGraphs.keys
    val sameNames = sameIris && first.blankGraphCount == second.blankGraphCount
    val dataset = first.namedGraphs.isNotEmpty() || second.namedGraphs.isNotEmpty()
    val same =
        sameNames &&
            if (dataset) isomorphic(first.asQuadGraph(), second.asQuadGraph()) else isomorphic(first.defaultGraph, second.defaultGraph)
    if (same) {
        out.println(
            "ISOMORPHIC - datasets match up to blank node relabelling " +
                "(${first.tripleCount} triples in first file, ${second.tripleCount} in second).",
        )
        return EXIT_OK
    }

    // The answer is known from here on: a limit reached while explaining where the inputs differ only leaves that
    // graph unexplained, it does not take the answer back.
    val differences = mutableListOf<String>()
    val unexplained = mutableListOf<String>()
    var unexplainedBecause: String? = null
    if (!sameIris) {
        differences += "named graphs differ: ${first.iriGraphs.keys.sorted()} vs ${second.iriGraphs.keys.sorted()}"
    }
    if (first.blankGraphCount != second.blankGraphCount) {
        differences += "graphs named by blank nodes: ${first.blankGraphCount} vs ${second.blankGraphCount}"
    }
    val pairs = listOf<Pair<String, Pair<RdfGraph, RdfGraph>>>("(default graph)" to (first.defaultGraph to second.defaultGraph)) +
        first.iriGraphs.keys.intersect(second.iriGraphs.keys).sorted()
            .map { "<$it>" to (first.iriGraphs.getValue(it) to second.iriGraphs.getValue(it)) }
    for ((label, graphs) in pairs) {
        val left = DifferenceSample(graphs.first, graphs.second)
        val right = DifferenceSample(graphs.second, graphs.first)
        // Different triples without blank nodes, or different sizes, settle it; otherwise (datasets only: for graph
        // formats the check above already answered) the graph is compared on its own.
        val differs =
            left.onlyHere > 0 || right.onlyHere > 0 || graphs.first.size() != graphs.second.size() ||
                !dataset ||
                try {
                    !isomorphic(graphs.first, graphs.second)
                } catch (e: CliError) {
                    // isomorphic() only throws the error of a reached limit.
                    unexplained += label
                    unexplainedBecause = unexplainedBecause ?: e.message?.substringAfter("diff stopped without an answer: ")
                    false
                }
        if (differs) {
            differences += "$label is not isomorphic"
            left.print(err, label, p1.name)
            right.print(err, label, p2.name)
        }
    }
    if (unexplained.isNotEmpty()) {
        differences += "not compared on their own, a limit was reached: ${unexplained.joinToString(", ")}: $unexplainedBecause"
    } else if (differences.isEmpty()) {
        differences +=
            if (first.blankGraphCount > 0) {
                "the default graph and the graphs named by IRIs are isomorphic on their own; the difference is in the " +
                    "${first.blankGraphCount} graph(s) named by blank nodes (compared only as part of the whole dataset) " +
                    "or in blank nodes shared across graphs"
            } else {
                "every graph is isomorphic on its own, but blank nodes shared across graphs do not correspond"
            }
    }
    err.println("NOT ISOMORPHIC")
    differences.forEach { err.println("  $it") }
    return EXIT_NOT_ISOMORPHIC
}

/** Lines of a difference sample shown per input and graph. */
internal const val SAMPLE_LINES = 64

/**
 * What [graph] has that [other] lacks: the [SAMPLE_LINES] smallest triples without blank nodes that are not in
 * [other], found in one pass with a bounded priority queue (nothing is sorted or serialised in full).
 *
 * When there are none, the sample is of the triples with blank nodes (their labels cannot be compared across files,
 * so all of them are candidates). Parser labels differ on every parse, so that sample is chosen and printed without
 * them ([blankSample]): two more passes over the graph, made only when the sample is printed.
 */
private class DifferenceSample(private val graph: RdfGraph, other: RdfGraph) {
    private val ground = PriorityQueue(SAMPLE_LINES + 1, compareByDescending<Pair<String, RdfTriple>> { it.first })

    /** Triples without blank nodes that [other] does not have. */
    var onlyHere = 0
        private set
    private var withBlankNodes = 0

    init {
        for (t in graph.getTriplesSequence()) {
            if (hasBlankNode(t.subject) || hasBlankNode(t.obj)) {
                withBlankNodes++
            } else if (!other.hasTriple(t)) {
                onlyHere++
                offer(ground, "${t.subject} ${t.predicate} ${t.obj}", t)
            }
        }
    }

    private fun offer(queue: PriorityQueue<Pair<String, RdfTriple>>, key: String, t: RdfTriple) {
        if (queue.size < SAMPLE_LINES) {
            queue += key to t
        } else if (key < queue.peek().first) {
            queue.poll()
            queue += key to t
        }
    }

    private fun hasBlankNode(term: RdfTerm): Boolean =
        term is BlankNode || (term is TripleTerm && (hasBlankNode(term.triple.subject) || hasBlankNode(term.triple.obj)))

    /** [term] with every blank node written by [blank] (labels never reach the text). */
    private fun render(term: RdfTerm, blank: (BlankNode) -> String): String =
        when (term) {
            is BlankNode -> blank(term)
            is TripleTerm -> "<<( ${render(term.triple.subject, blank)} ${term.triple.predicate} ${render(term.triple.obj, blank)} )>>"
            else -> term.toString()
        }

    private fun render(t: RdfTriple, blank: (BlankNode) -> String): String = "${render(t.subject, blank)} ${t.predicate} ${render(t.obj, blank)}"

    private fun blankNodes(term: RdfTerm, into: MutableList<BlankNode>) {
        when (term) {
            is BlankNode -> into += term
            is TripleTerm -> {
                blankNodes(term.triple.subject, into)
                blankNodes(term.triple.obj, into)
            }
            else -> Unit
        }
    }

    /**
     * The [SAMPLE_LINES] smallest triples with blank nodes in an order that does not depend on labels, with their
     * blank nodes renamed `b1`, `b2`, … in order of appearance.
     *
     * A blank node is first described by its triples (position, predicate and the other term, other blank nodes left
     * anonymous), accumulated into one number per node; a triple is then ordered by its text with every blank node
     * replaced by that number. Triples that this cannot tell apart (structurally alike nodes) may be taken in either
     * order: they print as the same lines.
     */
    private fun blankSample(): List<RdfTriple> {
        val signatures = HashMap<BlankNode, Long>()
        val nodes = ArrayList<BlankNode>(2)
        for (t in graph.getTriplesSequence()) {
            nodes.clear()
            blankNodes(t.subject, nodes)
            blankNodes(t.obj, nodes)
            if (nodes.isEmpty()) continue
            val shape = render(t) { "_:" }
            nodes.forEachIndexed { position, node -> signatures.merge(node, hash("$position $shape"), Long::plus) }
        }
        val queue = PriorityQueue(SAMPLE_LINES + 1, compareByDescending<Pair<String, RdfTriple>> { it.first })
        for (t in graph.getTriplesSequence()) {
            if (!hasBlankNode(t.subject) && !hasBlankNode(t.obj)) continue
            offer(queue, render(t) { "_:" + java.lang.Long.toHexString(signatures.getValue(it)) }, t)
        }
        val names = HashMap<BlankNode, BlankNode>()

        fun rename(term: RdfTerm): RdfTerm =
            when (term) {
                is BlankNode -> names.getOrPut(term) { BlankNode("b${names.size + 1}") }
                is TripleTerm -> TripleTerm(RdfTriple(rename(term.triple.subject) as RdfResource, term.triple.predicate, rename(term.triple.obj)))
                else -> term
            }
        return queue.sortedBy { it.first }.map { (_, t) -> RdfTriple(rename(t.subject) as RdfResource, t.predicate, rename(t.obj)) }
    }

    /** FNV-1a, 64 bits: a fixed hash, so the order of the sample is the same in every JVM. */
    private fun hash(text: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in text) {
            h = (h xor c.code.toLong()) * 0x100000001b3L
        }
        return h
    }

    fun print(err: PrintStream, label: String, file: String) {
        val blank = onlyHere == 0
        val total = if (blank) withBlankNodes else onlyHere
        if (total == 0) return
        val sample = if (blank) blankSample() else ground.map { it.second }
        val title =
            if (blank) {
                "triples with blank nodes in $file (numbered in order of appearance); its other triples are all in the other input"
            } else {
                "triples only in $file"
            }
        err.println("--- $label: $title (sorted N-Triples, first lines) ---")
        val lines = MemoryGraph(sample).serialize(RdfFormat.N_TRIPLES).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        lines.forEach(err::println)
        if (total > sample.size) err.println("... (${total - sample.size} more)")
    }
}
