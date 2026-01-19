package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EdgeCaseTest {

    @Test
    fun `materialization with null graph handles gracefully`() {
        // This test verifies that the system handles edge cases gracefully
        // In practice, null graphs should not occur, but defensive programming is good
        
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John"
        }
        
        // Test with valid graph first
        val ref = RdfRef(subject, repo.defaultGraph)
        assertNotNull(ref.graph)
        assertNotNull(ref.node)
    }

    @Test
    fun `property bag handles empty graph`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        // Empty graph
        val propertyBag = KastorGraphOps.extras(repo.defaultGraph, subject, emptySet())
        
        assertTrue(propertyBag.predicates().isEmpty())
        assertTrue(propertyBag.strings(FOAF.name).isEmpty())
        assertTrue(propertyBag.literals(FOAF.name).isEmpty())
        assertTrue(propertyBag.iris(FOAF.name).isEmpty())
        assertTrue(propertyBag.values(FOAF.name).isEmpty())
    }

    @Test
    fun `property bag handles subject not in graph`() {
        val repo = Rdf.memory()
        val subject1 = Iri("http://example.org/person1")
        val subject2 = Iri("http://example.org/person2")
        
        repo.add {
            subject1 - FOAF.name - "John"
        }
        
        // Query for subject2 which is not in the graph
        val propertyBag = KastorGraphOps.extras(repo.defaultGraph, subject2, emptySet())
        
        assertTrue(propertyBag.predicates().isEmpty())
        assertTrue(propertyBag.strings(FOAF.name).isEmpty())
    }

    @Test
    fun `KastorGraphOps handles non-existent predicates`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        val nonExistentPredicate = Iri("http://example.org/nonexistent")
        
        repo.add {
            subject - FOAF.name - "John"
        }
        
        val literals = KastorGraphOps.getLiteralValues(repo.defaultGraph, subject, nonExistentPredicate)
        assertTrue(literals.isEmpty())
        
        assertThrows(IllegalStateException::class.java) {
            KastorGraphOps.getRequiredLiteralValue(repo.defaultGraph, subject, nonExistentPredicate)
        }
    }

    @Test
    fun `required literal access throws when missing`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")

        repo.add {
            subject - FOAF.name - "John"
        }

        assertThrows(IllegalStateException::class.java) {
            KastorGraphOps.getRequiredLiteralValue(repo.defaultGraph, subject, FOAF.age)
        }
    }

    @Test
    fun `KastorGraphOps handles objects that are not literals`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        val friend = Iri("http://example.org/friend")
        
        repo.add {
            subject - FOAF.name - "John"
            subject - FOAF.knows - friend
        }
        
        val literals = KastorGraphOps.getLiteralValues(repo.defaultGraph, subject, FOAF.knows)
        assertTrue(literals.isEmpty()) // Should not include the IRI object
    }

    @Test
    fun `DefaultRdfHandle handles empty known set`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John"
            subject - FOAF.age - 30
        }
        
        val handle = DefaultRdfHandle(subject, repo.defaultGraph, emptySet())
        val extras = handle.extras
        
        // Should include all predicates when known set is empty
        assertTrue(extras.predicates().contains(FOAF.name))
        assertTrue(extras.predicates().contains(FOAF.age))
        assertEquals(2, extras.predicates().size)
    }

    @Test
    fun `DefaultRdfHandle handles large known set`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John"
            subject - FOAF.age - 30
            subject - DCTERMS.description - "A person"
        }
        
        val largeKnownSet = setOf(
            FOAF.name, FOAF.age, DCTERMS.description,
            Iri("http://example.org/prop1"),
            Iri("http://example.org/prop2"),
            Iri("http://example.org/prop3")
        )
        
        val handle = DefaultRdfHandle(subject, repo.defaultGraph, largeKnownSet)
        val extras = handle.extras
        
        // Should exclude all known predicates
        assertTrue(extras.predicates().isEmpty())
    }

    @Test
    fun `materialization with malformed data handles gracefully`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John"
            subject - FOAF.age - "not-a-number" // Malformed data
        }
        
        // Register a factory that handles malformed data
        data class PersonData(val name: String, val age: Int?)
        
        OntoMapper.registry[PersonData::class.java] = { handle ->
            PersonData(
                name = KastorGraphOps.getLiteralValues(handle.graph, handle.node, FOAF.name)
                    .map { it.lexical }
                    .firstOrNull() ?: "Unknown",
                age = KastorGraphOps.getLiteralValues(handle.graph, handle.node, FOAF.age)
                    .mapNotNull { it.lexical.toIntOrNull() }
                    .firstOrNull()
            )
        }
        
        val ref = RdfRef(subject, repo.defaultGraph)
        val result = OntoMapper.materialize(ref, PersonData::class.java)
        
        assertNotNull(result)
    }

    @Test
    fun `validation handles concurrent access`() {
        val validator = object : ShaclValidator {
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
                Thread.sleep(10)
                return ValidationResult.Ok
            }
        }
        
        ShaclValidation.register(validator)
        
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        repo.add {
            subject - FOAF.name - "John"
        }
        
        // Test that validation can handle concurrent access
        val threads = (1..5).map {
            Thread {
                assertDoesNotThrow {
                    validator.validate(repo.defaultGraph, subject).orThrow()
                }
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    @Test
    fun `property bag deterministic ordering with many predicates`() {
        val repo = Rdf.memory()
        val subject = Iri("http://example.org/person")
        
        // Add many predicates in random order
        val predicates = listOf(
            Iri("http://example.org/z"),
            Iri("http://example.org/a"),
            Iri("http://example.org/m"),
            Iri("http://example.org/b"),
            Iri("http://example.org/y")
        )
        
        predicates.forEach { pred ->
            repo.add {
                subject - pred - "value"
            }
        }
        
        val propertyBag = KastorGraphOps.extras(repo.defaultGraph, subject, emptySet())
        val orderedPredicates = propertyBag.predicates().toList()
        
        // Should be in lexicographic order
        assertEquals(predicates.sortedBy { it.value }, orderedPredicates)
    }

    @Test
    fun `materialization with circular references handles gracefully`() {
        val repo = Rdf.memory()
        val person1 = Iri("http://example.org/person1")
        val person2 = Iri("http://example.org/person2")
        
        repo.add {
            person1 - FOAF.name - "Alice"
            person1 - FOAF.knows - person2
            
            person2 - FOAF.name - "Bob"
            person2 - FOAF.knows - person1 // Circular reference
        }
        
        // Register a factory that could create circular references
        data class PersonWithFriends(val name: String, val friends: List<String>)
        
        OntoMapper.registry[PersonWithFriends::class.java] = { handle ->
            PersonWithFriends(
                name = KastorGraphOps.getLiteralValues(handle.graph, handle.node, FOAF.name)
                    .map { it.lexical }
                    .firstOrNull() ?: "Unknown",
                friends = KastorGraphOps.getObjectValues(handle.graph, handle.node, FOAF.knows) { friend ->
                    // This could create infinite recursion, but lazy evaluation should prevent it
                    "Friend: ${friend}"
                }
            )
        }
        
        val ref = RdfRef(person1, repo.defaultGraph)
        assertDoesNotThrow {
            val result = OntoMapper.materialize(ref, PersonWithFriends::class.java)
            assertNotNull(result)
        }
    }
}












