package com.geoknoesis.kastor.ontoquality.metrics.integration

import com.geoknoesis.kastor.rdf.Rdf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ImportanceCalculatorTest {
    private val ns = "http://example.org/imp#"

    @Test
    fun `descendant hints count shared and cyclic descendants exactly once`() {
        // Top ⊒ A, B; A, B ⊒ Shared; Shared ⊒ Leaf; X <-> Y cycle under Top.
        val ttl =
            """
            @prefix : <$ns> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            :Top a owl:Class ; rdfs:label "Top" .
            :A rdfs:subClassOf :Top .
            :B rdfs:subClassOf :Top .
            :Shared rdfs:subClassOf :A , :B .
            :Leaf rdfs:subClassOf :Shared .
            :X rdfs:subClassOf :Top , :Y .
            :Y rdfs:subClassOf :X .
            :p a owl:ObjectProperty ; rdfs:domain :A ; rdfs:range :A .
            """.trimIndent()
        val context = KastorMetricsProvider().compute(Rdf.parse(ttl, "TURTLE"))
        val hints = context.entityHints
        // Top: A, B, Shared, Leaf, X, Y (Shared counted once despite two routes)
        assertEquals("3 direct subclasses; 6 transitive descendants", hints.getValue("${ns}Top"))
        assertEquals(
            "1 direct subclasses; 2 transitive descendants; referenced by 2 properties (domain/range)",
            hints.getValue("${ns}A"),
        )
        // Cycle members reach each other (and themselves), matching the traversal semantics.
        assertEquals("1 direct subclasses; 2 transitive descendants", hints.getValue("${ns}X"))
        assertEquals("no subclasses in asserted hierarchy", hints.getValue("${ns}Leaf"))
        // Label signal only for Top.
        val top = context.entityImportance.getValue("${ns}Top")
        val leaf = context.entityImportance.getValue("${ns}Leaf")
        kotlin.test.assertTrue(top > leaf)
    }

    @Test
    fun `cycle participants get a neutral shallow-depth signal instead of ranking like roots`() {
        // Root (depth 1) -> Mid (2) -> Deep (3); X <-> Y is a cycle with no acyclic superclass.
        val ttl =
            """
            @prefix : <$ns> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            :Root a owl:Class .
            :Mid rdfs:subClassOf :Root .
            :Deep rdfs:subClassOf :Mid .
            :X rdfs:subClassOf :Y .
            :Y rdfs:subClassOf :X .
            """.trimIndent()
        val shallowOnly =
            com.geoknoesis.kastor.ontoquality.metrics.ImportanceWeights(
                fanOutWeight = 0.0,
                incomingPropertiesWeight = 0.0,
                shallowDepthWeight = 1.0,
                labelPresenceWeight = 0.0,
            )
        val importance = KastorMetricsProvider(importanceWeights = shallowOnly).compute(Rdf.parse(ttl, "TURTLE")).entityImportance
        assertEquals(1.0, importance.getValue("${ns}Root"), 1e-12)
        assertEquals(0.5, importance.getValue("${ns}Mid"), 1e-12)
        assertEquals(1.0 / 3.0, importance.getValue("${ns}Deep"), 1e-12)
        // Previously depth 0 gave 1 / max(1, 0) = 1, as central as a root; now the documented neutral 0.5.
        assertEquals(0.5, importance.getValue("${ns}X"), 1e-12)
        assertEquals(0.5, importance.getValue("${ns}Y"), 1e-12)
        assertEquals(0.5, CYCLE_PARTICIPANT_SHALLOW_SIGNAL, 0.0)
    }
}
