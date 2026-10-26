package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.LiteralValidation
import org.apache.jena.datatypes.RDFDatatype
import org.apache.jena.graph.Node
import org.apache.jena.query.QueryParseException
import org.apache.jena.riot.RDFParserBuilder
import org.apache.jena.riot.RiotException
import org.apache.jena.riot.system.FactoryRDFCaching

/**
 * Bounds what a literal may cost Jena. Jena computes the value of every `xsd:decimal` literal, and of every literal
 * of `xsd:integer` and the types derived from it, when it creates the node, with `BigInteger` / `BigDecimal`, in time
 * quadratic in the number of digits (measured with Jena 6.2 on JDK 21: 0.7 s for 200,000 digits, 12 s for 800,000; a
 * graph parse pays it twice). A document of a few megabytes could hold the CPU for hours. The limit is the one both
 * providers apply, [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH]:
 * - every Kastor parse ([JenaParsing.parser], `JenaBridge.fromUrl`) creates its nodes with [bounded]'s factory, which
 *   refuses such a literal before Jena computes its value: a parse error ([RiotException], reported as
 *   `RdfFormatException`);
 * - the graph API refuses it with [IllegalArgumentException] (`JenaTerms.toJenaNode`);
 * - SPARQL text is refused before Jena parses it when it holds a number of more digits than the limit, counted across
 *   its point and exponent ([requireBoundedQuery]): a numeric literal of the query is turned into a value as it is
 *   parsed. (So is a string literal of the query that holds such a run of digits; no realistic query does.)
 * - Jena's literal checker reads the fractional seconds of a date/time or duration as an `int` and throws
 *   [NumberFormatException] beyond 17 digits; a parse reports that as a parse error ([formatErrorOf]).
 *
 * The limit covers the date/time, Gregorian and duration types too: Jena compares their seconds and years with
 * `BigDecimal`, in quadratic time (2.5 s for one comparison of a dateTime with 400,000 fraction digits). Other
 * datatypes (strings, `xsd:double`, whose value Jena computes in linear time) are not limited.
 */
internal object JenaLiteralBounds {

    /** The node factory of Kastor's parses: the one Jena's parser uses by default, refusing over-long numeric literals. */
    private class BoundedFactory : FactoryRDFCaching() {
        override fun createTypedLiteral(lexical: String, datatype: RDFDatatype): Node {
            LiteralValidation.numericLengthProblem(datatype.uri, lexical)?.let { throw RiotException(it) }
            return super.createTypedLiteral(lexical, datatype)
        }
    }

    /** [builder] creating its nodes with a [BoundedFactory] (a new one per parse: the factory holds blank-node labels). */
    fun bounded(builder: RDFParserBuilder): RDFParserBuilder = builder.factory(BoundedFactory())

    /**
     * [sparql], after checking that it holds no run of more than [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH] digits
     * once its escapes are decoded.
     *
     * Jena turns a numeric literal of a query (`123`, `"123"^^xsd:integer`, `"1.5"^^xsd:decimal`) into a node, and so
     * computes its value, while it parses the query, so the parsed query is too late to look at. The digits are
     * counted on the text as Jena's parser reads it: a `\u` escape (four hex digits) or a `\U` escape (eight), which
     * Jena decodes inside a string literal (`"\u0037..."^^xsd:integer` is a 7...), counts as the character it stands for.
     * Every such escape is counted, wherever it is (also with more than one `u`, and one whose backslash is itself
     * escaped): that only errs on the side of refusing a query no realistic one resembles.
     * @throws QueryParseException when it does
     */
    fun requireBoundedQuery(sparql: String): String {
        val number = DigitRun()
        var i = 0
        while (i < sparql.length) {
            val start = i
            val c = escapeAt(sparql, i)?.also { (_, end) -> i = end }?.first ?: sparql[i++].code
            if (number.next(c) > LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH) {
                throw QueryParseException(
                    "the query holds a number of more than ${LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH} digits (near " +
                        "character $start); Kastor refuses it: computing its value takes time quadratic in its length",
                    -1, -1,
                )
            }
        }
        return sparql
    }

    /**
     * The digits of the number being read: a run of digits continues across one `.` and one exponent marker (`e`/`E`,
     * with its sign), so `"<5,001 digits>.<5,001 digits>"^^xsd:decimal` counts 10,002 digits, as its lexical form does.
     */
    private class DigitRun {
        private var digits = 0
        private var point = false
        private var exponent = false
        private var previous = -1

        /** The digit count after the character [c]. */
        fun next(c: Int): Int {
            when {
                c in '0'.code..'9'.code -> digits++
                c == '.'.code && digits > 0 && !point && !exponent -> point = true
                (c == 'e'.code || c == 'E'.code) && digits > 0 && !exponent -> exponent = true
                (c == '+'.code || c == '-'.code) && (previous == 'e'.code || previous == 'E'.code) && exponent -> Unit
                else -> { digits = 0; point = false; exponent = false }
            }
            previous = c
            return digits
        }
    }

    /** The code point of the `\u` / `\U` escape at [index] of [text] and the index after it, or null for none. */
    private fun escapeAt(text: String, index: Int): Pair<Int, Int>? {
        if (text[index] != '\\' || index + 1 >= text.length) return null
        var i = index + 1
        val hexDigits = when (text[i]) {
            'u' -> { while (i < text.length && text[i] == 'u') i++; 4 }
            'U' -> { i++; 8 }
            else -> return null
        }
        if (i + hexDigits > text.length) return null
        val hex = text.substring(i, i + hexDigits)
        if (!hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        val code = hex.toLongOrNull(16)?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        return code to i + hexDigits
    }

    /**
     * [e] as a parse error when it is Jena's literal checker failing on a literal it cannot read: the
     * [NumberFormatException] of `XSDAbstractDateTimeType.parseInt` for fractional seconds of 18 digits or more, which
     * Jena lets escape its parser. `null` for any other throwable.
     */
    fun formatErrorOf(e: Throwable): RiotException? =
        (e as? NumberFormatException)?.let { RiotException("Jena cannot read a literal of the document: ${it.message}", it) }
}
