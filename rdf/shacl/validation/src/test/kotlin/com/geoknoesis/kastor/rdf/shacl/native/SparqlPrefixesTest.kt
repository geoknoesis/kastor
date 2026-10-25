package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class SparqlPrefixesTest {
    private fun ex(local: String) = Iri("http://example.org/$local")

    private val header = """
        @prefix ex: <http://example.org/> .
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
    """.trimIndent()

    private fun collect(body: String): Map<String, String> {
        val index = ShapeGraphIndex(Rdf.parse(header + "\n" + body, RdfFormat.TURTLE).getTriples())
        return SparqlPrefixes.collect(ex("C"), index)
    }

    @Test
    fun `prefixes reach declarations through owl versionIRI and imports`() {
        val result = collect(
            """
            ex:C sh:prefixes ex:v1 .
            ex:G owl:versionIRI ex:v1 ; owl:imports ex:v2 ;
                sh:declare [ sh:prefix "a" ; sh:namespace "http://a/"^^xsd:anyURI ] .
            ex:H owl:versionIRI ex:v2 ; sh:declare [ sh:prefix "b" ; sh:namespace "http://b/" ] .
            """.trimIndent(),
        )
        assertEquals(mapOf("a" to "http://a/", "b" to "http://b/"), result)
    }

    @Test
    fun `sh prefixes values must be IRIs or blank nodes`() {
        assertThrows(ShapeCompileException::class.java) { collect("ex:C sh:prefixes \"x\" .") }
        assertThrows(ShapeCompileException::class.java) { collect("ex:C sh:prefixes ex:P , 1 . ex:P sh:declare [ sh:prefix \"p\" ; sh:namespace \"http://p/\" ] .") }
    }

    @Test
    fun `graph-level declarations apply only without sh prefixes`() {
        val withPrefixes = collect(
            """
            ex:C sh:prefixes ex:P .
            ex:P sh:declare [ sh:prefix "p" ; sh:namespace "http://p/" ] .
            ex:SG a sh:ShapesGraph ; sh:declare [ sh:prefix "z" ; sh:namespace "http://z/" ] .
            """.trimIndent(),
        )
        assertEquals(mapOf("p" to "http://p/"), withPrefixes)

        val without = collect(
            """
            ex:O a owl:Ontology ; sh:declare [ sh:prefix "o" ; sh:namespace "http://o/" ] .
            ex:D a sh:DataGraph ; sh:declare [ sh:prefix "d" ; sh:namespace "http://d/" ] .
            ex:S a sh:ShapesGraph ; sh:declare [ sh:prefix "s" ; sh:namespace "http://s/" ] .
            ex:R a sh:RulesGraph ; sh:declare [ sh:prefix "r" ; sh:namespace "http://r/" ] .
            ex:Mine rdfs:subClassOf sh:ShapesGraph .
            ex:M a ex:Mine ; sh:declare [ sh:prefix "m" ; sh:namespace "http://m/" ] .
            ex:N sh:declare [ sh:prefix "n" ; sh:namespace "http://n/" ] .
            """.trimIndent(),
        )
        assertEquals(
            mapOf("o" to "http://o/", "d" to "http://d/", "s" to "http://s/", "r" to "http://r/", "m" to "http://m/"),
            without,
        )
    }

    @Test
    fun `conflicting namespaces for one prefix are ill-formed`() {
        assertThrows(ShapeCompileException::class.java) {
            collect(
                """
                ex:C sh:prefixes ex:P .
                ex:P sh:declare [ sh:prefix "a" ; sh:namespace "http://a1/" ] , [ sh:prefix "a" ; sh:namespace "http://a2/" ] .
                """.trimIndent(),
            )
        }
        assertThrows(ShapeCompileException::class.java) {
            collect(
                """
                ex:O a owl:Ontology ; sh:declare [ sh:prefix "a" ; sh:namespace "http://a1/" ] .
                ex:S a sh:ShapesGraph ; sh:declare [ sh:prefix "a" ; sh:namespace "http://a2/" ] .
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `prefix and namespace datatypes are checked`() {
        assertThrows(ShapeCompileException::class.java) {
            collect(
                """
                ex:C sh:prefixes ex:P .
                ex:P sh:declare [ sh:prefix "a"^^xsd:token ; sh:namespace "http://a/" ] .
                """.trimIndent(),
            )
        }
        assertThrows(ShapeCompileException::class.java) {
            collect(
                """
                ex:C sh:prefixes ex:P .
                ex:P sh:declare [ sh:prefix "a" ; sh:namespace 5 ] .
                """.trimIndent(),
            )
        }
        assertEquals(
            mapOf("a" to "http://a/", "b" to "http://b/"),
            collect(
                """
                ex:C sh:prefixes ex:P .
                ex:P sh:declare [ sh:prefix "a" ; sh:namespace "http://a/" ] , [ sh:prefix "b" ; sh:namespace "http://b/"^^xsd:anyURI ] .
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `sh prefix must be a SPARQL PN_PREFIX`() {
        fun declaring(label: String) = "ex:C sh:prefixes ex:P .\nex:P sh:declare [ sh:prefix \"$label\" ; sh:namespace \"http://p/\" ] ."
        for (bad in listOf("a\\nb", "a b", "a:b", "a\\\"b", "a'b", "a.", "1a", "_a", "-a", ".a", "a#b", "a<b")) {
            assertThrows(ShapeCompileException::class.java, { collect(declaring(bad)) }, "label '$bad'")
        }
        for (good in listOf("ex", "", "a.b", "a-b_c1", "été", "a·b")) {
            assertEquals(mapOf(good to "http://p/"), collect(declaring(good)), "label '$good'")
        }
    }

    @Test
    fun `graph-level scan is cached per index and stops at the preparation budget`() {
        val ttl = (0 until 300).joinToString("\n") {
            "ex:G$it a sh:ShapesGraph ; sh:declare [ sh:prefix \"p$it\" ; sh:namespace \"http://p$it/\" ] ."
        }
        val triples = Rdf.parse(header + "\n" + ttl, RdfFormat.TURTLE).getTriples()

        val unlimited = ShapeGraphIndex(triples)
        assertEquals(300, SparqlPrefixes.collect(ex("C"), unlimited).size)
        // Computed once and shared by every node without sh:prefixes.
        assertSame(SparqlPrefixes.collect(ex("C"), unlimited), SparqlPrefixes.collect(ex("D"), unlimited))

        val now = AtomicLong()
        val index = ShapeGraphIndex(triples, ValidationBudget(Duration.ofNanos(10)) { now.get() })
        now.set(1_000)
        assertThrows(ShaclValidationException::class.java) { SparqlPrefixes.collect(ex("C"), index) }
    }
}
