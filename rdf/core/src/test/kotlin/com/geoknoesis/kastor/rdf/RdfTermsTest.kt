package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.*
import java.util.Base64

class RdfTermsTest {
    
    @Test
    fun `IRI creation and validation works`() {
        // Test valid IRIs
        val httpIri = Iri("http://example.org/resource")
        assertEquals("http://example.org/resource", httpIri.value, "HTTP IRI should preserve value")
        
        val httpsIri = Iri("https://example.org/secure")
        assertEquals("https://example.org/secure", httpsIri.value, "HTTPS IRI should preserve value")
        
        val urnIri = Iri("urn:example:resource")
        assertEquals("urn:example:resource", urnIri.value, "URN IRI should preserve value")
        
        val dataIri = Iri("data:text/plain;base64,SGVsbG8gV29ybGQ=")
        assertEquals("data:text/plain;base64,SGVsbG8gV29ybGQ=", dataIri.value, "Data IRI should preserve value")
    }
    
    @Test
    fun `blank node creation works`() {
        // Test blank node with explicit ID
        val bnode1 = bnode("b1")
        assertEquals("b1", bnode1.id, "Blank node should preserve ID")
        
        // Test type alias
        val bnode2: BNode = bnode("b2")
        assertEquals("b2", bnode2.id, "BNode alias should work")
    }
    
    @Test
    fun `blank node validation rejects blank id`() {
        // Test that blank node rejects empty string
        assertThrows(IllegalArgumentException::class.java) {
            BlankNode("")
        }
        
        // Test that blank node rejects whitespace-only string
        assertThrows(IllegalArgumentException::class.java) {
            BlankNode("   ")
        }
        
        // Test that blank node rejects newline-only string
        assertThrows(IllegalArgumentException::class.java) {
            BlankNode("\n")
        }
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
        val intLit = 42.toLiteral()
        assertEquals("42", intLit.lexical, "Integer literal should convert to string")
        assertEquals(XSD.integer, intLit.datatype, "Integer literal should have xsd:integer datatype")
        
            // Test long literal
            val longLit = Literal(123456789L)
            assertEquals("123456789", longLit.lexical, "Long literal should convert to string")
            assertEquals(XSD.integer, longLit.datatype, "Long literal should have xsd:integer datatype")
        
        // Test double literal
        val doubleLit = 3.14159.toLiteral()
        assertEquals("3.14159", doubleLit.lexical, "Double literal should convert to string")
        assertEquals(XSD.double, doubleLit.datatype, "Double literal should have xsd:double datatype")
        
        // Test float literal
        val floatLit = Literal(2.5f)
        assertEquals("2.5", floatLit.lexical, "Float literal should convert to string")
        assertEquals(XSD.float, floatLit.datatype, "Float literal should have xsd:float datatype")
        
        // Test boolean literal
        val boolLit = true.toLiteral()
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
        
        // Test ZonedDateTime literal (explicit string)
        val zonedDateTime = ZonedDateTime.parse("2023-12-25T14:30:45Z")
        val zonedDateTimeLit = Literal(zonedDateTime.toString(), XSD.string)
        assertTrue(zonedDateTimeLit.lexical.startsWith("2023-12-25T14:30:45"), "ZonedDateTime literal should format correctly")
        assertEquals(XSD.string, zonedDateTimeLit.datatype, "ZonedDateTime literal should have xsd:string datatype")
        
        // Test OffsetDateTime literal
        val offsetDateTimeLit = Literal(OffsetDateTime.parse("2023-12-25T14:30:45+01:00"))
        assertTrue(offsetDateTimeLit.lexical.contains("2023-12-25T14:30:45"), "OffsetDateTime literal should format correctly")
        assertEquals(XSD.dateTime, offsetDateTimeLit.datatype, "OffsetDateTime literal should have xsd:dateTime datatype")
        
        // Test Instant literal
        val instantLit = Literal(Instant.parse("2023-12-25T14:30:45Z"))
        assertTrue(instantLit.lexical.contains("2023-12-25T14:30:45"), "Instant literal should format correctly")
        assertEquals(XSD.dateTimeStamp, instantLit.datatype, "Instant literal should have xsd:dateTimeStamp datatype")
        
        // Test Year literal
        val yearLit = Literal(Year.of(2023))
        assertEquals("2023", yearLit.lexical, "Year literal should format correctly")
        assertEquals(XSD.gYear, yearLit.datatype, "Year literal should have xsd:gYear datatype")
        
        // Test YearMonth literal
        val yearMonthLit = Literal(YearMonth.of(2023, 12))
        assertEquals("2023-12", yearMonthLit.lexical, "YearMonth literal should format correctly")
        assertEquals(XSD.gYearMonth, yearMonthLit.datatype, "YearMonth literal should have xsd:gYearMonth datatype")
    }
    
    @Test
    fun `literal creation with ByteArray works`() {
        val bytes = "Hello, World!".toByteArray()
        val byteArrayLit = Literal(bytes)
        
        val expectedBase64 = Base64.getEncoder().encodeToString(bytes)
        assertEquals(expectedBase64, byteArrayLit.lexical, "ByteArray literal should be Base64 encoded")
        assertEquals(XSD.base64Binary, byteArrayLit.datatype, "ByteArray literal should have xsd:base64Binary datatype")
        
        // Test extension function
        val byteArrayLit2 = bytes.toLiteral()
        assertEquals(expectedBase64, byteArrayLit2.lexical, "Extension function should work")
        assertEquals(XSD.base64Binary, byteArrayLit2.datatype, "Extension function should have correct datatype")
    }
    
    @Test
    fun `boolean literal creation with different lexical forms works`() {
        // Test "true" lexical form
        val trueLit1 = Literal("true", XSD.boolean)
        assertSame(TrueLiteral, trueLit1, "Literal('true', XSD.boolean) should return TrueLiteral")
        
        // Test "1" lexical form
        val trueLit2 = Literal("1", XSD.boolean)
        assertSame(TrueLiteral, trueLit2, "Literal('1', XSD.boolean) should return TrueLiteral")
        
        // Test "false" lexical form
        val falseLit1 = Literal("false", XSD.boolean)
        assertSame(FalseLiteral, falseLit1, "Literal('false', XSD.boolean) should return FalseLiteral")
        
        // Test "0" lexical form
        val falseLit2 = Literal("0", XSD.boolean)
        assertSame(FalseLiteral, falseLit2, "Literal('0', XSD.boolean) should return FalseLiteral")
        
        // Test boolean extension function
        val trueLit3 = true.toLiteral()
        assertSame(TrueLiteral, trueLit3, "true.toLiteral() should return TrueLiteral")
        
        val falseLit3 = false.toLiteral()
        assertSame(FalseLiteral, falseLit3, "false.toLiteral() should return FalseLiteral")
        
        // Test boolean factory function
        val trueLit4 = boolean(true)
        assertSame(TrueLiteral, trueLit4, "boolean(true) should return TrueLiteral")
        
        val falseLit4 = boolean(false)
        assertSame(FalseLiteral, falseLit4, "boolean(false) should return FalseLiteral")
    }
    
    @Test
    fun `boolean literal creation with invalid lexical forms throws exception`() {
        // Test invalid boolean lexical forms
        assertThrows(IllegalArgumentException::class.java) {
            Literal("maybe", XSD.boolean)
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            Literal("yes", XSD.boolean)
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            Literal("no", XSD.boolean)
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            Literal("2", XSD.boolean)
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            Literal("True", XSD.boolean)  // Case sensitive
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            Literal("FALSE", XSD.boolean)  // Case sensitive
        }
    }
    
    @Test
    fun `BigDecimal literal strips trailing zeros`() {
        val decimalWithZeros = BigDecimal("123.4500")
        val decimalLit = Literal(decimalWithZeros)
        
        assertEquals("123.45", decimalLit.lexical, "BigDecimal literal should strip trailing zeros")
        assertEquals(XSD.decimal, decimalLit.datatype, "BigDecimal literal should have xsd:decimal datatype")
        
        // Test extension function
        val decimalLit2 = decimalWithZeros.toLiteral()
        assertEquals("123.45", decimalLit2.lexical, "Extension function should strip trailing zeros")
    }
    
    @Test
    fun `int factory function works`() {
        val intLit = int(42)
        assertEquals("42", intLit.lexical, "int() should create integer literal")
        assertEquals(XSD.integer, intLit.datatype, "int() should have xsd:integer datatype")
    }
    
    @Test
    fun `lang factory function works`() {
        val langLit = lang("Hello", "en")
        assertEquals("Hello", langLit.lexical, "lang() should preserve value")
        assertTrue(langLit is LangString, "lang() should create LangString")
        assertEquals("en", (langLit as LangString).lang, "lang() should set language tag")
        assertEquals(RDF.langString, langLit.datatype, "lang() should have rdf:langString datatype")
        
        // Test Literal factory with language tag
        val langLit2 = Literal("Bonjour", "fr")
        assertTrue(langLit2 is LangString, "Literal(value, lang) should create LangString")
        assertEquals("fr", (langLit2 as LangString).lang, "Literal(value, lang) should set language tag")
    }
    
    // Literal(Any) overload removed to enforce explicit conversions.
    
    @Test
    fun `triple term and quoted triple works`() {
        val baseTriple = RdfTriple(
            Iri("http://example.org/person"),
            Iri("http://example.org/name"),
            string("John Doe")
        )
        
        // Test TripleTerm creation
        val tripleTerm = TripleTerm(baseTriple)
        assertEquals(baseTriple, tripleTerm.triple, "TripleTerm should preserve triple")
        
        // Test quoted function
        val quoted = quoted(baseTriple)
        assertEquals(baseTriple, quoted.triple, "quoted() should create TripleTerm")
        
        // Test using quoted triple as subject
        val metadataTriple = RdfTriple(
            quoted,
            Iri("http://example.org/source"),
            string("Wikipedia")
        )
        assertEquals(quoted, metadataTriple.subject, "Quoted triple should be usable as subject")
    }
    
    @Test
    fun `type aliases work correctly`() {
        // Test IRI alias
        val iri1: IRI = Iri("http://example.org/resource")
        val iri2: Iri = IRI("http://example.org/resource")
        assertEquals(iri1, iri2, "IRI and Iri should be equivalent")
        
        // Test BNode alias
        val bnode1: BNode = BlankNode("b1")
        val bnode2: BlankNode = BNode("b1")
        assertEquals(bnode1, bnode2, "BNode and BlankNode should be equivalent")
    }
    
    @Test
    fun `extension functions for toLiteral work`() {
        // Test all extension functions
        assertEquals(XSD.integer, 42.toLiteral().datatype)
        assertEquals(XSD.integer, 123456789L.toLiteral().datatype)
        assertEquals(XSD.double, 3.14.toLiteral().datatype)
        assertEquals(XSD.float, 2.5f.toLiteral().datatype)
        assertEquals(XSD.decimal, BigDecimal("123.45").toLiteral().datatype)
        assertEquals(XSD.integer, BigInteger("123456789").toLiteral().datatype)
        assertEquals(XSD.date, LocalDate.of(2023, 12, 25).toLiteral().datatype)
        assertEquals(XSD.time, LocalTime.of(14, 30, 45).toLiteral().datatype)
        assertEquals(XSD.dateTime, LocalDateTime.of(2023, 12, 25, 14, 30, 45).toLiteral().datatype)
        assertEquals(XSD.dateTimeStamp, Instant.parse("2023-12-25T14:30:45Z").toLiteral().datatype)
        assertEquals(XSD.gYear, Year.of(2023).toLiteral().datatype)
        assertEquals(XSD.gYearMonth, YearMonth.of(2023, 12).toLiteral().datatype)
        assertEquals(XSD.base64Binary, "Hello".toByteArray().toLiteral().datatype)
    }
    
    @Test
    fun `graph editor operations work`() {
        val repo = Rdf.memory()
        val graph = repo.defaultGraph
        val editor = repo.editDefaultGraph()
        
        val triple1 = RdfTriple(Iri("http://example.org/s1"), Iri("http://example.org/p1"), string("o1"))
        val triple2 = RdfTriple(Iri("http://example.org/s2"), Iri("http://example.org/p2"), string("o2"))
        val triple3 = RdfTriple(Iri("http://example.org/s3"), Iri("http://example.org/p3"), string("o3"))
        
        // Test addTriples
        editor.addTriples(listOf(triple1, triple2, triple3))
        assertEquals(3, graph.size(), "Graph should have 3 triples after addTriples")
        
        // Test removeTriples
        val removed = editor.removeTriples(listOf(triple1, triple2))
        assertTrue(removed, "removeTriples should return true when triples are removed")
        assertEquals(1, graph.size(), "Graph should have 1 triple after removeTriples")
        assertTrue(graph.hasTriple(triple3), "Graph should still contain triple3")
        
        // Test clear
        editor.addTriple(triple1)
        editor.addTriple(triple2)
        assertEquals(3, graph.size(), "Graph should have 3 triples before clear")
        
        val cleared = editor.clear()
        assertTrue(cleared, "clear() should return true when triples are removed")
        assertEquals(0, graph.size(), "Graph should be empty after clear")
        
        // Test removeTriples with non-existent triples
        val removedNone = editor.removeTriples(listOf(triple1, triple2))
        assertFalse(removedNone, "removeTriples should return false when no triples are removed")
        
        // Test clear on empty graph
        val clearedEmpty = editor.clear()
        assertFalse(clearedEmpty, "clear() should return false when graph is already empty")
        
        repo.close()
    }
    
    @Test
    fun `triple creation and access works`() {
        val subject = Iri("http://example.org/subject")
        val predicate = Iri("http://example.org/predicate")
        val obj = Literal("object value")
        
        val triple = RdfTriple(subject, predicate, obj)
        
        assertEquals(subject, triple.subject, "Triple should preserve subject")
        assertEquals(predicate, triple.predicate, "Triple should preserve predicate")
        assertEquals(obj, triple.obj, "Triple should preserve object")
    }
    
    @Test
    fun `triple creation with different term types works`() {
        // Test with IRI subject and object
        val iriSubj = Iri("http://example.org/person")
        val iriPred = Iri("http://example.org/name")
        val iriObj = Iri("http://example.org/John")
        
        val triple1 = RdfTriple(iriSubj, iriPred, iriObj)
        assertEquals(iriSubj, triple1.subject, "Triple with IRI object should work")
        
        // Test with blank node subject
        val bnodeSubj = bnode("b1")
        val literalObj = Literal("John Doe")
        
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
        val editor = repo.editDefaultGraph()
        
        // Test initial state
        assertEquals(0, graph.size(), "New graph should be empty")
        
        // Test adding triples
        val subject = Iri("http://example.org/subject")
        val predicate = Iri("http://example.org/predicate")
        val obj = Literal("object value")
        val triple = RdfTriple(subject, predicate, obj)
        
        editor.addTriple(triple)
        
        assertEquals(1, graph.size(), "Graph should have one triple after adding")
        
        // Test finding all triples
        val allTriples = graph.getTriples()
        assertEquals(1, allTriples.size, "Should find one triple")
        assertEquals(triple, allTriples.first(), "Found triple should match added triple")
        
        // Test checking if triple exists
        assertTrue(graph.hasTriple(triple), "Graph should contain the triple")
        
        // Test removing triple
        val removed = editor.removeTriple(triple)
        assertTrue(removed, "Should successfully remove triple")
        assertEquals(0, graph.size(), "Graph should be empty after removing triple")
        
        repo.close()
    }
    
    @Test
    fun `graph with multiple triples works`() {
        val repo = Rdf.memory()
        val graph = repo.defaultGraph
        val editor = repo.editDefaultGraph()
        
        // Add multiple triples
        val s1 = Iri("http://example.org/person1")
        val s2 = Iri("http://example.org/person2")
        val p1 = Iri("http://example.org/name")
        val p2 = Iri("http://example.org/age")
        val o1 = Literal("Alice")
        val o2 = Literal("Bob")
        val o3 = Literal("30")
        
        editor.addTriple(RdfTriple(s1, p1, o1))
        editor.addTriple(RdfTriple(s2, p1, o2))
        editor.addTriple(RdfTriple(s1, p2, o3))
        
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
        val editor = repo.editDefaultGraph()
        
        val subject = Iri("http://example.org/subject")
        val predicate = Iri("http://example.org/predicate")
        val obj = Literal("object value")
        val triple = RdfTriple(subject, predicate, obj)
        
        // Test contains before adding
        assertFalse(graph.hasTriple(triple), "Graph should not contain triple before adding")
        
        // Add triple
        editor.addTriple(triple)
        
        // Test contains after adding
        assertTrue(graph.hasTriple(triple), "Graph should contain triple after adding")
        
        // Test contains with different triple
        val differentTriple = RdfTriple(subject, Iri("http://example.org/different"), obj)
        assertFalse(graph.hasTriple(differentTriple), "Graph should not contain different triple")
        
        repo.close()
    }
    
    @Test
    fun `RDF term equality and hashCode work`() {
        // Test IRI equality
        val iri1 = Iri("http://example.org/resource")
        val iri2 = Iri("http://example.org/resource")
        val iri3 = Iri("http://example.org/different")
        
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
        val triple1 = RdfTriple(iri1, Iri("http://example.org/pred"), literal1)
        val triple2 = RdfTriple(iri1, Iri("http://example.org/pred"), literal1)
        val triple3 = RdfTriple(iri3, Iri("http://example.org/pred"), literal1)
        
        assertEquals(triple1, triple2, "Same triples should be equal")
        assertNotEquals(triple1, triple3, "Different triples should not be equal")
        assertEquals(triple1.hashCode(), triple2.hashCode(), "Same triples should have same hashCode")
    }
    
    @Test
    fun `string conversion works for all term types`() {
        // Test IRI string conversion
        val iri = Iri("http://example.org/resource")
        assertTrue(iri.toString().contains("http://example.org/resource"), "IRI toString should contain value")
        
        // Test blank node string conversion
        val bnode = bnode("b1")
        assertTrue(bnode.toString().contains("b1"), "Blank node toString should contain ID")
        
        // Test literal string conversion
        val literal = Literal("test value", XSD.string)
        assertTrue(literal.toString().contains("test value"), "Literal toString should contain value")
        assertTrue(literal.toString().contains("string"), "Literal toString should contain datatype info")
        
        // Test triple string conversion
        val triple = RdfTriple(iri, Iri("http://example.org/pred"), literal)
        assertTrue(triple.toString().contains("http://example.org/resource"), "Triple toString should contain subject")
        assertTrue(triple.toString().contains("test value"), "Triple toString should contain object")
    }
}









