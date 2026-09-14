package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.`var`
import com.geoknoesis.kastor.rdf.toLiteral
import com.geoknoesis.kastor.rdf.vocab.SHACL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `sh:closed`, `sh:severity` and `sh:deactivated` allow a single value: repeated DSL calls replace it. */
class ShaclDslSingleValueParametersTest {

    private fun List<RdfTriple>.valuesOf(subject: Iri, predicate: Iri) = filter { it.subject == subject && it.predicate == predicate }.map { it.obj }

    @Test
    fun `node shape closed severity and deactivated keep only the last value`() {
        val shape = Iri("http://example.org/S")
        val triples = shacl {
            nodeShape(shape.value) {
                closed(true)
                closed(false)
                severity(Severity.Warning)
                severity(Severity.Info)
                deactivated(true)
                deactivated(false)
            }
        }.getTriples().toList()
        assertEquals(listOf(false.toLiteral()), triples.valuesOf(shape, SHACL.closed))
        assertEquals(listOf(SHACL.Info), triples.valuesOf(shape, SHACL.severity))
        assertEquals(listOf(false.toLiteral()), triples.valuesOf(shape, SHACL.deactivated))
    }

    @Test
    @Suppress("DEPRECATION_ERROR")
    fun `deprecated digit setters fail loudly instead of silently dropping the limit`() {
        val total = assertThrows(UnsupportedOperationException::class.java) {
            shacl { nodeShape("http://example.org/S") { property("http://example.org/p") { totalDigits = 3 } } }
        }
        val fraction = assertThrows(UnsupportedOperationException::class.java) {
            shacl { nodeShape("http://example.org/S") { property("http://example.org/p") { fractionDigits = 2 } } }
        }
        for (error in listOf(total, fraction)) assertTrue(error.message.orEmpty().contains("pattern"), error.message)
    }

    @Test
    fun `property shape and sparql constraint severity and deactivated keep only the last value`() {
        val triples = shacl {
            nodeShape("http://example.org/S") {
                property("http://example.org/p") {
                    severity(Severity.Warning)
                    severity(Severity.Violation)
                    deactivated(true)
                    deactivated(false)
                }
                sparql(configureQuery = {
                    where { triple(`var`("this"), Iri("http://example.org/q"), `var`("o")) }
                }) {
                    severity(Severity.Warning)
                    severity(Severity.Info)
                    deactivated(true)
                    deactivated(false)
                }
            }
        }.getTriples().toList()
        assertEquals(setOf(SHACL.Violation, SHACL.Info), triples.filter { it.predicate == SHACL.severity }.map { it.obj }.toSet())
        assertEquals(2, triples.count { it.predicate == SHACL.severity })
        assertEquals(listOf(false.toLiteral(), false.toLiteral()), triples.filter { it.predicate == SHACL.deactivated }.map { it.obj })
    }
}
