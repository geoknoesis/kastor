package com.geoknoesis.kastor.gen.runtime

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaterializationSafetyTest {

    private val knows = Iri("http://xmlns.com/foaf/0.1/knows")

    /** Eager immutable snapshot, shaped like a `NestedMode.DATA_CLASS` factory output. */
    data class PersonSnapshot(val id: String, val knows: List<PersonSnapshot>) : RdfProjection

    @AfterEach
    fun cleanup() {
        OntoMapper.unregister(PersonSnapshot::class.java)
    }

    private fun registerEagerFactory() {
        OntoMapper.register(PersonSnapshot::class.java, replace = true) { handle ->
            PersonSnapshot(
                id = handle.node.toString(),
                knows = KastorGraphOps.getObjectValues(handle.graph, handle.node, knows) { child ->
                    OntoMapper.materialize(RdfRef(child, handle.graph), PersonSnapshot::class.java)
                },
            )
        }
    }

    @Test
    fun `cyclic eager snapshot fails with a clear MaterializationException instead of overflowing the stack`() {
        registerEagerFactory()
        val a = Iri("urn:a")
        val b = Iri("urn:b")
        val graph = MemoryGraph()
        graph.addTriple(RdfTriple(a, knows, b))
        graph.addTriple(RdfTriple(b, knows, a))

        val e = assertFailsWith<MaterializationException> {
            OntoMapper.materialize(RdfRef(a, graph), PersonSnapshot::class.java)
        }
        assertTrue(e.message!!.contains("Cyclic reference"), e.message)
        assertTrue(e.message!!.contains("urn:a") && e.message!!.contains("urn:b"), e.message)

        // The scope is cleaned up: an acyclic graph still materializes on the same thread.
        val acyclic = MemoryGraph()
        acyclic.addTriple(RdfTriple(a, knows, b))
        val person = OntoMapper.materialize(RdfRef(a, acyclic), PersonSnapshot::class.java)
        assertEquals(listOf("urn:b"), person.knows.map { it.id.removePrefix("<").removeSuffix(">") })
    }

    @Test
    fun `shared nested node is materialized once per outer call`() {
        var builds = 0
        OntoMapper.register(PersonSnapshot::class.java, replace = true) { handle ->
            builds++
            PersonSnapshot(
                id = handle.node.toString(),
                knows = KastorGraphOps.getObjectValues(handle.graph, handle.node, knows) { child ->
                    OntoMapper.materialize(RdfRef(child, handle.graph), PersonSnapshot::class.java)
                },
            )
        }
        val graph = MemoryGraph()
        val root = Iri("urn:root")
        val shared = Iri("urn:shared")
        graph.addTriple(RdfTriple(root, knows, Iri("urn:x")))
        graph.addTriple(RdfTriple(root, knows, Iri("urn:y")))
        graph.addTriple(RdfTriple(Iri("urn:x"), knows, shared))
        graph.addTriple(RdfTriple(Iri("urn:y"), knows, shared))
        val person = OntoMapper.materialize(RdfRef(root, graph), PersonSnapshot::class.java)
        assertEquals(4, builds)
        assertSame(person.knows[0].knows[0], person.knows[1].knows[0])
    }

    @Test
    fun `factory failures propagate with context instead of silently dropping values`() {
        val graph = MemoryGraph()
        val s = Iri("urn:s")
        graph.addTriple(RdfTriple(s, knows, Iri("urn:o")))
        val e = assertFailsWith<MaterializationException> {
            KastorGraphOps.getObjectValues(graph, s, knows) { throw NumberFormatException("bad number") }
        }
        assertTrue(e.message!!.contains("http://xmlns.com/foaf/0.1/knows"), e.message)
        assertTrue(e.cause is NumberFormatException)
    }

    @Test
    fun `xsd codecs follow XML Schema lexical rules`() {
        assertEquals(true, XsdLiterals.boolean(TypedLiteral("1", XSD.boolean)))
        assertEquals(false, XsdLiterals.boolean(TypedLiteral("0", XSD.boolean)))
        assertNull(XsdLiterals.boolean(TypedLiteral("yes", XSD.boolean)))
        assertEquals(BigInteger("123456789012345678901234567890"), XsdLiterals.bigInteger(TypedLiteral("123456789012345678901234567890", XSD.integer)))
        assertEquals(BigDecimal("1.50"), XsdLiterals.bigDecimal(TypedLiteral("1.50", XSD.decimal)))
        assertEquals(Double.POSITIVE_INFINITY, XsdLiterals.double(TypedLiteral("INF", XSD.double)))
        assertNull(XsdLiterals.double(TypedLiteral("Infinity", XSD.double)))
        assertEquals(1.5f, XsdLiterals.float(TypedLiteral("1.5", XSD.float)))
        assertEquals(LocalDate.of(2024, 2, 29), XsdLiterals.localDate(TypedLiteral("2024-02-29Z", XSD.date)))
        assertEquals(LangString("hi", "en"), XsdLiterals.langString(LangString("hi", "en")))

        assertEquals(TypedLiteral("INF", XSD.float), XsdLiterals.encode(Float.POSITIVE_INFINITY, XSD.float))
        assertEquals(TypedLiteral("7", XSD.int), XsdLiterals.encode(7, XSD.int))
        assertEquals(TypedLiteral("0.000001", XSD.decimal), XsdLiterals.encode(BigDecimal("0.000001"), XSD.decimal))
        assertEquals(Literal(true), XsdLiterals.encode(true, XSD.boolean))
        assertEquals(LangString("hi", "en"), XsdLiterals.encode(LangString("hi", "en"), XSD.string))
        assertEquals(TypedLiteral("P1D", Iri("http://www.w3.org/2001/XMLSchema#duration")), XsdLiterals.encode("P1D", Iri("http://www.w3.org/2001/XMLSchema#duration")))
    }
}
