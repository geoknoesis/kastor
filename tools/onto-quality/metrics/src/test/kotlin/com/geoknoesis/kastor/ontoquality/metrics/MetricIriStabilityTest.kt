package com.geoknoesis.kastor.ontoquality.metrics

import com.geoknoesis.kastor.rdf.Rdf
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.vocabulary.OWL2
import org.junit.jupiter.api.Test
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A metric IRI never changes meaning between releases. NOCOnto, CBOOnto and TMOnto were computed with different
 * formulas in 0.2.x; the published-formula metrics therefore have their own IRIs and the 0.2.x IRIs are retired
 * (owl:deprecated, never emitted).
 */
class MetricIriStabilityTest {
    private val retired =
        mapOf(
            "${KastorMetricsVocab.NS}numberOfChildren" to KastorMetricsVocab.numberOfChildrenOquare,
            "${KastorMetricsVocab.NS}couplingBetweenObjects" to KastorMetricsVocab.couplingBetweenObjectsOquare,
            "${KastorMetricsVocab.NS}tangledness" to KastorMetricsVocab.tanglednessOquare,
        )

    private val ttl =
        """
        @prefix : <http://example.org/s/> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        :A a owl:Class . :B a owl:Class ; rdfs:subClassOf :A . :C a owl:Class ; rdfs:subClassOf :A , :B .
        """.trimIndent()

    @Test
    fun `published NOC, CBO and TM metrics use new IRIs and retired IRIs are never emitted`() {
        val report = VocabularyMetrics.compute(Rdf.parse(ttl, "TURTLE"))
        val oq = report.owl.oquare
        assertEquals(KastorMetricsVocab.numberOfChildrenOquare, oq.numberOfChildren.metricIri)
        assertEquals(KastorMetricsVocab.couplingBetweenObjectsOquare, oq.couplingBetweenObjects.metricIri)
        assertEquals(KastorMetricsVocab.tanglednessOquare, oq.tangledness.metricIri)

        val empty = VocabularyMetrics.compute(Rdf.graph {})
        val emitted = (oq.toList() + empty.owl.oquare.toList()).map { it.metricIri }.toSet()
        for (old in retired.keys) {
            assertFalse(old in emitted, "retired IRI $old emitted")
            assertFalse(report.toTurtle().contains("<$old>"), "retired IRI $old in Turtle")
            assertFalse(report.toJson().contains("\"$old\""), "retired IRI $old in JSON")
        }
    }

    @Test
    fun `vocabulary declares the new IRIs and deprecates the retired ones`() {
        val m = ModelFactory.createDefaultModel()
        m.read(StringReader(javaClass.getResourceAsStream("/vocab/kastor-metrics.ttl")!!.bufferedReader().readText()), null, "TTL")
        val replacedBy = m.createProperty("http://purl.org/dc/terms/isReplacedBy")
        for ((old, new) in retired) {
            val oldRes = m.getResource(old)
            assertTrue(m.containsLiteral(oldRes, OWL2.deprecated, true), "$old must be owl:deprecated")
            assertTrue(m.contains(oldRes, replacedBy, m.getResource(new)), "$old must be dct:isReplacedBy $new")
            assertTrue(m.contains(m.getResource(new), null as org.apache.jena.rdf.model.Property?), "$new must be declared")
            assertFalse(m.containsLiteral(m.getResource(new), OWL2.deprecated, true))
        }
    }
}
