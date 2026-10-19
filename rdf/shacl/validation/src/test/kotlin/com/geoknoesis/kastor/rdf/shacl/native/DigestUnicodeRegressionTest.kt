package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DigestUnicodeRegressionTest {
    @Test
    fun `digest distinguishes arbitrary UTF-16 terms including nested blank nodes`() {
        val values = listOf("?", "\uD800", "\uD801", "\uDC00", "\uFFFD", "\uD83D\uDE00")
        val s = Iri("urn:s")
        val p = Iri("urn:p")
        val variations: List<(String) -> RdfTriple> = listOf(
            { RdfTriple(BlankNode(it), p, s) },
            { RdfTriple(s, p, string(it)) },
            { RdfTriple(s, p, TripleTerm(RdfTriple(BlankNode(it), p, s))) },
        )
        variations.forEach { triple ->
            val digests = values.map { ShapesStructuralDigest.digest(listOf(triple(it)), ValidationConfig()) }
            assertEquals(values.size, digests.distinct().size)
        }
    }

    @Test
    fun `a cache hit cannot reuse a different hasValue literal after UTF-8 replacement`() {
        val shape = Iri("urn:Shape")
        val subject = Iri("urn:subject")
        val p = Iri("urn:p")
        val property = BlankNode("property")
        fun shapes(value: String) = MemoryGraph(listOf(
            RdfTriple(shape, RDF.type, SHACL.NodeShape),
            RdfTriple(shape, SHACL.targetNode, subject),
            RdfTriple(shape, SHACL.`property`, property),
            RdfTriple(property, SHACL.path, p),
            RdfTriple(property, SHACL.hasValue, string(value)),
        ))
        val data = MemoryGraph(listOf(RdfTriple(subject, p, string("\uD800"))))
        val validator = NativeShaclValidator(ValidationConfig())
        assertTrue(validator.validate(data, shapes("\uD800")).isValid)
        assertFalse(validator.validate(data, shapes("\uD801")).isValid)
        assertEquals(2, validator.cacheStatistics.entries)
        assertFalse(validator.validate(data, shapes("\uD801")).isValid)
        assertTrue(validator.cacheStatistics.hits > 0)
    }
}
