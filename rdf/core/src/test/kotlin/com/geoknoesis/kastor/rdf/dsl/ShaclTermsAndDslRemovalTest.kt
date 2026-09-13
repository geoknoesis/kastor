package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShaclTermsAndDslRemovalTest {

    @Test
    fun `SHACL prefix declaration terms have the spec IRIs`() {
        assertEquals("http://www.w3.org/ns/shacl#declare", SHACL.declare.value)
        assertEquals("http://www.w3.org/ns/shacl#prefix", SHACL.prefixProperty.value)
        assertEquals("http://www.w3.org/ns/shacl#namespace", SHACL.namespaceProperty.value)
        // The vocabulary's own prefix/namespace strings are unchanged.
        assertEquals("sh", SHACL.prefix)
        assertEquals("http://www.w3.org/ns/shacl#", SHACL.namespace)
    }

    @Test
    fun `removeTriple drops one collected occurrence`() {
        val dsl = GraphDsl()
        val t = RdfTriple(Iri("urn:s"), Iri("urn:p"), Iri("urn:o"))
        dsl.triple(t.subject, t.predicate, t.obj)
        dsl.triple(t.subject, t.predicate, t.obj)
        assertTrue(dsl.removeTriple(t))
        assertEquals(listOf(t), dsl.triples)
        assertTrue(dsl.removeTriple(t))
        assertFalse(dsl.removeTriple(t))
        assertTrue(dsl.triples.isEmpty())
    }
}
