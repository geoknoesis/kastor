package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.LiteralValidation
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.TripleTerm

/** Checks of parsed literals that Rio does not make (see [Rdf4jFormatSupport]). */
internal object Rdf4jLiteralChecks {
    /**
     * Rejects a literal (also inside a triple term) whose lexical form holds an unpaired UTF-16 surrogate: Rio decodes
     * a numeric escape of a surrogate code point into one, which is not a Unicode string (RDF 1.2 Turtle forbids such
     * escapes; the Jena provider and the SPARQL JSON reader reject them), or a numeric literal longer than
     * [LiteralValidation.MAX_NUMERIC_LEXICAL_LENGTH]. IRIs are checked by [com.geoknoesis.kastor.rdf.Iri] itself.
     * @throws IllegalArgumentException for such a literal
     */
    fun requireValid(triple: RdfTriple) {
        when (val obj = triple.obj) {
            is Literal -> {
                require(hasPairedSurrogates(obj.lexical)) { "literal lexical form contains an unpaired surrogate" }
                // The limit the Jena provider needs (its parser computes literal values in quadratic time), for parity.
                LiteralValidation.numericLengthProblem(obj.datatype.value, obj.lexical)?.let { throw IllegalArgumentException(it) }
            }
            is TripleTerm -> requireValid(obj.triple)
            else -> Unit
        }
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
