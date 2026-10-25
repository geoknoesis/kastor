package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

/**
 * A SHACL-SPARQL constraint whose query runs out of the validation's time ends the validation with a message that says
 * the time limit was reached and names it, on every provider (it read "SPARQL execution failed: null" on Jena).
 */
class SparqlConstraintTimeLimitTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
    """.trimIndent()

    private val shapes = """
        ex:Slow a sh:NodeShape ; sh:targetNode ex:n0 ;
          sh:sparql [ sh:select '''SELECT ${'$'}this WHERE { ?a ?b ?c . ?d ?e ?f . ?g ?h ?i . ?j ?k ?l . FILTER (STRLEN(CONCAT(STR(?c), STR(?f), STR(?i), STR(?l))) < 0) }''' ] .
    """

    private val data = (0 until 200).joinToString("\n") { "ex:n$it ex:p \"$it\" ." }

    private fun repository(provider: String): RdfRepository =
        if (provider == "jena") JenaProvider().createRepository("memory", RdfConfig()) else Rdf4jProvider().createRepository("memory", RdfConfig())

    @ParameterizedTest @ValueSource(strings = ["jena", "rdf4j"])
    fun `a SPARQL constraint that runs out of time names the time limit`(provider: String) {
        val config = ValidationConfig.default().copy(timeout = Duration.ofSeconds(3))
        val validator = NativeShaclValidator(config) { repository(provider) }
        val error = assertThrows(ShaclValidationException::class.java) {
            validator.validate(Rdf.parse("$prefixes\n$data", RdfFormat.TURTLE), Rdf.parse("$prefixes\n$shapes", RdfFormat.TURTLE))
        }
        val message = error.message!!
        assertFalse(message.contains("null"), message)
        assertTrue(message.contains("time limit") && message.contains("3000 ms"), message)
    }
}
