package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfTriple
import org.junit.jupiter.api.Test
import kotlin.test.*

class RdfHVocabularyTest {
  @Test fun `RDF-H terms and h prefix are discoverable without the optional processor`() {
    assertSame(RDFH, Vocabularies.findByPrefix("h"))
    assertSame(RDFH, Vocabularies.findByNamespace("https://w3id.org/rdf-h#"))
    for (term in listOf(RDFH.Holon, RDFH.CompleteHolon, RDFH.inHolon, RDFH.contentGraph,
      RDFH.parentHolon, RDFH.imports, RDFH.importsShapes, RDFH.AssertedBaseTripleShape, RDFH.HolonIntegrityShape,
      RDFH.ReifierMembershipShape, RDFH.ContentGraphMappingShape, RDFH.CompositionHierarchyShape)) {
      assertTrue(term.value.startsWith(RDFH.namespace))
      assertSame(RDFH, Vocabularies.findVocabularyForTerm(term))
    }
    assertEquals(Iri("https://w3id.org/rdf-h#Holon"), RDFH.Holon)
    val h = Iri("https://example.org/H")
    val graph = Rdf.graph { h - RDF.type - qname("h:Holon") }
    assertTrue(graph.hasTriple(RdfTriple(h, RDF.type, RDFH.Holon)))
  }
}
