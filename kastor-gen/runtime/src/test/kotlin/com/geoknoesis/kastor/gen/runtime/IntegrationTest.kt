package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.DCTERMS
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.SKOS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class IntegrationTest {

    // Test domain interfaces
    interface Person {
        val name: List<String>
        val age: List<Int>
        val friends: List<Person>
    }

    interface Organization {
        val name: List<String>
        val members: List<Person>
    }

    // Manual wrapper implementations (simulating KSP-generated code)
    class PersonWrapper(override val rdf: RdfHandle) : Person, RdfBacked {
        override val name: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
        }
        
        override val age: List<Int> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.age).mapNotNull { it.lexical.toIntOrNull() }
        }
        
        override val friends: List<Person> by lazy {
            KastorGraphOps.getObjectValues(rdf.graph, rdf.node, FOAF.knows) { child ->
                PersonWrapper(DefaultRdfHandle(child, rdf.graph, setOf(FOAF.name, FOAF.age, FOAF.knows)))
            }
        }
    }

    class OrganizationWrapper(override val rdf: RdfHandle) : Organization, RdfBacked {
        override val name: List<String> by lazy {
            KastorGraphOps.getLiteralValues(rdf.graph, rdf.node, FOAF.name).map { it.lexical }
        }
        
        override val members: List<Person> by lazy {
            KastorGraphOps.getObjectValues(rdf.graph, rdf.node, FOAF.member) { child ->
                PersonWrapper(DefaultRdfHandle(child, rdf.graph, setOf(FOAF.name, FOAF.age, FOAF.knows)))
            }
        }
    }

    @Test
    fun `end-to-end materialization with nested objects works`() {
        // Register wrapper factories (simulating KSP-generated registry entries)
        OntoMapper.registry[Person::class.java] = { handle -> PersonWrapper(handle) }
        OntoMapper.registry[Organization::class.java] = { handle -> OrganizationWrapper(handle) }

        val repo = Rdf.memory()
        val person1 = Iri("http://example.org/person1")
        val person2 = Iri("http://example.org/person2")
        val org = Iri("http://example.org/org")

        repo.add {
            // Person 1
            person1 - FOAF.name - "Alice"
            person1 - FOAF.age - 30
            person1 - FOAF.knows - person2
            person1 - SKOS.altLabel - "Ali"

            // Person 2
            person2 - FOAF.name - "Bob"
            person2 - FOAF.age - 25

            // Organization
            org - FOAF.name - "Example Corp"
            org - FOAF.member - person1
            org - FOAF.member - person2
        }

        // Materialize organization
        val orgRef = RdfRef(org, repo.defaultGraph)
        val organization: Organization = orgRef.asType()

        // Test domain interface
        assertEquals(listOf("Example Corp"), organization.name)
        assertEquals(2, organization.members.size)

        val alice = organization.members.find { it.name.contains("Alice") }
        assertNotNull(alice)
        assertEquals(listOf("Alice"), alice!!.name)
        assertEquals(listOf(30), alice.age)
        assertEquals(1, alice.friends.size)
        assertEquals(listOf("Bob"), alice.friends.first().name)

        // Test RDF side-channel access
        val aliceRdf = alice.asRdf()
        val altLabels = aliceRdf.extras.strings(SKOS.altLabel)
        assertEquals(listOf("Ali"), altLabels)
    }

    @Test
    fun `materialization with validation works`() {
        val validator = object : ValidationContext {
            override fun validate(data: RdfGraph, focus: RdfTerm): ValidationResult {
                val hasName = data.getTriples().any {
                    it.subject == focus && it.predicate == FOAF.name
                }
                return if (hasName) {
                    ValidationResult.Ok
                } else {
                    ValidationResult.Violations(listOf(ShaclViolation(FOAF.name, "Person must have a name")))
                }
            }
        }

        OntoMapper.registry[Person::class.java] = { handle -> PersonWrapper(handle) }

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")

        // Valid person
        repo.add {
            person - FOAF.name - "John"
            person - FOAF.age - 30
        }

        val personRef = RdfRef(person, repo.defaultGraph)
        assertDoesNotThrow {
            val p: Person = OntoMapper.materializeValidated(personRef, Person::class.java, validator)
            assertEquals(listOf("John"), p.name)
        }

        // Invalid person (no name)
        val invalidRepo = Rdf.memory()
        val invalidPerson = Iri("http://example.org/invalid")
        invalidRepo.add {
            invalidPerson - FOAF.age - 30
        }

        val invalidRef = RdfRef(invalidPerson, invalidRepo.defaultGraph)
        assertThrows(ValidationException::class.java) {
            OntoMapper.materializeValidated(invalidRef, Person::class.java, validator)
        }
    }

    @Test
    fun `property bag integration with materialization works`() {
        OntoMapper.registry[Person::class.java] = { handle -> PersonWrapper(handle) }

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")

        repo.add {
            person - FOAF.name - "John"
            person - FOAF.age - 30
            person - DCTERMS.description - "A person"
            person - SKOS.altLabel - "Johnny"
            person - SKOS.altLabel - "J"
        }

        val personRef = RdfRef(person, repo.defaultGraph)
        val personObj: Person = personRef.asType()

        val rdfHandle = personObj.asRdf()
        val extras = rdfHandle.extras

        // Test property bag functionality
        val descriptions = extras.strings(DCTERMS.description)
        assertEquals(listOf("A person"), descriptions)

        val altLabels = extras.strings(SKOS.altLabel)
        assertEquals(setOf("J", "Johnny"), altLabels.toSet()) // Check content, not order

        val allPredicates = extras.predicates()
        assertTrue(allPredicates.contains(DCTERMS.description))
        assertTrue(allPredicates.contains(SKOS.altLabel))
        // Note: FOAF.name and FOAF.age are not in the known set for PersonWrapper, so they should be included
        assertTrue(allPredicates.contains(FOAF.name))
        assertTrue(allPredicates.contains(FOAF.age))
    }

    @Test
    fun `cross-module compatibility works`() {
        // Test that the API works from different modules
        OntoMapper.registry[Person::class.java] = { handle -> PersonWrapper(handle) }

        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")

        repo.add {
            person - FOAF.name - "Jane"
            person - FOAF.age - 28
        }

        // Test Java-style API compatibility
        val personRef = RdfRef(person, repo.defaultGraph)
        val personObj: Person = OntoMapper.materialize(personRef, Person::class.java)

        // Test that RdfBacked interface works
        assertTrue(personObj is RdfBacked)
        val rdfBacked = personObj as RdfBacked
        assertEquals(person, rdfBacked.rdf.node)
        assertEquals(repo.defaultGraph, rdfBacked.rdf.graph)

        // Test that asRdf() extension works
        val handle = personObj.asRdf()
        assertEquals(person, handle.node)
        assertEquals(repo.defaultGraph, handle.graph)
    }

    @Test
    fun `error handling in integration scenarios`() {
        // Test unregistered type
        val repo = Rdf.memory()
        val person = Iri("http://example.org/person")
        val personRef = RdfRef(person, repo.defaultGraph)

        // Clear registry to test unregistered type
        val originalRegistry = OntoMapper.registry.toMap()
        OntoMapper.registry.clear()

        assertThrows(IllegalStateException::class.java) {
            personRef.asType<Person>()
        }

        // Restore registry
        OntoMapper.registry.putAll(originalRegistry)

        // Test invalid asRdf() call
        val regularObject = object {}
        assertThrows(IllegalStateException::class.java) {
            regularObject.asRdf()
        }
    }
}












