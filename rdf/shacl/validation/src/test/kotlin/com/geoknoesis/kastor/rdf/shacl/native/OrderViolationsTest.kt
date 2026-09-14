package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.vocab.XSD
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `sh:lessThan` / `sh:lessThanOrEquals` pair violations: same pairs as the pairwise definition, fewer comparisons. */
class OrderViolationsTest {

    private fun int(i: Int) = TypedLiteral(i.toString(), XSD.integer)

    private fun pairwise(values: List<RdfTerm>, others: List<RdfTerm>, strict: Boolean) =
        values.flatMap { v -> others.filter { w -> !(if (strict) literalLess(v, w) else literalLessOrEqual(v, w)) }.map { v to it } }

    @Test fun `violations match the pairwise definition for comparable, mixed and incomparable values`() {
        val pool: List<RdfTerm> = listOf(
            int(1), int(5), int(-3), TypedLiteral("2.5", XSD.decimal), TypedLiteral("5.0", XSD.decimal),
            TypedLiteral("3.0E0", XSD.double), TypedLiteral("NaN", XSD.double), TypedLiteral("abc", XSD.integer),
            TypedLiteral("b", XSD.string), TypedLiteral("a", XSD.string), TypedLiteral("true", XSD.boolean),
            TypedLiteral("false", XSD.boolean), LangString("x", "en"), Iri("http://example.org/i"),
            TypedLiteral("2020-01-01", XSD.date), TypedLiteral("2020-01-02Z", XSD.date), TypedLiteral("2020-01-01Z", XSD.date),
        )
        val random = Random(42)
        repeat(2_000) {
            val values = pool.shuffled(random).take(random.nextInt(0, 6))
            val others = pool.shuffled(random).take(random.nextInt(0, 6))
            for (strict in listOf(true, false)) {
                assertEquals(pairwise(values, others, strict).toSet(), orderViolations(values, others, strict).toSet(), "$values vs $others")
                assertEquals(pairwise(values, others, strict).size, orderViolations(values, others, strict).size)
            }
        }
        repeat(500) {
            val values = List(random.nextInt(0, 30)) { int(random.nextInt(-20, 20)) }.distinct()
            val others = List(random.nextInt(0, 30)) { int(random.nextInt(-20, 20)) }.distinct()
            for (strict in listOf(true, false)) {
                assertEquals(pairwise(values, others, strict).toSet(), orderViolations(values, others, strict).toSet())
            }
        }
    }

    @Test fun `totally ordered values need O((n + m) log m + results) comparisons`() {
        val n = 2_000
        val random = Random(7)
        val values = (0 until n).map { int(it) }.shuffled(random)
        val others = (n until 2 * n).map { int(it) }.shuffled(random)
        var comparisons = 0L
        assertTrue(orderViolations(values, others, strict = true) { comparisons++ }.isEmpty())
        // The pairwise definition needs n * m = 4 000 000 comparisons.
        assertTrue(comparisons < 40L * n, "comparisons: $comparisons")

        comparisons = 0L
        val overlapping = values + int(2 * n + 10)
        assertEquals(n, orderViolations(overlapping, others, strict = false) { comparisons++ }.size)
        assertTrue(comparisons < 40L * n, "comparisons: $comparisons")
    }
}
