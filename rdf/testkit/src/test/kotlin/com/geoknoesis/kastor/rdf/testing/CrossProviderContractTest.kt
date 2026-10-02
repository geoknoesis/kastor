package com.geoknoesis.kastor.rdf.testing

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.provider.MemoryRepository
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.*
import kotlin.random.Random

class CrossProviderContractTest {
    @TestFactory
    fun advertisedFormatsRoundTrip() = listOf(
        com.geoknoesis.kastor.rdf.jena.JenaProvider(),
        com.geoknoesis.kastor.rdf.rdf4j.Rdf4jProvider(),
    ).flatMap { provider ->
        provider.getCapabilities(null).supportedInputFormats.map { format ->
            DynamicTest.dynamicTest("${provider.id} advertises and round-trips $format") {
                assertTrue(provider.supportsFormat(format))
                assertTrue(provider.supportsFormat(" ${format.lowercase()} "))
                // Use an RDF/XML-compatible predicate namespace and the matching graph/dataset API.
                val value = RdfTriple(Iri("https://example.org/s"), Iri("https://example.org/p"), string("value"))
                val alias = " ${format.lowercase()} "
                if (RdfFormat.isQuadFormat(format)) {
                    val name = Iri("https://example.org/graph")
                    val namedValue = value.copy(obj = string("named"))
                    providers.getValue(provider.id)().use { source ->
                        source.editDefaultGraph().addTriple(value)
                        source.editGraph(name).addTriple(namedValue)
                        val serialized = provider.serializeDataset(source, alias, SerializationOptions.DEFAULT)
                        providers.getValue(provider.id)().use { parsed ->
                            serialized.byteInputStream().use { provider.parseDataset(parsed, it, alias) }
                            assertEquals(setOf(value), parsed.defaultGraph.getTriples().toSet())
                            assertEquals(setOf(namedValue), parsed.getGraph(name).getTriples().toSet())
                            assertEquals(setOf(name), parsed.listGraphs().toSet())
                        }
                    }
                } else {
                    val graph = MemoryGraph(listOf(value))
                    val serialized = provider.serializeGraph(graph, alias, SerializationOptions.DEFAULT)
                    val parsed = serialized.byteInputStream().use { provider.parseGraph(it, alias) }
                    assertEquals(setOf(value), parsed.getTriples().toSet())
                }
            }
        }
    }
    private val providers: Map<String, () -> RdfRepository> = linkedMapOf(
        "memory" to { MemoryRepository(RdfConfig()) },
        "jena" to { JenaRepository.MemoryRepository() },
        "rdf4j" to { Rdf4jRepository.MemoryRepository() },
    )
    private val triple = RdfTriple(Iri("urn:s"), Iri("urn:p"), string("value"))

    @TestFactory
    fun transactionsAndLifecycle() = providers.map { (name, factory) ->
        DynamicTest.dynamicTest("$name rollback, read-only, graph lifecycle and repeated close") {
            repeat(100) {
                val repo = factory()
                val view = repo.defaultGraph
                try {
                    assertFailsWith<IllegalArgumentException> {
                        repo.transaction {
                            editDefaultGraph().addTriple(triple)
                            transaction { editGraph(Iri("urn:g")).addTriple(triple) }
                            throw IllegalArgumentException("rollback")
                        }
                    }
                    assertEquals(0, view.size())
                    assertFalse(repo.hasGraph(Iri("urn:g")))
                    repo.readTransaction {
                        assertFailsWith<IllegalStateException> { editDefaultGraph().addTriple(triple) }
                        assertFailsWith<IllegalStateException> { transaction { editGraph(Iri("urn:g")).addTriple(triple) } }
                    }
                    repo.transaction { editGraph(Iri("urn:g")).addTriple(triple) }
                    repo.createGraph(Iri("urn:g"))
                    assertEquals(setOf(triple), repo.getGraph(Iri("urn:g")).getTriples().toSet())
                    assertTrue(repo.clear())
                    assertFalse(repo.clear())
                } finally { repo.close() }
                repo.close()
                assertTrue(repo.isClosed())
                assertFailsWith<IllegalStateException> { view.getTriples() }
            }
        }
    }

    @TestFactory
    fun randomizedIsomorphismOracle() = (0 until 120).map { seed ->
        DynamicTest.dynamicTest("Jena differential oracle seed $seed") {
            val random = Random(seed)
            val nodes = List(8) { BlankNode("a$it") }
            val renamed = nodes.shuffled(random).mapIndexed { i, node -> node to BlankNode("renamed$i") }.toMap()
            val triples = List(25) {
                RdfTriple(nodes.random(random), Iri("urn:p${random.nextInt(3)}"),
                    if (random.nextBoolean()) nodes.random(random) else string("literal/;:${random.nextInt(4)}"))
            }.toSet()
            fun rename(term: RdfTerm): RdfTerm = if (term is BlankNode) renamed.getValue(term) else term
            val copy = triples.map { RdfTriple(rename(it.subject) as RdfResource, it.predicate, rename(it.obj)) }.toMutableSet()
            if (seed % 2 == 0) {
                val original = copy.first()
                copy.remove(original)
                copy.add(original.copy(predicate = Iri("urn:mutation")))
            }
            val left = MemoryGraph(triples.toList())
            val right = MemoryGraph(copy.toList())
            // Jena's matcher is the oracle, for core's check and for the testkit's (which is built on core's).
            val oracle = jenaIsomorphic(left, right)
            assertEquals(oracle, left.isIsomorphicTo(right), "seed=$seed")
            assertEquals(oracle, RdfGraphIsomorphism.isIsomorphic(left, right), "seed=$seed")
        }
    }

    private fun jenaIsomorphic(left: RdfGraph, right: RdfGraph): Boolean {
        val first = com.geoknoesis.kastor.rdf.jena.JenaBridge.copyToJenaModel(left)
        try {
            val second = com.geoknoesis.kastor.rdf.jena.JenaBridge.copyToJenaModel(right)
            try {
                return first.isIsomorphicWith(second)
            } finally {
                second.close()
            }
        } finally {
            first.close()
        }
    }
}
