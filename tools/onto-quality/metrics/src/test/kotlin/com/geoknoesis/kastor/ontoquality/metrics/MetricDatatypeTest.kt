package com.geoknoesis.kastor.ontoquality.metrics

import com.geoknoesis.kastor.rdf.Rdf
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.vocabulary.RDFS
import org.junit.jupiter.api.Test
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A metric is written with the datatype its property declares in `kastor-metrics.ttl`, whatever its value. */
class MetricDatatypeTest {
    private val xsd = "http://www.w3.org/2001/XMLSchema#"

    private fun declaredRanges(): Map<String, String> {
        val text = javaClass.getResourceAsStream("/vocab/kastor-metrics.ttl")!!.bufferedReader().readText()
        val vocab = ModelFactory.createDefaultModel()
        vocab.read(StringReader(text), null, "TTL")
        val metric = vocab.createResource("${KastorMetricsVocab.NS}Metric")
        val out = HashMap<String, String>()
        val it = vocab.listSubjectsWithProperty(org.apache.jena.vocabulary.RDF.type, metric)
        while (it.hasNext()) {
            val m = it.next()
            m.getProperty(RDFS.range)?.let { range -> out[m.uri] = range.resource.uri }
        }
        return out
    }

    private fun emittedDatatypes(ttl: String): Map<String, String> {
        val report = VocabularyMetrics.compute(Rdf.parse(ttl, "TURTLE"))
        val m = ModelFactory.createDefaultModel()
        m.read(StringReader(report.toTurtle()), null, "TTL")
        val out = HashMap<String, String>()
        val it = m.listStatements()
        while (it.hasNext()) {
            val st = it.next()
            if (st.predicate.uri.startsWith(KastorMetricsVocab.NS) && st.`object`.isLiteral) {
                out[st.predicate.uri] = st.literal.datatypeURI
            }
        }
        return out
    }

    private val wholeValues =
        """
        @prefix : <http://example.org/dt#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
        :A a owl:Class . :B a owl:Class ; rdfs:subClassOf :A . :C a owl:Class ; rdfs:subClassOf :A .
        :c1 a skos:Concept ; skos:prefLabel "one"@en ; skos:definition "one"@en .
        """.trimIndent()

    private val fractionalValues =
        """
        @prefix : <http://example.org/dt#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
        :A a owl:Class . :B a owl:Class ; rdfs:subClassOf :A . :C a owl:Class ; rdfs:subClassOf :A .
        :D a owl:Class ; rdfs:subClassOf :B , :C . :E a owl:Class ; rdfs:subClassOf :D .
        :p a owl:ObjectProperty ; rdfs:domain :A ; rdfs:range :B .
        :c1 a skos:Concept ; skos:prefLabel "one"@en .
        :c2 a skos:Concept . :c3 a skos:Concept ; skos:definition "three"@en .
        """.trimIndent()

    @Test
    fun `every emitted metric has the datatype declared in the vocabulary`() {
        val declared = declaredRanges()
        for (ttl in listOf(wholeValues, fractionalValues)) {
            val emitted = emittedDatatypes(ttl).filterKeys { it in declared }
            assertTrue(emitted.size >= 10, "metrics emitted: ${emitted.keys}")
            for ((metric, datatype) in emitted) {
                assertEquals(declared.getValue(metric), datatype, metric)
            }
        }
    }

    @Test
    fun `a metric keeps its datatype when its value happens to be whole`() {
        val whole = emittedDatatypes(wholeValues)
        val fractional = emittedDatatypes(fractionalValues)
        // 1 of 1 concepts has a prefLabel (1.0) versus 1 of 3 (0.3333): the same metric, the same datatype.
        assertEquals("${xsd}decimal", whole.getValue(KastorMetricsVocab.prefLabelCoverage))
        assertEquals("${xsd}decimal", fractional.getValue(KastorMetricsVocab.prefLabelCoverage))
        assertEquals("${xsd}integer", whole.getValue(KastorMetricsVocab.conceptCount))
        assertEquals("${xsd}integer", whole.getValue(KastorMetricsVocab.depthOfInheritanceTree))
        for ((metric, datatype) in whole) {
            fractional[metric]?.let { assertEquals(it, datatype, metric) }
        }
    }

    @Test
    fun `the integer metrics of the serializer are the integer ranges of the vocabulary`() {
        val integers = declaredRanges().filterValues { it == "${xsd}integer" }.keys
        assertEquals(integers, KastorMetricsVocab.integerMetrics)
    }
}
