package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// Test domain interface
interface TestPersonForWriteToGraph {
    val name: List<String>
    val age: List<Int>?
}

class WriteToGraphTest {

    @BeforeEach
    fun setUp() {
        // Clear registry before each test
        OntoMapper.registeredTypes().forEach { OntoMapper.unregister(it) }
    }

    @Test
    fun `writeToGraph writes CBD closure to target graph`() {
        // Register factory
        OntoMapper.register(TestPersonForWriteToGraph::class.java, replace = true) { handle ->
            object : TestPersonForWriteToGraph, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int>? by lazy {
                    val ages = KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
                    if (ages.isEmpty()) null else ages.mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        
        repo.add {
            alice has FOAF.name with "Alice"
            alice has FOAF.age with 30
        }

        val person: TestPersonForWriteToGraph = RdfRef(alice, repo.defaultGraph).asType()
        val targetGraph = Rdf.graph { }

        (person as RdfBacked).writeToGraph(targetGraph)

        val triples = targetGraph.getTriples()
        assertEquals(2, triples.size, "Target graph should contain 2 triples")
        assertTrue(triples.any { it.subject == alice && it.predicate == FOAF.name })
        assertTrue(triples.any { it.subject == alice && it.predicate == FOAF.age })

        repo.close()
    }

    @Test
    fun `writeToGraph follows blank nodes recursively`() {
        // Register factory
        OntoMapper.register(TestPersonForWriteToGraph::class.java, replace = true) { handle ->
            object : TestPersonForWriteToGraph, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int>? by lazy { 
                    val ages = KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
                    if (ages.isEmpty()) null else ages.mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        val b1 = bnode("b1")
        val b2 = bnode("b2")

        repo.add {
            alice has FOAF.name with "Alice"
            alice has FOAF.knows with b1
            b1 has FOAF.name with "Bob"
            b1 has FOAF.mbox with "bob@example.com"
            b1 has FOAF.knows with b2
            b2 has FOAF.name with "Charlie"
        }

        val person: TestPersonForWriteToGraph = RdfRef(alice, repo.defaultGraph).asType()
        val targetGraph = Rdf.graph { }

        (person as RdfBacked).writeToGraph(targetGraph)

        val triples = targetGraph.getTriples()
        assertEquals(6, triples.size, "Target graph should contain all 6 triples including blank nodes")
        assertTrue(triples.any { it.subject == alice && it.predicate == FOAF.name })
        // Blank nodes are written as fresh nodes, consistently within the write.
        val n1 = targetGraph.find(alice, FOAF.knows).single().obj as BlankNode
        val n2 = targetGraph.find(n1, FOAF.knows).single().obj as BlankNode
        assertTrue(n1 != b1 && n1 != b2 && n2 != b1 && n2 != b2 && n1 != n2)
        assertEquals(listOf("Bob"), names(targetGraph, n1))
        assertEquals(1, targetGraph.find(n1, FOAF.mbox).size)
        assertEquals(listOf("Charlie"), names(targetGraph, n2))

        repo.close()
    }

    @Test
    fun `writeToGraph uses rdf node as subject when subject not provided`() {
        // Register factory
        OntoMapper.register(TestPersonForWriteToGraph::class.java, replace = true) { handle ->
            object : TestPersonForWriteToGraph, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int>? by lazy { 
                    val ages = KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
                    if (ages.isEmpty()) null else ages.mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")

        repo.add {
            alice has FOAF.name with "Alice"
        }

        val person: TestPersonForWriteToGraph = RdfRef(alice, repo.defaultGraph).asType()
        val targetGraph = Rdf.graph { }

        (person as RdfBacked).writeToGraph(targetGraph)  // No subject parameter

        val triples = targetGraph.getTriples()
        assertEquals(1, triples.size)
        assertEquals(alice, triples.first().subject, "Should use rdf.node as subject")

        repo.close()
    }

    @Test
    fun `writeToGraph uses provided subject when specified`() {
        // Register factory
        OntoMapper.register(TestPersonForWriteToGraph::class.java, replace = true) { handle ->
            object : TestPersonForWriteToGraph, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int>? by lazy { 
                    val ages = KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
                    if (ages.isEmpty()) null else ages.mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        val copy = Iri("http://example.org/copy")

        repo.add {
            alice has FOAF.name with "Alice"
        }

        val person: TestPersonForWriteToGraph = RdfRef(alice, repo.defaultGraph).asType()
        val targetGraph = Rdf.graph { }

        (person as RdfBacked).writeToGraph(targetGraph, subject = copy)

        val triples = targetGraph.getTriples()
        assertEquals(1, triples.size)
        assertEquals(copy, triples.first().subject, "Should use provided subject")
        assertEquals("Alice", (triples.first().obj as Literal).lexical, "Object should be preserved")

        repo.close()
    }

    @Test
    fun `writeToGraph works with blank node as subject`() {
        // Register factory with blank node
        OntoMapper.register(TestPersonForWriteToGraph::class.java, replace = true) { handle ->
            object : TestPersonForWriteToGraph, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int>? by lazy { 
                    val ages = KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
                    if (ages.isEmpty()) null else ages.mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val b1 = bnode("b1")

        repo.add {
            b1 has FOAF.name with "Anonymous"
        }

        val person: TestPersonForWriteToGraph = RdfRef(b1, repo.defaultGraph).asType()
        val targetGraph = Rdf.graph { }

        // Should work with blank node as rdf.node; the node is written as a fresh blank node, which is returned
        val written = (person as RdfBacked).writeToGraph(targetGraph)

        val triples = targetGraph.getTriples()
        assertEquals(1, triples.size)
        assertTrue(written is BlankNode && written != b1, written.toString())
        assertEquals(written, triples.first().subject)

        repo.close()
    }

    @Test
    fun `writeToGraph does not follow IRI objects`() {
        // Register factory
        OntoMapper.register(TestPersonForWriteToGraph::class.java, replace = true) { handle ->
            object : TestPersonForWriteToGraph, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int>? by lazy { 
                    val ages = KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
                    if (ages.isEmpty()) null else ages.mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        val bob = Iri("http://example.org/bob")

        repo.add {
            alice has FOAF.name with "Alice"
            alice has FOAF.knows with bob
            bob has FOAF.name with "Bob"
            bob has FOAF.mbox with "bob@example.com"
        }

        val person: TestPersonForWriteToGraph = RdfRef(alice, repo.defaultGraph).asType()
        val targetGraph = Rdf.graph { }

        (person as RdfBacked).writeToGraph(targetGraph)

        val triples = targetGraph.getTriples()
        assertEquals(2, triples.size, "Should only contain alice's direct properties")
        assertTrue(triples.any { it.subject == alice && it.predicate == FOAF.name })
        assertTrue(triples.any { it.subject == alice && it.predicate == FOAF.knows && it.obj == bob })
        // Bob's properties should NOT be included
        assertFalse(triples.any { it.subject == bob })

        repo.close()
    }

    @Test
    fun `writeToGraph handles empty CBD closure`() {
        // Register factory
        OntoMapper.register(TestPersonForWriteToGraph::class.java, replace = true) { handle ->
            object : TestPersonForWriteToGraph, RdfBacked {
                override val rdf = handle
                override val name: List<String> = emptyList()
                override val age: List<Int>? by lazy { 
                    val ages = KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
                    if (ages.isEmpty()) null else ages.mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        // No triples added for alice

        val person: TestPersonForWriteToGraph = RdfRef(alice, repo.defaultGraph).asType()
        val targetGraph = Rdf.graph { }

        (person as RdfBacked).writeToGraph(targetGraph)

        val triples = targetGraph.getTriples()
        assertTrue(triples.isEmpty(), "Target graph should be empty for resource with no properties")

        repo.close()
    }

    @Test
    fun `writeToGraph can write to existing graph`() {
        // Register factory
        OntoMapper.register(TestPersonForWriteToGraph::class.java, replace = true) { handle ->
            object : TestPersonForWriteToGraph, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int>? by lazy { 
                    val ages = KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
                    if (ages.isEmpty()) null else ages.mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        val bob = Iri("http://example.org/bob")

        repo.add {
            alice has FOAF.name with "Alice"
            bob has FOAF.name with "Bob"
        }

        val person: TestPersonForWriteToGraph = RdfRef(alice, repo.defaultGraph).asType()
        val targetGraph = Rdf.graph {
            val bob = Iri("http://example.org/bob")
            bob has FOAF.name with "Bob"
        }

        val initialSize = targetGraph.size()
        (person as RdfBacked).writeToGraph(targetGraph)

        val triples = targetGraph.getTriples()
        assertTrue(triples.size > initialSize, "Target graph should have more triples after writeToGraph")
        assertTrue(triples.any { it.subject == alice && it.predicate == FOAF.name })
        assertTrue(triples.any { it.subject == bob && it.predicate == FOAF.name })

        repo.close()
    }

    @Test
    fun `writeToGraph prevents cycles with blank nodes`() {
        // Register factory
        OntoMapper.register(TestPersonForWriteToGraph::class.java, replace = true) { handle ->
            object : TestPersonForWriteToGraph, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int>? by lazy { 
                    val ages = KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
                    if (ages.isEmpty()) null else ages.mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        val b1 = bnode("b1")
        val b2 = bnode("b2")

        // Create a cycle: b1 -> b2 -> b1
        repo.add {
            alice has FOAF.name with "Alice"
            alice has FOAF.knows with b1
            b1 has FOAF.name with "Bob"
            b1 has FOAF.knows with b2
            b2 has FOAF.name with "Charlie"
            b2 has FOAF.knows with b1  // Cycle back to b1
        }

        val person: TestPersonForWriteToGraph = RdfRef(alice, repo.defaultGraph).asType()
        val targetGraph = Rdf.graph { }

        (person as RdfBacked).writeToGraph(targetGraph)

        val triples = targetGraph.getTriples()
        assertEquals(6, triples.size, "Should contain all triples without infinite loop")
        assertTrue(triples.any { it.subject == alice && it.predicate == FOAF.name })
        val n1 = targetGraph.find(alice, FOAF.knows).single().obj as BlankNode
        val n2 = targetGraph.find(n1, FOAF.knows).single().obj as BlankNode
        assertEquals(n1, targetGraph.find(n2, FOAF.knows).single().obj, "the cycle is kept between the fresh nodes")
        assertEquals(listOf("Bob"), names(targetGraph, n1))
        assertEquals(listOf("Charlie"), names(targetGraph, n2))

        repo.close()
    }

    @Test
    fun `writeToGraph works with multiple instances`() {
        // Register factory
        OntoMapper.register(TestPersonForWriteToGraph::class.java, replace = true) { handle ->
            object : TestPersonForWriteToGraph, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int>? by lazy { 
                    val ages = KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age)
                    if (ages.isEmpty()) null else ages.mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        val bob = Iri("http://example.org/bob")

        repo.add {
            alice has FOAF.name with "Alice"
            bob has FOAF.name with "Bob"
        }

        val alicePerson = RdfRef(alice, repo.defaultGraph).asType<TestPersonForWriteToGraph>()
        val bobPerson = RdfRef(bob, repo.defaultGraph).asType<TestPersonForWriteToGraph>()
        val targetGraph = Rdf.graph { }

        (alicePerson as RdfBacked).writeToGraph(targetGraph)
        (bobPerson as RdfBacked).writeToGraph(targetGraph)

        val triples = targetGraph.getTriples()
        assertEquals(2, triples.size, "Target graph should contain both persons' CBD closures")
        assertTrue(triples.any { it.subject == alice && it.predicate == FOAF.name })
        assertTrue(triples.any { it.subject == bob && it.predicate == FOAF.name })

        repo.close()
    }

    private fun names(graph: RdfGraph, node: RdfResource): List<String> =
        graph.find(node, FOAF.name).map { (it.obj as Literal).lexical }.sorted()

    private fun registerPlainFactory() {
        OntoMapper.register(TestPersonForWriteToGraph::class.java, replace = true) { handle ->
            object : TestPersonForWriteToGraph, RdfBacked {
                override val rdf = handle
                override val name: List<String> get() = emptyList()
                override val age: List<Int>? get() = null
            }
        }
    }

    @Test
    fun `writeToGraph does not merge closure blank nodes with same-labelled nodes of the target graph`() {
        registerPlainFactory()
        val alice = Iri("http://example.org/alice")
        val b0 = bnode("b0")
        val source = com.geoknoesis.kastor.rdf.provider.MemoryGraph(listOf(
            RdfTriple(alice, FOAF.knows, b0),
            RdfTriple(b0, FOAF.name, Literal("Bob")),
        ))
        val target = com.geoknoesis.kastor.rdf.provider.MemoryGraph(listOf(RdfTriple(b0, FOAF.name, Literal("Unrelated"))))

        (RdfRef(alice, source).asType<TestPersonForWriteToGraph>() as RdfBacked).writeToGraph(target)

        assertEquals(listOf("Unrelated"), names(target, b0), "the target's own _:b0 is untouched")
        val copied = target.find(alice, FOAF.knows).single().obj as BlankNode
        assertNotEquals(b0, copied)
        assertEquals(listOf("Bob"), names(target, copied))
    }

    @Test
    fun `copying to a new IRI in the same graph gives the copy its own blank nodes`() {
        registerPlainFactory()
        val alice = Iri("http://example.org/alice")
        val copy = Iri("http://example.org/copy")
        val b0 = bnode("b0")
        val graph = com.geoknoesis.kastor.rdf.provider.MemoryGraph(listOf(
            RdfTriple(alice, FOAF.knows, b0),
            RdfTriple(b0, FOAF.name, Literal("Bob")),
        ))

        val written = (RdfRef(alice, graph).asType<TestPersonForWriteToGraph>() as RdfBacked).writeToGraph(graph, copy)

        assertEquals(copy, written)
        val copied = graph.find(copy, FOAF.knows).single().obj as BlankNode
        assertNotEquals(b0, copied, "the copy does not share the original's nested node")
        graph.removeTriple(RdfTriple(copied, FOAF.name, Literal("Bob")))
        assertEquals(listOf("Bob"), names(graph, b0), "editing the copy leaves the original intact")
    }

    @Test
    fun `blank nodes nested in triple terms are written as the same fresh nodes as elsewhere in the closure`() {
        registerPlainFactory()
        val alice = Iri("http://example.org/alice")
        val b0 = bnode("b0")
        val says = Iri("http://example.org/says")
        val quoted = TripleTerm(RdfTriple(b0, FOAF.name, TripleTerm(RdfTriple(b0, FOAF.age, Literal("3")))))
        val source = com.geoknoesis.kastor.rdf.provider.MemoryGraph(listOf(
            RdfTriple(alice, FOAF.knows, b0),
            RdfTriple(b0, FOAF.name, Literal("Bob")),
            RdfTriple(alice, says, quoted),
        ))
        val target = com.geoknoesis.kastor.rdf.provider.MemoryGraph(listOf(RdfTriple(b0, FOAF.name, Literal("Unrelated"))))

        (RdfRef(alice, source).asType<TestPersonForWriteToGraph>() as RdfBacked).writeToGraph(target)

        val copied = target.find(alice, FOAF.knows).single().obj as BlankNode
        assertNotEquals(b0, copied)
        val written = (target.find(alice, says).single().obj as TripleTerm).triple
        assertEquals(copied, written.subject, "the quoted triple's blank node is the copy, not the target's _:b0")
        assertEquals(copied, ((written.obj as TripleTerm).triple).subject, "nested triple terms are renamed too")
        assertEquals(listOf("Unrelated"), names(target, b0))
    }
}
