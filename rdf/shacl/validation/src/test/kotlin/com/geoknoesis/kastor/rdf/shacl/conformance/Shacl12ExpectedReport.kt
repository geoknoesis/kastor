package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource

/**
 * Expected `sh:ValidationReport` from a W3C `mf:result` node (or from a reference engine report).
 *
 * [results] contains the `sh:result` rows as an RDF graph rooted at [W3C_REPORT_NODE] (blank nodes kept),
 * including `sh:value`, `sh:sourceShape`, complete `sh:resultPath` structures and `sh:resultMessage`, and
 * excluding `sh:conforms` (asserted separately) and `sh:detail` (optional nested results, not compared).
 */
data class ExpectedConformanceReport(
    val conforms: Boolean,
    val results: RdfGraph,
    /** `sh:resultMessage` literals present in the expected rows; actual messages are compared only against these. */
    val expectedMessages: Set<Literal>,
    /**
     * `sh:conformanceDisallows` severities from the manifest (SHACL 1.2 validation parameter). When null, any
     * validation result makes the report non-conforming.
     */
    val conformanceDisallowsSeverityIrises: Set<String>? = null,
)

internal val W3C_REPORT_NODE = Iri("urn:x-kastor-test:report")

object Shacl12ExpectedReport {

    private const val SH = "http://www.w3.org/ns/shacl#"

    fun parse(model: Model, expectedReport: Resource): ExpectedConformanceReport {
        val conformsStmt =
            expectedReport.getProperty(model.createProperty(SH + "conforms"))
                ?: error("expected ValidationReport missing sh:conforms")
        val conforms = conformsStmt.boolean
        val messages = mutableSetOf<Literal>()
        val triples = mutableListOf<Triple<RdfResource, Iri, RdfTerm>>()

        fun addClosure(node: RDFNode, seen: MutableSet<Resource>) {
            if (!node.isAnon) return
            val r = node.asResource()
            if (!seen.add(r)) return
            for (st in r.listProperties().collectStatements()) {
                triples.add(Triple(lexicalPreservingTerm(r) as RdfResource, Iri(st.predicate.uri), lexicalPreservingTerm(st.`object`)))
                addClosure(st.`object`, seen)
            }
        }

        triples.add(Triple(W3C_REPORT_NODE, Iri(RDF_TYPE), Iri(SH + "ValidationReport")))
        for (rs in expectedReport.listProperties(model.createProperty(SH + "result")).collectStatements()) {
            val row = rs.`object`.asResource()
            val rowTerm = lexicalPreservingTerm(row) as RdfResource
            triples.add(Triple(W3C_REPORT_NODE, Iri(SH + "result"), rowTerm))
            for (st in row.listProperties().collectStatements()) {
                val p = st.predicate.uri
                if (p == SH + "detail") continue
                val obj = lexicalPreservingTerm(st.`object`)
                if (p == SH + "resultMessage" && obj is Literal) messages.add(obj)
                triples.add(Triple(rowTerm, Iri(p), obj))
                if (p == SH + "resultPath") addClosure(st.`object`, mutableSetOf())
            }
        }

        val disallows = expectedReport.listProperties(model.createProperty(SH + "conformanceDisallows")).collectStatements()
            .mapNotNull { it.`object`.takeIf { o -> o.isURIResource }?.asResource()?.uri }
            .toSet()
            .takeIf { it.isNotEmpty() }

        return ExpectedConformanceReport(
            conforms = conforms,
            results = Rdf.graph { triples.forEach { (s, p, o) -> triple(s, p, o) } },
            expectedMessages = messages,
            conformanceDisallowsSeverityIrises = disallows,
        )
    }

    private const val RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
}
