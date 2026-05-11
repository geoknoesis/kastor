package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * RDF 1.2 reification semantics: a reifier (IRI or blank node) is linked via
 * `rdf:reifies` to a triple term, and metadata is attached to the reifier.
 */
class Rdf12ReificationTest {

    @Test
    fun `embedded function still constructs an RdfStarTriple`() {
        val alice = Iri("http://example.org/alice")
        val bob = Iri("http://example.org/bob")
        val embeddedTriple = embedded(alice, FOAF.knows, bob)
        assertEquals(alice, embeddedTriple.subject)
        assertEquals(FOAF.knows, embeddedTriple.predicate)
        assertEquals(bob, embeddedTriple.obj)
    }

    @Test
    fun `TripleTerm renders with RDF 1 dot 2 syntax`() {
        val alice = Iri("http://example.org/alice")
        val tt = TripleTerm(RdfTriple(alice, FOAF.age, 30.toLiteral()))
        // Spec syntax: <<( s p o )>>
        assertTrue(tt.toString().startsWith("<<( "))
        assertTrue(tt.toString().endsWith(" )>>"))
    }

    @Test
    fun `TripleTerm is a RdfTerm but not a RdfResource`() {
        val tt: RdfTerm = TripleTerm(RdfTriple(Iri("urn:s"), Iri("urn:p"), Iri("urn:o")))
        // Hard break: triple terms must not be subjects in RDF 1.2.
        val asAny: Any = tt
        assertFalse(asAny is RdfResource, "TripleTerm must not implement RdfResource in RDF 1.2")
    }

    @Test
    fun `reifies builder emits reifier rdf-reifies tripleTerm`() {
        val graph = Rdf.graph {
            val alice = iri("http://example.org/alice")
            val claim = RdfTriple(alice, FOAF.age, 30.toLiteral())
            triple(claim.subject, claim.predicate, claim.obj)
            reifies(claim) { reifier ->
                reifier - iri("http://example.org/certainty") - 0.9
            }
        }

        val triples = graph.getTriples()
        // Asserted base triple
        assertTrue(triples.any { it.predicate == FOAF.age })
        // rdf:reifies linking a reifier to a TripleTerm
        val reifiesTriple = triples.singleOrNull { it.predicate == RDF.reifies }
        assertNotNull(reifiesTriple, "expected exactly one rdf:reifies triple")
        val tt = reifiesTriple!!.obj
        assertTrue(tt is TripleTerm, "rdf:reifies object should be a TripleTerm, got $tt")
        // Metadata triple anchored on the same reifier
        val reifier = reifiesTriple.subject
        assertTrue(triples.any { it.subject == reifier && it.predicate.value.endsWith("certainty") })
    }

    @Test
    fun `directional lang literal carries dirLangString datatype`() {
        val rtl = LangString("\u0645\u0631\u062D\u0628\u0627", "ar", Direction.RTL)
        assertEquals(RDF.dirLangString, rtl.datatype)
        assertEquals("\"\u0645\u0631\u062D\u0628\u0627\"@ar--rtl", rtl.toString())

        val ltr = lang("Hello", "en", Direction.LTR)
        assertEquals(RDF.dirLangString, ltr.datatype)
        assertTrue(ltr.toString().endsWith("@en--ltr"))

        val plain = LangString("Hello", "en")
        assertEquals(RDF.langString, plain.datatype)
        assertEquals("\"Hello\"@en", plain.toString())
    }
}
