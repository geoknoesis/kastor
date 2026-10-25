package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoning
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Valid OWL 2 patterns the OWL quality catalogue must not report (and the real pitfalls next to them it must keep
 * reporting).
 */
class OwlQualityFalsePositiveTest {

    private val checker by lazy {
        QualityChecker.builder(ShaclValidation.validator())
            .addCatalog(BundledCatalogs.OWL_QUALITY)
            .build()
    }

    private val prefixes =
        """
        @prefix ex: <http://example.org/onto#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix dc: <http://purl.org/dc/elements/1.1/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        <http://example.org/onto> a owl:Ontology .
        """.trimIndent()

    /**
     * The findings of [pitfall]. Every fixture here is valid OWL 2 around its one pitfall, so a finding on a blank node
     * (an anonymous class expression or restriction) is a false positive whatever detector produced it.
     */
    private fun findings(turtle: String, pitfall: String): List<QualityFinding> {
        val report = checker.check(Rdf.parse(prefixes + "\n" + turtle, RdfFormat.TURTLE))
        assertEquals(emptyList<String>(), report.findings.blankFocus(), "findings on blank nodes")
        return report.findings.filter { (it.pitfall as? PitfallReference.Oops)?.number == pitfall }
    }

    private fun List<QualityFinding>.blankFocus(): List<String> =
        filter { it.violation.focusNode is BlankNode }.map { "${it.violation.shapeUri}: ${it.violation.message}" }

    private fun List<QualityFinding>.focusIris(): Set<String> =
        mapNotNull { (it.violation.focusNode as? Iri)?.value }.toSet()

    private fun List<QualityFinding>.valueIris(): Set<String> =
        mapNotNull { (it.violation.value as? Iri)?.value }.toSet()

    private fun ex(local: String) = "http://example.org/onto#$local"

    @Test
    fun `P34 does not report a declared datatype used as a range`() {
        val found =
            findings(
                """
                ex:age a owl:DatatypeProperty ; rdfs:range ex:PositiveInt .
                ex:PositiveInt a rdfs:Datatype ;
                    owl:equivalentClass [ a rdfs:Datatype ; owl:onDatatype xsd:integer ;
                                          owl:withRestrictions ( [ xsd:minExclusive 0 ] ) ] .
                ex:code a owl:DatatypeProperty ; rdfs:range ex:Code .
                ex:Code a owl:DataRange .
                ex:size a owl:DatatypeProperty ; rdfs:range ex:Undeclared .
                """.trimIndent(),
                "P34",
            )
        assertEquals(setOf(ex("Undeclared")), found.focusIris(), "findings: ${found.map { it.violation.message }}")
    }

    @Test
    fun `P27 compares domain and range sets, not pairs`() {
        val found =
            findings(
                """
                ex:A a owl:Class . ex:B a owl:Class . ex:C a owl:Class .
                ex:p a owl:ObjectProperty ; rdfs:domain ex:A , ex:B ; owl:equivalentProperty ex:q .
                ex:q a owl:ObjectProperty ; rdfs:domain ex:A , ex:B .
                ex:r a owl:ObjectProperty ; rdfs:range ex:A ; owl:equivalentProperty ex:s .
                ex:s a owl:ObjectProperty ; rdfs:range ex:A , ex:C .
                """.trimIndent(),
                "P27",
            )
        assertEquals(setOf(ex("r")), found.focusIris(), "findings: ${found.map { it.violation.message }}")
    }

    @Test
    fun `P04 counts class expressions and restriction fillers as connections`() {
        val found =
            findings(
                """
                ex:A a owl:Class . ex:B a owl:Class . ex:C a owl:Class . ex:D a owl:Class .
                ex:Orphan a owl:Class .
                ex:p a owl:ObjectProperty ; rdfs:domain [ a owl:Class ; owl:unionOf ( ex:A ex:B ) ] .
                ex:E a owl:Class ;
                    rdfs:subClassOf [ a owl:Restriction ; owl:onProperty ex:p ; owl:someValuesFrom ex:C ] ,
                                    [ a owl:Restriction ; owl:onProperty ex:p ; owl:allValuesFrom ex:D ] .
                ex:F a owl:Class ;
                    rdfs:subClassOf [ a owl:Restriction ; owl:onProperty ex:p ; owl:onClass ex:G ;
                                      owl:minQualifiedCardinality "1"^^xsd:nonNegativeInteger ] .
                ex:G a owl:Class .
                ex:H a owl:Class .
                ex:I a owl:Class ; owl:complementOf ex:H .
                """.trimIndent(),
                "P04",
            )
        assertEquals(setOf(ex("Orphan")), found.focusIris(), "findings: ${found.map { it.violation.message }}")
    }

    @Test
    fun `P35 accepts DC elements and OWL property characteristics as declarations`() {
        val found =
            findings(
                """
                ex:A a owl:Class ; dc:title "A" ; ex:t ex:A ; ex:f ex:A ; ex:u ex:A .
                ex:t a owl:TransitiveProperty .
                ex:f a owl:FunctionalProperty .
                ex:i a owl:InverseFunctionalProperty . ex:A ex:i ex:A .
                ex:s a owl:SymmetricProperty . ex:A ex:s ex:A .
                ex:as a owl:AsymmetricProperty . ex:A ex:as ex:A .
                ex:r a owl:ReflexiveProperty . ex:A ex:r ex:A .
                ex:ir a owl:IrreflexiveProperty . ex:A ex:ir ex:A .
                """.trimIndent(),
                "P35",
            )
        assertEquals(setOf(ex("u")), found.valueIris(), "findings: ${found.map { it.violation.message }}")
    }

    @Test
    fun `P40 ignores a bare declaration of an external class but keeps redefinitions`() {
        val found =
            findings(
                """
                foaf:Person a owl:Class .
                ex:Member a owl:Class ; rdfs:subClassOf foaf:Person .
                foaf:Persona a owl:Class ; rdfs:label "Persona" .
                foaf:knowsAbout a owl:ObjectProperty ; rdfs:domain foaf:Person .
                """.trimIndent(),
                "P40",
            )
        val focus = found.focusIris()
        assertTrue("http://xmlns.com/foaf/0.1/Person" !in focus, "bare external declaration reported: $focus")
        assertTrue("http://xmlns.com/foaf/0.1/Persona" in focus, "redefinition not reported: $focus")
        assertTrue("http://xmlns.com/foaf/0.1/knowsAbout" in focus, "redefinition not reported: $focus")
    }

    @ParameterizedTest
    @EnumSource(OntoQualityReasoningProfile::class)
    fun `no bundled detector reports a documented ontology's anonymous class expressions`(profile: OntoQualityReasoningProfile) {
        assumeTrue(OntoQualityReasoning.supports(profile), "reasoner $profile not on the classpath")
        val ontology =
            Rdf.parse(
                prefixes + "\n" +
                    """
                    @prefix dct: <http://purl.org/dc/terms/> .
                    @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
                    <http://example.org/onto> rdfs:label "Fruit"@en ; rdfs:comment "Fruit and their parts."@en ;
                        dct:creator "Kastor" ; dct:license <http://example.org/license> ; owl:versionInfo "1" .
                    ex:Apple a owl:Class ; rdfs:label "Apple"@en ; rdfs:comment "An apple."@en .
                    ex:Pear a owl:Class ; rdfs:label "Pear"@en ; rdfs:comment "A pear."@en .
                    ex:Fruit a owl:Class ; rdfs:label "Fruit"@en ; rdfs:comment "A fruit."@en ;
                        owl:equivalentClass [ a owl:Class ; owl:unionOf ( ex:Apple ex:Pear ) ] .
                    ex:Core a owl:Class ; rdfs:label "Core"@en ; rdfs:comment "The core of a fruit."@en ;
                        rdfs:subClassOf [ a owl:Class ; owl:complementOf ex:Fruit ] .
                    ex:Seed a owl:Class ; rdfs:label "Seed"@en ; rdfs:comment "A seed."@en ;
                        rdfs:subClassOf [ a owl:Class ; owl:intersectionOf ( ex:Core
                            [ a owl:Restriction ; owl:onProperty ex:partOf ; owl:someValuesFrom ex:Fruit ] ) ] .
                    ex:Colour a owl:Class ; rdfs:label "Colour"@en ; rdfs:comment "A colour."@en ;
                        owl:equivalentClass [ a owl:Class ; owl:oneOf ( ex:red ex:green ) ] .
                    ex:red a owl:NamedIndividual , ex:Colour ; rdfs:label "red"@en .
                    ex:green a owl:NamedIndividual , ex:Colour ; rdfs:label "green"@en .
                    ex:partOf a owl:ObjectProperty ; rdfs:label "part of"@en ; rdfs:comment "Relates a part to its whole."@en ;
                        rdfs:domain [ a owl:Class ; owl:unionOf ( ex:Core ex:Seed ) ] ; rdfs:range ex:Fruit .
                    ex:hasPart a owl:ObjectProperty ; rdfs:label "has part"@en ; rdfs:comment "Inverse of part of."@en ;
                        owl:inverseOf ex:partOf ; rdfs:domain ex:Fruit ; rdfs:range [ a owl:Class ; owl:unionOf ( ex:Core ex:Seed ) ] .
                    """.trimIndent(),
                RdfFormat.TURTLE,
            )
        val report = QualityChecker.default(ShaclValidation.validator()).check(ontology, profile)
        assertEquals(emptyList<String>(), report.findings.blankFocus(), "findings on blank nodes with $profile")
    }

    @Test
    fun `absence detectors still report named classes and properties`() {
        val report =
            checker.check(
                Rdf.parse(
                    prefixes + "\n" + "ex:bare a owl:Class . ex:p a owl:ObjectProperty . ex:d a owl:DatatypeProperty .",
                    RdfFormat.TURTLE,
                ),
            )
        val messages = report.findings.groupBy({ (it.violation.focusNode as? Iri)?.value }, { it.violation.message })
        for (expected in listOf("Class has no rdfs:label.", "Class has neither rdfs:comment nor skos:definition.")) {
            assertTrue(expected in messages[ex("bare")].orEmpty(), "missing '$expected': $messages")
        }
        for ((p, kind) in listOf("p" to "Object", "d" to "Datatype")) {
            for (what in listOf("label", "domain", "range")) {
                val expected = "$kind property has no rdfs:$what."
                assertTrue(expected in messages[ex(p)].orEmpty(), "missing '$expected': $messages")
            }
        }
        assertTrue(messages[ex("bare")].orEmpty().any { "UpperCamelCase" in it }, "ex:bare naming not reported: $messages")
    }
}
