package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.reasoning.RdfReasoning
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import com.geoknoesis.kastor.rdf.reasoning.ReasonerRegistry
import com.geoknoesis.kastor.rdf.reasoning.ReasonerType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class HermitLifecycleTest {

    private val ontology = """
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix ex: <http://example.org/ns#> .
        ex:A a owl:Class .
        ex:B a owl:Class .
        ex:C a owl:Class ; owl:equivalentClass [ a owl:Class ; owl:intersectionOf ( ex:A ex:B ) ] .
        ex:D a owl:Class ; rdfs:subClassOf ex:A .
        ex:i a owl:NamedIndividual , ex:C .
    """.trimIndent()

    @Test
    fun `HermiT is constructible through the registry by type`() {
        val byType = RdfReasoning.reasoner(ReasonerType.HERMIT)
        assertTrue(byType is HermitRdfReasoner)
        assertTrue(RdfReasoning.reasoner(ReasonerConfig(reasonerType = ReasonerType.HERMIT)) is HermitRdfReasoner)
        assertTrue(RdfReasoning.reasoner(ReasonerConfig.owlDl()) is HermitRdfReasoner, "OWL_DL must have a provider")
        assertEquals("hermit", ReasonerRegistry.findProviderForType(ReasonerType.OWL_DL)?.getType())
    }

    @Test
    fun `OWL Micro is rejected with a clear error`() {
        val provider = HermitReasonerProvider()
        assertFalse(provider.isSupported(ReasonerType.OWL_MICRO))
        val error = assertThrows(IllegalArgumentException::class.java) {
            provider.createReasoner(ReasonerConfig(reasonerType = ReasonerType.OWL_MICRO))
        }
        assertTrue(error.message!!.contains("OWL_MICRO"), error.message)
    }

    @Test
    fun `inferred triples exclude asserted axioms, headers, declarations and blank nodes`() {
        val graph = Rdf.parse(ontology, RdfFormat.TURTLE)
        val inferred = HermitRdfReasoner(ReasonerConfig.hermit()).reason(graph).inferredTriples
        val ns = "http://example.org/ns#"
        val type = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
        assertTrue(RdfTriple(Iri(ns + "i"), type, Iri(ns + "A")) in inferred, "$inferred")
        assertTrue(RdfTriple(Iri(ns + "i"), type, Iri(ns + "B")) in inferred, "$inferred")
        assertTrue(inferred.none { it.subject is BlankNode || it.obj is BlankNode }, "no blank nodes: $inferred")
        assertTrue(inferred.none { it.obj == Iri("http://www.w3.org/2002/07/owl#Ontology") }, "no ontology header: $inferred")
        assertTrue(inferred.none { it.obj == Iri("http://www.w3.org/2002/07/owl#Class") }, "no declaration triples: $inferred")
        val asserted = graph.getTriples().toSet()
        assertTrue(inferred.none { it in asserted }, "asserted triples are not inferred")
        assertTrue(inferred.none { it.subject == it.obj }, "no reflexive subclass axioms")
    }

    @Test
    fun `an exhausted timeout fails and leaves no watchdog thread behind`() {
        val graph = Rdf.parse(ontology, RdfFormat.TURTLE)
        val reasoner = HermitRdfReasoner(ReasonerConfig.hermit().copy(timeout = Duration.ofMillis(1)))
        val error = assertThrows(IllegalStateException::class.java) { reasoner.reason(graph) }
        assertTrue(error.message!!.contains("timed out"), error.message)
        // The watchdog is shut down in all cases.
        Thread.sleep(50)
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "kastor-hermit-watchdog" && it.isAlive })
    }

    @Test
    fun `successful runs shut the watchdog down`() {
        val graph = Rdf.parse(ontology, RdfFormat.TURTLE)
        repeat(3) { HermitRdfReasoner(ReasonerConfig.hermit()).isConsistent(graph) }
        Thread.sleep(50)
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "kastor-hermit-watchdog" && it.isAlive })
    }
}
