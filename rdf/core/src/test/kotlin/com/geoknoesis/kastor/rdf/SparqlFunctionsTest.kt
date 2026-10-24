package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SparqlFunctionsTest {
    private val f = SparqlFunction("urn:f", listOf(false, true)) { null }

    @Test
    fun `bindArguments maps errors and missing optional arguments to null`() {
        assertEquals(listOf(Iri("urn:a"), null), f.bindArguments(listOf(Iri("urn:a"))))
        assertEquals(listOf(Iri("urn:a"), null), f.bindArguments(listOf(Iri("urn:a"), null)))
    }

    @Test
    fun `bindArguments rejects what the spec makes an error`() {
        assertNull(f.bindArguments(listOf(null, Iri("urn:b"))))
        assertNull(f.bindArguments(listOf(BlankNode("b"))))
        assertNull(f.bindArguments(listOf()))
        assertNull(f.bindArguments(listOf(Iri("urn:a"), Iri("urn:b"), Iri("urn:c"))))
    }

    @Test
    fun `optional blank node argument becomes null`() {
        assertEquals(listOf(Iri("urn:a"), null), f.bindArguments(listOf(Iri("urn:a"), BlankNode("b"))))
    }

    @Test
    fun `library lookup`() {
        assertSame(f, SparqlFunctionLibrary.of(listOf(f)).function("urn:f"))
        assertNull(SparqlFunctionLibrary.EMPTY.function("urn:f"))
        val g = SparqlFunction("urn:f", listOf()) { null }
        assertSame(g, SparqlFunctionLibrary.of(listOf(f, g)).function("urn:f"))
    }

    @Test
    fun `sandboxed query equality uses sparql and library identity`() {
        val lib = SparqlFunctionLibrary.of(listOf(f))
        assertEquals(SandboxedSelectQuery("q"), SandboxedSelectQuery("q"))
        assertEquals(SandboxedSelectQuery("q", lib), SandboxedSelectQuery("q", lib))
        assertEquals(SandboxedSelectQuery("q", lib).hashCode(), SandboxedSelectQuery("q", lib).hashCode())
        assertNotEquals(SandboxedSelectQuery("q", lib), SandboxedSelectQuery("q"))
        assertNotEquals(SandboxedSelectQuery("q"), SandboxedSelectQuery("r"))
    }
}
