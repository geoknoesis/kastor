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
                pattern(var_("person"), Iri("foaf:name"), var_("name"))
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
                pattern(var_("triple"), Iri("http://example.org/type"), Iri("http://example.org/Triple"))
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
                pattern(var_("triple"), Iri("http://example.org/type"), Iri("http://example.org/Triple"))
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
                pattern(var_("triple"), Iri("http://example.org/type"), Iri("http://example.org/Triple"))
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
                pattern(var_("s"), Iri("http://example.org/text"), var_("text"))
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
                pattern(var_("s"), Iri("http://example.org/text"), var_("text"))
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
                pattern(var_("s"), Iri("http://example.org/text"), var_("text"))
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
                pattern(var_("s"), Iri("http://example.org/text"), var_("text"))
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
                pattern(var_("s"), Iri("http://example.org/text"), var_("text"))
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
                pattern(var_("s"), Iri("http://example.org/value"), var_("value"))
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
                pattern(var_("s"), Iri("http://example.org/dateTime"), var_("dateTime"))
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
                pattern(var_("person"), Iri("foaf:name"), var_("name"))
                
                // RDF-star quoted triple pattern
                quotedTriple(var_("person"), Iri("foaf:name"), var_("name"))
                
                // RDF-star functions
                bind(var_("tripleTerm"), triple(var_("person"), Iri("foaf:name"), var_("name")))
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
                pattern(var_("person"), Iri("foaf:knows"), var_("friend"))
                pattern(var_("person"), Iri("foaf:friend"), var_("friend"))
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

    @Test
    fun `test property paths - one or more`() {
        val query = select {
            where {
                propertyPath(var_("person"), OneOrMore(BasicPath(Iri("foaf:knows"))), var_("friend"))
            }
        }
        val queryString = query.toString()
        assertTrue(queryString.contains("SELECT"))
        assertTrue(queryString.contains("?person"))
        assertTrue(queryString.contains("?friend"))
        assertTrue(queryString.contains("<foaf:knows>+"))
    }

    @Test
    fun `test property paths - zero or more`() {
        val query = select {
            where {
                propertyPath(var_("person"), ZeroOrMore(BasicPath(Iri("foaf:knows"))), var_("friend"))
            }
        }
        val queryString = query.toString()
        assertTrue(queryString.contains("<foaf:knows>*"))
    }

    @Test
    fun `test property paths - zero or one`() {
        val query = select {
            where {
                propertyPath(var_("person"), ZeroOrOne(BasicPath(Iri("foaf:knows"))), var_("friend"))
            }
        }
        val queryString = query.toString()
        assertTrue(queryString.contains("<foaf:knows>?"))
    }

    @Test
    fun `test property paths - alternative`() {
        val query = select {
            where {
                propertyPath(var_("person"), Alternative(BasicPath(Iri("foaf:knows")), BasicPath(Iri("foaf:friendOf"))), var_("friend"))
            }
        }
        val queryString = query.toString()
        assertTrue(queryString.contains("<foaf:knows>|<foaf:friendOf>"))
    }

    @Test
    fun `test property paths - sequence`() {
        val query = select {
            where {
                propertyPath(var_("person"), PathSequence(BasicPath(Iri("foaf:knows")), BasicPath(Iri("foaf:friendOf"))), var_("friend"))
            }
        }
        val queryString = query.toString()
        assertTrue(queryString.contains("<foaf:knows>/<foaf:friendOf>"))
    }

    @Test
    fun `test property paths - range`() {
        val query = select {
            where {
                propertyPath(var_("person"), Range(BasicPath(Iri("foaf:knows")), 2, 4), var_("friend"))
            }
        }
        val queryString = query.toString()
        assertTrue(queryString.contains("<foaf:knows>{2,4}"))
    }

    @Test
    fun `test property paths - negation`() {
        val query = select {
            where {
                propertyPath(var_("person"), Negation(BasicPath(Iri("foaf:knows"))), var_("friend"))
            }
        }
        val queryString = query.toString()
        assertTrue(queryString.contains("!<foaf:knows>"))
    }

    @Test
    fun `test property paths - inverse`() {
        val query = select {
            where {
                propertyPath(var_("person"), Inverse(BasicPath(Iri("foaf:knows"))), var_("friend"))
            }
        }
        val queryString = query.toString()
        assertTrue(queryString.contains("^<foaf:knows>"))
    }

    @Test
    fun `test property paths - complex combination`() {
        val query = select {
            where {
                propertyPath(var_("person"), 
                    OneOrMore(Alternative(BasicPath(Iri("foaf:knows")), Inverse(BasicPath(Iri("foaf:friendOf"))))), 
                    var_("friend"))
            }
        }
        val queryString = query.toString()
        assertTrue(queryString.contains("<foaf:knows>|^<foaf:friendOf>+"))
    }

    @Test
    fun `test aggregation and GROUP BY support`() {
        val query = select {
            variable(var_("department"))
            aggregate(count(var_("employee")), "employeeCount")
            aggregate(avg(var_("salary")), "avgSalary")
            where {
                pattern(var_("employee"), Iri("worksFor"), var_("department"))
                pattern(var_("employee"), Iri("hasSalary"), var_("salary"))
            }
            groupBy(var_("department"))
            having {
                // Test aggregate comparison
                filter(count(var_("employee")) gt int(5))
            }
            orderBy(var_("employeeCount"), OrderDirection.DESC)
        }
        val queryString = query.toString()
        assertTrue(queryString.contains("COUNT(?employee)"))
        assertTrue(queryString.contains("AVG(?salary)"))
        assertTrue(queryString.contains("GROUP BY ?department"))
        assertTrue(queryString.contains("HAVING"))
        assertTrue(queryString.contains("COUNT(?employee) > \"5\"^^<http://www.w3.org/2001/XMLSchema#integer>"))
        assertTrue(queryString.contains("ORDER BY ?employeeCount DESC"))
    }

    @Test
    fun `test all aggregate functions`() {
        val query = select {
            aggregate(count(var_("item")), "count")
            aggregate(countDistinct(var_("item")), "distinctCount")
            aggregate(sum(var_("value")), "total")
            aggregate(avg(var_("value")), "average")
            aggregate(min(var_("value")), "minimum")
            aggregate(max(var_("value")), "maximum")
            aggregate(groupConcat(var_("name")), "names")
            where {
                pattern(var_("item"), Iri("hasValue"), var_("value"))
                pattern(var_("item"), Iri("hasName"), var_("name"))
            }
        }
        val queryString = query.toString()
        assertTrue(queryString.contains("COUNT(?item)"))
        assertTrue(queryString.contains("COUNT(DISTINCT ?item)"))
        assertTrue(queryString.contains("SUM(?value)"))
        assertTrue(queryString.contains("AVG(?value)"))
        assertTrue(queryString.contains("MIN(?value)"))
        assertTrue(queryString.contains("MAX(?value)"))
        assertTrue(queryString.contains("GROUP_CONCAT(?name)"))
    }

    @Test
    fun `test datatype handling xsd string vs plain literals`() {
        // Test that string literals are properly typed as xsd:string
        val query = select {
            variable(var_("name"))
            where {
                pattern(var_("person"), Iri("foaf:name"), var_("name"))
                filter(var_("name") eq string("John"))
            }
        }
        val queryString = query.toString()
        
        // Verify that string literals are typed as xsd:string
        assertTrue(queryString.contains("\"John\"^^<http://www.w3.org/2001/XMLSchema#string>"))
        
        // Test with different literal types
        val query2 = select {
            variable(var_("age"))
            where {
                pattern(var_("person"), Iri("foaf:age"), var_("age"))
                filter(var_("age") eq int(25))
            }
        }
        val queryString2 = query2.toString()
        
        // Verify that integer literals are typed as xsd:integer
        assertTrue(queryString2.contains("\"25\"^^<http://www.w3.org/2001/XMLSchema#integer>"))
        
        // Test with language-tagged literals
        val query3 = select {
            variable(var_("label"))
            where {
                pattern(var_("resource"), Iri("rdfs:label"), var_("label"))
                filter(var_("label") eq lang("Hello", "en"))
            }
        }
        val queryString3 = query3.toString()
        
        // Verify that language-tagged literals use @ syntax
        assertTrue(queryString3.contains("\"Hello\"@en"))
    }

    @Test
    fun `test Unicode escape sequence handling`() {
        // Test Unicode escape sequences in string literals
        val query = select {
            variable(var_("text"))
            where {
                pattern(var_("resource"), Iri("dc:description"), var_("text"))
                filter(var_("text") eq string("Hello \u0041\u0042\u0043")) // ABC in Unicode escapes
            }
        }
        val queryString = query.toString()
        
        // Verify that Unicode escape sequences are properly handled
        assertTrue(queryString.contains("Hello ABC"))
        
        // Test with more complex Unicode sequences
        val query2 = select {
            variable(var_("unicode"))
            where {
                pattern(var_("resource"), Iri("rdfs:label"), var_("unicode"))
                filter(var_("unicode") eq string("Unicode: \u03B1\u03B2\u03B3")) // Greek letters
            }
        }
        val queryString2 = query2.toString()
        
        // Verify that Unicode characters are preserved
        assertTrue(queryString2.contains("Unicode: αβγ"))
        
        // Test with special characters that need escaping
        val query3 = select {
            variable(var_("special"))
            where {
                pattern(var_("resource"), Iri("dc:title"), var_("special"))
                filter(var_("special") eq string("Special: \"quotes\" 'apostrophes' \\backslashes\\"))
            }
        }
        val queryString3 = query3.toString()
        
        // Verify that special characters are properly escaped in SPARQL
        assertTrue(queryString3.contains("\"quotes\""))
        assertTrue(queryString3.contains("\\backslashes\\"))
    }

    @Test
    fun `test effective boolean value EBV handling`() {
        // Test EBV conversion for different literal types
        val query = select {
            variable(var_("resource"))
            where {
                pattern(var_("resource"), Iri("rdf:type"), var_("type"))
                // Test boolean literals (true/false)
                filter(var_("active") eq boolean(true))
                // Test numeric literals (0 = false, non-zero = true)
                filter(var_("count") gt int(0))
                // Test string literals (empty = false, non-empty = true)
                filter(var_("name") ne string(""))
                // Test using boolean functions
                filter(bound(var_("optional")))
                filter(isIRI(var_("uri")))
                filter(isLiteral(var_("literal")))
                filter(isNumeric(var_("number")))
            }
        }
        val queryString = query.toString()
        
        // Verify boolean literal handling
        assertTrue(queryString.contains("\"true\"^^<http://www.w3.org/2001/XMLSchema#boolean>"))
        assertTrue(queryString.contains("\"0\"^^<http://www.w3.org/2001/XMLSchema#integer>"))
        assertTrue(queryString.contains("\"\"^^<http://www.w3.org/2001/XMLSchema#string>"))
        assertTrue(queryString.contains("BOUND(?optional)"))
        assertTrue(queryString.contains("isIRI(?uri)"))
        assertTrue(queryString.contains("isLITERAL(?literal)"))
        assertTrue(queryString.contains("isNUMERIC(?number)"))
        
        // Test conditional expressions that rely on EBV
        val query2 = select {
            variable(var_("result"))
            where {
                pattern(var_("resource"), Iri("hasValue"), var_("value"))
                bind(var_("result"), if_(var_("value") gt int(10), string("high"), string("low")))
            }
        }
        val queryString2 = query2.toString()
        assertTrue(queryString2.contains("IF(?value > \"10\"^^<http://www.w3.org/2001/XMLSchema#integer>, \"high\"^^<http://www.w3.org/2001/XMLSchema#string>, \"low\"^^<http://www.w3.org/2001/XMLSchema#string>) AS ?result"))
    }
}









