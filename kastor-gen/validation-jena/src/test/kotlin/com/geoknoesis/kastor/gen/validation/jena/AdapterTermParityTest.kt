package com.geoknoesis.kastor.gen.validation.jena

import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.gen.validation.rdf4j.Rdf4jValidation
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Test
import kotlin.test.*

/** A term matrix through all three evaluators, including repeated loads of the same graph handle. */
class AdapterTermParityTest {
    private val focus = Iri("urn:test:focus")
    private val p = Iri("urn:test:p")
    private fun shapes(value: RdfTerm): RdfGraph = MemoryGraph(listOf(
        RdfTriple(Iri("urn:shape"), RDF.type, SHACL.NodeShape),
        RdfTriple(Iri("urn:shape"), SHACL.targetNode, focus),
        RdfTriple(Iri("urn:shape"), SHACL.property, Iri("urn:property")),
        RdfTriple(Iri("urn:property"), SHACL.path, p),
        RdfTriple(Iri("urn:property"), SHACL.hasValue, value),
    ))

    @Test fun `term equality and cache reloads agree with native evaluation`() {
        val terms = listOf(Iri("urn:value"), string("urn:value"), string(""), string("1"),
            Literal("1", XSD.integer), Literal("01", XSD.integer), TrueLiteral, Literal("1", XSD.boolean),
            LangString("text", "en"), LangString("text", "EN"), LangString("text", "fr"),
            BlankNode("term-one"), BlankNode("term-two"),
            TripleTerm(RdfTriple(Iri("urn:s"), p, Iri("urn:value"))),
            TripleTerm(RdfTriple(Iri("urn:s"), p, string("urn:value"))),
            TripleTerm(RdfTriple(BlankNode("term-one"), p,
                TripleTerm(RdfTriple(Iri("urn:s"), p, string("nested"))))))
        val native = NativeShaclValidatorProvider().createValidator(
            ValidationConfig(allowTripleTermsInShapeParameters = true))
        for (required in terms) {
            val shapes = shapes(required)
            val rdf4j = if (required is BlankNode || required is TripleTerm) {
                assertFailsWith<IllegalArgumentException> { Rdf4jValidation(shapes) }
                null
            } else Rdf4jValidation(shapes)
            try { JenaValidation(shapes).use { jena ->
                val data = MemoryGraph()
                for (value in terms) {
                    data.clear(); data.addTriple(RdfTriple(focus, p, value))
                    val expected = native.validate(data, shapes).isValid
                    assertEquals(expected, jena.validate(data, focus) == ValidationResult.Ok, "Jena: $required / $value")
                    if (rdf4j != null) assertEquals(expected, rdf4j.validate(data, focus) == ValidationResult.Ok, "RDF4J: $required / $value")
                }
            } } finally { rdf4j?.close() }
        }
    }

    @Test fun `failed atomic edits do not poison cached validation`() {
        val triple = RdfTriple(focus, p, string("valid"))
        val data = MemoryGraph(listOf(triple))
        val shapes = shapes(triple.obj)
        JenaValidation(shapes).use { jena -> Rdf4jValidation(shapes).use { rdf4j ->
            for (validator in listOf(jena, rdf4j)) {
                assertEquals(ValidationResult.Ok, validator.validate(data, focus))
                assertFailsWith<IllegalStateException> {
                    data.mutateAtomically {
                        clear()
                        assertIs<ValidationResult.Violations>(validator.validate(data, focus))
                        error("rollback")
                    }
                }
                assertEquals(ValidationResult.Ok, validator.validate(data, focus))
                assertEquals(listOf(triple), data.getTriples())
            }
        } }
    }
}
