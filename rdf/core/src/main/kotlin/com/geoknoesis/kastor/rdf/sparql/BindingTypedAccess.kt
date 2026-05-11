package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TripleTerm

/**
 * Typed access to SPARQL SELECT bindings to avoid stringly-typed [BindingSet.get] mistakes.
 *
 * ## [getAs] / [getAsOrThrow]
 *
 * Supported type arguments:
 * - [Iri], [BlankNode], [Literal], [TripleTerm], [RdfTerm], [RdfResource]
 * - [String] — lexical form of a [Literal] only (not an IRI string; use [Iri] for IRIs)
 * - [Int], [Long], [Double], [Boolean] — XSD-friendly conversions (same rules as [BindingSet.getInt], etc.)
 *
 * @see requireVariables
 */
fun BindingSet.requireVariables(vararg variables: String) {
    if (variables.isEmpty()) return
    val missing = variables.filter { !hasBinding(it) }
    if (missing.isNotEmpty()) {
        throw IllegalStateException(
            "Binding is missing required variable(s): $missing (present: ${getVariableNames()})",
        )
    }
}

@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> BindingSet.getAs(variable: String): T? {
    val raw = get(variable) ?: return null
    return when {
        T::class == RdfTerm::class -> raw as T
        T::class == Iri::class -> (raw as? Iri) as T?
        T::class == BlankNode::class -> (raw as? BlankNode) as T?
        T::class == Literal::class -> (raw as? Literal) as T?
        T::class == RdfResource::class -> (raw as? RdfResource) as T?
        T::class == TripleTerm::class -> (raw as? TripleTerm) as T?
        T::class == String::class -> (raw as? Literal)?.lexical as T?
        T::class == Int::class -> (getInt(variable)) as T?
        T::class == Long::class -> (getString(variable)?.toLongOrNull()) as T?
        T::class == Double::class -> (getDouble(variable)) as T?
        T::class == Boolean::class -> (getBoolean(variable)) as T?
        else -> null
    }
}

inline fun <reified T : Any> BindingSet.getAsOrThrow(variable: String): T {
    val raw = get(variable)
        ?: throw IllegalArgumentException("Unbound SPARQL variable '$variable'")
    val coerced = getAs<T>(variable)
    if (coerced == null) {
        throw IllegalArgumentException(
            "SPARQL variable '$variable': expected ${typeLabelFor(T::class)}, found ${raw::class.simpleName}",
        )
    }
    return coerced
}

fun typeLabelFor(clazz: kotlin.reflect.KClass<*>): String = when (clazz) {
    Iri::class -> "IRI"
    BlankNode::class -> "blank node"
    Literal::class -> "literal"
    RdfResource::class -> "resource (IRI or blank node)"
    TripleTerm::class -> "triple term"
    String::class -> "literal lexical"
    Int::class -> "integral literal (Int range)"
    Long::class -> "integral literal (Long lexical)"
    Double::class -> "numeric literal"
    Boolean::class -> "boolean literal"
    RdfTerm::class -> "RDF term"
    else -> clazz.simpleName ?: "value"
}
