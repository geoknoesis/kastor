package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.EmbeddedShapesRefusedException
import com.geoknoesis.kastor.gen.runtime.ValidationContext
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.gen.validation.rdf4j.Rdf4jValidation
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfProvider
import com.geoknoesis.kastor.rdf.RdfProviderRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Round-seventeen integration: JSON-LD data graphs with embedded shapes, parsed by each provider's JSON-LD reader
 * (with its completeness check), then validated by both no-argument validators of Kastor Gen (embedded-shape bounds;
 * the RDF4J one on the Kastor validator). Every provider and validator pair must agree.
 */
@Timeout(60)
class JsonLdEmbeddedShapesIntegrationTest {
    private val context = "'@context':{'sh':'http://www.w3.org/ns/shacl#','ex':'http://ex/'," +
        "'sh:class':{'@type':'@id'},'sh:path':{'@type':'@id'},'sh:targetNode':{'@type':'@id'},'@vocab':'http://ex/'}"
    private val focus = Iri("http://ex/a")

    private fun doc(graph: String) = "{$context,'@graph':[$graph]}".replace('\'', '"')

    private fun xoneShape(operands: Int = 2) = "{'@id':'ex:S','@type':'sh:NodeShape','sh:targetNode':'ex:a'," +
        "'sh:xone':{'@list':[" + (1..operands).joinToString(",") { "{'sh:class':'ex:C$it'}" } + "]}}"

    private val providers: List<RdfProvider> = listOf("jena", "rdf4j").map {
        requireNotNull(RdfProviderRegistry.getProvider(it)) { "provider $it is not on the test class path" }
    }

    private fun parsed(json: String): List<Pair<String, RdfGraph>> =
        providers.map { it.id to it.parseGraph(json.byteInputStream(), "JSON-LD", null) }

    private fun validators(): List<Pair<String, ValidationContext>> =
        listOf("JenaValidation()" to JenaValidation(), "Rdf4jValidation()" to Rdf4jValidation())

    private fun eachPair(json: String, check: (String, ValidationContext, RdfGraph) -> Unit) {
        for ((provider, graph) in parsed(json)) for ((name, validator) in validators()) {
            try { check("$provider / $name", validator, graph) } finally { validator.close() }
        }
    }

    @Test fun `an sh_xone violation embedded in JSON-LD fails on every provider and validator`() {
        eachPair(doc(xoneShape() + ",{'@id':'ex:a','@type':['ex:C1','ex:C2']}")) { label, validator, graph ->
            assertIs<ValidationResult.Violations>(validator.validate(graph, focus), label)
        }
    }

    @Test fun `conforming JSON-LD data passes on every provider and validator`() {
        eachPair(doc(xoneShape() + ",{'@id':'ex:a','@type':'ex:C1'}")) { label, validator, graph ->
            assertEquals(ValidationResult.Ok, validator.validate(graph, focus), label)
        }
    }

    @Test fun `embedded shapes past the operand cap are refused after a JSON-LD parse`() {
        eachPair(doc(xoneShape(operands = 65) + ",{'@id':'ex:a','@type':'ex:C1'}")) { label, validator, graph ->
            assertFailsWith<EmbeddedShapesRefusedException>(label) { validator.validate(graph, focus) }
        }
    }

    @Test fun `JSON-LD whose shapes would be dropped is refused before any validator sees it`() {
        // An undefined key (no @vocab), and a relative sh:path IRI with "@base": null.
        val undefined = ("{'@context':{'sh':'http://www.w3.org/ns/shacl#'},'@id':'http://ex/S','@type':'sh:NodeShape'," +
            "'targetNode':{'@id':'http://ex/a'}}").replace('\'', '"')
        val relative = ("{'@context':{'@base':null,'sh':'http://www.w3.org/ns/shacl#','sh:path':{'@type':'@id'}}," +
            "'@id':'http://ex/S','@type':'sh:NodeShape','sh:property':{'sh:path':'p','sh:minCount':1}}").replace('\'', '"')
        for (json in listOf(undefined, relative)) for (provider in providers) {
            assertFailsWith<RdfFormatException>("${provider.id}: $json") {
                provider.parseGraph(json.byteInputStream(), "JSON-LD", null)
            }
        }
    }
}
