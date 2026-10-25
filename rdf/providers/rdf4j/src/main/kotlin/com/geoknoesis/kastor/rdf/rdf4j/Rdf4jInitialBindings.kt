package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.FalseLiteral
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TripleTerm
import com.geoknoesis.kastor.rdf.TrueLiteral
import com.geoknoesis.kastor.rdf.TypedLiteral

/** Initial bindings of a timed SELECT ([Rdf4jRepository.withSelectRows]) applied to the query text. */
internal object Rdf4jInitialBindings {
    /**
     * A query text with initial bindings applied (see the timed [withSelectRows]): [placeholders] are bound natively and
     * [hidden] variables are not returned in rows.
     */
    class BoundQuery(val sparql: String, val placeholders: Map<String, org.eclipse.rdf4j.model.Value>, val hidden: Set<String>)

    /** Applies [bindings] to [sparql]; a query the substitution rejects fails with [IllegalArgumentException]. */
    fun bind(sparql: String, bindings: Map<String, RdfTerm>): BoundQuery {
        // Fresh variables: bound natively with setBinding, or declared with BIND for triple terms in expressions.
        val placeholders = LinkedHashMap<String, org.eclipse.rdf4j.model.Value>()
        val expressions = HashMap<String, String>()
        val constants = bindings.mapValues { (name, term) ->
            val spelled = sparqlConstant(term)
            when {
                spelled == null && term is TripleTerm -> throw IllegalArgumentException(
                    "Initial binding ?$name is a triple term that contains a blank node or a directional language string. " +
                        "RDF4J can neither write such a triple term into the query text nor bind a triple value natively; " +
                        "bind its components to separate variables and match the triple term in the query " +
                        "(e.g. ?x ?y << ?s ?p ?o >>) instead."
                )
                spelled == null -> placeholderFor(name, sparql, placeholders.keys + expressions.values)
                    .also { placeholders[it] = Rdf4jTerms.toRdf4jValue(term) }.let { "?$it" }
                term is TripleTerm -> spelled.also {
                    expressions[name] = placeholderFor(name, sparql, placeholders.keys + expressions.values)
                }
                else -> spelled
            }
        }
        // Outside queryOperation: a query the substitution rejects is the caller's error (IllegalArgumentException).
        val rewritten = com.geoknoesis.kastor.rdf.sparql.internal.SparqlInitialBindings.apply(sparql, constants, expressions)
        return BoundQuery(rewritten, placeholders, placeholders.keys + expressions.values)
    }

    /** A variable name for a natively bound term that occurs nowhere in [sparql] and is not in [taken]. */
    private fun placeholderFor(name: String, sparql: String, taken: Set<String>): String {
        var candidate = "kastorInitial_$name"
        var n = 0
        while (sparql.contains(candidate) || candidate in taken) candidate = "kastorInitial_${name}_${++n}"
        return candidate
    }

    /**
     * SPARQL text for a binding substituted into the query (as the SPARQL endpoint adapter renders terms),
     * or null for terms bound natively.
     */
    private fun sparqlConstant(term: RdfTerm): String? {
        val lexical = com.geoknoesis.kastor.rdf.sparql.internal.SparqlLexical
        return when (term) {
            is Iri -> lexical.iriRef(term.value)
            is LangString -> if (term.direction == null) lexical.langLiteral(term.lexical, term.lang) else null
            is TypedLiteral ->
                if (term.datatype == com.geoknoesis.kastor.rdf.vocab.XSD.string) lexical.quoted(term.lexical)
                else lexical.typedLiteral(term.lexical, term.datatype.value)
            is TrueLiteral -> "\"true\"^^" + lexical.iriRef(com.geoknoesis.kastor.rdf.vocab.XSD.boolean.value)
            is FalseLiteral -> "\"false\"^^" + lexical.iriRef(com.geoknoesis.kastor.rdf.vocab.XSD.boolean.value)
            // RDF4J's SPARQL-star syntax for a triple term whose components can all be spelled (triple patterns only);
            // null when a component is a blank node or a directional language string (the caller rejects the binding).
            is TripleTerm -> {
                val subject = (term.triple.subject as? Iri)?.let { sparqlConstant(it) } ?: return null
                val obj = sparqlConstant(term.triple.obj) ?: return null
                "<< $subject ${lexical.iriRef(term.triple.predicate.value)} $obj >>"
            }
            else -> null
        }
    }
}
