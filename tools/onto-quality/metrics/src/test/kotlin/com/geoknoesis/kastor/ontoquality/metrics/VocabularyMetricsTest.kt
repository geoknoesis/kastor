package com.geoknoesis.kastor.ontoquality.metrics

import com.geoknoesis.kastor.rdf.Rdf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        for (m in r.owl.oquare.toList() + r.owl.kastorAdapted.toList()) {
            assertFalse(m.computable, m.metricIri)
        }
    }

    @Test
    fun `single class - NOC and CBO not computable, CBOOntoKastor zero, depth one below owl Thing`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("single-class.ttl")))
        // Published NOCOnto / CBOOnto divide by |C| - |Root| = 0.
        assertFalse(r.owl.oquare.numberOfChildren.computable)
        assertFalse(r.owl.oquare.couplingBetweenObjects.computable)
        assertEquals("no non-root classes", r.owl.oquare.couplingBetweenObjects.notes)
        assertFalse(r.owl.kastorAdapted.numberOfChildren.computable)
        assertTrue(r.owl.kastorAdapted.couplingBetweenObjects.computable)
        assertEquals(0.0, r.owl.kastorAdapted.couplingBetweenObjects.rawValue, EPS)
        assertEquals(0.0, r.owl.oquare.tangledness.rawValue, EPS)
        assertTrue(r.owl.oquare.depthOfInheritanceTree.computable)
        // An isolated class is a direct child of owl:Thing: depth 1, one path of length 1.
        assertEquals(1.0, r.owl.oquare.depthOfInheritanceTree.rawValue, EPS)
        assertEquals(1.0, r.owl.oquare.lackOfCohesionInMethods.rawValue, EPS)
    }

    @Test
    fun `linear chain A to D - DIT and NAC`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("linear-chain.ttl")))
        val oq = r.owl.oquare
        // Thing -> A (1) -> B (2) -> C (3) -> D (4).
        assertEquals(4.0, oq.depthOfInheritanceTree.rawValue, EPS)
        assertEquals(1.0, oq.numberOfAncestorClasses.rawValue, EPS)
        // 3 subclass edges over |C| - |Root| = 3 non-root classes.
        assertEquals(1.0, oq.numberOfChildren.rawValue, EPS)
        assertEquals(1.0, oq.couplingBetweenObjects.rawValue, EPS)
        // Kastor couplings: A 0, B {A}, C {B}, D {C} = 3 / 4 classes.
        assertEquals(0.75, r.owl.kastorAdapted.couplingBetweenObjects.rawValue, EPS)
        // No multiple inheritance: TMOnto is 0 (best band) in both definitions.
        assertEquals(0.0, oq.tangledness.rawValue, EPS)
        assertEquals(5, oq.tangledness.score)
        assertEquals(0.0, r.owl.kastorAdapted.tangledness.rawValue, EPS)
        assertEquals(5, r.owl.kastorAdapted.tangledness.score)
        // One path Thing-A-B-C-D of length 4.
        assertEquals(4.0, oq.lackOfCohesionInMethods.rawValue, EPS)
    }

    @Test
    fun `wide tree fan-out - NOC and DIT`() {
        val r = VocabularyMetrics.compute(parse(wideTreeTtl(50)))
        val oq = r.owl.oquare
        assertEquals(2.0, oq.depthOfInheritanceTree.rawValue, EPS)
        // Published: 50 subclass / superclass links over 51 - 1 non-root classes.
        assertEquals(1.0, oq.numberOfChildren.rawValue, EPS)
        assertEquals(1.0, oq.couplingBetweenObjects.rawValue, EPS)
        // Kastor-adapted: A is the only class with subclasses and has 50 of them; 50 couplings over 51 classes.
        assertEquals(50.0, r.owl.kastorAdapted.numberOfChildren.rawValue, EPS)
        assertEquals(1, r.owl.kastorAdapted.numberOfChildren.score)
        assertEquals(50.0 / 51.0, r.owl.kastorAdapted.couplingBetweenObjects.rawValue, EPS)
        assertEquals(1.0, oq.numberOfAncestorClasses.rawValue, EPS)
    }

    @Test
    fun `diamond - NAC mean direct supers on leaf`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("diamond.ttl")))
        assertEquals(2.0, r.owl.oquare.numberOfAncestorClasses.rawValue, EPS)
        assertEquals(3.0, r.owl.oquare.depthOfInheritanceTree.rawValue, EPS)
        // Published TMOnto: |{D}| / 4 classes.
        assertEquals(0.25, r.owl.oquare.tangledness.rawValue, EPS)
        assertEquals(5, r.owl.oquare.tangledness.score)
        assertEquals(2.0, r.owl.kastorAdapted.tangledness.rawValue, EPS)
        assertEquals(4, r.owl.kastorAdapted.tangledness.score)
    }

    @Test
    fun `TMOntoKastor bands distinguish two-parent tangling from no tangling`() {
        val scoring = com.geoknoesis.kastor.ontoquality.metrics.compute.OquareScoring
        assertEquals(listOf(5, 4, 3, 3, 2, 1), listOf(0.0, 2.0, 3.0, 4.0, 8.0, 8.5).map { scoring.scoreTMKastor(it) })
    }

    @Test
    fun `published TMOnto band - at most 2 scores 5`() {
        val scoring = com.geoknoesis.kastor.ontoquality.metrics.compute.OquareScoring
        assertEquals(listOf(5, 5, 4, 4, 3, 2, 1), listOf(0.0, 2.0, 3.0, 4.0, 6.0, 8.0, 8.5).map { scoring.scoreTM(it) })
    }

    @Test
    fun `distinct object count includes RDF 1_2 triple terms`() {
        val ttl =
            """
            @prefix : <http://example.org/tt#> .
            :s :p <<( :a :b :c )>> , :o .
            :t :p <<( :a :b :c )>> , <<( :a :b :d )>> , <<( :a :b "c" )>> .
            """.trimIndent()
        val r = VocabularyMetrics.compute(parse(ttl))
        assertEquals(5L, r.graph.tripleCount)
        // <<( :a :b :c )>> (twice), <<( :a :b :d )>>, <<( :a :b "c" )>> and :o.
        assertEquals(4L, r.graph.distinctObjectCount)
    }

    @Test
    fun `Kastor-adapted variants are serialised under their own names`() {
        val r = VocabularyMetrics.compute(parse(loadFixture("diamond.ttl")))
        val json = Json.parseToJsonElement(r.toJson()).jsonObject.getValue("owl").jsonObject
        val adapted = json.getValue("kastorAdapted").jsonObject
        assertEquals(setOf("couplingBetweenObjectsKastor", "numberOfChildrenKastor", "tanglednessKastor"), adapted.keys)
        assertEquals("TMOntoKastor", adapted.getValue("tanglednessKastor").jsonObject.getValue("oquareName").jsonPrimitive.content)
        assertEquals("2.0", adapted.getValue("tanglednessKastor").jsonObject.getValue("rawValue").jsonPrimitive.content)
        assertEquals("0.25", json.getValue("oquare").jsonObject.getValue("tangledness").jsonObject.getValue("rawValue").jsonPrimitive.content)

        val m = ModelFactory.createDefaultModel()
        m.read(StringReader(r.toTurtle()), null, "TTL")
        val ns = "https://w3id.org/kastor/metrics#"
        val report = m.listSubjectsWithProperty(m.createProperty("${ns}tanglednessKastor")).toList().single()
        assertEquals(2L, report.getProperty(m.createProperty("${ns}tanglednessKastor")).long)
        val schemes =
            m.listStatements(null, m.createProperty("${ns}onMetric"), m.createResource("${ns}tanglednessKastor")).toList()
                .map { it.subject.getProperty(m.createProperty("${ns}scoringScheme")).resource.uri }
        assertEquals(listOf("${ns}KastorAdaptedScoring"), schemes)

        assertTrue(r.describeText().contains("[Kastor-adapted (not OQuaRE)]"), r.describeText())
        assertTrue(r.describeMarkdown().contains("### Kastor-adapted (not OQuaRE)"), r.describeMarkdown())
    }

    @Test
    fun `distinct object count keys literals by lexical form, datatype and language`() {
        val ttl =
            """
            @prefix : <http://example.org/void#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            :s :p "1", "1"^^xsd:integer, "1"@en, "1"@fr, :o .
            :t :p "1" .
            """.trimIndent()
        val r = VocabularyMetrics.compute(parse(ttl))
        assertEquals(6L, r.graph.tripleCount)
        // "1" (xsd:string), "1"^^xsd:integer, "1"@en, "1"@fr and :o; the second plain "1" is the same term.
        assertEquals(5L, r.graph.distinctObjectCount)
    }

    @Test
    fun `SKOS definition coverage counts skos definition only`() {
        val ttl =
            """
            @prefix : <http://example.org/skosdef#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            :c1 a skos:Concept ; skos:definition "Defined." .
            :c2 a skos:Concept ; rdfs:comment "Only a comment." .
            """.trimIndent()
        val r = VocabularyMetrics.compute(parse(ttl))
        assertEquals(0.5, r.skos.definitionCoverage.rawValue, EPS)
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
