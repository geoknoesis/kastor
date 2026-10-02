package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.BindingSet
import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.TripleTerm
import java.math.BigInteger
import kotlin.reflect.KClass

/**
 * Typed access to SPARQL SELECT bindings to avoid stringly-typed [BindingSet.get] mistakes.
 *
 * ## [getAs] / [getAsOrThrow]
 *
 * Supported type arguments:
 * - [Iri], [BlankNode], [Literal], [TripleTerm], [RdfTerm], [RdfResource]: the term, when it is one.
 * - [String]: the lexical form of a [Literal] of any datatype (not an IRI string; use [Iri] for IRIs).
 * - [Int], [Long]: a literal whose datatype is `xsd:integer` or derived from it (`xsd:long`,
 *   `xsd:int`, `xsd:short`, `xsd:byte`, `xsd:nonNegativeInteger`, `xsd:positiveInteger`,
 *   `xsd:nonPositiveInteger`, `xsd:negativeInteger` and the `unsigned` types), whose lexical form is
 *   an integer (an optional sign and digits) within the range of the type asked for.
 * - [Double]: a literal of a numeric datatype (the integer types, `xsd:decimal`, `xsd:float`,
 *   `xsd:double`) with a lexical form of `xsd:double` (`INF`, `-INF` and `NaN` included).
 * - [Boolean]: an `xsd:boolean` literal (`true`, `false`, `1`, `0`).
 *
 * The datatype decides: `"42"` (an `xsd:string`), `"42"@en` or `"42"^^xsd:date` is not a number,
 * and `"42"^^xsd:decimal` is not an [Int]. [getAs] returns `null` when the variable is unbound or
 * its term is not of the type asked for; [getAsOrThrow] throws [IllegalArgumentException] in both
 * cases. A type argument that is not in the list above is a mistake in the call and throws
 * [IllegalArgumentException] from both, whatever the binding holds.
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

/** The binding of [variable] as a [T], or `null`; see [requireVariables] for the types and rules. */
inline fun <reified T : Any> BindingSet.getAs(variable: String): T? = typedBinding(this, variable, T::class) as T?

inline fun <reified T : Any> BindingSet.getAsOrThrow(variable: String): T {
    val coerced = getAs<T>(variable)
    if (coerced == null) {
        val raw = get(variable) ?: throw IllegalArgumentException("Unbound SPARQL variable '$variable'")
        throw IllegalArgumentException(
            "SPARQL variable '$variable': expected ${typeLabelFor(T::class)}, found ${describeTerm(raw)}",
        )
    }
    return coerced
}

/** What [getAs] returns for [type]; not inline, so that the rules can change without recompiling callers. */
@PublishedApi
internal fun typedBinding(binding: BindingSet, variable: String, type: KClass<*>): Any? {
    require(type in SUPPORTED_TYPES) {
        "Typed access does not support ${type.qualifiedName ?: type}; supported types: " +
            SUPPORTED_TYPES.joinToString { it.simpleName.orEmpty() }
    }
    val raw = binding.get(variable) ?: return null
    return when (type) {
        RdfTerm::class -> raw
        Iri::class -> raw as? Iri
        BlankNode::class -> raw as? BlankNode
        Literal::class -> raw as? Literal
        RdfResource::class -> raw as? RdfResource
        TripleTerm::class -> raw as? TripleTerm
        String::class -> (raw as? Literal)?.lexical
        Int::class -> integer(raw)?.takeIf { it.bitLength() < Int.SIZE_BITS }?.toInt()
        Long::class -> integer(raw)?.takeIf { it.bitLength() < Long.SIZE_BITS }?.toLong()
        Double::class -> if (datatypeOf(raw) in NUMERIC_DATATYPES) binding.getDouble(variable) else null
        Boolean::class -> if (datatypeOf(raw) == XSD_NAMESPACE + "boolean") binding.getBoolean(variable) else null
        else -> error("unreachable: $type is in SUPPORTED_TYPES")
    }
}

/** A short description of [term] for messages: its kind and, for a literal, its datatype; never its text. */
@PublishedApi
internal fun describeTerm(term: RdfTerm): String = when (term) {
    is Literal -> "a literal of datatype <${term.datatype.value}>"
    is Iri -> "an IRI"
    is BlankNode -> "a blank node"
    is TripleTerm -> "a triple term"
    else -> term::class.simpleName ?: "a term"
}

private fun datatypeOf(term: RdfTerm): String? = (term as? Literal)?.datatype?.value

/** The value of an integer literal of any size, or `null` when [term] is no integer literal. */
private fun integer(term: RdfTerm): BigInteger? {
    if (datatypeOf(term) !in INTEGER_DATATYPES) return null
    // xsd:integer collapses white space; its lexical space is an optional sign and digits.
    val lexical = (term as Literal).lexical.trim(' ', '\t', '\n', '\r')
    return if (INTEGER_LEXICAL.matches(lexical)) BigInteger(lexical.removePrefix("+")) else null
}

private const val XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema#"

private val INTEGER_DATATYPES: Set<String> = listOf(
    "integer", "long", "int", "short", "byte", "nonNegativeInteger", "positiveInteger", "nonPositiveInteger", "negativeInteger",
    "unsignedLong", "unsignedInt", "unsignedShort", "unsignedByte",
).map { XSD_NAMESPACE + it }.toSet()

private val NUMERIC_DATATYPES: Set<String> = INTEGER_DATATYPES + listOf("decimal", "float", "double").map { XSD_NAMESPACE + it }

private val INTEGER_LEXICAL = Regex("[+-]?[0-9]+")

private val SUPPORTED_TYPES: List<KClass<*>> = listOf(
    Iri::class, BlankNode::class, Literal::class, TripleTerm::class, RdfTerm::class, RdfResource::class,
    String::class, Int::class, Long::class, Double::class, Boolean::class,
)

fun typeLabelFor(clazz: kotlin.reflect.KClass<*>): String = when (clazz) {
    Iri::class -> "IRI"
    BlankNode::class -> "blank node"
    Literal::class -> "literal"
    RdfResource::class -> "resource (IRI or blank node)"
    TripleTerm::class -> "triple term"
    String::class -> "literal lexical"
    Int::class -> "integer literal (Int range)"
    Long::class -> "integer literal (Long range)"
    Double::class -> "numeric literal"
    Boolean::class -> "boolean literal"
    RdfTerm::class -> "RDF term"
    else -> clazz.simpleName ?: "value"
}
