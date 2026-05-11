package com.geoknoesis.kastor.rdf

import com.geoknoesis.kastor.rdf.vocab.FOAF
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CbdClosureTest {

    @Test
    fun `getCbdClosure returns direct properties for IRI resource`() {
        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        
        repo.add {
            alice has FOAF.name with "Alice"
            alice has FOAF.age with 30
        }
        
        val cbd = repo.defaultGraph.getCbdClosure(alice)
        
        assertEquals(2, cbd.size, "CBD should contain 2 direct properties")
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.age })
        
        repo.close()
    }

    @Test
    fun `getCbdClosure follows blank nodes recursively`() {
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
        
        val cbd = repo.defaultGraph.getCbdClosure(alice)
        
        assertEquals(6, cbd.size, "CBD should contain all 6 triples")
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.knows && it.obj == b1 })
        assertTrue(cbd.any { it.subject == b1 && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == b1 && it.predicate == FOAF.mbox })
        assertTrue(cbd.any { it.subject == b1 && it.predicate == FOAF.knows && it.obj == b2 })
        assertTrue(cbd.any { it.subject == b2 && it.predicate == FOAF.name })
        
        repo.close()
    }

    @Test
    fun `getCbdClosure does not follow IRI objects`() {
        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        val bob = Iri("http://example.org/bob")
        
        repo.add {
            alice has FOAF.name with "Alice"
            alice has FOAF.knows with bob
            bob has FOAF.name with "Bob"
            bob has FOAF.mbox with "bob@example.com"
        }
        
        val cbd = repo.defaultGraph.getCbdClosure(alice)
        
        assertEquals(2, cbd.size, "CBD should only contain alice's direct properties")
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.knows && it.obj == bob })
        // Bob's properties should NOT be included
        assertFalse(cbd.any { it.subject == bob })
        
        repo.close()
    }

    @Test
    fun `getCbdClosure handles nested blank nodes`() {
        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        val b1 = bnode("b1")
        val b2 = bnode("b2")
        val b3 = bnode("b3")
        
        repo.add {
            alice has FOAF.name with "Alice"
            alice has FOAF.knows with b1
            b1 has FOAF.name with "Bob"
            b1 has FOAF.knows with b2
            b2 has FOAF.name with "Charlie"
            b2 has FOAF.knows with b3
            b3 has FOAF.name with "David"
        }
        
        val cbd = repo.defaultGraph.getCbdClosure(alice)
        
        assertEquals(7, cbd.size, "CBD should contain all nested blank node triples")
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == b1 && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == b2 && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == b3 && it.predicate == FOAF.name })
        
        repo.close()
    }

    @Test
    fun `getCbdClosure prevents cycles with blank nodes`() {
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
        
        val cbd = repo.defaultGraph.getCbdClosure(alice)
        
        // Should contain all triples but not infinite loop
        assertEquals(6, cbd.size, "CBD should contain all triples without infinite loop")
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == b1 && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == b2 && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == b1 && it.predicate == FOAF.knows && it.obj == b2 })
        assertTrue(cbd.any { it.subject == b2 && it.predicate == FOAF.knows && it.obj == b1 })
        
        repo.close()
    }

    @Test
    fun `getCbdClosure works with blank node as root resource`() {
        val repo = Rdf.memory()
        val b1 = bnode("b1")
        val b2 = bnode("b2")
        
        repo.add {
            b1 has FOAF.name with "Bob"
            b1 has FOAF.mbox with "bob@example.com"
            b1 has FOAF.knows with b2
            b2 has FOAF.name with "Charlie"
        }
        
        val cbd = repo.defaultGraph.getCbdClosure(b1)
        
        assertEquals(4, cbd.size, "CBD should contain all blank node triples")
        assertTrue(cbd.any { it.subject == b1 && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == b1 && it.predicate == FOAF.mbox })
        assertTrue(cbd.any { it.subject == b1 && it.predicate == FOAF.knows && it.obj == b2 })
        assertTrue(cbd.any { it.subject == b2 && it.predicate == FOAF.name })
        
        repo.close()
    }

    @Test
    fun `getCbdClosure returns empty set for resource with no properties`() {
        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        
        // No triples added for alice
        
        val cbd = repo.defaultGraph.getCbdClosure(alice)
        
        assertTrue(cbd.isEmpty(), "CBD should be empty for resource with no properties")
        
        repo.close()
    }

    @Test
    fun `getCbdClosure handles mixed IRI and blank node objects`() {
        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        val bob = Iri("http://example.org/bob")
        val b1 = bnode("b1")
        
        repo.add {
            alice has FOAF.name with "Alice"
            alice has FOAF.knows with bob  // IRI object - should not be followed
            alice has FOAF.knows with b1   // Blank node - should be followed
            bob has FOAF.name with "Bob"   // Should not be in CBD
            b1 has FOAF.name with "Anonymous"  // Should be in CBD
        }
        
        val cbd = repo.defaultGraph.getCbdClosure(alice)
        
        assertEquals(4, cbd.size, "CBD should contain alice's properties and b1's properties")
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.knows && it.obj == bob })
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.knows && it.obj == b1 })
        assertTrue(cbd.any { it.subject == b1 && it.predicate == FOAF.name })
        // Bob's properties should NOT be included
        assertFalse(cbd.any { it.subject == bob })
        
        repo.close()
    }

    @Test
    fun `getCbdClosure handles literal objects correctly`() {
        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        
        repo.add {
            alice has FOAF.name with "Alice"
            alice has FOAF.age with 30
            alice has FOAF.mbox with "alice@example.com"
        }
        
        val cbd = repo.defaultGraph.getCbdClosure(alice)
        
        assertEquals(3, cbd.size, "CBD should contain all literal properties")
        assertTrue(cbd.all { it.obj is Literal }, "All objects should be literals")
        
        repo.close()
    }

    @Test
    fun `getCbdClosure is idempotent`() {
        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        val b1 = bnode("b1")
        
        repo.add {
            alice has FOAF.name with "Alice"
            alice has FOAF.knows with b1
            b1 has FOAF.name with "Bob"
        }
        
        val cbd1 = repo.defaultGraph.getCbdClosure(alice)
        val cbd2 = repo.defaultGraph.getCbdClosure(alice)
        
        assertEquals(cbd1.size, cbd2.size, "CBD should be the same on multiple calls")
        assertEquals(cbd1, cbd2, "CBD sets should be equal")
        
        repo.close()
    }

    @Test
    fun `getCbdClosure handles multiple blank nodes at same level`() {
        val repo = Rdf.memory()
        val alice = Iri("http://example.org/alice")
        val b1 = bnode("b1")
        val b2 = bnode("b2")
        
        repo.add {
            alice has FOAF.name with "Alice"
            alice has FOAF.knows with b1
            alice has FOAF.knows with b2
            b1 has FOAF.name with "Bob"
            b2 has FOAF.name with "Charlie"
        }
        
        val cbd = repo.defaultGraph.getCbdClosure(alice)
        
        assertEquals(5, cbd.size, "CBD should contain alice's properties and both blank nodes")
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.knows && it.obj == b1 })
        assertTrue(cbd.any { it.subject == alice && it.predicate == FOAF.knows && it.obj == b2 })
        assertTrue(cbd.any { it.subject == b1 && it.predicate == FOAF.name })
        assertTrue(cbd.any { it.subject == b2 && it.predicate == FOAF.name })
        
        repo.close()
    }
}

