package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.XSD
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `ksh:` vocabulary is published as a Turtle document shipped with the module
 * ([KastorShaclVocabulary.VOCABULARY_RESOURCE]). Every term the Kotlin object declares and every `ksh:` term the engine
 * writes into an RDF validation report must be declared there.
 */
class KastorShaclVocabularyTest {

    private val document: RdfGraph by lazy {
        val stream = KastorShaclVocabulary::class.java.getResourceAsStream(KastorShaclVocabulary.VOCABULARY_RESOURCE)
        assertNotNull(stream, "vocabulary document ${KastorShaclVocabulary.VOCABULARY_RESOURCE} is missing from the classpath")
        Rdf.parse(stream!!.use { it.readBytes().toString(Charsets.UTF_8) }, RdfFormat.TURTLE)
    }

    private val ontology = Iri(KastorShaclVocabulary.NAMESPACE.removeSuffix("#"))

    private fun isKsh(term: RdfTerm) = term is Iri && term.value.startsWith(KastorShaclVocabulary.NAMESPACE)

    private fun declaredTerms(): Set<Iri> =
        document.getTriples().map { it.subject }.filter { isKsh(it) }.map { it as Iri }.toSet()

    @Test
    fun `every term is declared with a type, a label, a comment and its defining ontology`() {
        val triples = document.getTriples()
        assertTrue(KastorShaclVocabulary.terms.isNotEmpty())
        for (term in KastorShaclVocabulary.terms) {
            fun values(predicate: Iri) = triples.filter { it.subject == term && it.predicate == predicate }.map { it.obj }
            assertTrue(values(RDF.type).isNotEmpty(), "$term has no rdf:type")
            assertEquals(1, values(RDFS.label).size, "$term must have one rdfs:label")
            val comment = values(RDFS.comment).singleOrNull() as? Literal
            assertTrue(comment != null && comment.lexical.length > 40, "$term must have one descriptive rdfs:comment")
            assertEquals(listOf<RdfTerm>(ontology), values(RDFS.isDefinedBy), "$term rdfs:isDefinedBy")
        }
        assertEquals(KastorShaclVocabulary.terms.toSet(), declaredTerms(), "the document declares exactly the terms of KastorShaclVocabulary")
        assertTrue(triples.any { it.subject == ontology && it.predicate == RDF.type }, "the ontology itself is described")
        // The status individuals are instances of the class the property ranges over.
        for (status in listOf(KastorShaclVocabulary.UndefinedRecursion, KastorShaclVocabulary.PatternTimeout, KastorShaclVocabulary.PatternTooComplex)) {
            assertTrue(triples.any { it.subject == status && it.predicate == RDF.type && it.obj == KastorShaclVocabulary.ResultStatusClass }, status.toString())
        }
        assertTrue(triples.any { it.subject == KastorShaclVocabulary.resultStatus && it.predicate == RDFS.range && it.obj == KastorShaclVocabulary.ResultStatusClass })
    }

    @Test
    fun `terms lists every IRI constant of the vocabulary object`() {
        // Iri is a value class: the constants are static String fields of the object.
        val constants = KastorShaclVocabulary::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
            .filter { it.startsWith(KastorShaclVocabulary.NAMESPACE) && it != KastorShaclVocabulary.NAMESPACE }
        assertTrue(constants.size >= 7, constants.toString())
        assertEquals(constants.toSet(), KastorShaclVocabulary.terms.map { it.value }.toSet())
        assertEquals(KastorShaclVocabulary.terms.size, KastorShaclVocabulary.terms.toSet().size)
    }

    @Test
    fun `every ksh term the engine emits is declared in the vocabulary document`() {
        val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://example.org/> .\n"
        fun graph(turtle: String) = Rdf.parse(prefixes + turtle, RdfFormat.TURTLE)
        val ex = "http://example.org/"
        val one = TypedLiteral("1", XSD.integer)
        // One run that produces every kind of Kastor-specific report content: an undefined recursive answer, a
        // pattern that times out, a pattern that exhausts the stack, a failing reifier and a report-level warning.
        val shapes = graph(
            """
            ex:Undef a sh:NodeShape ; sh:targetNode ex:x ; sh:property [ sh:path ex:self ; sh:not ex:Undef ] .
            ex:Slow a sh:NodeShape ; sh:targetNode ex:slow ; sh:property [ sh:path ex:p ; sh:pattern "^(.*a){30}$" ] .
            ex:Deep a sh:NodeShape ; sh:targetNode ex:deep ; sh:property [ sh:path ex:p ; sh:pattern "^(a|b)*$" ] .
            ex:Reified a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:reifierShape ex:Trusted ] .
            ex:Trusted sh:property [ sh:path ex:source ; sh:minCount 1 ] .
            ex:Skipped a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:values [ sh:path ex:q ] ] .
            """,
        )
        val data = Rdf.graph {
            Iri(ex + "x") - Iri(ex + "self") - Iri(ex + "x")
            Iri(ex + "slow") - Iri(ex + "p") - ("a".repeat(60) + "!")
            Iri(ex + "deep") - Iri(ex + "p") - "ab".repeat(150_000)
            Iri(ex + "a") - Iri(ex + "p") - one
            Iri(ex + "r") - RDF.reifies - TripleTerm(RdfTriple(Iri(ex + "a"), Iri(ex + "p"), one))
        }
        val validator = NativeShaclValidator(
            ValidationConfig(unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING, patternTimeout = java.time.Duration.ofMillis(50)),
        )
        // Deterministic pattern budget: the clock advances one millisecond per consultation.
        var now = 0L
        validator.patternClock = { now += 1_000_000L; now }
        val report = validator.validate(data, shapes)
        val rdf = report.toShaclValidationReportRdf().getTriples()

        val emitted = rdf.flatMap { listOf(it.subject, it.predicate, it.obj) }.filter { isKsh(it) }.map { it as Iri }.toSet()
        val undeclared = emitted - declaredTerms()
        assertTrue(undeclared.isEmpty(), "ksh: terms emitted but not declared in the vocabulary document: $undeclared")
        // The run above really covers everything the report serialization can emit (all terms but the class).
        assertEquals(KastorShaclVocabulary.terms.toSet() - KastorShaclVocabulary.ResultStatusClass, emitted)
        // No term outside the sh: and ksh: vocabularies is used as a property of the report or of its results.
        val foreign = rdf.map { it.predicate }.filter { !isKsh(it) && !it.value.startsWith(com.geoknoesis.kastor.rdf.vocab.SHACL.namespace) && it != RDF.type }
        assertTrue(foreign.isEmpty(), foreign.toString())
    }
}
