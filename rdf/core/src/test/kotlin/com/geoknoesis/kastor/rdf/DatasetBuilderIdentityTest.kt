package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import org.junit.jupiter.api.Test
import kotlin.test.*

class DatasetBuilderIdentityTest {
    private val name = Iri("urn:graph")

    @Test fun `duplicate graph insertion fails without replacing the first input`() {
        val first = MemoryGraph(listOf(RdfTriple(Iri("urn:s"), Iri("urn:p"), string("first"))))
        val builder = DatasetBuilder().defaultGraph(first).namedGraph(name, first)
        assertFailsWith<IllegalArgumentException> { builder.namedGraph(name, MemoryGraph()) }
        assertEquals(first.getTriples(), builder.build().getNamedGraph(name)!!.getTriples())
    }

    @Test fun `bulk and repository overloads enforce the same name uniqueness`() {
        assertFailsWith<IllegalArgumentException> {
            DatasetBuilder().defaultGraph(MemoryGraph()).namedGraphs(name to MemoryGraph(), name to MemoryGraph())
        }
        Rdf.repository { providerId = "memory" }.use { repository ->
            val builder = DatasetBuilder().defaultGraph(repository).namedGraph(name, repository)
            assertFailsWith<IllegalArgumentException> { builder.namedGraph(name, repository) }
        }
    }
}
