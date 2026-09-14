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

    /** Parses [path] with Jena RIOT into a dataset of raw nodes. Triple formats fill the default graph. */
    fun parse(path: Path, format: TestFormat, base: String?): DatasetGraph {
        val lang = when (format) {
            TestFormat.TURTLE -> Lang.TURTLE
            TestFormat.TRIG -> Lang.TRIG
            TestFormat.N_TRIPLES -> Lang.NTRIPLES
            TestFormat.N_QUADS -> Lang.NQUADS
        }
        val dataset = DatasetGraphFactory.create()
        val builder = RDFParser.source(path).lang(lang)
        if (base != null) builder.base(base)
        builder.parse(dataset)
        return dataset
    }

    /** Builds a dataset of Jena nodes from provider output. */
    fun dataset(quads: Collection<KastorQuad>): DatasetGraph {
        val dataset = DatasetGraphFactory.create()
        for ((graph, triple) in quads) {
            dataset.add(
                Quad(
                    graph?.let(NodeFactory::createURI) ?: Quad.defaultGraphIRI,
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
        is LangString -> term.direction?.let { NodeFactory.createLiteralDirLang(term.lexical, term.lang, TextDirection.create(it.token)) }
            ?: NodeFactory.createLiteralLang(term.lexical, term.lang)
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
