package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.LiteralValidation
import com.geoknoesis.kastor.rdf.sparql.internal.SparqlNumberText
import org.apache.jena.datatypes.RDFDatatype
import org.apache.jena.graph.Graph
import org.apache.jena.graph.Node
import org.apache.jena.query.QueryParseException
import org.apache.jena.riot.RDFParserBuilder
import org.apache.jena.riot.RiotException
import org.apache.jena.riot.system.FactoryRDFCaching
import org.apache.jena.sparql.core.DatasetGraph
import org.apache.jena.sparql.core.DatasetGraphWrapper
import org.apache.jena.sparql.core.GraphView
import org.apache.jena.sparql.core.Quad

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
 * - a SPARQL UPDATE writes through [boundedWrites], which refuses it before the store values it: the literals the
 *   update computes (`STRDT(CONCAT(...), xsd:integer)`, a cast), copies (`INSERT ... WHERE`, `ADD`, `COPY`, `MOVE`)
 *   or reads with `LOAD`, which its text does not hold (as the RDF4J stores do; reported as `RdfQueryException`);
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
    internal class BoundedFactory : FactoryRDFCaching() {
        override fun createTypedLiteral(lexical: String, datatype: RDFDatatype): Node {
            LiteralValidation.numericLengthProblem(datatype.uri, lexical)?.let { throw RiotException(it) }
            return super.createTypedLiteral(lexical, datatype)
        }
    }

    /**
     * [builder] creating its nodes with a [BoundedFactory] (a new one per parse: the factory holds blank-node labels).
     * Jena's term checker, which strict mode turns on, values every typed literal **before** the factory sees it
     * (19.5 s for a 1,000,000-digit integer), so the readers of a bounded parse check first ([JenaBoundedReaders]).
     */
    fun bounded(builder: RDFParserBuilder): RDFParserBuilder = builder.factory(BoundedFactory()).also { JenaBoundedReaders.install() }

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
        val limit = LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH
        val start = SparqlNumberText.numberLongerThan(sparql, limit)
        if (start >= 0) {
            throw QueryParseException(
                "the query holds a number of more than $limit digits (near character $start); Kastor refuses it: " +
                    "computing its value takes time quadratic in its length",
                -1, -1,
            )
        }
        return sparql
    }

    /**
     * [dataset] as a SPARQL UPDATE sees it: every quad written through it, by any update operation, is refused with
     * [org.apache.jena.update.UpdateException] when its subject or object (or a term of a triple term) is a literal
     * that [LiteralValidation.numericLengthProblem] refuses. The check runs before the quad reaches the store, which
     * computes the literal's value when it indexes it (Jena 6.2: 7 s for 330,000 digits, 105 s for 1,300,000).
     */
    fun boundedWrites(dataset: org.apache.jena.query.Dataset): org.apache.jena.query.Dataset =
        org.apache.jena.query.DatasetFactory.wrap(BoundedWrites(dataset.asDatasetGraph()))

    private class BoundedWrites(base: DatasetGraph) : DatasetGraphWrapper(base) {
        override fun add(quad: Quad) {
            requireBounded(quad.subject)
            requireBounded(quad.`object`)
            super.add(quad)
        }

        override fun add(g: Node, s: Node, p: Node, o: Node) {
            requireBounded(s)
            requireBounded(o)
            super.add(g, s, p, o)
        }

        override fun addGraph(graphName: Node, graph: Graph) {
            graph.find().forEachRemaining { requireBounded(it.subject); requireBounded(it.`object`) }
            super.addGraph(graphName, graph)
        }

        // Graph views over this wrapper, so that writes through a graph (LOAD INTO GRAPH, ADD, COPY) are checked too.
        override fun getDefaultGraph(): Graph = GraphView.createDefaultGraph(this)

        override fun getGraph(graphNode: Node): Graph = when {
            Quad.isUnionGraph(graphNode) -> super.getGraph(graphNode)
            Quad.isDefaultGraph(graphNode) -> GraphView.createDefaultGraph(this)
            else -> GraphView.createNamedGraph(this, graphNode)
        }
    }

    private fun requireBounded(node: Node) {
        when {
            node.isLiteral -> LiteralValidation.numericLengthProblem(node.literalDatatypeURI, node.literalLexicalForm)
                ?.let { throw org.apache.jena.update.UpdateException(it) }
            node.isTripleTerm -> node.triple.let { requireBounded(it.subject); requireBounded(it.`object`) }
        }
    }

    /**
     * [e] as a parse error when it is Jena's literal checker failing on a literal it cannot read: the
     * [NumberFormatException] of `XSDAbstractDateTimeType.parseInt` for fractional seconds of 18 digits or more, which
     * Jena lets escape its parser. `null` for any other throwable.
     */
    fun formatErrorOf(e: Throwable): RiotException? =
        (e as? NumberFormatException)?.let { RiotException("Jena cannot read a literal of the document: ${it.message}", it) }
}
