package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.LiteralValidation
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.sparql.internal.SparqlNumberText
import org.eclipse.rdf4j.query.MalformedQueryException

/**
 * Checks of literals that Rio and RDF4J do not make: of parsed literals (see [Rdf4jFormatSupport]), of the literals the
 * graph API writes ([requireBoundedLength], from `Rdf4jTerms.toRdf4jValue`) and of SPARQL UPDATE text
 * ([requireBoundedUpdate]). RDF4J's MemoryStore turns an `xsd:integer` or `xsd:decimal` into a `BigInteger` when it
 * stores it, in time quadratic in its digits (two 1,000,000-digit integers: 33 s), so every write path applies
 * [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH], as on Jena.
 */
internal object Rdf4jLiteralChecks {
    /**
     * Rejects a literal (also inside a triple term) whose lexical form holds an unpaired UTF-16 surrogate: Rio decodes
     * a numeric escape of a surrogate code point into one, which is not a Unicode string (RDF 1.2 Turtle forbids such
     * escapes; the Jena provider and the SPARQL JSON reader reject them), or a numeric or temporal literal longer than
     * [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH]. IRIs are checked by [com.geoknoesis.kastor.rdf.Iri] itself.
     * @throws IllegalArgumentException for such a literal
     */
    fun requireValid(triple: RdfTriple) {
        when (val obj = triple.obj) {
            is Literal -> {
                require(hasPairedSurrogates(obj.lexical)) { "literal lexical form contains an unpaired surrogate" }
                // The limit the Jena provider needs (its parser computes literal values in quadratic time), for parity.
                requireBoundedLength(obj.datatype.value, obj.lexical)
            }
            is TripleTerm -> requireValid(obj.triple)
            else -> Unit
        }
    }

    /**
     * Rejects a numeric or temporal literal of [datatypeIri] whose [lexical] form is longer than
     * [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH], with the parse path's message.
     * @throws IllegalArgumentException for such a literal
     */
    fun requireBoundedLength(datatypeIri: String, lexical: String) {
        LiteralValidation.numericLengthProblem(datatypeIri, lexical)?.let { throw IllegalArgumentException(it) }
    }

    /**
     * [sparql], after rejecting SPARQL UPDATE text holding a number of more than [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH] digits
     * ([SparqlNumberText]), before RDF4J parses it, as the Jena provider does.
     * @throws MalformedQueryException when it does
     */
    fun requireBoundedUpdate(sparql: String): String {
        val limit = LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH
        val at = SparqlNumberText.numberLongerThan(sparql, limit)
        if (at >= 0) {
            throw MalformedQueryException(
                "the update holds a number of more than $limit digits (near character $at); Kastor refuses it: computing " +
                    "its value takes time quadratic in its length",
            )
        }
        return sparql
    }

    private fun hasPairedSurrogates(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return false
                i += 2
            } else {
                if (c.isLowSurrogate()) return false
                i++
            }
        }
        return true
    }
}
