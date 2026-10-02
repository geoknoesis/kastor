package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.vocab.GEO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `qname(...)`: a declared prefix always wins, an undeclared prefix that is a well-known URI scheme makes an absolute
 * IRI, and the built-in default prefixes never capture a URI of a well-known scheme.
 */
class QNameSchemeResolutionTest {
    private fun dsl(configure: GraphDsl.() -> Unit = {}) = GraphDsl().apply(configure)

    @Test
    fun `a declared prefix wins over a URI scheme of the same name`() {
        val declared = dsl {
            prefix("data", "http://ex/data/")
            prefix("urn", "http://ex/urn#")
            prefixes {
                put("file", "http://ex/file/")
                this["mailto"] = "http://ex/mailto/"
                putAll(mapOf("tel" to "http://ex/tel/", "http" to "http://ex/http/"))
            }
        }
        assertEquals(Iri("http://ex/data/item1"), declared.qname("data:item1"))
        assertEquals(Iri("http://ex/urn#isbn"), declared.qname("urn:isbn"))
        assertEquals(Iri("http://ex/file/a"), declared.qname("file:a"))
        assertEquals(Iri("http://ex/mailto/bob"), declared.qname("mailto:bob"))
        assertEquals(Iri("http://ex/tel/x"), declared.qname("tel:x"))
        assertEquals(Iri("http://ex/http/x"), declared.qname("http:x"))
        // Even then, what is unmistakably a URL stays one.
        assertEquals(Iri("http://example.org/x"), declared.qname("http://example.org/x"))
    }

    @Test
    fun `an undeclared well-known scheme is an absolute IRI`() {
        val plain = dsl()
        for (iri in listOf(
            "urn:isbn:0451450523", "urn:uuid:6e8bc430-9c3a-11d9-9669-0800200c9a66", "urn:x",
            "data:text/plain;base64,SGVsbG8=", "data:item1", "file:///etc/hosts", "file:/C:/x.ttl",
            "mailto:alice@example.org", "tel:+1-201-555-0123", "tel:7042", "geo:37.7,-122.4", "geo:37.786971,-122.399677;u=35",
            "http://example.org/a", "https://example.org/a#b", "http:relative",
        )) {
            assertEquals(Iri(iri), plain.qname(iri), iri)
        }
    }

    @Test
    fun `the built-in geo prefix expands names and leaves geo URIs alone`() {
        val plain = dsl()
        assertEquals(Iri(GEO.namespace + "asWKT"), plain.qname("geo:asWKT"))
        assertEquals(Iri(GEO.namespace + "hasGeometry"), plain.qname("geo:hasGeometry"))
        assertEquals(Iri("geo:37.7,-122.4"), plain.qname("geo:37.7,-122.4"))
        assertEquals(Iri("geo:48.2010,16.3695,183"), plain.qname("geo:48.2010,16.3695,183"))
        // Declared by the user, the prefix captures everything, like any declared prefix.
        val declared = dsl { prefix("geo", "http://ex/geo#") }
        assertEquals(Iri("http://ex/geo#37.7"), declared.qname("geo:37.7"))
        assertEquals(Iri("http://ex/geo#asWKT"), declared.qname("geo:asWKT"))
        // Declaring the built-in namespace again is a declaration too.
        val same = dsl { prefix("geo", GEO.namespace) }
        assertEquals(Iri(GEO.namespace + "37.7"), same.qname("geo:37.7"))
    }

    @Test
    fun `other built-in prefixes and unknown prefixes behave as before`() {
        val plain = dsl()
        assertEquals(Iri("http://www.w3.org/2000/01/rdf-schema#label"), plain.qname("rdfs:label"))
        assertEquals(Iri("http://www.w3.org/2006/time#Instant"), plain.qname("time:Instant"))
        val unknown = assertThrows(IllegalArgumentException::class.java) { plain.qname("nope:thing") }
        assertTrue(unknown.message!!.contains("Unknown prefix: 'nope'"), unknown.message)
        // Not a QName and not an absolute IRI.
        assertThrows(IllegalArgumentException::class.java) { plain.qname("plain") }
        // A removed declaration no longer wins.
        val removed = dsl { prefix("data", "http://ex/data/"); prefixes { remove("data") } }
        assertEquals(Iri("data:item1"), removed.qname("data:item1"))
    }
}
