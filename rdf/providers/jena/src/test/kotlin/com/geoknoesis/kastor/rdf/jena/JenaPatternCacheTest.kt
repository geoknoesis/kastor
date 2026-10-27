package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.TrueLiteral
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A constant `REGEX` pattern is compiled once, not once per execution: a SHACL engine runs a constraint query once per
 * focus node, each a new parse, and compiling a pattern with Unicode classes on RE2/J costs milliseconds.
 */
class JenaPatternCacheTest {

    private val repository: RdfRepository = JenaRepository.MemoryRepository().apply {
        editDefaultGraph().addTriples(Rdf.parse("<urn:a> <urn:p> \"Alpha\", \"Beta\", \"gamma\" .", RdfFormat.TURTLE).getTriples())
    }

    @AfterEach fun close() = repository.close()

    private fun rows(sparql: String) = repository.withSelectRows(SandboxedSelectQuery(sparql)) { it.toList() }

    @Test
    fun `a constant pattern is compiled once across executions and solutions`() {
        // A pattern no other test uses, so the count is this test's own.
        val pattern = "^[\\\\p{Lu}\\\\p{Lo}][\\\\p{L}\\\\p{M}\\\\p{N}]*(cache-test)?$"
        val before = JenaPatternCache.compilations
        repeat(20) { i ->
            val found = rows("SELECT ?o WHERE { <urn:a> <urn:p> ?o FILTER (REGEX(?o, \"$pattern\") && $i >= 0) }")
            assertEquals(2, found.size)
        }
        assertEquals(1, JenaPatternCache.compilations - before, "compilations of one constant pattern in 20 executions of 3 solutions")
    }

    @Test
    fun `patterns are keyed by text and flags`() {
        val a = JenaPatternCache.compile("key-test", null)
        assertSame(a, JenaPatternCache.compile("key-test", null))
        val i = JenaPatternCache.compile("key-test", "i")
        assertTrue(a !== i)
        assertEquals(listOf(TrueLiteral), rows("SELECT ?m { BIND (REGEX(\"KEY-TEST\", \"key-test\", \"i\") AS ?m) }").map { it.get("m") })
    }

    @Test
    fun `the cache is bounded and keeps neither long patterns nor failures`() {
        JenaPatternCache.clear()
        for (k in 0 until JenaPatternCache.MAX_ENTRIES + 50) JenaPatternCache.compile("bound-$k", null)
        assertEquals(JenaPatternCache.MAX_ENTRIES, JenaPatternCache.size)
        // The most recently used are kept.
        val before = JenaPatternCache.compilations
        JenaPatternCache.compile("bound-${JenaPatternCache.MAX_ENTRIES + 49}", null)
        assertEquals(before, JenaPatternCache.compilations)
        JenaPatternCache.compile("bound-0", null)
        assertEquals(before + 1, JenaPatternCache.compilations)

        val long = "a".repeat(JenaPatternCache.MAX_PATTERN_LENGTH + 1)
        val beforeLong = JenaPatternCache.compilations
        repeat(3) { JenaPatternCache.compile(long, null) }
        assertEquals(beforeLong + 3, JenaPatternCache.compilations)

        repeat(2) { assertThrows(Exception::class.java) { JenaPatternCache.compile("(unclosed", null) } }
        // An invalid pattern is still a SPARQL error of the expression in every execution.
        repeat(2) { assertEquals(listOf(null), rows("SELECT ?m { BIND (REGEX(\"ab\", CONCAT(\"(unclosed\")) AS ?m) }").map { it.get("m") }) }
    }
}
