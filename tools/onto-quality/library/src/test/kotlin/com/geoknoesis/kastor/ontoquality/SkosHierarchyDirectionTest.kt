package com.geoknoesis.kastor.ontoquality

import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The SKOS hierarchy detectors (broader cycles, S27) must see a hierarchy whichever direction it is stated in:
 * `skos:narrower` is the inverse of `skos:broader` (S25), and Jena's rule reasoners do not know that unless the
 * SKOS ontology itself is loaded.
 */
class SkosHierarchyDirectionTest {

    private val checker by lazy {
        QualityChecker.builder(ShaclValidation.validator()).addCatalog(BundledCatalogs.SKOS_VALIDATION).build()
    }

    private val prefixes =
        """
        @prefix ex: <http://example.org/v#> .
        @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
        ex:s a skos:ConceptScheme ; skos:prefLabel "S"@en ; skos:hasTopConcept ex:a .
        ex:a a skos:Concept ; skos:prefLabel "a"@en ; skos:inScheme ex:s ; skos:topConceptOf ex:s .
        ex:b a skos:Concept ; skos:prefLabel "b"@en ; skos:inScheme ex:s .
        ex:d a skos:Concept ; skos:prefLabel "d"@en ; skos:inScheme ex:s .
        """.trimIndent()

    private fun ex(local: String) = "http://example.org/v#$local"

    private fun focus(turtle: String, messagePart: String, profile: OntoQualityReasoningProfile): Set<String> =
        checker.check(Rdf.parse(prefixes + "\n" + turtle, RdfFormat.TURTLE), profile).findings
            .filter { messagePart in it.violation.message }
            .mapNotNull { (it.violation.focusNode as? Iri)?.value }
            .toSet()

    private val hierarchies =
        mapOf(
            "broader" to "ex:b skos:broader ex:a . ex:a skos:broader ex:b . ex:d skos:broader ex:a .",
            "narrower" to "ex:a skos:narrower ex:b . ex:b skos:narrower ex:a , ex:d .",
            "mixed" to "ex:b skos:broader ex:a . ex:b skos:narrower ex:a . ex:a skos:narrower ex:d .",
            "transitive" to "ex:a skos:narrowerTransitive ex:b . ex:b skos:narrowerTransitive ex:a , ex:d .",
        )

    @ParameterizedTest
    @EnumSource(OntoQualityReasoningProfile::class, names = ["NONE", "OWL_RL"])
    fun `a broader cycle is found whichever direction it is stated in`(profile: OntoQualityReasoningProfile) {
        for ((name, hierarchy) in hierarchies) {
            assertEquals(setOf(ex("a"), ex("b")), focus(hierarchy, "cycle", profile), "$name hierarchy, $profile")
        }
    }

    @ParameterizedTest
    @EnumSource(OntoQualityReasoningProfile::class, names = ["NONE", "OWL_RL"])
    fun `S27 is found whichever direction the hierarchy is stated in`(profile: OntoQualityReasoningProfile) {
        for ((name, hierarchy) in hierarchies) {
            val found = focus("$hierarchy ex:a skos:related ex:d . ex:d skos:related ex:a .", "S27", profile)
            assertEquals(setOf(ex("a"), ex("d")), found, "$name hierarchy, $profile")
        }
    }

    @Test
    fun `an acyclic narrower hierarchy is not a cycle`() {
        val found = focus("ex:a skos:narrower ex:b , ex:d .", "cycle", OntoQualityReasoningProfile.NONE)
        assertEquals(emptySet<String>(), found)
    }
}
