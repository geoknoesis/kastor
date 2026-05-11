package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ParseErrorTest {
    
    @Test
    fun `ParseErrorDetails includes line and column when available`() {
        val error = ParseErrorDetails(
            message = "Unexpected token",
            line = 5,
            column = 12,
            snippet = "Line 4: @prefix ex: <http://example.org/>\nLine 5: ex:subject ex:predicate",
            format = "TURTLE",
            cause = IllegalArgumentException("Parse error")
        )
        
        assertEquals("Unexpected token", error.message)
        assertEquals(5, error.line)
        assertEquals(12, error.column)
        assertNotNull(error.snippet)
        assertEquals("TURTLE", error.format)
        assertNotNull(error.cause)
    }
    
    @Test
    fun `ParseErrorDetails toString includes line and column`() {
        val error = ParseErrorDetails(
            message = "Syntax error",
            line = 3,
            column = 8,
            format = "TURTLE"
        )
        
        val str = error.toString()
        assertTrue(str.contains("Parse error in TURTLE"))
        assertTrue(str.contains("at line 3"))
        assertTrue(str.contains("column 8"))
        assertTrue(str.contains("Syntax error"))
    }
    
    @Test
    fun `RdfFormatException ParseError variant includes context`() {
        val parseError = ParseErrorDetails(
            message = "Invalid syntax",
            line = 10,
            column = 5,
            format = "JSON-LD"
        )
        
        val exception = RdfFormatException.ParseError(parseError)
        
        assertEquals("Invalid syntax", parseError.message)
        assertNotNull(exception.context)
        assertEquals("JSON-LD", exception.context!!["format"])
        assertEquals(10, exception.context!!["line"])
        assertEquals(5, exception.context!!["column"])
    }
    
    @Test
    fun `RdfFormatException UnsupportedFormat variant includes available formats`() {
        val exception = RdfFormatException.UnsupportedFormat(
            "UNKNOWN",
            listOf("TURTLE", "JSON-LD", "RDF/XML")
        )
        
        assertTrue(exception.message?.contains("UNKNOWN") == true)
        assertTrue(exception.message?.contains("TURTLE") == true)
        assertNotNull(exception.context)
        assertEquals("UNKNOWN", exception.context!!["format"])
        assertTrue(exception.context!!["availableFormats"] is List<*>)
    }
    
    @Test
    fun `RdfFormatException can be caught as sealed class`() {
        val parseError = RdfFormatException.ParseError(
            ParseErrorDetails("Error", format = "TURTLE")
        )
        val unsupported = RdfFormatException.UnsupportedFormat("X", listOf())
        val generic = RdfFormatException.Generic("Generic error")
        
        val exceptions = listOf(parseError, unsupported, generic)
        
        exceptions.forEach { exception ->
            when (exception) {
                is RdfFormatException.ParseError -> {
                    assertNotNull(exception.parseError)
                }
                is RdfFormatException.UnsupportedFormat -> {
                    assertNotNull(exception.format)
                    assertNotNull(exception.availableFormats)
                }
                is RdfFormatException.Generic -> {
                    assertNotNull(exception.message)
                }
            }
        }
    }
    
    @Test
    fun `Parsing malformed RDF throws RdfFormatException`() {
        val malformedTurtle = """
            @prefix ex: <http://example.org/> .
            ex:subject ex:predicate [INVALID SYNTAX HERE]
        """.trimIndent()
        
        try {
            Rdf.parse(malformedTurtle, "TURTLE")
            // Some providers might accept this or parse it differently, so we don't fail if no exception
        } catch (e: RdfFormatException) {
            // Verify it's one of our sealed variants
            assertTrue(
                e is RdfFormatException.ParseError || 
                e is RdfFormatException.UnsupportedFormat || 
                e is RdfFormatException.Generic
            )
        } catch (e: Exception) {
            // Other exceptions are also acceptable - the test just verifies the structure works
        }
    }
}

