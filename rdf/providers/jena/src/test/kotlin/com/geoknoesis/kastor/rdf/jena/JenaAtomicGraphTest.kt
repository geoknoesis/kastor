package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.apache.jena.rdf.model.ModelFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JenaAtomicGraphTest {
    @Test
    fun `standalone model adapters reject atomic edits before entering the block`() {
        val model = ModelFactory.createDefaultModel()
        try {
            val graph = JenaGraph(model)
            val old = RdfTriple(Iri("urn:s"), Iri("urn:p"), string("old"))
            graph.addTriple(old)
            var entered = false
            assertThrows(UnsupportedOperationException::class.java) {
                graph.mutateAtomically { entered = true; clear() }
            }
            assertFalse(entered)
            assertEquals(listOf(old), graph.getTriples())
        } finally { model.close() }
    }
}
