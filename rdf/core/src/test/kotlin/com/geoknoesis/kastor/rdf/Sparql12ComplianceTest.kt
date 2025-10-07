package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for SPARQL 1.2 compliance in the QueryTerms DSL.
 * 
 * This test suite verifies that the QueryTerms DSL correctly supports
 * all major SPARQL 1.2 features including RDF-star, new functions,
 * and enhanced syntax.
 */
class Sparql12ComplianceTest {

    @Test
    fun `test VERSION declaration support`() {
        val query = select("name") {
            version("1.2")
            prefix("foaf", "http://xmlns.com/foaf/0.1/")
            where {
                pattern(var_("person"), iri("foaf:name"), var_("name"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("VERSION 1.2"))
        assertTrue(queryString.contains("PREFIX foaf: <http://xmlns.com/foaf/0.1/>"))
        assertTrue(queryString.contains("SELECT ?name"))
    }

    @Test
    fun `test RDF-star quoted triple patterns`() {
        val query = select("subject", "predicate", "object") {
            version("1.2")
            where {
                quotedTriple(var_("subject"), var_("predicate"), var_("object"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("<< ?subject ?predicate ?object >>"))
    }

    @Test
    fun `test RDF-star TRIPLE function`() {
        val query = select {
            version("1.2")
            expression(triple(var_("s"), var_("p"), var_("o")), "tripleTerm")
            where {
                pattern(var_("s"), var_("p"), var_("o"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("TRIPLE(?s, ?p, ?o) AS ?tripleTerm"))
    }

    @Test
    fun `test RDF-star isTRIPLE function`() {
        val query = select("isTriple") {
            version("1.2")
            where {
                pattern(var_("s"), var_("p"), var_("o"))
                filter(isTriple(var_("s")))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("FILTER(isTRIPLE(?s))"))
    }

    @Test
    fun `test RDF-star SUBJECT function`() {
        val query = select {
            version("1.2")
            expression(subject(var_("triple")), "subj")
            where {
                pattern(var_("triple"), iri("http://example.org/type"), iri("http://example.org/Triple"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("SUBJECT(?triple) AS ?subj"))
    }

    @Test
    fun `test RDF-star PREDICATE function`() {
        val query = select {
            version("1.2")
            expression(predicate(var_("triple")), "pred")
            where {
                pattern(var_("triple"), iri("http://example.org/type"), iri("http://example.org/Triple"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("PREDICATE(?triple) AS ?pred"))
    }

    @Test
    fun `test RDF-star OBJECT function`() {
        val query = select {
            version("1.2")
            expression(`object`(var_("triple")), "obj")
            where {
                pattern(var_("triple"), iri("http://example.org/type"), iri("http://example.org/Triple"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("OBJECT(?triple) AS ?obj"))
    }

    @Test
    fun `test literal base direction LANGDIR function`() {
        val query = select {
            version("1.2")
            expression(langdir(var_("text")), "direction")
            where {
                pattern(var_("s"), iri("http://example.org/text"), var_("text"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("LANGDIR(?text) AS ?direction"))
    }

    @Test
    fun `test literal base direction hasLANG function`() {
        val query = select("text") {
            version("1.2")
            where {
                pattern(var_("s"), iri("http://example.org/text"), var_("text"))
                filter(hasLang(var_("text"), "en"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("hasLANG(?text"))
    }

    @Test
    fun `test literal base direction hasLANGDIR function`() {
        val query = select("text") {
            version("1.2")
            where {
                pattern(var_("s"), iri("http://example.org/text"), var_("text"))
                filter(hasLangdir(var_("text"), "rtl"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("hasLANGDIR(?text"))
    }

    @Test
    fun `test literal base direction STRLANGDIR function`() {
        val query = select {
            version("1.2")
            expression(strlangdir(var_("text"), "en", "ltr"), "strWithDir")
            where {
                pattern(var_("s"), iri("http://example.org/text"), var_("text"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("STRLANGDIR(?text"))
    }

    @Test
    fun `test enhanced string functions`() {
        val query = select {
            version("1.2")
            expression(replaceAll(var_("text"), "old", "new"), "replaced")
            expression(encodeForUri(var_("text")), "encoded")
            expression(decodeForUri(var_("text")), "decoded")
            where {
                pattern(var_("s"), iri("http://example.org/text"), var_("text"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("REPLACE_ALL(?text"))
        assertTrue(queryString.contains("ENCODE_FOR_URI(?text) AS ?encoded"))
        assertTrue(queryString.contains("DECODE_FOR_URI(?text) AS ?decoded"))
    }

    @Test
    fun `test enhanced numeric functions`() {
        val query = select {
            version("1.2")
            expression(rand(), "randomValue")
            expression(random(), "randomValue2")
            expression(now(), "currentTime")
            expression(timezone(), "timezone")
            where {
                pattern(var_("s"), iri("http://example.org/value"), var_("value"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("RAND() AS ?randomValue"))
        assertTrue(queryString.contains("RANDOM() AS ?randomValue2"))
        assertTrue(queryString.contains("NOW() AS ?currentTime"))
        assertTrue(queryString.contains("TIMEZONE() AS ?timezone"))
    }

    @Test
    fun `test enhanced date time functions`() {
        val query = select {
            version("1.2")
            expression(dateTime(var_("dateTime")), "dt")
            expression(date(var_("dateTime")), "d")
            expression(time(var_("dateTime")), "t")
            expression(tz(var_("dateTime")), "tz")
            where {
                pattern(var_("s"), iri("http://example.org/dateTime"), var_("dateTime"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("DATETIME(?dateTime) AS ?dt"))
        assertTrue(queryString.contains("DATE(?dateTime) AS ?d"))
        assertTrue(queryString.contains("TIME(?dateTime) AS ?t"))
        assertTrue(queryString.contains("TZ(?dateTime) AS ?tz"))
    }

    @Test
    fun `test complex SPARQL 1_2 query with multiple features`() {
        val query = select("person", "confidence") {
            version("1.2")
            prefix("foaf", "http://xmlns.com/foaf/0.1/")
            prefix("ex", "http://example.org/")
            
            where {
                // Regular triple pattern
                pattern(var_("person"), iri("foaf:name"), var_("name"))
                
                // RDF-star quoted triple pattern
                quotedTriple(var_("person"), iri("foaf:name"), var_("name"))
                
                // RDF-star functions
                bind(var_("tripleTerm"), triple(var_("person"), iri("foaf:name"), var_("name")))
                bind(var_("confidence"), var_("confidence"))
                
                // Filters with new functions
                filter(hasLang(var_("name"), "en"))
                filter(isTriple(var_("tripleTerm")))
            }
            
            orderBy(var_("person"))
            limit(10)
        }

        val queryString = query.toString()
        
        // Check VERSION declaration
        assertTrue(queryString.contains("VERSION 1.2"))
        
        // Check prefix declarations
        assertTrue(queryString.contains("PREFIX foaf: <http://xmlns.com/foaf/0.1/>"))
        assertTrue(queryString.contains("PREFIX ex: <http://example.org/>"))
        
        // Check SELECT clause
        assertTrue(queryString.contains("SELECT ?person ?confidence"))
        
        // Check WHERE clause with RDF-star
        assertTrue(queryString.contains("<< ?person <foaf:name> ?name >>"))
        assertTrue(queryString.contains("TRIPLE(?person, <foaf:name>, ?name) AS ?tripleTerm"))
        assertTrue(queryString.contains("hasLANG(?name"))
        assertTrue(queryString.contains("isTRIPLE(?tripleTerm)"))
        
        // Check ORDER BY and LIMIT
        assertTrue(queryString.contains("ORDER BY ?person"))
        assertTrue(queryString.contains("LIMIT 10"))
    }

    @Test
    fun `test property paths with SPARQL 1_2 enhancements`() {
        val query = select("person", "friend") {
            version("1.2")
            prefix("foaf", "http://xmlns.com/foaf/0.1/")
            
            where {
                // Property path pattern using regular patterns for now
                pattern(var_("person"), iri("foaf:knows"), var_("friend"))
                pattern(var_("person"), iri("foaf:friend"), var_("friend"))
            }
        }

        val queryString = query.toString()
        assertTrue(queryString.contains("foaf:knows"))
        assertTrue(queryString.contains("foaf:friend"))
    }

    @Test
    fun `test VersionDeclaration validation`() {
        // Valid version
        val validVersion = VersionDeclaration("1.2")
        assertEquals("VERSION 1.2", validVersion.toString())
        
        // Test invalid versions
        assertThrows(IllegalArgumentException::class.java) {
            VersionDeclaration("")
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            VersionDeclaration("1")
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            VersionDeclaration("1.2.3")
        }
    }

    @Test
    fun `test QuotedTriplePattern toString`() {
        val quotedTriple = QuotedTriplePattern(
            var_("s"),
            var_("p"), 
            var_("o")
        )
        
        assertEquals("<< ?s ?p ?o >>", quotedTriple.toString())
    }

    @Test
    fun `test RdfStarTriplePattern toString`() {
        val rdfStarPattern = RdfStarTriplePattern(
            QuotedTriplePattern(var_("s"), var_("p"), var_("o")),
            var_("pred"),
            var_("obj")
        )
        
        assertEquals("<< ?s ?p ?o >> ?pred ?obj .", rdfStarPattern.toString())
    }
}
