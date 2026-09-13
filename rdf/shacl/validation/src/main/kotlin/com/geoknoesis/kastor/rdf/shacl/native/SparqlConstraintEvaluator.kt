package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import java.time.Duration

internal object SparqlConstraintEvaluator {
    /** One provider repository per validation run, initialized only if a SPARQL constraint is evaluated. */
    class Session(graph: RdfGraph) : AutoCloseable {
        private val repo = Rdf.memory()
        init {
            try { repo.transaction { editDefaultGraph().addTriples(graph.getTriples()) } }
            catch (e: Throwable) { repo.close(); throw e }
        }
        fun selectReturnsRows(query: String, focus: RdfTerm?, timeout: Duration): Boolean = try {
            val bindings = if (focus == null) emptyMap() else mapOf("this" to focus)
            repo.withSelectRows(SparqlSelectQuery(query), bindings, timeout) { it.iterator().hasNext() }
        } catch (e: Exception) { throw ShaclValidationException("SPARQL constraint failed: ${e.message}", e) }
        override fun close() = repo.close()
    }
    fun selectReturnsRows(query: String, mergedDefaultGraph: RdfGraph, focusNode: RdfTerm? = null): Boolean =
        Session(mergedDefaultGraph).use { it.selectReturnsRows(query, focusNode, Duration.ofMinutes(5)) }
}
