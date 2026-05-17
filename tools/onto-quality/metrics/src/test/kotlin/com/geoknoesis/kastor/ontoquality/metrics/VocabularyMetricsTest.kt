package com.geoknoesis.kastor.ontoquality.metrics

import com.geoknoesis.kastor.rdf.Rdf
import kotlinx.serialization.json.Json
import org.apache.jena.rdf.model.ModelFactory
import java.io.StringReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VocabularyMetricsTest {
    private fun loadFixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    private fun parse(ttl: String) = Rdf.parse(ttl, "TURTLE")

    private fun wideTreeTtl(children: Int = 50): String =
        buildString {
            appendLine("@prefix : <http://example.org/w/> .")
            appendLine("@prefix owl: <http://www.w3.org/2002/07/owl#> .")
            appendLine("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .")
            appendLine(":A a owl:Class .")
            for (i in 1..children) {
                appendLine(":C$i a owl:Class .")
                appendLine(":C$i rdfs:subClassOf :A .")
            }
        }

    @Test
    fun `empty graph - all OQuaRE metrics not computable`() {
        val g = Rdf.graph {}
        val r = VocabularyMetrics.compute(g)
        for (m in r.owl.oquare.toList()) {
            assertFalse(m.computable, m.metricIri)
        }
    }

    @Test
    fun `single class - NOC and CBO not computable`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("single-class.ttl")))
        assertFalse(r.owl.oquare.numberOfChildren.computable)
        assertFalse(r.owl.oquare.couplingBetweenObjects.computable)
        assertTrue(r.owl.oquare.depthOfInheritanceTree.computable)
        assertEquals(0.0, r.owl.oquare.depthOfInheritanceTree.rawValue, EPS)
    }

    @Test
    fun `linear chain A to D - DIT and NAC`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("linear-chain.ttl")))
        val oq = r.owl.oquare
        assertEquals(3.0, oq.depthOfInheritanceTree.rawValue, EPS)
        assertEquals(1.0, oq.numberOfAncestorClasses.rawValue, EPS)
        assertEquals(1.0, oq.numberOfChildren.rawValue, EPS)
        assertEquals(1.0, oq.tangledness.rawValue, EPS)
    }

    @Test
    fun `wide tree fan-out - NOC and DIT`() {
        val r = VocabularyMetrics.compute(parse(wideTreeTtl(50)))
        val oq = r.owl.oquare
        assertEquals(1.0, oq.depthOfInheritanceTree.rawValue, EPS)
        assertEquals(1.0, oq.numberOfChildren.rawValue, EPS)
        assertEquals(1.0, oq.numberOfAncestorClasses.rawValue, EPS)
    }

    @Test
    fun `diamond - NAC mean direct supers on leaf`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("diamond.ttl")))
        assertEquals(2.0, r.owl.oquare.numberOfAncestorClasses.rawValue, EPS)
        assertEquals(2.0, r.owl.oquare.depthOfInheritanceTree.rawValue, EPS)
        assertEquals(2.0, r.owl.oquare.tangledness.rawValue, EPS)
    }

    @Test
    fun `cycle A B A - cycle participants counted`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("cycle.ttl")))
        assertEquals(2L, r.owl.extensions.classHierarchyDepth.cyclesDetected)
        assertEquals(2, r.owl.extensions.classHierarchyDepth.cycleParticipants.size)
    }

    @Test
    fun `SKOS sibling cohorts fixture`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("skos-sibling-cohorts.ttl")))
        val sk = r.skos
        assertEquals(9L, sk.conceptCount.rawValue.toLong())
        assertEquals(3.0, sk.siblingCohorts.cohortCount.rawValue, EPS)
        assertEquals(2.0, sk.siblingCohorts.maxCohortSize.rawValue, EPS)
        assertEquals(2L, sk.structuralEdgeCounts.relatedEdges)
    }

    @Test
    fun `empty SKOS section counts zero concepts`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("linear-chain.ttl")))
        assertEquals(0.0, r.skos.conceptCount.rawValue, EPS)
        assertFalse(r.skos.prefLabelCoverage.computable)
        assertFalse(r.skos.definitionCoverage.computable)
    }

    @Test
    fun `determinism - same graph yields equal reports aside from timestamp`() {
        val g = parse(loadFixture("small-synthetic.ttl"))
        val r1 = VocabularyMetrics.compute(g)
        val r2 = VocabularyMetrics.compute(g)
        assertEquals(r1.copy(computedAt = r2.computedAt), r2)
    }

    @Test
    fun `JSON output parses`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("linear-chain.ttl")))
        Json.parseToJsonElement(r.toJson())
    }

    @Test
    fun `Turtle output parses with Jena`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("linear-chain.ttl")))
        val m = ModelFactory.createDefaultModel()
        m.read(StringReader(r.toTurtle()), null, "TTL")
    }

    @Test
    fun `Markdown output non-empty`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("linear-chain.ttl")))
        assertTrue(r.describeMarkdown().contains("OQuaRE"))
    }

    companion object {
        private const val EPS = 1e-9
    }
}
