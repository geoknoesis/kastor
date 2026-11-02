package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.LiteralValidation
import org.apache.jena.datatypes.RDFDatatype
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.graph.Node
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFParserRegistry
import org.apache.jena.riot.ReaderRIOT
import org.apache.jena.riot.ReaderRIOTFactory
import org.apache.jena.riot.RiotException
import org.apache.jena.riot.system.ParserProfile
import org.apache.jena.riot.system.ParserProfileWrapper
import org.apache.jena.riot.tokens.Token
import org.apache.jena.riot.tokens.TokenType

/**
 * Makes the readers of a Kastor parse refuse an over-long numeric or temporal literal **before** Jena's term checker
 * computes its value. In strict mode (every Kastor parse) Jena's parser profile validates a typed literal against its
 * datatype, by computing its value in time quadratic in its length, and only then hands it to the node factory, so
 * [JenaLiteralBounds.bounded]'s factory came too late: a 1,000,000-digit integer took 19.5 s to be refused.
 *
 * Jena gives a parse no way to choose its profile, so [install] wraps Jena's reader factories for the formats Kastor
 * parses. The wrapper changes nothing for a parse whose node factory is not Kastor's ([JenaLiteralBounds.BoundedFactory]);
 * for a Kastor parse it wraps the profile, which refuses such a literal with [RiotException] from the token or the
 * lexical form before delegating. [install] runs before every Kastor parse and installs the wrappers again if anything
 * replaced them.
 */
internal object JenaBoundedReaders {
    private val TRIPLES = listOf(Lang.TURTLE, Lang.NTRIPLES, Lang.RDFXML)
    private val QUADS = listOf(Lang.TRIG, Lang.NQUADS, Lang.JSONLD)

    fun install() {
        if ((TRIPLES + QUADS).all { RDFParserRegistry.getFactory(it) is Bounding }) return
        synchronized(this) {
            for (lang in TRIPLES) RDFParserRegistry.getFactory(lang).takeIf { it !is Bounding }?.let { RDFParserRegistry.registerLangTriples(lang, Bounding(it)) }
            for (lang in QUADS) RDFParserRegistry.getFactory(lang).takeIf { it !is Bounding }?.let { RDFParserRegistry.registerLangQuads(lang, Bounding(it)) }
        }
    }

    private class Bounding(private val inner: ReaderRIOTFactory) : ReaderRIOTFactory {
        override fun create(language: Lang, profile: ParserProfile): ReaderRIOT =
            inner.create(language, if (profile.factorRDF is JenaLiteralBounds.BoundedFactory) BoundedProfile(profile) else profile)
    }

    /** A profile that checks a typed literal's length before Jena's own profile checks (and values) it. */
    private class BoundedProfile(profile: ParserProfile) : ParserProfileWrapper(profile) {
        override fun createTypedLiteral(lexical: String, datatype: RDFDatatype, line: Long, col: Long): Node {
            requireBounded(datatype.uri, lexical)
            return super.createTypedLiteral(lexical, datatype, line, col)
        }

        override fun create(currentGraph: Node?, token: Token): Node {
            check(currentGraph, token)
            return super.create(currentGraph, token)
        }

        override fun createNodeFromToken(scope: Node?, token: Token, line: Long, col: Long): Node {
            check(scope, token)
            return super.createNodeFromToken(scope, token, line, col)
        }

        /** A number token, or a typed literal token (whose datatype token is resolved as Jena would). */
        private fun check(graph: Node?, token: Token) {
            if (token.image == null || token.image.length <= LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH) return
            val datatype = when (token.type) {
                TokenType.INTEGER -> XSDDatatype.XSDinteger.uri
                TokenType.DECIMAL -> XSDDatatype.XSDdecimal.uri
                TokenType.LITERAL_DT -> token.subToken2?.let { super.create(graph, it) }?.takeIf { it.isURI }?.uri
                else -> null
            }
            datatype?.let { requireBounded(it, token.image) }
        }

        private fun requireBounded(datatype: String, lexical: String) {
            LiteralValidation.numericLengthProblem(datatype, lexical)?.let { throw RiotException(it) }
        }
    }
}
