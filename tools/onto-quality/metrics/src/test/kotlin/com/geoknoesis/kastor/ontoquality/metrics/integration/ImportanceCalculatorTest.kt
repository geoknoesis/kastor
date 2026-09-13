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
}
