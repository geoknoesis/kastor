package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.catalog.ShapeCatalog
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ViolationSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Round-12 detector fixes, each with the false positive it must no longer report and the true positive it must keep
 * reporting, at the severity the catalogue now declares.
 */
class DetectorRegressionTest {

    private val prefixes =
        """
        @prefix ex: <http://example.org/onto#> .
        @prefix ey: <http://example.org/other#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix dct: <http://purl.org/dc/terms/> .
        @prefix dc: <http://purl.org/dc/elements/1.1/> .
        @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        @prefix dcat: <http://www.w3.org/ns/dcat#> .
        """.trimIndent()

    private fun findings(catalog: ShapeCatalog, turtle: String): List<QualityFinding> =
        QualityChecker.builder(ShaclValidation.validator(ValidationConfig(maxViolations = Int.MAX_VALUE)))
            .addCatalog(catalog)
            .build()
            .check(Rdf.parse("$prefixes\n$turtle", RdfFormat.TURTLE))
            .findings

    private fun List<QualityFinding>.of(shape: String) = filter { it.violation.shapeUri?.substringAfter('#') == shape }

    private fun List<QualityFinding>.withMessage(text: String) = filter { text in it.violation.message }

    private fun List<QualityFinding>.focus() = map { (it.violation.focusNode as? Iri)?.value ?: it.violation.focusNode.toString() }.toSet()

    private fun List<QualityFinding>.severities() = map { it.violation.severity }.toSet()

    private fun ex(local: String) = "http://example.org/onto#$local"

    // ---- owl-quality ------------------------------------------------------------------------------------------

    @Test
    fun `P34 and P35 are warnings and skip the standard vocabularies, XSD facets included`() {
        val found =
            findings(
                BundledCatalogs.OWL_QUALITY,
                """
                <http://example.org/onto> a owl:Ontology ; dcat:contactPoint <mailto:a@example.org> ; ey:note "x" .
                ex:p a owl:ObjectProperty ; rdfs:range foaf:Person , ey:Undeclared .
                ex:d a owl:DatatypeProperty ; rdfs:range [ a rdfs:Datatype ; owl:onDatatype xsd:integer ; owl:withRestrictions ( [ xsd:minInclusive 0 ] ) ] .
                """.trimIndent(),
            )
        val untypedClass = found.of("UntypedClassShape")
        assertEquals(setOf("http://example.org/other#Undeclared"), untypedClass.focus(), "P34: ${untypedClass.map { it.violation.message }}")
        val untypedProperty = found.of("UntypedPropertyShape")
        assertEquals(setOf("http://example.org/other#note"), untypedProperty.map { (it.violation.value as Iri).value }.toSet())
        assertEquals(setOf(ViolationSeverity.WARNING), (untypedClass + untypedProperty).severities())
    }

    @Test
    fun `range checks give the DatatypeProperty hint only for datatypes and leave undeclared IRIs to P34`() {
        val found =
            findings(
                BundledCatalogs.OWL_QUALITY,
                """
                ex:A a owl:Class .
                ex:q a owl:ObjectProperty .
                ex:toNumber a owl:ObjectProperty ; rdfs:range xsd:integer .
                ex:toProperty a owl:ObjectProperty ; rdfs:range ex:q .
                ex:toUndeclared a owl:ObjectProperty ; rdfs:range ex:Nowhere .
                ex:toUnion a owl:ObjectProperty ; rdfs:range [ owl:unionOf ( ex:A ex:A2 ) ] .
                ex:A2 a owl:Class .
                ex:toRestriction a owl:ObjectProperty ; rdfs:range [ a owl:Restriction ; owl:onProperty ex:q ; owl:someValuesFrom ex:A ] .
                ex:onUnion a owl:ObjectProperty ; rdfs:domain [ owl:unionOf ( ex:A ex:A2 ) ] .
                ex:onProperty a owl:ObjectProperty ; rdfs:domain ex:q .
                """.trimIndent(),
            )
        val range = found.of("ObjectPropertyRangeIsClassShape")
        assertEquals(setOf(ex("toNumber"), ex("toProperty")), range.focus(), range.map { it.violation.message }.toString())
        assertEquals(setOf(ex("toNumber")), range.withMessage("owl:DatatypeProperty").focus())
        assertEquals(setOf(ex("toProperty")), range.withMessage("is not a class").focus())
        assertEquals(setOf(ex("onProperty")), found.of("DomainIsClassShape").focus())
        // A property used as a range is also a class use P34 reports; an undeclared range is reported by P34 only.
        assertEquals(setOf(ex("Nowhere"), ex("q")), found.of("UntypedClassShape").focus())
    }

    @Test
    fun `ontology metadata accepts the usual equivalents and never reports a severity twice`() {
        val documented =
            findings(
                BundledCatalogs.OWL_QUALITY,
                """<http://example.org/onto> a owl:Ontology ; dct:title "T" ; dct:description "D" ; dc:creator "Ada" ; <http://creativecommons.org/ns#license> <https://creativecommons.org/licenses/by/4.0/> ; <https://schema.org/version> "1" ."""
                    .trimIndent(),
            )
        assertEquals(emptyList<String>(), documented.filter { it.violation.shapeUri?.contains("Ontology") == true }.map { it.violation.message })
        val bare = findings(BundledCatalogs.OWL_QUALITY, "<http://example.org/onto> a owl:Ontology .")
        val creator = bare.withMessage("dct:creator")
        assertEquals(1, creator.size)
        assertEquals(ViolationSeverity.WARNING, creator.single().violation.severity)
    }

    @Test
    fun `legal OWL 2 punning is advisory, the punning OWL 2 DL forbids is a violation`() {
        val found =
            findings(
                BundledCatalogs.OWL_QUALITY,
                """
                ex:Parent a owl:Class , owl:ObjectProperty .
                ex:both a owl:ObjectProperty , owl:DatatypeProperty .
                ex:Code a owl:Class , rdfs:Datatype .
                """.trimIndent(),
            )
        assertEquals(setOf(ViolationSeverity.WARNING), found.of("PolysemousClassAndPropertyShape").severities())
        val illegal = found.of("IllegalPunningShape")
        assertEquals(setOf(ex("both"), ex("Code")), illegal.focus())
        assertEquals(setOf(ViolationSeverity.VIOLATION), illegal.severities())
    }

    @Test
    fun `P26, P24 recursion and import cycles are warnings, a class under its own complement a violation`() {
        val found =
            findings(
                BundledCatalogs.OWL_QUALITY,
                """
                <http://example.org/onto> a owl:Ontology ; owl:imports <http://example.org/other> .
                <http://example.org/other> a owl:Ontology ; owl:imports <http://example.org/onto> .
                ex:knows a owl:ObjectProperty , owl:SymmetricProperty ; owl:inverseOf ex:knownBy .
                ex:knownBy a owl:ObjectProperty .
                ex:A a owl:Class ; owl:equivalentClass [ owl:unionOf ( ex:A ex:B ) ] .
                ex:B a owl:Class .
                ex:Empty a owl:Class ; rdfs:subClassOf [ owl:complementOf ex:Empty ] .
                ex:Paradox a owl:Class ; owl:equivalentClass [ owl:complementOf ex:Paradox ] .
                """.trimIndent(),
            )
        // A nested property shape reports under its own (blank) shape node: select by pitfall.
        val p26 = found.filter { (it.pitfall as? PitfallReference.Oops)?.number == "P26" }
        assertEquals(setOf(ex("knows")), p26.focus())
        assertEquals(setOf(ViolationSeverity.WARNING), p26.severities())
        assertEquals(setOf(ex("A")), found.of("RecursiveClassExpressionShape").focus())
        assertEquals(setOf(ViolationSeverity.WARNING), found.of("RecursiveClassExpressionShape").severities())
        assertEquals(setOf(ex("Empty"), ex("Paradox")), found.of("SelfComplementClassShape").focus())
        assertEquals(setOf(ViolationSeverity.VIOLATION), found.of("SelfComplementClassShape").severities())
        assertEquals(setOf(ViolationSeverity.WARNING), found.of("OwlImportsCycleShape").severities())
        assertEquals(2, found.of("OwlImportsCycleShape").size)
    }

    @Test
    fun `deprecation accepts every boolean spelling and ignores replacement links`() {
        val found =
            findings(
                BundledCatalogs.OWL_QUALITY,
                """
                ex:Old a owl:Class ; owl:deprecated "true" ; dct:isReplacedBy ex:New .
                ex:New a owl:Class ; dct:replaces ex:Old ; rdfs:seeAlso ex:Old .
                ex:User a owl:Class ; rdfs:subClassOf ex:Old .
                [] a owl:Axiom ; owl:annotatedSource ex:New ; owl:annotatedProperty dct:replaces ; owl:annotatedTarget ex:Old .
                """.trimIndent(),
            )
        val deprecated = found.of("DeprecatedNotInUseShape")
        assertEquals(setOf(ex("User")), deprecated.map { (it.violation.value as Iri).value }.toSet(), deprecated.map { it.violation.message }.toString())
        assertTrue(found.of("OrphanClassShape").none { (it.violation.focusNode as? Iri)?.value == ex("Old") })
    }

    @Test
    fun `naming accepts every script and OBO identifiers and still reports a lower-case class`() {
        val found =
            findings(
                BundledCatalogs.OWL_QUALITY,
                """
                ex:Bâtiment a owl:Class . ex:Straße a owl:Class . ex:建物 a owl:Class .
                <http://purl.obolibrary.org/obo/GO_0008150> a owl:Class .
                <urn:example:Thing> a owl:Class .
                ex:lowercase a owl:Class .
                ex:höhe a owl:DatatypeProperty . ex:Height a owl:DatatypeProperty .
                dct:creator a owl:AnnotationProperty .
                """.trimIndent(),
            )
        assertEquals(setOf(ex("lowercase")), found.of("ClassNameUpperCamelShape").focus())
        assertEquals(setOf(ex("Height")), found.of("PropertyNameLowerCamelShape").focus())
    }

    @Test
    fun `namespace hijacking skips MIREOT terms and www prefixes but keeps redefinitions`() {
        val found =
            findings(
                BundledCatalogs.OWL_QUALITY,
                """
                <https://www.example.org/onto> a owl:Ontology .
                <https://example.org/onto#A> a owl:Class ; rdfs:label "A" .
                <http://purl.obolibrary.org/obo/BFO_0000040> a owl:Class ; rdfs:label "material entity" ;
                    rdfs:isDefinedBy <http://purl.obolibrary.org/obo/bfo.owl> .
                foaf:Persona a owl:Class ; rdfs:label "Persona" .
                """.trimIndent(),
            )
        assertEquals(setOf("http://xmlns.com/foaf/0.1/Persona"), found.of("NamespaceMismatchShape").focus())
    }

    @Test
    fun `external standard terms declared Protege-style are not asked for labels`() {
        val found =
            findings(
                BundledCatalogs.OWL_QUALITY,
                """
                foaf:Person a owl:Class . foaf:name a owl:DatatypeProperty . foaf:knows a owl:ObjectProperty .
                ex:Mine a owl:Class .
                """.trimIndent(),
            )
        val annotated = found.filter { it.category == QualityCategory.OWL_ANNOTATIONS || it.category == QualityCategory.OWL_PROPERTY_DECLARATIONS }
        assertEquals(setOf(ex("Mine")), annotated.focus(), annotated.map { it.violation.message }.toString())
    }

    // ---- skos-validation --------------------------------------------------------------------------------------

    @Test
    fun `S46 is found from either end of the mapping links, once per pair`() {
        val found =
            findings(
                BundledCatalogs.SKOS_VALIDATION,
                """
                ex:A a skos:Concept ; skos:exactMatch ey:X .
                ey:X skos:narrowMatch ex:A .
                ex:B a skos:Concept ; skos:narrowMatch ey:Y .
                ey:Y skos:exactMatch ex:B .
                ex:C a skos:Concept ; skos:relatedMatch ex:D .
                ex:D a skos:Concept ; skos:exactMatch ex:C .
                ex:E a skos:Concept ; skos:exactMatch ey:Z ; skos:closeMatch ey:Z .
                """.trimIndent(),
            )
        val s46 = found.filter { (it.pitfall as? PitfallReference.Skos)?.number == "S46" }
        assertEquals(listOf(ex("A"), ex("B"), ex("C")), s46.map { (it.violation.focusNode as Iri).value }.sorted(), s46.map { it.violation.message }.toString())
        assertEquals(setOf(ViolationSeverity.VIOLATION), s46.severities())
    }

    @Test
    fun `a one-sided skos related link is reported once, on its subject`() {
        val found = findings(BundledCatalogs.SKOS_VALIDATION, "ex:B a skos:Concept ; skos:related ex:Y .\nex:Y a skos:Concept .\nex:P a skos:Concept ; skos:related ex:Q .\nex:Q a skos:Concept ; skos:related ex:P .")
        val symmetry = found.of("RelatedSymmetryShape")
        assertEquals(listOf(ex("B")), symmetry.map { (it.violation.focusNode as Iri).value })
    }

    @Test
    fun `top concepts named from the scheme are in the scheme, and range statements are warnings`() {
        val found =
            findings(
                BundledCatalogs.SKOS_VALIDATION,
                """
                ex:S a skos:ConceptScheme ; skos:hasTopConcept ex:A .
                ex:A a skos:Concept ; skos:prefLabel "a"@en ; skos:broader ey:Untyped .
                ex:Orphan a skos:Concept ; skos:prefLabel "o"@en .
                """.trimIndent(),
            )
        assertEquals(setOf(ex("Orphan")), found.of("ConceptInSchemeShape").focus())
        val range = found.withMessage("is not declared a skos:Concept")
        assertEquals(setOf(ex("A")), range.focus())
        assertEquals(setOf(ViolationSeverity.WARNING), range.severities())
    }

    // ---- modern-engineering, embedding, rdf12 -----------------------------------------------------------------

    @Test
    fun `mixed label tags are reported once per untagged label, punning is informational`() {
        val found =
            findings(
                BundledCatalogs.MODERN_ENGINEERING,
                """
                ex:Person a owl:Class ; rdfs:label "Person" , "Personne"@fr , "Persona"@es , "Person"@de .
                ex:Lion a owl:Class , ex:Species .
                ex:Species a owl:Class .
                """.trimIndent(),
            )
        assertEquals(1, found.of("LanguageTagConsistencyShape").size)
        assertEquals(setOf(ViolationSeverity.INFO), found.of("NoClassAsInstanceShape").severities())
    }

    @Test
    fun `namespace style compares the ontology's own terms only`() {
        val external =
            findings(
                BundledCatalogs.MODERN_ENGINEERING,
                """
                <http://example.org/onto> a owl:Ontology .
                ex:A a owl:Class . <http://xmlns.com/foaf/0.1/Person> a owl:Class . <http://example.org/vocab/terms/B> a owl:Class .
                """.trimIndent(),
            )
        assertEquals(emptyList<QualityFinding>(), external.of("ConsistentNamespaceStyleShape"))
        val mixed =
            findings(BundledCatalogs.MODERN_ENGINEERING, "<http://example.org/onto> a owl:Ontology .\nex:A a owl:Class .\n<http://example.org/onto/B> a owl:Class .")
        assertEquals(1, mixed.of("ConsistentNamespaceStyleShape").size)
    }

    @Test
    fun `an import is used by the terms of its namespace, file suffix or version aside`() {
        val found =
            findings(
                BundledCatalogs.MODERN_ENGINEERING,
                """
                <http://example.org/onto> a owl:Ontology ;
                    owl:imports <http://purl.obolibrary.org/obo/bfo.owl> , <http://example.org/other/2.1> , <http://example.org/unused> .
                ex:A a owl:Class ; rdfs:subClassOf <http://purl.obolibrary.org/obo/BFO_0000001> , ey:Thing .
                """.trimIndent(),
            )
        assertEquals(setOf("http://example.org/unused"), found.of("UnusedImportShape").map { it.violation.message.substringAfter("imports ").substringBefore(" but").trim('<', '>') }.toSet())
    }

    @Test
    fun `catch-all class names are whole words`() {
        val found =
            findings(
                BundledCatalogs.EMBEDDING_QUALITY,
                """
                ex:Miscarriage a owl:Class ; rdfs:label "Miscarriage" .
                ex:Otter a owl:Class ; rdfs:label "Otter" .
                ex:OtherStuff a owl:Class .
                ex:C1 a owl:Class ; rdfs:label "Misc. items" .
                ex:Old a owl:Class ; rdfs:label "Car" ; owl:deprecated true .
                ex:Car a owl:Class ; rdfs:label "Car" .
                ex:Auto a owl:Class ; rdfs:label "Auto" ; owl:equivalentClass ex:Automobile .
                ex:Automobile a owl:Class ; rdfs:label "Auto" .
                ex:Bus a owl:Class ; rdfs:label "Bus" .
                ex:Coach a owl:Class ; rdfs:label "Bus" .
                """.trimIndent(),
            )
        assertEquals(setOf(ex("OtherStuff"), ex("C1")), found.of("JunkDrawerClassShape").focus())
        assertEquals(setOf(ex("Bus")), found.of("SameLabelDifferentClassShape").focus())
    }

    @Test
    fun `right-to-left labels are recognised by their primary subtag`() {
        val found =
            findings(
                BundledCatalogs.RDF12_QUALITY,
                """
                ex:A rdfs:label "القاهرة"@ar-EG .
                ex:B skos:prefLabel "שלום"@HE .
                ex:C rdfs:label "Paris"@fr .
                """.trimIndent(),
            )
        val rtl = found.of("DirectionalLanguageStringShape")
        assertEquals(2, rtl.size, rtl.map { it.violation.message }.toString())
    }
}
