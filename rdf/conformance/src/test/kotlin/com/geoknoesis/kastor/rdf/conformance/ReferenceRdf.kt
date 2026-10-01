package com.geoknoesis.kastor.rdf.conformance

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import org.apache.jena.datatypes.TypeMapper
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.graph.TextDirection
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.riot.RDFParser
import org.apache.jena.sparql.core.DatasetGraph
import org.apache.jena.sparql.core.DatasetGraphFactory
import org.apache.jena.sparql.core.Quad
import org.apache.jena.sparql.util.IsoMatcher
import java.io.StringWriter
import java.nio.file.Path

/** One statement produced by the provider under test; [graph] is the graph IRI, or null for the default graph. */
data class KastorQuad(val graph: String?, val triple: RdfTriple)

/**
 * Reference side of the eval comparison, independent of the providers under test.
 *
 * - Expected result files are parsed with Jena RIOT **directly** and kept as raw Jena nodes, so a bug in a
 *   provider's parser or term adapter cannot also corrupt the expectation (symmetric bugs would cancel).
 * - The provider's output (Kastor terms) is mapped to Jena nodes by the minimal mapping below, which lives
 *   in the test sources and does not use any provider adapter.
 * - Results are compared as **datasets** with a single isomorphism over all quads, so blank nodes shared
 *   between graphs must correspond consistently across graphs.
 */
object ReferenceRdf {

    /**
     * Datatype prefix standing in for a language tag. Jena canonicalises tag case whenever it creates a
     * language-tagged node (`en-gb` becomes `en-GB`), which would hide tag-case bugs on both sides of the
     * comparison, so language-tagged literals are compared as typed literals whose datatype spells the tag (and
     * base direction) exactly as written.
     */
    private const val LANG_TAG_DATATYPE = "urn:kastor:conformance:langtag:"

    /** Language-tagged literal with its tag (and direction) spelled exactly, see [LANG_TAG_DATATYPE]. */
    fun exactLangLiteral(lexical: String, tag: String, direction: String?): Node =
        NodeFactory.createLiteralDT(
            lexical,
            TypeMapper.getInstance().getSafeTypeByName(LANG_TAG_DATATYPE + tag + (direction?.let { "--$it" } ?: "")),
        )

    /** RIOT node factory that keeps language tags exactly as they appear in the expected file. */
    private class ExactLangTagFactory : org.apache.jena.riot.system.FactoryRDFStd() {
        override fun createLangLiteral(lexical: String, langTag: String): Node = exactLangLiteral(lexical, langTag, null)
        override fun createLangDirLiteral(lexical: String, langTag: String, direction: String): Node = exactLangLiteral(lexical, langTag, direction)
    }

    /** Parses [path] with Jena RIOT into a dataset of raw nodes. Triple formats fill the default graph. */
    fun parse(path: Path, format: TestFormat, base: String?): DatasetGraph {
        val lang = when (format) {
            TestFormat.TURTLE -> Lang.TURTLE
            TestFormat.TRIG -> Lang.TRIG
            TestFormat.N_TRIPLES -> Lang.NTRIPLES
            TestFormat.N_QUADS -> Lang.NQUADS
        }
        val dataset = DatasetGraphFactory.create()
        val builder = RDFParser.source(path).lang(lang).factory(ExactLangTagFactory())
        if (base != null) builder.base(base)
        builder.parse(dataset)
        return dataset
    }

    /**
     * Stable fingerprint of how [actual] differs from [expected]: the quads only in one of them, with blank nodes
     * anonymised, as a multiset difference in both directions, hashed. Independent of blank-node labels and quad order.
     */
    fun mismatchFingerprint(expected: DatasetGraph, actual: DatasetGraph): String {
        fun lines(dataset: DatasetGraph): Map<String, Int> {
            val out = StringWriter()
            RDFDataMgr.write(out, dataset, Lang.NQUADS)
            return out.toString().lineSequence().filter { it.isNotBlank() }
                .map { it.replace(Regex("_:[A-Za-z0-9_.\\-]+"), "_:b") }
                .groupingBy { it }.eachCount()
        }
        val want = lines(expected)
        val got = lines(actual)
        val diff = StringBuilder()
        for (line in (want.keys + got.keys).sorted()) {
            val delta = (got[line] ?: 0) - (want[line] ?: 0)
            if (delta != 0) diff.append(if (delta < 0) "-" else "+").append(Math.abs(delta)).append(' ').append(line).append('\n')
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(diff.toString().toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Prefix of the IRIs a provider mints for blank-node graph names when loading into a repository (which names
     * graphs by IRI only): `urn:kastor:skolem:<load>:<blank node id>`, `<load>` being 32 hex digits chosen per load.
     *
     * Such a graph is compared as **the blank node whose id it carries**, not as some anonymous blank node: the
     * isomorphism then requires distinct labels to stay distinct graphs, and a blank node that is both a graph name
     * and a term of a triple to be the same node in both places. An IRI in this namespace that is not of that form,
     * or one document whose skolem names come from different loads (one label with two names), is a harness error
     * rather than a match.
     */
    const val SKOLEM_GRAPH_PREFIX = "urn:kastor:skolem:"

    private val SKOLEM_GRAPH_NAME = Regex(Regex.escape(SKOLEM_GRAPH_PREFIX) + "([0-9a-f]{32}):([A-Za-z0-9._%-]+)")

    /** Builds a dataset of Jena nodes from provider output (the quads of one parsed document). */
    fun dataset(quads: Collection<KastorQuad>): DatasetGraph {
        val dataset = DatasetGraphFactory.create()
        var load: String? = null
        fun graphNode(name: String): Node {
            if (!name.startsWith(SKOLEM_GRAPH_PREFIX)) return NodeFactory.createURI(name)
            val match = requireNotNull(SKOLEM_GRAPH_NAME.matchEntire(name)) {
                "Malformed skolem graph name (expected $SKOLEM_GRAPH_PREFIX<32 hex digits>:<blank node id>): $name"
            }
            val (loadId, label) = match.destructured
            require(load == null || load == loadId) { "Skolem graph names of one document come from different loads: $load and $loadId" }
            load = loadId
            return NodeFactory.createBlankNode(java.net.URLDecoder.decode(label, Charsets.UTF_8))
        }
        for ((graph, triple) in quads) {
            dataset.add(
                Quad(
                    graph?.let(::graphNode) ?: Quad.defaultGraphIRI,
                    node(triple.subject),
                    NodeFactory.createURI(triple.predicate.value),
                    node(triple.obj),
                ),
            )
        }
        return dataset
    }

    /** True when both datasets are isomorphic, with one blank-node bijection across all graphs. */
    fun isomorphic(expected: DatasetGraph, actual: DatasetGraph): Boolean = IsoMatcher.isomorphic(expected, actual)

    /** Minimal, adapter-independent Kastor term to Jena node mapping. */
    fun node(term: RdfTerm): Node = when (term) {
        is Iri -> NodeFactory.createURI(term.value)
        is BlankNode -> NodeFactory.createBlankNode(term.id)
        is TripleTerm -> NodeFactory.createTripleTerm(
            node(term.triple.subject),
            NodeFactory.createURI(term.triple.predicate.value),
            node(term.triple.obj),
        )
        // Tag spelled exactly as the provider produced it (Jena's lang-literal factory would canonicalise its case).
        is LangString -> exactLangLiteral(term.lexical, term.lang, term.direction?.token)
        is Literal -> NodeFactory.createLiteralDT(term.lexical, TypeMapper.getInstance().getSafeTypeByName(term.datatype.value))
        else -> throw IllegalArgumentException("Unsupported term ${term.javaClass}")
    }

    /** Sorted N-Quads preview for assertion messages. */
    fun preview(dataset: DatasetGraph, maxLines: Int = 40): String {
        val out = StringWriter()
        RDFDataMgr.write(out, dataset, Lang.NQUADS)
        val lines = out.toString().lineSequence().filter { it.isNotBlank() }.sorted().toList()
        val head = lines.take(maxLines).joinToString("\n    ", prefix = "    ")
        return if (lines.size > maxLines) "$head\n    ... (${lines.size - maxLines} more)" else head
    }

    fun size(dataset: DatasetGraph): Long {
        var count = 0L
        dataset.find().forEachRemaining { count++ }
        return count
    }
}
