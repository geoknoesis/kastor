package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.KastorGenInternalApi
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(KastorGenInternalApi::class)
class DirectionalTermAdmissionTest {
    private val focus = Iri("urn:test:focus")
    private val predicate = Iri("urn:test:p")

    private fun shapes(value: RdfTerm): RdfGraph {
        val shape = Iri("urn:test:shape")
        val property = Iri("urn:test:property")
        return MemoryGraph(listOf(
            RdfTriple(shape, RDF.type, SHACL.NodeShape),
            RdfTriple(shape, SHACL.targetNode, focus),
            RdfTriple(shape, SHACL.property, property),
            RdfTriple(property, SHACL.path, predicate),
            RdfTriple(property, SHACL.hasValue, value),
        ))
    }

    @Test fun `directional shape parameters are rejected instead of weakened`() {
        for (direction in Direction.entries) {
            val failure = assertFailsWith<IllegalArgumentException> {
                Rdf4jValidation(shapes(LangString("text", "en", direction)))
            }
            assertContains(failure.message.orEmpty(), "base direction")
        }
    }

    @Test fun `unsupported data and nested terms cannot replace the last valid cached state`() {
        Rdf4jValidation(shapes(LangString("text", "en"))).use { validator ->
            val valid = RdfTriple(focus, predicate, LangString("text", "en"))
            val data = MemoryGraph(listOf(valid))
            assertEquals(ValidationResult.Ok, validator.validate(data, focus))
            val loads = validator.loadCount
            for (direction in Direction.entries) {
                for (nested in listOf(false, true)) {
                    val literal = LangString("text", "en", direction)
                    val value = if (nested) TripleTerm(RdfTriple(focus, predicate, literal)) else literal
                    val invalid = RdfTriple(focus, predicate, value)
                    data.clear(); data.addTriple(invalid)
                    assertFailsWith<IllegalArgumentException> { validator.validate(data, focus) }
                    assertEquals(loads, validator.loadCount)
                    data.clear(); data.addTriple(valid)
                    assertEquals(ValidationResult.Ok, validator.validate(data, focus))
                    assertEquals(loads, validator.loadCount)
                }
            }
        }
    }

    @Test fun `ordinary language and datatype distinctions remain exact`() {
        Rdf4jValidation(shapes(LangString("text", "en"))).use { validator ->
            for (value in listOf(LangString("text", "fr"), string("text"), Literal("text", Iri("urn:type")))) {
                assertIs<ValidationResult.Violations>(validator.validate(MemoryGraph(listOf(RdfTriple(focus, predicate, value))), focus))
            }
        }
    }

    @Test fun `unsupported hasValue parameters are rejected in fixed and embedded shapes before reload`() {
        val unsupported = listOf(BlankNode("constant"),
            TripleTerm(RdfTriple(focus, predicate, string("constant"))))
        val validShapes = shapes(string("valid")).getTriples()
        val data = MemoryGraph(validShapes + RdfTriple(focus, predicate, string("valid")))
        Rdf4jValidation().use { validator ->
            assertEquals(ValidationResult.Ok, validator.validate(data, focus))
            val loads = validator.loadCount
            for (value in unsupported) {
                assertContains(assertFailsWith<IllegalArgumentException> {
                    Rdf4jValidation(shapes(value))
                }.message.orEmpty(), "sh:hasValue")
                assertFailsWith<IllegalArgumentException> { Rdf4jValidation(shapes(value), maxCachedGraphs = 2) }
                data.clear(); data.addTriples(shapes(value).getTriples())
                assertContains(assertFailsWith<IllegalArgumentException> {
                    validator.validate(data, focus)
                }.message.orEmpty(), "sh:hasValue")
                assertEquals(loads, validator.loadCount)
                data.clear(); data.addTriples(validShapes + RdfTriple(focus, predicate, string("valid")))
                assertEquals(ValidationResult.Ok, validator.validate(data, focus))
                assertEquals(loads, validator.loadCount)
            }
        }
    }
}
