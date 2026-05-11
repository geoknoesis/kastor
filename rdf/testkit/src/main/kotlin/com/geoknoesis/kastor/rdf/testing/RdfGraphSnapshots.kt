package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.jena.JenaBridge
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import java.io.StringWriter

/**
 * Diagnostics helpers: stable, sorted N-Triples views of a graph for assertion messages
 * and golden-file review (blank node labels are implementation-specific).
 */
object RdfGraphSnapshots {

    private const val DEFAULT_SNIPPET_LINES = 64

    fun sortedNtriplesLines(graph: RdfGraph): List<String> {
        val model = JenaBridge.toJenaModel(graph)
        val sw = StringWriter()
        RDFDataMgr.write(sw, model, Lang.NTRIPLES)
        return sw.toString()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .toList()
    }

    fun sortedNtriplesString(graph: RdfGraph): String =
        sortedNtriplesLines(graph).joinToString("\n")

    fun formatSnippet(graph: RdfGraph, maxLines: Int = DEFAULT_SNIPPET_LINES): String {
        val lines = sortedNtriplesLines(graph)
        val head = lines.take(maxLines).joinToString("\n")
        val omitted = lines.size - maxLines
        return if (omitted > 0) {
            head + "\n... ($omitted more lines)"
        } else {
            head
        }
    }
}
