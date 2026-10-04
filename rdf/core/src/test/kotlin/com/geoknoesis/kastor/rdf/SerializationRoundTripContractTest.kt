package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class SerializationRoundTripContractTest {
    private val subject = Iri("https://example.org/subject")
    private val predicate = Iri("https://example.org/property")
    private val child = BlankNode("shared-child")
    private val triples = setOf(
        RdfTriple(subject, predicate, child),
        RdfTriple(child, predicate, string("quotes \" and slash \\ and newline\n")),
        RdfTriple(subject, Iri("https://example.org/label"), LangString("Bonjour", "fr")),
        RdfTriple(subject, Iri("https://example.org/amount"), TypedLiteral("12.50", XSD.decimal)),
    )

    @TestFactory
    fun `graph serialization preserves blank nodes language tags datatypes and escaped text`() =
        listOf(RdfFormat.TURTLE, RdfFormat.N_TRIPLES, RdfFormat.RDF_XML, RdfFormat.JSON_LD).flatMap { format ->
            listOf(SerializationOptions.COMPACT, SerializationOptions.PRETTY).map { options ->
                dynamicTest("$format pretty=${options.prettyPrint}") {
                    val source = MemoryGraph(triples)
                    val serialized = source.serialize(format, options)
                    val parsed = Rdf.parse(serialized, format)
                    assertTrue(source.isIsomorphicTo(parsed), "Triples changed after $format round trip: $serialized")
                    assertEquals(triples, source.getTriples().toSet(), "Serialization changed the source graph")
                }
            }
        }

    @TestFactory
    fun `dataset serialization preserves graph boundaries and shared blank node identity`() =
        listOf(RdfFormat.TRIG, RdfFormat.N_QUADS).flatMap { format ->
            listOf(false, true).map { configured ->
                dynamicTest("$format configured=$configured") {
                    Rdf.memory().use { source ->
                        val name = Iri("https://example.org/graph")
                        source.editDefaultGraph().addTriple(RdfTriple(subject, predicate, child))
                        source.editGraph(name).addTriple(RdfTriple(child, predicate, LangString("child label", "en")))
                        val serialized = if (configured) source.serializeDataset(format) {
                            prefix("ex", "https://example.org/")
                            prettyPrint = true
                        } else source.serializeDataset(format.formatName)
                        Rdf.memory().use { restored ->
                            serialized.byteInputStream().use { Rdf.parseDataset(restored, it, format) }
                            assertEquals(setOf(name), restored.listGraphs().toSet())
                            assertTrue(source.defaultGraph.isIsomorphicTo(restored.defaultGraph))
                            assertTrue(source.getGraph(name).isIsomorphicTo(restored.getGraph(name)))
                            val restoredChild = restored.defaultGraph.find(subject, predicate).single().obj
                            assertInstanceOf(BlankNode::class.java, restoredChild)
                            assertEquals(restoredChild, restored.getGraph(name).getTriples().single().subject,
                                "One blank node shared across graphs must not become two nodes")
                        }
                        assertEquals(1, source.defaultGraph.size())
                        assertEquals(1, source.getGraph(name).size())
                    }
                }
            }
        }
}
