package com.geoknoesis.kastor.ontoquality.metrics.compute

import com.geoknoesis.kastor.ontoquality.metrics.MetricsConfig
import com.geoknoesis.kastor.rdf.Rdf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Restrictions of a defined class sit inside `owl:intersectionOf` / `owl:unionOf` lists, not directly on the
 * `owl:equivalentClass` node: the property and coupling metrics must follow the lists.
 */
class ClassExpressionListTest {
    private val ns = "http://example.org/ce#"

    private val prefixes =
        """
        @prefix : <$ns> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        :Base a owl:Class . :Topping a owl:Class . :Country a owl:Class . :Spice a owl:Class .
        :hasTopping a owl:ObjectProperty . :hasOrigin a owl:ObjectProperty . :hasSpice a owl:ObjectProperty .
        """.trimIndent()

    private fun scan(body: String): IntermediateQuantities =
        GraphScanner.scan(Rdf.parse(prefixes + "\n" + body.trimIndent(), "TURTLE"), MetricsConfig()).intermediate

    @Test
    fun `restrictions inside an intersection of a defined class count for properties and coupling`() {
        val iq =
            scan(
                """
                :Defined a owl:Class ; owl:equivalentClass [
                    a owl:Class ;
                    owl:intersectionOf (
                        :Base
                        [ a owl:Restriction ; owl:onProperty :hasTopping ; owl:someValuesFrom :Topping ]
                        [ a owl:Restriction ; owl:onProperty :hasOrigin ; owl:allValuesFrom :Country ]
                    )
                ] .
                """,
            )
        assertEquals(setOf("${ns}hasTopping", "${ns}hasOrigin"), iq.propertiesOfClass["${ns}Defined"])
        assertEquals(setOf("${ns}Topping", "${ns}Country"), iq.couplingsOf["${ns}Defined"])
        assertEquals(2L, iq.propertyUsageCount)
    }

    @Test
    fun `nested unions and intersections are followed, and a list cycle terminates`() {
        val iq =
            scan(
                """
                :Nested a owl:Class ; rdfs:subClassOf [
                    a owl:Class ;
                    owl:unionOf (
                        [ a owl:Class ; owl:intersectionOf (
                            [ a owl:Restriction ; owl:onProperty :hasSpice ; owl:someValuesFrom :Spice ]
                            :Base
                        ) ]
                        [ a owl:Restriction ; owl:onProperty :hasTopping ; owl:someValuesFrom :Topping ]
                    )
                ] .
                :Looping a owl:Class ; rdfs:subClassOf _:expr .
                _:expr owl:intersectionOf _:cell .
                _:cell <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> _:expr ;
                       <http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> _:cell .
                """,
            )
        assertEquals(setOf("${ns}hasSpice", "${ns}hasTopping"), iq.propertiesOfClass["${ns}Nested"])
        assertEquals(setOf("${ns}Spice", "${ns}Topping"), iq.couplingsOf["${ns}Nested"])
        assertEquals(null, iq.propertiesOfClass["${ns}Looping"])
    }

    @Test
    fun `a direct restriction is still counted once`() {
        val iq =
            scan(
                """
                :Direct a owl:Class ; rdfs:subClassOf
                    [ a owl:Restriction ; owl:onProperty :hasTopping ; owl:someValuesFrom :Topping ] .
                """,
            )
        assertEquals(setOf("${ns}hasTopping"), iq.propertiesOfClass["${ns}Direct"])
        assertEquals(setOf("${ns}Topping"), iq.couplingsOf["${ns}Direct"])
    }
}
