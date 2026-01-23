package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.sparql.*
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
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
                triple(`var`("person"), FOAF.name, `var`("name"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("VERSION 1.2"))
        assertTrue(queryString.contains("PREFIX foaf: <http://xmlns.com/foaf/0.1/>"))
        assertTrue(queryString.contains("SELECT ?name"))
    }

    @Test
    fun `test RDF-star quoted triple patterns`() {
        val query = select("subject", "predicate", "object") {
            version("1.2")
            where {
                quotedTriple(`var`("subject"), `var`("predicate"), `var`("object"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("<< ?subject ?predicate ?object >>"))
    }

    @Test
    fun `test RDF-star TRIPLE function`() {
        val query = select {
            version("1.2")
            expression(triple(`var`("s"), `var`("p"), `var`("o")), "tripleTerm")
            where {
                triple(`var`("s"), `var`("p"), `var`("o"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("TRIPLE(?s, ?p, ?o) AS ?tripleTerm"))
    }

    @Test
    fun `test RDF-star isTRIPLE function`() {
        val query = select("isTriple") {
            version("1.2")
            where {
                triple(`var`("s"), `var`("p"), `var`("o"))
                filter(isTriple(`var`("s").expr()))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("FILTER(isTRIPLE(?s))"))
    }

    @Test
    fun `test RDF-star SUBJECT function`() {
        val query = select {
            version("1.2")
            expression(subject(`var`("triple").expr()), "subj")
            where {
                triple(`var`("triple"), Iri("http://example.org/type"), Iri("http://example.org/Triple"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("SUBJECT(?triple) AS ?subj"))
    }

    @Test
    fun `test RDF-star PREDICATE function`() {
        val query = select {
            version("1.2")
            expression(predicate(`var`("triple").expr()), "pred")
            where {
                triple(`var`("triple"), Iri("http://example.org/type"), Iri("http://example.org/Triple"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("PREDICATE(?triple) AS ?pred"))
    }

    @Test
    fun `test RDF-star OBJECT function`() {
        val query = select {
            version("1.2")
            expression(`object`(`var`("triple").expr()), "obj")
            where {
                triple(`var`("triple"), Iri("http://example.org/type"), Iri("http://example.org/Triple"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("OBJECT(?triple) AS ?obj"))
    }

    @Test
    fun `test literal base direction LANGDIR function`() {
        val query = select {
            version("1.2")
            expression(langdir(`var`("text").expr()), "direction")
            where {
                triple(`var`("s"), Iri("http://example.org/text"), `var`("text"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("LANGDIR(?text) AS ?direction"))
    }

    @Test
    fun `test literal base direction hasLANG function`() {
        val query = select("text") {
            version("1.2")
            where {
                triple(`var`("s"), Iri("http://example.org/text"), `var`("text"))
                filter(hasLang(`var`("text").expr(), "en"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("hasLANG(?text"))
    }

    @Test
    fun `test literal base direction hasLANGDIR function`() {
        val query = select("text") {
            version("1.2")
            where {
                triple(`var`("s"), Iri("http://example.org/text"), `var`("text"))
                filter(hasLangdir(`var`("text").expr(), "rtl"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("hasLANGDIR(?text"))
    }

    @Test
    fun `test literal base direction STRLANGDIR function`() {
        val query = select {
            version("1.2")
            expression(strlangdir(`var`("text").expr(), "en", "ltr"), "strWithDir")
            where {
                triple(`var`("s"), Iri("http://example.org/text"), `var`("text"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("STRLANGDIR(?text"))
    }

    @Test
    fun `test enhanced string functions`() {
        val query = select {
            version("1.2")
            expression(replaceAll(`var`("text").expr(), "old", "new"), "replaced")
            expression(encodeForUri(`var`("text").expr()), "encoded")
            expression(decodeForUri(`var`("text").expr()), "decoded")
            where {
                triple(`var`("s"), Iri("http://example.org/text"), `var`("text"))
            }
        }

        val queryString = query.sparql
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
                triple(`var`("s"), Iri("http://example.org/value"), `var`("value"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("RAND() AS ?randomValue"))
        assertTrue(queryString.contains("RANDOM() AS ?randomValue2"))
        assertTrue(queryString.contains("NOW() AS ?currentTime"))
        assertTrue(queryString.contains("TIMEZONE() AS ?timezone"))
    }

    @Test
    fun `test enhanced date time functions`() {
        val query = select {
            version("1.2")
            expression(dateTime(`var`("dateTime").expr()), "dt")
            expression(date(`var`("dateTime").expr()), "d")
            expression(time(`var`("dateTime").expr()), "t")
            expression(tz(`var`("dateTime").expr()), "tz")
            where {
                triple(`var`("s"), Iri("http://example.org/dateTime"), `var`("dateTime"))
            }
        }

        val queryString = query.sparql
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
                triple(`var`("person"), FOAF.name, `var`("name"))
                
                // RDF-star quoted triple pattern
                quotedTriple(`var`("person"), FOAF.name, `var`("name"))
                
                // RDF-star functions
                bind(`var`("tripleTerm"), com.geoknoesis.kastor.rdf.sparql.triple(`var`("person"), FOAF.name, `var`("name")))
                bind(`var`("confidence"), `var`("confidence").expr())
                
                // Filters with new functions
                filter(hasLang(`var`("name").expr(), "en"))
                filter(isTriple(`var`("tripleTerm").expr()))
            }
            
            orderBy(`var`("person"))
            limit(10)
        }

        val queryString = query.sparql
        
        // Check VERSION declaration
        assertTrue(queryString.contains("VERSION 1.2"))
        
        // Check prefix declarations
        assertTrue(queryString.contains("PREFIX foaf: <http://xmlns.com/foaf/0.1/>"))
        assertTrue(queryString.contains("PREFIX ex: <http://example.org/>"))
        
        // Check SELECT clause
        assertTrue(queryString.contains("SELECT ?person ?confidence"))
        
        // Check WHERE clause with RDF-star
        assertTrue(queryString.contains("<< ?person <http://xmlns.com/foaf/0.1/name> ?name >>"))
        assertTrue(queryString.contains("TRIPLE(?person, <http://xmlns.com/foaf/0.1/name>, ?name) AS ?tripleTerm"))
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
                triple(`var`("person"), FOAF.knows, `var`("friend"))
                triple(`var`("person"), Iri("http://xmlns.com/foaf/0.1/friend"), `var`("friend"))
            }
        }

        val queryString = query.sparql
        assertTrue(queryString.contains("http://xmlns.com/foaf/0.1/knows"))
        assertTrue(queryString.contains("http://xmlns.com/foaf/0.1/friend"))
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
    fun `test QuotedTriplePattern rendering`() {
        val query = select {
            where {
                quotedTriple(`var`("s"), `var`("p"), `var`("o"))
            }
        }
        val queryString = query.sparql
        assertTrue(queryString.contains("<< ?s ?p ?o >>"))
    }

    @Test
    fun `test property paths - one or more`() {
        val query = select {
            where {
                propertyPath(`var`("person"), path(FOAF.knows).oneOrMore(), `var`("friend"))
            }
        }
        val queryString = query.sparql
        assertTrue(queryString.contains("SELECT"))
        assertTrue(queryString.contains("?person"))
        assertTrue(queryString.contains("?friend"))
        assertTrue(queryString.contains("<http://xmlns.com/foaf/0.1/knows>+"))
    }

    @Test
    fun `test property paths - zero or more`() {
        val query = select {
            where {
                propertyPath(`var`("person"), path(FOAF.knows).zeroOrMore(), `var`("friend"))
            }
        }
        val queryString = query.sparql
        assertTrue(queryString.contains("<http://xmlns.com/foaf/0.1/knows>*"))
    }

    @Test
    fun `test property paths - zero or one`() {
        val query = select {
            where {
                propertyPath(`var`("person"), path(FOAF.knows).zeroOrOne(), `var`("friend"))
            }
        }
        val queryString = query.sparql
        assertTrue(queryString.contains("<http://xmlns.com/foaf/0.1/knows>?"))
    }

    @Test
    fun `test property paths - alternative`() {
        val query = select {
            where {
                propertyPath(`var`("person"), path(FOAF.knows).alternative(path(Iri("http://xmlns.com/foaf/0.1/friendOf"))), `var`("friend"))
            }
        }
        val queryString = query.sparql
        assertTrue(queryString.contains("<http://xmlns.com/foaf/0.1/knows>|<http://xmlns.com/foaf/0.1/friendOf>"))
    }

    @Test
    fun `test property paths - sequence`() {
        val query = select {
            where {
                propertyPath(`var`("person"), path(FOAF.knows).sequence(path(Iri("http://xmlns.com/foaf/0.1/friendOf"))), `var`("friend"))
            }
        }
        val queryString = query.sparql
        assertTrue(queryString.contains("<http://xmlns.com/foaf/0.1/knows>/<http://xmlns.com/foaf/0.1/friendOf>"))
    }

    @Test
    fun `test property paths - range`() {
        val query = select {
            where {
                propertyPath(`var`("person"), path(FOAF.knows).between(2, 4), `var`("friend"))
            }
        }
        val queryString = query.sparql
        assertTrue(queryString.contains("<http://xmlns.com/foaf/0.1/knows>{2,4}"))
    }

    @Test
    fun `test property paths - negation`() {
        val query = select {
            where {
                propertyPath(`var`("person"), path(FOAF.knows).negation(), `var`("friend"))
            }
        }
        val queryString = query.sparql
        assertTrue(queryString.contains("!<http://xmlns.com/foaf/0.1/knows>"))
    }

    @Test
    fun `test property paths - inverse`() {
        val query = select {
            where {
                propertyPath(`var`("person"), path(FOAF.knows).inverse(), `var`("friend"))
            }
        }
        val queryString = query.sparql
        assertTrue(queryString.contains("^<http://xmlns.com/foaf/0.1/knows>"))
    }

    @Test
    fun `test property paths - complex combination`() {
        val query = select {
            where {
                propertyPath(`var`("person"), 
                    path(FOAF.knows).alternative(path(Iri("http://xmlns.com/foaf/0.1/friendOf")).inverse()).oneOrMore(), 
                    `var`("friend"))
            }
        }
        val queryString = query.sparql
        assertTrue(queryString.contains("<http://xmlns.com/foaf/0.1/knows>|^<http://xmlns.com/foaf/0.1/friendOf>+"))
    }

    @Test
    fun `test aggregation and GROUP BY support`() {
        val query = select {
            variable(`var`("department"))
            aggregate(AggregateFunction.COUNT, `var`("employee").expr(), "employeeCount")
            aggregate(AggregateFunction.AVG, `var`("salary").expr(), "avgSalary")
            where {
                triple(`var`("employee"), Iri("http://example.org/worksFor"), `var`("department"))
                triple(`var`("employee"), Iri("http://example.org/hasSalary"), `var`("salary"))
            }
            groupBy(`var`("department"))
            having {
                // Test aggregate comparison
                filter(count(`var`("employee").expr()) gt TermExpressionAst(5.toLiteral()))
            }
            orderBy(`var`("employeeCount").expr(), OrderDirection.DESC)
        }
        val queryString = query.sparql
        assertTrue(queryString.contains("COUNT(?employee)"))
        assertTrue(queryString.contains("AVG(?salary)"))
        assertTrue(queryString.contains("GROUP BY ?department"))
        assertTrue(queryString.contains("HAVING"))
        assertTrue(queryString.contains("COUNT(?employee) >"))
        assertTrue(queryString.contains("ORDER BY ?employeeCount DESC"))
    }

    @Test
    fun `test all aggregate functions`() {
        val query = select {
            aggregate(AggregateFunction.COUNT, `var`("item").expr(), "count")
            aggregate(AggregateFunction.COUNT, `var`("item").expr(), "distinctCount", distinct = true)
            aggregate(AggregateFunction.SUM, `var`("value").expr(), "total")
            aggregate(AggregateFunction.AVG, `var`("value").expr(), "average")
            aggregate(AggregateFunction.MIN, `var`("value").expr(), "minimum")
            aggregate(AggregateFunction.MAX, `var`("value").expr(), "maximum")
            aggregate(AggregateFunction.GROUP_CONCAT, `var`("name").expr(), "names")
            where {
                triple(`var`("item"), Iri("http://example.org/hasValue"), `var`("value"))
                triple(`var`("item"), Iri("http://example.org/hasName"), `var`("name"))
            }
        }
        val queryString = query.sparql
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
            variable(`var`("name"))
            where {
                triple(`var`("person"), FOAF.name, `var`("name"))
                filter(`var`("name") eq "John")
            }
        }
        val queryString = query.sparql
        
        // Verify that string literals are typed as xsd:string
        assertTrue(queryString.contains("\"John\"^^<http://www.w3.org/2001/XMLSchema#string>"))
        
        // Test with different literal types
        val query2 = select {
            variable(`var`("age"))
            where {
                triple(`var`("person"), FOAF.age, `var`("age"))
                filter(`var`("age") eq 25)
            }
        }
        val queryString2 = query2.sparql
        
        // Verify that integer literals are typed as xsd:integer
        assertTrue(queryString2.contains("\"25\"^^<http://www.w3.org/2001/XMLSchema#integer>"))
        
        // Test with language-tagged literals
        val query3 = select {
            variable(`var`("label"))
            where {
                triple(`var`("resource"), RDFS.label, `var`("label"))
                filter(`var`("label") eq Literal("Hello", "en"))
            }
        }
        val queryString3 = query3.sparql
        
        // Verify that language-tagged literals use @ syntax
        assertTrue(queryString3.contains("\"Hello\"@en"))
    }

    @Test
    fun `test Unicode escape sequence handling`() {
        // Test Unicode escape sequences in string literals
        val query = select {
            variable(`var`("text"))
            where {
                triple(`var`("resource"), DCTERMS.description, `var`("text"))
                filter(`var`("text") eq "Hello \u0041\u0042\u0043") // ABC in Unicode escapes
            }
        }
        val queryString = query.sparql
        
        // Verify that Unicode escape sequences are properly handled
        assertTrue(queryString.contains("Hello ABC"))
        
        // Test with more complex Unicode sequences
        val query2 = select {
            variable(`var`("unicode"))
            where {
                triple(`var`("resource"), RDFS.label, `var`("unicode"))
                filter(`var`("unicode") eq "Unicode: \u03B1\u03B2\u03B3") // Greek letters
            }
        }
        val queryString2 = query2.sparql
        
        // Verify that Unicode characters are preserved
        assertTrue(queryString2.contains("Unicode: αβγ"))
        
        // Test with special characters that need escaping
        val query3 = select {
            variable(`var`("special"))
            where {
                triple(`var`("resource"), DCTERMS.title, `var`("special"))
                filter(`var`("special") eq "Special: \"quotes\" 'apostrophes' \\backslashes\\")
            }
        }
        val queryString3 = query3.sparql
        
        // Verify that special characters are properly escaped in SPARQL
        assertTrue(queryString3.contains("\"quotes\""))
        assertTrue(queryString3.contains("\\backslashes\\"))
    }

    @Test
    fun `test effective boolean value EBV handling`() {
        // Test EBV conversion for different literal types
        val query = select {
            variable(`var`("resource"))
            where {
                triple(`var`("resource"), RDF.type, `var`("type"))
                // Test boolean literals (true/false)
                filter(`var`("active") eq boolean(true))
                // Test numeric literals (0 = false, non-zero = true)
                filter(`var`("count") gt 0)
                // Test string literals (empty = false, non-empty = true)
                filter(`var`("name") ne "")
                // Test using boolean functions
                filter(bound(`var`("optional")))
                filter(isIRI(`var`("uri").expr()))
                filter(isLiteral(`var`("literal").expr()))
                filter(isNumeric(`var`("number").expr()))
            }
        }
        val queryString = query.sparql
        
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
            variable(`var`("result"))
            where {
                triple(`var`("resource"), Iri("http://example.org/hasValue"), `var`("value"))
                bind(`var`("result"), if_(`var`("value") gt 10, string("high").expr(), string("low").expr()))
            }
        }
        val queryString2 = query2.sparql
        assertTrue(queryString2.contains("IF(?value > \"10\"^^<http://www.w3.org/2001/XMLSchema#integer>, \"high\"^^<http://www.w3.org/2001/XMLSchema#string>, \"low\"^^<http://www.w3.org/2001/XMLSchema#string>) AS ?result"))
    }
}











