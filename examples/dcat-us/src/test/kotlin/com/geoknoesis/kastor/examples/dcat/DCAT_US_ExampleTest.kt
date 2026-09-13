package com.geoknoesis.kastor.examples.dcat

import com.geoknoesis.kastor.examples.dcat.generated.Catalog
import com.geoknoesis.kastor.gen.runtime.RdfBacked
import com.geoknoesis.kastor.gen.runtime.materialize
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Smoke test over the interfaces generated from the DCAT-US 3.0 SHACL shapes during this build. */
class DCAT_US_ExampleTest {

    @Test
    fun `generated DCAT-US Catalog materializes from RDF`() {
        val graph = MemoryGraph()
        val node = Iri("https://data.example.gov/catalog")
        graph.addTriple(RdfTriple(node, RDF.type, Iri("http://www.w3.org/ns/dcat#Catalog")))

        val catalog: Catalog = graph.materialize(node)

        assertTrue(catalog is RdfBacked)
        assertEquals(node, (catalog as RdfBacked).rdf.node)
    }
}
