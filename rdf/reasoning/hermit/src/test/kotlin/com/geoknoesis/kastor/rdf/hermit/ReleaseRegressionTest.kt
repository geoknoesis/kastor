package com.geoknoesis.kastor.rdf.hermit

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.reasoning.ReasonerConfig
import org.junit.jupiter.api.Test
import kotlin.test.*

class ReleaseRegressionTest {
    @Test fun `classification extracts hierarchy without exporting inferred triples`() {
        val graph = Rdf.parse("""
            @prefix ex: <urn:ex:> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            ex:A a owl:Class ; rdfs:subClassOf ex:B .
            ex:B a owl:Class .
            ex:i a owl:NamedIndividual, ex:A .
        """.trimIndent(), RdfFormat.TURTLE)
        // A one-triple materialization budget must not restrict consistency or classification.
        val engine = HermitRdfReasoner(ReasonerConfig.hermit().copy(materializationThreshold = 1))
        assertTrue(engine.isConsistent(graph))
        val classification = engine.classify(graph)
        assertTrue(Iri("urn:ex:B") in classification.classHierarchy.getValue(Iri("urn:ex:A")))
        assertTrue(Iri("urn:ex:B") in classification.instanceClassifications.getValue(Iri("urn:ex:i")))
    }
}
