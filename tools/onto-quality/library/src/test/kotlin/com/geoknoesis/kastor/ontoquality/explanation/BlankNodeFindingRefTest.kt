package com.geoknoesis.kastor.ontoquality.explanation

import com.geoknoesis.kastor.ontoquality.QualityCategory
import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.ontoquality.QualityTier
import com.geoknoesis.kastor.ontoquality.catalog.ShapeCatalog
import com.geoknoesis.kastor.ontoquality.catalog.ShapeMetadata
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Findings on blank nodes (e.g. owl:Restriction) get the same [FindingRef] each time the same file is parsed. */
class BlankNodeFindingRefTest {
    private val shapes =
        """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix ex: <http://example.org/shapes#> .
        ex:RestrictionNeedsSome a sh:NodeShape ;
            sh:targetClass owl:Restriction ;
            sh:property [ sh:path owl:someValuesFrom ; sh:minCount 1 ; sh:severity sh:Warning ; sh:message "restriction without someValuesFrom" ] .
        """.trimIndent()

    private val catalog =
        object : ShapeCatalog {
            override val id = "test"
            override val name = "test"
            override val version = "1"
            override val shapeMetadata: Map<String, ShapeMetadata> = emptyMap()

            override fun loadShapesGraph(): RdfGraph = Rdf.parse(shapes, "TURTLE")
        }

    private val ontology =
        """
        @prefix : <http://example.org/r#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        :p a owl:ObjectProperty . :q a owl:ObjectProperty .
        :A a owl:Class ; rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :p ; owl:allValuesFrom :B ] .
        :B a owl:Class ; rdfs:subClassOf [ a owl:Restriction ; owl:onProperty :q ; owl:allValuesFrom [ a owl:Class ; owl:unionOf ( :A :B ) ] ] .
        """.trimIndent()

    private fun check(): QualityReport =
        QualityChecker.builder(ShaclValidation.validator()).addCatalog(catalog).build().check(Rdf.parse(ontology, "TURTLE"))

    @Test
    fun `refs of blank-node findings are identical across two parses of the same file`() {
        val first = check()
        val second = check()
        assertEquals(2, first.findings.size, first.describeText())
        assertTrue(first.findings.all { it.violation.focusNode is BlankNode })
        // Parser labels differ between parses, so a ref derived from the label would differ too.
        assertNotEquals(
            first.findings.map { it.violation.focusNode }.toSet(),
            second.findings.map { it.violation.focusNode }.toSet(),
            "precondition: blank node labels differ per parse",
        )
        val refs1 = first.findings.map { FindingRef.from(it).hexSha256 }.toSet()
        val refs2 = second.findings.map { FindingRef.from(it).hexSha256 }.toSet()
        assertEquals(refs1, refs2)
        assertEquals(2, refs1.size, "structurally different restrictions get different refs")
        assertTrue(first.findings.all { it.category == QualityCategory.UNCATEGORIZED && it.tier == QualityTier.STRUCTURAL })
    }

    @Test
    fun `blank node keys are cycle safe and bounded`() {
        val cyclic =
            """
            @prefix : <http://example.org/c#> .
            :s :p _:a . _:a :next _:b . _:b :next _:a . _:a :v "1" . _:b :v "2" .
            """.trimIndent()
        val g1 = Rdf.parse(cyclic, "TURTLE")
        val g2 = Rdf.parse(cyclic, "TURTLE")
        fun keys(g: RdfGraph): Set<String> {
            val nodes = g.getTriples().flatMap { listOf(it.subject, it.obj) }.filterIsInstance<BlankNode>().toSet()
            return BlankNodeKeys.compute(g, nodes).values.toSet()
        }
        assertEquals(keys(g1), keys(g2))
        assertEquals(2, keys(g1).size)
    }
}
