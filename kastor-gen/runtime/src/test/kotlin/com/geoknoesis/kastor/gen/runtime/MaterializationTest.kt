package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

// Create a simple domain interface for testing
interface TestPerson {
    val name: List<String>
    val age: List<Int>
}

class MaterializationTest {

    @Test
    fun `RdfRef asType materializes and asRdf works on result`() {
        
        // Register a simple factory
        OntoMapper.registry[TestPerson::class.java] = { handle ->
            object : TestPerson, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age).mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }
        
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "Alice"
            subject - FOAF.age - 30
            subject - DCTERMS.description - "A test person"
        }
        
        val ref = RdfRef(subject, repo.defaultGraph)
        val person: TestPerson = ref.asType()
        
        // Test domain interface
        assertEquals(listOf("Alice"), person.name)
        assertEquals(listOf(30), person.age)
        
        // Test RDF side-channel
        val rdfHandle = person.asRdf()
        assertEquals(subject, rdfHandle.node)
        assertEquals(repo.defaultGraph, rdfHandle.graph)
        
        // Test extras
        val extras = rdfHandle.extras
        val descriptions = extras.strings(DCTERMS.description)
        assertEquals(listOf("A test person"), descriptions)
    }
    
    @Test
    fun `graph ref materialize and repository shortcuts match RdfRef asType`() {
        OntoMapper.registry[TestPerson::class.java] = { handle ->
            object : TestPerson, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age).mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        repo.add {
            subject - FOAF.name - "Eve"
            subject - FOAF.age - 22
        }
        val g = repo.defaultGraph

        val viaRef = RdfRef(subject, g).asType<TestPerson>()
        assertEquals(viaRef.name, g.ref(subject).asType<TestPerson>().name)
        assertEquals(viaRef.name, g.materialize<TestPerson>(subject).name)
        assertEquals(viaRef.name, subject.materializeIn<TestPerson>(g).name)
        assertEquals(viaRef.name, repo.materialize<TestPerson>(subject).name)
        assertEquals(
            listOf(viaRef.name, viaRef.name),
            listOf(subject, subject).materializeIn<TestPerson>(g).map { it.name },
        )
    }

    @Test
    fun `materialization fails for unregistered type`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        val ref = RdfRef(subject, repo.defaultGraph)
        
        assertThrows(IllegalStateException::class.java) {
            ref.asType<Any>()
        }
    }

    @Test
    fun `resource as materializes with live access`() {
        // Register a simple factory
        OntoMapper.registry[TestPerson::class.java] = { handle ->
            object : TestPerson, RdfBacked {
                override val rdf = handle
                override val name: List<String> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
                }
                override val age: List<Int> by lazy {
                    KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age).mapNotNull { it.lexical.toIntOrNull() }
                }
            }
        }

        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")

        repo.add {
            subject - FOAF.name - "Bob"
            subject - FOAF.age - 41
        }

        val resource = repo.resource(subject)
        val person = resource.asType<TestPerson>()

        assertEquals(listOf("Bob"), person.name)
        assertEquals(listOf(41), person.age)
    }

    @Test
    fun `resource convenience accessors expose properties`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")

        repo.add {
            subject - FOAF.name - "Cara"
            subject - FOAF.age - 25
            subject - DCTERMS.description - "A sample person"
        }

        val resource = repo.resource(subject)

        assertTrue(resource.predicates().containsAll(setOf(FOAF.name, FOAF.age, DCTERMS.description)))
        assertEquals(listOf("Cara"), resource.strings(FOAF.name))
        assertEquals(listOf("25"), resource.literals(FOAF.age).map { it.lexical })
        assertEquals(3, resource.properties().size)
    }

    @Test
    fun `resource setters update graph`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        val friend = Iri("http://example.org/friend")

        val resource = repo.resource(subject)

        resource.setLiteral(FOAF.name, "Dana")
        assertEquals(listOf("Dana"), resource.strings(FOAF.name))

        resource.setLiteral(FOAF.age, 33)
        assertEquals(listOf("33"), resource.literals(FOAF.age).map { it.lexical })

        resource.setResource(FOAF.knows, friend)
        assertEquals(listOf(friend), resource.iris(FOAF.knows))

        resource.addValue(FOAF.name, Literal("Dana 2"))
        assertEquals(2, resource.strings(FOAF.name).size)

        resource.removeValue(FOAF.name, Literal("Dana 2"))
        assertEquals(listOf("Dana"), resource.strings(FOAF.name))

        resource.clear(FOAF.name)
        assertEquals(emptyList<String>(), resource.strings(FOAF.name))
    }
    
    @Test
    fun `asRdf fails for non-RdfBacked object`() {
        val regularObject = object {}
        
        assertThrows(IllegalStateException::class.java) {
            regularObject.asRdf()
        }
    }
}












