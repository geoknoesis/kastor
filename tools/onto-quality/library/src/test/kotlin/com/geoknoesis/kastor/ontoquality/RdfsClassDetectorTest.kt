package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.catalog.ShapeCatalog
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An RDFS vocabulary declares its classes `rdfs:Class`, not `owl:Class` (and some vocabularies do not declare them at
 * all): the class-level detectors must check them all the same, starting with subclass cycles (P06).
 */
class RdfsClassDetectorTest {

    private val prefixes =
        """
        @prefix ex: <http://example.org/onto#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix dct: <http://purl.org/dc/terms/> .
        <http://example.org/onto> a owl:Ontology ; rdfs:label "O" ; rdfs:comment "An ontology" ; dct:creator "me" ;
            owl:versionInfo "1" ; dct:license <https://creativecommons.org/licenses/by/4.0/> .
        """.trimIndent()

    private val catalogs = listOf(BundledCatalogs.OWL_QUALITY, BundledCatalogs.MODERN_ENGINEERING, BundledCatalogs.EMBEDDING_QUALITY)

    private fun findings(turtle: String, catalogs: List<ShapeCatalog> = this.catalogs): List<QualityFinding> =
        QualityChecker.builder(ShaclValidation.validator(ValidationConfig(maxViolations = Int.MAX_VALUE)))
            .apply { catalogs.forEach { addCatalog(it) } }
            .build()
            .check(Rdf.parse("$prefixes\n$turtle", RdfFormat.TURTLE))
            .findings

    /** `shape -> focus` of each finding; a property shape (a blank node) is named `property shape`. */
    private fun List<QualityFinding>.keys() =
        map { "${it.violation.shapeUri?.takeUnless { u -> u.startsWith("_:") }?.substringAfter('#') ?: "property shape"} -> ${(it.violation.focusNode as? Iri)?.value?.substringAfter('#') ?: it.violation.focusNode}" }.toSortedSet()

    private fun List<QualityFinding>.cycleMembers() =
        filter { it.violation.shapeUri?.endsWith("#NoSubClassCycleShape") == true }.map { (it.violation.focusNode as Iri).value.substringAfter('#') }.toSet()

    /** Classes with the pitfalls of the class-level detectors; `CLS` is the class of every class. */
    private val pitfalls =
        """
        ex:Animal a CLS ; rdfs:label "Animal"@en ; rdfs:comment "An animal."@en .
        ex:A a CLS ; rdfs:label "A"@en ; rdfs:comment "a"@en ; rdfs:subClassOf ex:B .
        ex:B a CLS ; rdfs:label "B"@en ; rdfs:comment "b"@en ; rdfs:subClassOf ex:A .
        ex:Kitten a CLS ; rdfs:label "Kitten"@en , "Kitty"@en ; rdfs:comment "Kitten"@en ; rdfs:subClassOf ex:Animal , ex:Kitten .
        ex:Weird a CLS ; rdfs:label "" ; rdfs:subClassOf ex:Animal .
        ex:misc a CLS ; rdfs:label "Miscellaneous" ; rdfs:subClassOf ex:Animal .
        ex:Lonely a CLS ; rdfs:label "Lonely"@en ; rdfs:comment "Has no links."@en .
        ex:Nameless a CLS ; rdfs:subClassOf ex:Animal .
        ex:Same a CLS ; rdfs:label "Same"@en ; rdfs:comment "Same."@en ; owl:equivalentClass ex:Animal ; rdfs:subClassOf ex:Animal .
        ex:Loop a CLS ; rdfs:label "Loop"@en ; rdfs:comment "Loop."@en ; owl:equivalentClass [ owl:unionOf ( ex:Loop ex:Animal ) ] .
        """.trimIndent()

    @Test
    fun `an rdfs Class vocabulary gets the findings of the same vocabulary in owl Class`() {
        val owl = findings(pitfalls.replace("CLS", "owl:Class")).keys()
        val rdfs = findings(pitfalls.replace("CLS", "rdfs:Class")).keys()
        for (expected in listOf(
            "NoSubClassCycleShape -> A", "NoSubClassCycleShape -> B", "property shape -> Kitten" /* unique language */,
            "MisusedDocumentationAnnotationsShape -> Kitten", "MisusedDocumentationAnnotationsShape -> Weird",
            "ClassNameUpperCamelShape -> misc", "JunkDrawerClassShape -> misc", "property shape -> Nameless" /* no label */,
            "ClassHasDefinitionShape -> Nameless", "OrphanClassShape -> Lonely", "EquivalenceVsSubclassShape -> Same",
            "RecursiveClassExpressionShape -> Loop",
        )) {
            assertTrue(expected in owl, "owl:Class fixture: $expected missing from $owl")
        }
        assertEquals(owl, rdfs)
    }

    @Test
    fun `a subclass cycle among rdfs Class classes is a P06 violation`() {
        val found = findings(
            """
            ex:A a rdfs:Class ; rdfs:label "A" ; rdfs:comment "a" ; rdfs:subClassOf ex:B .
            ex:B a rdfs:Class ; rdfs:label "B" ; rdfs:comment "b" ; rdfs:subClassOf ex:C .
            ex:C a rdfs:Class ; rdfs:label "C" ; rdfs:comment "c" ; rdfs:subClassOf ex:A .
            ex:D a rdfs:Class ; rdfs:label "D" ; rdfs:comment "d" ; rdfs:subClassOf ex:A , ex:D .
            """.trimIndent(),
            listOf(BundledCatalogs.OWL_QUALITY),
        )
        assertEquals(setOf("A", "B", "C"), found.cycleMembers())
    }

    @Test
    fun `a subclass cycle among undeclared classes is a P06 violation`() {
        val found = findings(
            """
            ex:A rdfs:label "A" ; rdfs:subClassOf ex:B .
            ex:B rdfs:label "B" ; rdfs:subClassOf ex:A .
            """.trimIndent(),
            listOf(BundledCatalogs.OWL_QUALITY),
        )
        assertEquals(setOf("A", "B"), found.cycleMembers())
    }
}
