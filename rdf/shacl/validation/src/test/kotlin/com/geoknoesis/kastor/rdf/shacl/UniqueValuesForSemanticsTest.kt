package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `sh:uniqueValuesFor` beyond the single-valued W3C cases: the key of a target is the set of its values for each listed
 * property (RDF value nodes are sets, so value order is irrelevant), compared as RDF terms, and two targets conflict
 * when their keys are equal.
 */
class UniqueValuesForSemanticsTest {

    private val prefixes = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
    """.trimIndent()

    private fun ex(local: String) = Iri("http://example.org/$local")

    private fun conflicts(data: String, key: String = "ex:id"): Set<RdfTerm> {
        val shapes = Rdf.parse("$prefixes\nex:S a sh:NodeShape ; sh:targetClass ex:T ; sh:uniqueValuesFor $key .", RdfFormat.TURTLE)
        return NativeShaclValidator(ValidationConfig.default())
            .validate(Rdf.parse("$prefixes\n$data", RdfFormat.TURTLE), shapes)
            .violations.map { it.focusNode }.toSet()
    }

    @Test fun `multi-valued keys conflict when the value sets are equal`() {
        assertEquals(setOf(ex("a"), ex("b")), conflicts("ex:a a ex:T ; ex:id 1 , 2 . ex:b a ex:T ; ex:id 2 , 1 ."))
        assertEquals(setOf(ex("a"), ex("b")), conflicts("ex:a a ex:T ; ex:id 1 , 2 . ex:b a ex:T ; ex:id 2 , 1 . ex:c a ex:T ; ex:id 1 ."))
        assertEquals(emptySet<RdfTerm>(), conflicts("ex:a a ex:T ; ex:id 1 , 2 . ex:b a ex:T ; ex:id 2 , 3 ."))
    }

    @Test fun `composite keys compare the value sets of every property`() {
        val key = "( ex:id ex:scheme )"
        assertEquals(
            setOf(ex("a"), ex("b")),
            conflicts("ex:a a ex:T ; ex:id 1 , 2 ; ex:scheme ex:s . ex:b a ex:T ; ex:id 2 , 1 ; ex:scheme ex:s .", key),
        )
        assertEquals(emptySet<RdfTerm>(), conflicts("ex:a a ex:T ; ex:id 1 , 2 ; ex:scheme ex:s . ex:b a ex:T ; ex:id 1 , 2 ; ex:scheme ex:t .", key))
    }

    @Test fun `values are compared as RDF terms, not as typed values`() {
        assertEquals(emptySet<RdfTerm>(), conflicts("ex:a a ex:T ; ex:id 1 . ex:b a ex:T ; ex:id \"01\"^^xsd:integer ."))
        assertEquals(emptySet<RdfTerm>(), conflicts("ex:a a ex:T ; ex:id 1 . ex:b a ex:T ; ex:id \"1\" ."))
        assertEquals(setOf(ex("a"), ex("b")), conflicts("ex:a a ex:T ; ex:id \"x\"@EN . ex:b a ex:T ; ex:id \"x\"@en ."))
    }
}
