package com.geoknoesis.kastor.rdf.dsl

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.sparql.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.SHACL
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Regression tests checking that the DSL emits spec-conformant SHACL structures
 * (RDF lists, prefix declarations, single-valued parameters, unique blank nodes).
 */
class ShaclDslConformanceTest {

    private val ex = "http://example.org/"
    private fun iri(local: String) = Iri(ex + local)

    private fun MutableRdfGraph.all(): List<RdfTriple> = getTriples().toList()

    private fun List<RdfTriple>.objectsOf(s: RdfTerm, p: Iri): List<RdfTerm> =
        filter { it.subject == s && it.predicate == p }.map { it.obj }

    /** Parses a well-formed RDF list (exactly one rdf:first / rdf:rest per cell, ending in rdf:nil). */
    private fun List<RdfTriple>.parseList(head: RdfTerm): List<RdfTerm> {
        val out = mutableListOf<RdfTerm>()
        var node = head
        val seen = mutableSetOf<RdfTerm>()
        while (node != RDF.nil) {
            assertTrue(node is BlankNode, "list cell must be a blank node: $node")
            assertTrue(seen.add(node), "cyclic list")
            val firsts = objectsOf(node, RDF.first)
            val rests = objectsOf(node, RDF.rest)
            assertEquals(1, firsts.size, "list cell $node must have exactly one rdf:first")
            assertEquals(1, rests.size, "list cell $node must have exactly one rdf:rest")
            out += firsts.single()
            node = rests.single()
        }
        return out
    }

    // ---- 10a: sh:and / sh:or / sh:xone take ONE RDF list of shapes ----

    @Test
    fun `and or xone with shape references emit a single RDF list`() {
        val g = shacl {
            nodeShape(ex + "S") {
                and(listOf(iri("A"), iri("B")))
                or(ex + "C", ex + "D")
                xone(listOf(iri("E"), iri("F"), iri("G")))
            }
        }.all()
        val s = iri("S")
        val ands = g.objectsOf(s, SHACL.and)
        assertEquals(1, ands.size)
        assertEquals(listOf(iri("A"), iri("B")), g.parseList(ands.single()))
        val ors = g.objectsOf(s, SHACL.or)
        assertEquals(1, ors.size)
        assertEquals(listOf(iri("C"), iri("D")), g.parseList(ors.single()))
        val xones = g.objectsOf(s, SHACL.xone)
        assertEquals(1, xones.size)
        assertEquals(listOf(iri("E"), iri("F"), iri("G")), g.parseList(xones.single()))
    }

    @Test
    fun `block form of and emits a one-element list holding the operand shape`() {
        val g = shacl {
            nodeShape(ex + "S") {
                and {
                    property(ex + "name") { minCount = 1 }
                }
            }
        }.all()
        val ands = g.objectsOf(iri("S"), SHACL.and)
        assertEquals(1, ands.size)
        val members = g.parseList(ands.single())
        assertEquals(1, members.size)
        val operand = members.single()
        assertTrue(g.any { it.subject == operand && it.predicate == RDF.type && it.obj == SHACL.NodeShape })
        assertEquals(1, g.objectsOf(operand, SHACL.property).size)
    }

    // ---- 10b: languageDirection must not add a pattern that rejects every value ----

    @Test
    fun `languageDirection does not emit a bogus sh pattern`() {
        val g = shacl {
            nodeShape(ex + "S") {
                property(ex + "label") {
                    @Suppress("DEPRECATION")
                    languageDirection(Direction.RTL)
                }
            }
        }.all()
        assertFalse(g.any { it.predicate == SHACL.pattern }, "no sh:pattern expected")
        assertEquals(1, g.count { it.predicate == SHACL.datatype && it.obj == RDF.dirLangString })
    }

    // ---- 10c: blank node labels are unique across independently built graphs ----

    @Test
    fun `independently built shapes graphs share no blank node labels`() {
        fun build() = shacl {
            nodeShape(ex + "S") {
                property(ex + "p") { `in`("a", "b") }
                node { property(ex + "q") { minCount = 1 } }
            }
        }.all()
        fun bnodes(ts: List<RdfTriple>) =
            ts.flatMap { listOf(it.subject, it.obj) }.filterIsInstance<BlankNode>().map { it.id }.toSet()
        val a = bnodes(build())
        val b = bnodes(build())
        assertTrue(a.isNotEmpty())
        assertEquals(emptySet<String>(), a intersect b)
    }

    // ---- 19a: SPARQL-based constraints only support SELECT ----

    @Test
    fun `sparql constraint rejects ASK queries`() {
        val ask = AskBuilder().apply {
            where { triple(`var`("this"), Iri(ex + "valid"), true.toLiteral()) }
        }.build()
        assertThrows<IllegalArgumentException> {
            shacl { nodeShape(ex + "S") { sparql(ask) } }
        }
        assertThrows<IllegalArgumentException> {
            shacl { nodeShape(ex + "S") { property(ex + "p") { sparql(ask) } } }
        }
    }

    @Test
    fun `sparql constraint rejects CONSTRUCT and DESCRIBE queries`() {
        val construct = ConstructBuilder().apply {
            where { triple(`var`("this"), `var`("p"), `var`("o")) }
        }.build()
        val describe = DescribeBuilder(listOf(Iri(ex + "x"))).build()
        assertThrows<IllegalArgumentException> { shacl { nodeShape(ex + "S") { sparql(construct) } } }
        assertThrows<IllegalArgumentException> { shacl { nodeShape(ex + "S") { sparql(describe) } } }
    }

    // ---- 19b: sh:prefixes points at a node with sh:declare entries ----

    @Test
    fun `sparql prefixes emit sh declare entries`() {
        val g = shacl {
            nodeShape(ex + "S") {
                sparql(configureQuery = {
                    where { triple(`var`("this"), `var`("p"), `var`("o")) }
                }, configureConstraint = {
                    prefixes {
                        put("foaf", "http://xmlns.com/foaf/0.1/")
                        put("ex", ex)
                    }
                })
            }
        }.all()
        val constraint = g.objectsOf(iri("S"), SHACL.sparql).single()
        val declNodes = g.objectsOf(constraint, SHACL.prefixes)
        assertEquals(1, declNodes.size)
        assertFalse(g.any { it.subject == declNodes.single() && it.predicate == RDF.first })
        val declare = Iri("http://www.w3.org/ns/shacl#declare")
        val shPrefix = Iri("http://www.w3.org/ns/shacl#prefix")
        val shNamespace = Iri("http://www.w3.org/ns/shacl#namespace")
        val entries = g.objectsOf(declNodes.single(), declare)
        assertEquals(2, entries.size)
        val decoded = entries.associate { e ->
            val p = g.objectsOf(e, shPrefix).single() as Literal
            val ns = g.objectsOf(e, shNamespace).single() as Literal
            assertEquals(XSD.string, p.datatype)
            assertEquals(XSD.anyURI, ns.datatype)
            p.lexical to ns.lexical
        }
        assertEquals(mapOf("foaf" to "http://xmlns.com/foaf/0.1/", "ex" to ex), decoded)
    }

    // ---- 19c: sh:ignoredProperties is a single RDF list ----

    @Test
    fun `ignoredProperties emits a single RDF list`() {
        val g = shacl {
            nodeShape(ex + "S") {
                closed(true)
                ignoredProperties(ex + "a", ex + "b")
            }
            nodeShape(ex + "T") {
                closed(true)
                ignoredProperties(listOf(RDF.type))
            }
        }.all()
        val s = g.objectsOf(iri("S"), SHACL.ignoredProperties)
        assertEquals(1, s.size)
        assertEquals(listOf(iri("a"), iri("b")), g.parseList(s.single()))
        val t = g.objectsOf(iri("T"), SHACL.ignoredProperties)
        assertEquals(listOf(RDF.type), g.parseList(t.single()))
    }

    // ---- 19e / 19f: single-valued properties are readable and replace on re-assignment ----

    @Test
    fun `property getters return the last assigned value`() {
        shacl {
            nodeShape(ex + "S") {
                property(ex + "p") {
                    assertNull(minCount)
                    minCount = 1
                    maxCount = 3
                    pattern = "^a"
                    datatype = XSD.string
                    nodeKind = NodeKind.Literal
                    languageIn = listOf("en")
                    uniqueLang = true
                    assertEquals(1, minCount)
                    assertEquals(3, maxCount)
                    assertEquals("^a", pattern)
                    assertEquals(XSD.string, datatype)
                    assertEquals(NodeKind.Literal, nodeKind)
                    assertEquals(listOf("en"), languageIn)
                    assertEquals(true, uniqueLang)
                    assertEquals(iri("p"), path)
                }
            }
        }
    }

    @Test
    fun `re-assigning a single-valued property replaces the triple and null removes it`() {
        val g = shacl {
            nodeShape(ex + "S") {
                property(ex + "p") {
                    minCount = 1
                    minCount = 2
                    maxCount = 5
                    maxCount = null
                    datatype = XSD.string
                    datatype = XSD.integer
                    languageIn = listOf("en", "fr")
                    languageIn = listOf("de")
                    `in` = listOf(string("a"), string("b"))
                    `in` = listOf(string("c"))
                    path = Iri(ex + "q")
                }
            }
        }.all()
        assertEquals(listOf<RdfTerm>(2.toLiteral()), g.filter { it.predicate == SHACL.minCount }.map { it.obj })
        assertTrue(g.none { it.predicate == SHACL.maxCount })
        assertEquals(listOf<RdfTerm>(XSD.integer), g.filter { it.predicate == SHACL.datatype }.map { it.obj })
        assertEquals(listOf<RdfTerm>(iri("q")), g.filter { it.predicate == SHACL.path }.map { it.obj })
        val langIn = g.filter { it.predicate == SHACL.languageIn }
        assertEquals(1, langIn.size)
        assertEquals(listOf<RdfTerm>(string("de")), g.parseList(langIn.single().obj))
        val inValues = g.filter { it.predicate == SHACL.`in` }
        assertEquals(1, inValues.size)
        assertEquals(listOf<RdfTerm>(string("c")), g.parseList(inValues.single().obj))
        // The replaced list cells must be gone as well: only the two surviving one-element lists remain.
        assertEquals(2, g.count { it.predicate == RDF.first })
    }

    // ---- 10a: operand-collecting blocks ----

    @Test
    fun `orShapes collects several operand shapes into one list`() {
        val g = shacl {
            nodeShape(ex + "S") {
                orShapes {
                    shape { property(ex + "phone") { minCount = 1 } }
                    shape { property(ex + "mobile") { minCount = 1 } }
                    shape(ex + "EmailShape")
                }
                andShapes { shape(iri("A")) }
                xoneShapes {
                    shape(iri("X"))
                    shape(iri("Y"))
                }
            }
        }.all()
        val ors = g.objectsOf(iri("S"), SHACL.or)
        assertEquals(1, ors.size)
        val members = g.parseList(ors.single())
        assertEquals(3, members.size)
        members.take(2).forEach { m ->
            assertTrue(m is BlankNode)
            assertTrue(g.any { it.subject == m && it.predicate == RDF.type && it.obj == SHACL.NodeShape })
            assertEquals(1, g.objectsOf(m, SHACL.property).size)
        }
        assertEquals(iri("EmailShape"), members[2])
        assertEquals(listOf(iri("A")), g.parseList(g.objectsOf(iri("S"), SHACL.and).single()))
        assertEquals(listOf(iri("X"), iri("Y")), g.parseList(g.objectsOf(iri("S"), SHACL.xone).single()))
    }

    // ---- 19a: deprecated sparqlAsk throws ----

    @Test
    @Suppress("DEPRECATION_ERROR")
    fun `sparqlAsk throws on property shapes too`() {
        assertThrows<IllegalArgumentException> {
            shacl {
                nodeShape(ex + "S") {
                    property(ex + "p") { sparqlAsk(configureQuery = { where { } }) }
                }
            }
        }
    }

    // ---- 19d: bounds accept any number and literal bounds ----

    @Test
    fun `numeric bounds emit the XSD datatype matching the Kotlin number type`() {
        val g = shacl {
            nodeShape(ex + "S") {
                property(ex + "int") { minInclusive = 0; maxInclusive = 150L }
                property(ex + "dec") { minExclusive = java.math.BigDecimal("0.5"); maxExclusive = java.math.BigInteger.TEN }
                property(ex + "dbl") { minInclusive = 1.5; maxInclusive = 2.5f }
            }
        }.all()
        fun bound(p: Iri) = g.filter { it.predicate == p }.map { it.obj as Literal }
        assertEquals(
            setOf(Literal("0", XSD.integer), Literal("1.5", XSD.double)),
            bound(SHACL.minInclusive).toSet(),
        )
        assertEquals(
            setOf(Literal("150", XSD.integer), Literal("2.5", XSD.float)),
            bound(SHACL.maxInclusive).toSet(),
        )
        assertEquals(listOf(Literal("0.5", XSD.decimal)), bound(SHACL.minExclusive))
        assertEquals(listOf(Literal("10", XSD.integer)), bound(SHACL.maxExclusive))
    }

    @Test
    fun `literal bounds such as xsd date are supported and replace numeric bounds`() {
        val date = Literal("2020-01-01", XSD.date)
        lateinit var dsl: PropertyShapeDsl
        val g = shacl {
            nodeShape(ex + "S") {
                property(ex + "born") {
                    dsl = this
                    minInclusive = 5
                    assertEquals(5, minInclusive)
                    minInclusive(date)
                    maxExclusive(java.time.LocalDate.of(2030, 1, 1).toLiteral())
                }
            }
        }.all()
        assertNull(dsl.minInclusive, "Number getter returns null for a non-numeric bound")
        assertEquals(listOf<RdfTerm>(date), g.filter { it.predicate == SHACL.minInclusive }.map { it.obj })
        assertEquals(
            listOf<RdfTerm>(Literal("2030-01-01", XSD.date)),
            g.filter { it.predicate == SHACL.maxExclusive }.map { it.obj },
        )
    }

    // ---- 19f: vars replace, additive functions stay additive ----

    @Test
    fun `datatype and path string functions share the single-valued slot while class function is additive`() {
        val g = shacl {
            nodeShape(ex + "S") {
                property(ex + "p") {
                    datatype = XSD.string
                    datatype("xsd:integer")
                    path(ex + "q")
                    `class`(ex + "A")
                    `class`(ex + "B")
                    assertEquals(XSD.integer, datatype)
                    assertEquals(iri("q"), path)
                }
            }
        }.all()
        assertEquals(listOf<RdfTerm>(XSD.integer), g.filter { it.predicate == SHACL.datatype }.map { it.obj })
        assertEquals(listOf<RdfTerm>(iri("q")), g.filter { it.predicate == SHACL.path }.map { it.obj })
        assertEquals(2, g.count { it.predicate == SHACL.`class` })
    }

    // ---- KDoc example at the top of ShaclDsl.kt compiles and produces the expected shape ----

    @Test
    fun `kdoc example compiles`() {
        val foafPerson = com.geoknoesis.kastor.rdf.vocab.FOAF.Person
        val shapesGraph = shacl {
            nodeShape("http://example.org/PersonShape") {
                targetClass(foafPerson)

                property(com.geoknoesis.kastor.rdf.vocab.FOAF.name) {
                    minCount = 1
                    maxCount = 1
                    datatype = XSD.string
                    minLength = 1
                    maxLength = 100
                }

                property(com.geoknoesis.kastor.rdf.vocab.FOAF.age) {
                    minCount = 0
                    maxCount = 1
                    datatype = XSD.integer
                    minInclusive = 0
                    maxInclusive = 150
                }
            }
        }.all()
        assertTrue(shapesGraph.any { it.predicate == SHACL.maxInclusive && it.obj == Literal("150", XSD.integer) })
        assertEquals(2, shapesGraph.count { it.predicate == SHACL.property })
    }
}
