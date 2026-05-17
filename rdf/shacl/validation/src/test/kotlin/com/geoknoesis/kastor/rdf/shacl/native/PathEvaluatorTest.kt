package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PathEvaluatorTest {

    private val ex = "http://example.org/ns#"

    @Test
    fun `parallel triples preserve multiset on predicate path`() {
        val a = Iri("${ex}a")
        val o1 = Iri("${ex}o1")
        val o2 = Iri("${ex}o2")
        val g =
            Rdf.graph {
                a - Iri("${ex}p") - o1
                a - Iri("${ex}p") - o2
            }
        val idx = DataGraphIndex(g)
        val path = ShaclPath.Predicate(Iri("${ex}p"))
        val vals = PathEvaluator.evaluate(a, path, idx)
        assertEquals(listOf(o1, o2), vals)
    }

    @Test
    fun `sequence concatenates multisets`() {
        val a = Iri("${ex}a")
        val m1 = Iri("${ex}m1")
        val m2 = Iri("${ex}m2")
        val o1 = Iri("${ex}o1")
        val o2 = Iri("${ex}o2")
        val g =
            Rdf.graph {
                a - Iri("${ex}p1") - m1
                a - Iri("${ex}p1") - m2
                m1 - Iri("${ex}p2") - o1
                m1 - Iri("${ex}p2") - o2
                m2 - Iri("${ex}p2") - o1
                m2 - Iri("${ex}p2") - o2
            }
        val idx = DataGraphIndex(g)
        val path =
            ShaclPath.Sequence(
                listOf(ShaclPath.Predicate(Iri("${ex}p1")), ShaclPath.Predicate(Iri("${ex}p2"))),
            )
        val vals = PathEvaluator.evaluate(a, path, idx)
        assertEquals(4, vals.size)
        assertEquals(mapOf(o1 to 2, o2 to 2), vals.groupingBy { it }.eachCount())
    }

    @Test
    fun `alternative unions option lists preserving option order`() {
        val a = Iri("${ex}a")
        val x = Iri("${ex}x")
        val y = Iri("${ex}y")
        val g =
            Rdf.graph {
                a - Iri("${ex}p") - x
                a - Iri("${ex}q") - y
            }
        val idx = DataGraphIndex(g)
        val path =
            ShaclPath.Alternative(
                listOf(ShaclPath.Predicate(Iri("${ex}p")), ShaclPath.Predicate(Iri("${ex}q"))),
            )
        val vals = PathEvaluator.evaluate(a, path, idx)
        assertEquals(listOf(x, y), vals)
    }

    @Test
    fun `zeroOrMore merges boolean literals equal under SHACL`() {
        val a = Iri("${ex}a")
        val g =
            Rdf.graph {
                a - Iri("${ex}lit") - TrueLiteral
                a - Iri("${ex}lit") - TypedLiteral("true", XSD.boolean)
            }
        val idx = DataGraphIndex(g)
        val path = ShaclPath.ZeroOrMore(ShaclPath.Predicate(Iri("${ex}lit")))
        val vals = PathEvaluator.evaluate(a, path, idx)
        assertEquals(listOf(a, TrueLiteral), vals)
    }

    @Test
    fun `distinctShaclTerms dedups boolean spellings like SHACL equality`() {
        val vals = listOf(TrueLiteral, TypedLiteral("true", XSD.boolean))
        assertEquals(listOf(TrueLiteral), distinctShaclTerms(vals))
        assertEquals(
            shaclRdfTermFingerprint(TrueLiteral),
            shaclRdfTermFingerprint(TypedLiteral("true", XSD.boolean)),
        )
    }
}
