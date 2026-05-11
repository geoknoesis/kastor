package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.bnode
import com.geoknoesis.kastor.rdf.iri
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RdfGraphIsomorphismTest {

    @Test
    fun `isomorphic when blank node ids differ`() {
        val fromTurtle = Rdf.parse(
            """
            @prefix ex: <http://example.org/> .
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            [] a foaf:Person ; foaf:name "Alex" .
            """.trimIndent(),
            RdfFormat.TURTLE,
        )
        val fromDsl = Rdf.graph {
            val p = bnode("dsl-internal-id")
            p - RDF.type - FOAF.Person
            p - FOAF.name - "Alex"
        }
        assertTrue(RdfGraphIsomorphism.isIsomorphic(fromTurtle, fromDsl))
    }

    @Test
    fun `not isomorphic when predicate differs`() {
        val a = Rdf.graph {
            val s = iri("http://example.org/s")
            s - FOAF.name - "A"
        }
        val b = Rdf.graph {
            val s = iri("http://example.org/s")
            s - FOAF.homepage - iri("http://example.org/h")
        }
        assertFalse(RdfGraphIsomorphism.isIsomorphic(a, b))
    }

    @Test
    fun `assertGraphIsomorphicTurtle throws with message`() {
        val actual = Rdf.graph {
            val s = iri("http://example.org/s")
            s - FOAF.name - "Wrong"
        }
        val err = assertFailsWith<AssertionError> {
            assertGraphIsomorphicTurtle(
                """
                @prefix foaf: <http://xmlns.com/foaf/0.1/> .
                <http://example.org/s> foaf:name "Right" .
                """.trimIndent(),
                actual,
            )
        }
        assertTrue(err.message!!.contains("not isomorphic"))
    }
}
