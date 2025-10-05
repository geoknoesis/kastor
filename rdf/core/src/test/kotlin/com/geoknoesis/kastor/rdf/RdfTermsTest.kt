package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

class RdfTermsTest {
    
    @Test
    fun `IRI creation and validation works`() {
        // Test valid IRIs
        val httpIri = iri("http://example.org/resource")
        assertEquals("http://example.org/resource", httpIri.value, "HTTP IRI should preserve value")
        assertTrue(httpIri is Iri, "Should be instance of Iri")
        
        val httpsIri = iri("https://example.org/secure")
        assertEquals("https://example.org/secure", httpsIri.value, "HTTPS IRI should preserve value")
        
        val urnIri = iri("urn:example:resource")
        assertEquals("urn:example:resource", urnIri.value, "URN IRI should preserve value")
        
        val dataIri = iri("data:text/plain;base64,SGVsbG8gV29ybGQ=")
        assertEquals("data:text/plain;base64,SGVsbG8gV29ybGQ=", dataIri.value, "Data IRI should preserve value")
    }
    
    @Test
    fun `blank node creation works`() {
        // Test blank node with explicit ID
        val bnode1 = bnode("b1")
        assertEquals("b1", bnode1.id, "Blank node should preserve ID")
        assertTrue(bnode1 is BlankNode, "Should be instance of BlankNode")
    }
    
    @Test
    fun `literal creation with different types works`() {
        // Test string literal
        val stringLit = string("Hello, World!")
        assertEquals("Hello, World!", stringLit.lexical, "String literal should preserve value")
        assertEquals(XSD.string, stringLit.datatype, "String literal should have xsd:string datatype")
        
        // Test string literal with language tag
        val langLit = LangString("Hello", "en")
        assertEquals("Hello", langLit.lexical, "Language literal should preserve value")
        assertEquals("en", langLit.lang, "Language literal should have language tag")
        assertEquals(RDF.langString, langLit.datatype, "Language literal should have rdf:langString datatype")
        
        // Test typed literal
        val typedLit = TypedLiteral("42", XSD.integer)
        assertEquals("42", typedLit.lexical, "Typed literal should preserve value")
        assertEquals(XSD.integer, typedLit.datatype, "Typed literal should have specified datatype")
    }
    
    @Test
    fun `literal creation with Kotlin types works`() {
        // Test integer literal
        val intLit = literal(42)
        assertEquals("42", intLit.lexical, "Integer literal should convert to string")
        assertEquals(XSD.integer, intLit.datatype, "Integer literal should have xsd:integer datatype")
        
            // Test long literal
            val longLit = Literal(123456789L)
            assertEquals("123456789", longLit.lexical, "Long literal should convert to string")
            assertEquals(XSD.integer, longLit.datatype, "Long literal should have xsd:integer datatype")
        
        // Test double literal
        val doubleLit = literal(3.14159)
        assertEquals("3.14159", doubleLit.lexical, "Double literal should convert to string")
        assertEquals(XSD.double, doubleLit.datatype, "Double literal should have xsd:double datatype")
        
        // Test float literal
        val floatLit = Literal(2.5f)
        assertEquals("2.5", floatLit.lexical, "Float literal should convert to string")
        assertEquals(XSD.float, floatLit.datatype, "Float literal should have xsd:float datatype")
        
        // Test boolean literal
        val boolLit = Literal(true)
        assertEquals("true", boolLit.lexical, "Boolean literal should convert to string")
        assertEquals(XSD.boolean, boolLit.datatype, "Boolean literal should have xsd:boolean datatype")
        
        // Test string literal
        val stringLit = Literal("test")
        assertEquals("test", stringLit.lexical, "String literal should preserve value")
        assertEquals(XSD.string, stringLit.datatype, "String literal should have xsd:string datatype")
    }
    
    @Test
    fun `literal creation with BigDecimal and BigInteger works`() {
        // Test BigDecimal literal
        val decimalLit = Literal(BigDecimal("123.456789"))
        assertEquals("123.456789", decimalLit.lexical, "BigDecimal literal should preserve value")
        assertEquals(XSD.decimal, decimalLit.datatype, "BigDecimal literal should have xsd:decimal datatype")
        
        // Test BigInteger literal
        val integerLit = Literal(BigInteger("1234567890123456789"))
        assertEquals("1234567890123456789", integerLit.lexical, "BigInteger literal should preserve value")
        assertEquals(XSD.integer, integerLit.datatype, "BigInteger literal should have xsd:integer datatype")
    }
    
    @Test
    fun `literal creation with date and time types works`() {
        // Test LocalDate literal
        val dateLit = Literal(LocalDate.of(2023, 12, 25))
        assertEquals("2023-12-25", dateLit.lexical, "LocalDate literal should format correctly")
        assertEquals(XSD.date, dateLit.datatype, "LocalDate literal should have xsd:date datatype")
        
        // Test LocalTime literal
        val timeLit = Literal(LocalTime.of(14, 30, 45))
        assertEquals("14:30:45", timeLit.lexical, "LocalTime literal should format correctly")
        assertEquals(XSD.time, timeLit.datatype, "LocalTime literal should have xsd:time datatype")
        
        // Test LocalDateTime literal
        val dateTimeLit = Literal(LocalDateTime.of(2023, 12, 25, 14, 30, 45))
        assertEquals("2023-12-25T14:30:45", dateTimeLit.lexical, "LocalDateTime literal should format correctly")
        assertEquals(XSD.dateTime, dateTimeLit.datatype, "LocalDateTime literal should have xsd:dateTime datatype")
        
            // Test ZonedDateTime literal
            val zonedDateTimeLit = Literal(ZonedDateTime.parse("2023-12-25T14:30:45Z"))
            assertTrue(zonedDateTimeLit.lexical.startsWith("2023-12-25T14:30:45"), "ZonedDateTime literal should format correctly")
            assertEquals(XSD.string, zonedDateTimeLit.datatype, "ZonedDateTime literal should have xsd:string datatype")
    }
    
    @Test
    fun `triple creation and access works`() {
        val subject = iri("http://example.org/subject")
        val predicate = iri("http://example.org/predicate")
        val obj = literal("object value")
        
        val triple = RdfTriple(subject, predicate, obj)
        
        assertEquals(subject, triple.subject, "Triple should preserve subject")
        assertEquals(predicate, triple.predicate, "Triple should preserve predicate")
        assertEquals(obj, triple.obj, "Triple should preserve object")
    }
    
    @Test
    fun `triple creation with different term types works`() {
        // Test with IRI subject and object
        val iriSubj = iri("http://example.org/person")
        val iriPred = iri("http://example.org/name")
        val iriObj = iri("http://example.org/John")
        
        val triple1 = RdfTriple(iriSubj, iriPred, iriObj)
        assertEquals(iriSubj, triple1.subject, "Triple with IRI object should work")
        
        // Test with blank node subject
        val bnodeSubj = bnode("b1")
        val literalObj = literal("John Doe")
        
        val triple2 = RdfTriple(bnodeSubj, iriPred, literalObj)
        assertEquals(bnodeSubj, triple2.subject, "Triple with blank node subject should work")
        assertEquals(literalObj, triple2.obj, "Triple with literal object should work")
        
        // Test with blank node object
        val bnodeObj = bnode("b2")
        val triple3 = RdfTriple(iriSubj, iriPred, bnodeObj)
        assertEquals(bnodeObj, triple3.obj, "Triple with blank node object should work")
    }
    
    @Test
    fun `graph operations work`() {
        val repo = Rdf.memory()
        val graph = repo.defaultGraph
        
        // Test initial state
        assertEquals(0, graph.size(), "New graph should be empty")
        
        // Test adding triples
        val subject = iri("http://example.org/subject")
        val predicate = iri("http://example.org/predicate")
        val obj = literal("object value")
        val triple = RdfTriple(subject, predicate, obj)
        
        graph.addTriple(triple)
        
        assertEquals(1, graph.size(), "Graph should have one triple after adding")
        
        // Test finding all triples
        val allTriples = graph.getTriples()
        assertEquals(1, allTriples.size, "Should find one triple")
        assertEquals(triple, allTriples.first(), "Found triple should match added triple")
        
        // Test checking if triple exists
        assertTrue(graph.hasTriple(triple), "Graph should contain the triple")
        
        // Test removing triple
        val removed = graph.removeTriple(triple)
        assertTrue(removed, "Should successfully remove triple")
        assertEquals(0, graph.size(), "Graph should be empty after removing triple")
        
        repo.close()
    }
    
    @Test
    fun `graph with multiple triples works`() {
        val repo = Rdf.memory()
        val graph = repo.defaultGraph
        
        // Add multiple triples
        val s1 = iri("http://example.org/person1")
        val s2 = iri("http://example.org/person2")
        val p1 = iri("http://example.org/name")
        val p2 = iri("http://example.org/age")
        val o1 = literal("Alice")
        val o2 = literal("Bob")
        val o3 = literal("30")
        
        graph.addTriple(triple(s1, p1, o1))
        graph.addTriple(triple(s2, p1, o2))
        graph.addTriple(triple(s1, p2, o3))
        
        // Test total size
        assertEquals(3, graph.size(), "Should have 3 triples total")
        
        // Test finding all triples
        val allTriples = graph.getTriples()
        assertEquals(3, allTriples.size, "Should find all 3 triples")
        
        // Test finding triples by subject
        val subjectMatches = allTriples.filter { it.subject == s1 }
        assertEquals(2, subjectMatches.size, "Should find 2 triples for subject1")
        
        // Test finding triples by predicate
        val predicateMatches = allTriples.filter { it.predicate == p1 }
        assertEquals(2, predicateMatches.size, "Should find 2 triples for name predicate")
        
        // Test finding triples by object
        val objectMatches = allTriples.filter { it.obj == o1 }
        assertEquals(1, objectMatches.size, "Should find 1 triple for Alice object")
        
        repo.close()
    }
    
    @Test
    fun `graph contains operations work`() {
        val repo = Rdf.memory()
        val graph = repo.defaultGraph
        
        val subject = iri("http://example.org/subject")
        val predicate = iri("http://example.org/predicate")
        val obj = literal("object value")
        val triple = RdfTriple(subject, predicate, obj)
        
        // Test contains before adding
        assertFalse(graph.hasTriple(triple), "Graph should not contain triple before adding")
        
        // Add triple
        graph.addTriple(triple)
        
        // Test contains after adding
        assertTrue(graph.hasTriple(triple), "Graph should contain triple after adding")
        
        // Test contains with different triple
        val differentTriple = triple(subject, iri("http://example.org/different"), obj)
        assertFalse(graph.hasTriple(differentTriple), "Graph should not contain different triple")
        
        repo.close()
    }
    
    @Test
    fun `RDF term equality and hashCode work`() {
        // Test IRI equality
        val iri1 = iri("http://example.org/resource")
        val iri2 = iri("http://example.org/resource")
        val iri3 = iri("http://example.org/different")
        
        assertEquals(iri1, iri2, "Same IRIs should be equal")
        assertNotEquals(iri1, iri3, "Different IRIs should not be equal")
        assertEquals(iri1.hashCode(), iri2.hashCode(), "Same IRIs should have same hashCode")
        
        // Test blank node equality
        val bnode1 = bnode("b1")
        val bnode2 = bnode("b1")
        val bnode3 = bnode("b2")
        
        assertEquals(bnode1, bnode2, "Same blank nodes should be equal")
        assertNotEquals(bnode1, bnode3, "Different blank nodes should not be equal")
        assertEquals(bnode1.hashCode(), bnode2.hashCode(), "Same blank nodes should have same hashCode")
        
        // Test literal equality
        val literal1 = Literal("test", XSD.string)
        val literal2 = Literal("test", XSD.string)
        val literal3 = Literal("different", XSD.string)
        val literal4 = Literal("test", XSD.integer)
        
        assertEquals(literal1, literal2, "Same literals should be equal")
        assertNotEquals(literal1, literal3, "Different literal values should not be equal")
        assertNotEquals(literal1, literal4, "Different literal datatypes should not be equal")
        assertEquals(literal1.hashCode(), literal2.hashCode(), "Same literals should have same hashCode")
        
        // Test triple equality
        val triple1 = triple(iri1, iri("http://example.org/pred"), literal1)
        val triple2 = triple(iri1, iri("http://example.org/pred"), literal1)
        val triple3 = triple(iri3, iri("http://example.org/pred"), literal1)
        
        assertEquals(triple1, triple2, "Same triples should be equal")
        assertNotEquals(triple1, triple3, "Different triples should not be equal")
        assertEquals(triple1.hashCode(), triple2.hashCode(), "Same triples should have same hashCode")
    }
    
    @Test
    fun `string conversion works for all term types`() {
        // Test IRI string conversion
        val iri = iri("http://example.org/resource")
        assertTrue(iri.toString().contains("http://example.org/resource"), "IRI toString should contain value")
        
        // Test blank node string conversion
        val bnode = bnode("b1")
        assertTrue(bnode.toString().contains("b1"), "Blank node toString should contain ID")
        
        // Test literal string conversion
        val literal = Literal("test value", XSD.string)
        assertTrue(literal.toString().contains("test value"), "Literal toString should contain value")
        assertTrue(literal.toString().contains("string"), "Literal toString should contain datatype info")
        
        // Test triple string conversion
        val triple = RdfTriple(iri, iri("http://example.org/pred"), literal)
        assertTrue(triple.toString().contains("http://example.org/resource"), "Triple toString should contain subject")
        assertTrue(triple.toString().contains("test value"), "Triple toString should contain object")
    }
}
