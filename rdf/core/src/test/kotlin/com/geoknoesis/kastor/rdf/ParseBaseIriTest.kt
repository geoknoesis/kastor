package com.geoknoesis.kastor.rdf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** File, stream and dataset parsing resolve relative IRIs against a base IRI. */
class ParseBaseIriTest {

    @TempDir
    lateinit var dir: Path

    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val owlOntology = Iri("http://www.w3.org/2002/07/owl#Ontology")
    private val owlClass = Iri("http://www.w3.org/2002/07/owl#Class")

    private val turtle = """
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        <> a owl:Ontology .
        <#Foo> a owl:Class .
    """.trimIndent()

    @Test
    fun `parseFromFile resolves relative Turtle IRIs against the file URI`() {
        val file = dir.resolve("onto.ttl")
        Files.writeString(file, turtle)
        val base = file.toUri().toString()

        val graph = Rdf.parseFromFile(file.toString(), RdfFormat.TURTLE)

        assertEquals(2, graph.size())
        assertTrue(graph.hasTriple(RdfTriple(Iri(base), rdfType, owlOntology)), graph.getTriples().toString())
        assertTrue(graph.hasTriple(RdfTriple(Iri("$base#Foo"), rdfType, owlClass)), graph.getTriples().toString())
    }

    @Test
    fun `parseFromFile resolves rdf about fragments in RDF XML`() {
        val file = dir.resolve("onto.rdf")
        Files.writeString(
            file,
            """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="#Foo">
                <rdf:type rdf:resource="http://www.w3.org/2002/07/owl#Class"/>
              </rdf:Description>
            </rdf:RDF>
            """.trimIndent(),
        )
        val base = file.toUri().toString()

        val graph = Rdf.parseFromFile(file.toString(), RdfFormat.RDF_XML)

        assertEquals(listOf(RdfTriple(Iri("$base#Foo"), rdfType, owlClass)), graph.getTriples())
    }

    @Test
    fun `input stream, streaming and scoped parsing accept an explicit base IRI`() {
        val base = "http://example.org/doc"
        val expected = setOf(RdfTriple(Iri(base), rdfType, owlOntology), RdfTriple(Iri("$base#Foo"), rdfType, owlClass))

        assertEquals(expected, Rdf.parseFromInputStream(turtle.byteInputStream(), "TURTLE", base).getTriples().toSet())
        assertEquals(expected, Rdf.parseFromInputStream(turtle.byteInputStream(), RdfFormat.TURTLE, base).getTriples().toSet())
        assertEquals(expected, Rdf.parse(turtle, RdfFormat.TURTLE, base).getTriples().toSet())
        assertEquals(expected, Rdf.parse(turtle, "TURTLE", base).getTriples().toSet())
        assertEquals(expected, Rdf.parse(turtle, "ttl", base).getTriples().toSet())
        assertEquals(expected, Rdf.parseStreaming(turtle.byteInputStream(), RdfFormat.TURTLE, base).toSet())
        Rdf.openTripleStream(turtle.byteInputStream(), RdfFormat.TURTLE, base).use { assertEquals(expected, it.toSet()) }
    }

    @Test
    fun `dataset parsing from a file and a stream resolves relative IRIs`() {
        val trig = "<#s> <http://example.org/p> <#o> .\nGRAPH <#g> { <#s> <http://example.org/p> <other> . }\n"
        val file = dir.resolve("data.trig")
        Files.writeString(file, trig)
        val base = file.toUri().toString()

        val fileRepo = Rdf.parseDatasetFromFile(file.toString(), RdfFormat.TRIG) as RdfRepository
        try {
            assertTrue(fileRepo.defaultGraph.hasTriple(RdfTriple(Iri("$base#s"), Iri("http://example.org/p"), Iri("$base#o"))))
            val named = fileRepo.getGraph(Iri("$base#g"))
            // java.net.URI.resolve would rewrite file:/// to file:/, so build the expected sibling IRI textually.
            val otherIri = Iri(base.substringBeforeLast('/') + "/other")
            assertTrue(named.hasTriple(RdfTriple(Iri("$base#s"), Iri("http://example.org/p"), otherIri)), named.getTriples().toString())
        } finally {
            fileRepo.close()
        }

        val streamBase = "http://example.org/ds"
        val repo = Rdf.memory()
        try {
            Rdf.parseDataset(repo, trig.byteInputStream(), RdfFormat.TRIG, streamBase)
            assertTrue(repo.getGraph(Iri("$streamBase#g")).hasTriple(RdfTriple(Iri("$streamBase#s"), Iri("http://example.org/p"), Iri("http://example.org/other"))))
        } finally {
            repo.close()
        }
    }
}
