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
        OntoMapper.registry.clear()
    }

    @Test
    fun `writeToGraph writes CBD closure to target graph`() {
        // Register factory
        OntoMapper.registry[TestPersonForWriteToGraph::class.java] = { handle ->
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
        OntoMapper.registry[TestPersonForWriteToGraph::class.java] = { handle ->
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
        assertTrue(triples.any { it.subject == alice && it.predicate == FOAF.knows && it.obj == b1 })
        assertTrue(triples.any { it.subject == b1 && it.predicate == FOAF.name })
        assertTrue(triples.any { it.subject == b1 && it.predicate == FOAF.mbox })
        assertTrue(triples.any { it.subject == b1 && it.predicate == FOAF.knows && it.obj == b2 })
        assertTrue(triples.any { it.subject == b2 && it.predicate == FOAF.name })

        repo.close()
    }

    @Test
    fun `writeToGraph uses rdf node as subject when subject not provided`() {
        // Register factory
        OntoMapper.registry[TestPersonForWriteToGraph::class.java] = { handle ->
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
        OntoMapper.registry[TestPersonForWriteToGraph::class.java] = { handle ->
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
        OntoMapper.registry[TestPersonForWriteToGraph::class.java] = { handle ->
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

        // Should work with blank node as rdf.node
        (person as RdfBacked).writeToGraph(targetGraph)

        val triples = targetGraph.getTriples()
        assertEquals(1, triples.size)
        assertEquals(b1, triples.first().subject)

        repo.close()
    }

    @Test
    fun `writeToGraph does not follow IRI objects`() {
        // Register factory
        OntoMapper.registry[TestPersonForWriteToGraph::class.java] = { handle ->
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
        OntoMapper.registry[TestPersonForWriteToGraph::class.java] = { handle ->
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
        OntoMapper.registry[TestPersonForWriteToGraph::class.java] = { handle ->
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
        OntoMapper.registry[TestPersonForWriteToGraph::class.java] = { handle ->
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
        assertTrue(triples.any { it.subject == b1 && it.predicate == FOAF.name })
        assertTrue(triples.any { it.subject == b2 && it.predicate == FOAF.name })

        repo.close()
    }

    @Test
    fun `writeToGraph works with multiple instances`() {
        // Register factory
        OntoMapper.registry[TestPersonForWriteToGraph::class.java] = { handle ->
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
}

