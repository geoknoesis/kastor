package com.geoknoesis.kastor.rdf.jena

import org.apache.jena.datatypes.RDFDatatype
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.graph.Node
import org.apache.jena.riot.RDFParserBuilder
import org.apache.jena.riot.RiotException
import org.apache.jena.riot.system.FactoryRDFCaching

/**
 * Bounds what parsing a literal may cost. Jena computes the value of every `xsd:decimal` literal, and of every literal
 * of `xsd:integer` and the types derived from it, when it creates the node, with `BigInteger` / `BigDecimal`, in time
 * quadratic in the number of digits (measured with Jena 6.2 on JDK 21: 0.7 s for 200,000 digits, 12 s for 800,000; a
 * graph parse pays it twice). A document of a few megabytes could hold the CPU for hours.
 *
 * Every Kastor parse ([JenaParsing.parser], `JenaBridge.fromUrl`) creates its nodes with [factory], which refuses such
 * a literal whose lexical form is longer than [MAX_NUMERIC_LEXICAL_LENGTH] characters before Jena computes its value:
 * the parse fails with a parse error ([RiotException], reported as `RdfFormatException`). Other datatypes (strings,
 * `xsd:double`, whose value Jena computes in linear time) are not limited.
 */
internal object JenaLiteralBounds {

    /** The longest lexical form of an `xsd:integer` (or derived) or `xsd:decimal` literal that a parse accepts. */
    const val MAX_NUMERIC_LEXICAL_LENGTH = 10_000

    private val BOUNDED: Set<RDFDatatype> = setOf(
        XSDDatatype.XSDdecimal, XSDDatatype.XSDinteger, XSDDatatype.XSDlong, XSDDatatype.XSDint, XSDDatatype.XSDshort,
        XSDDatatype.XSDbyte, XSDDatatype.XSDnonNegativeInteger, XSDDatatype.XSDpositiveInteger,
        XSDDatatype.XSDnonPositiveInteger, XSDDatatype.XSDnegativeInteger, XSDDatatype.XSDunsignedLong,
        XSDDatatype.XSDunsignedInt, XSDDatatype.XSDunsignedShort, XSDDatatype.XSDunsignedByte,
    )

    /** The node factory of Kastor's parses: the one Jena's parser uses by default, refusing over-long numeric literals. */
    private class BoundedFactory : FactoryRDFCaching() {
        override fun createTypedLiteral(lexical: String, datatype: RDFDatatype): Node {
            if (lexical.length > MAX_NUMERIC_LEXICAL_LENGTH && datatype in BOUNDED) {
                throw RiotException(
                    "the ${datatype.uri.substringAfter('#')} literal has a lexical form of ${lexical.length} characters, " +
                        "more than the $MAX_NUMERIC_LEXICAL_LENGTH Kastor accepts (computing its value costs time " +
                        "quadratic in its length)",
                )
            }
            return super.createTypedLiteral(lexical, datatype)
        }
    }

    /** [builder] creating its nodes with a [BoundedFactory] (a new one per parse: the factory holds blank-node labels). */
    fun bounded(builder: RDFParserBuilder): RDFParserBuilder = builder.factory(BoundedFactory())
}
