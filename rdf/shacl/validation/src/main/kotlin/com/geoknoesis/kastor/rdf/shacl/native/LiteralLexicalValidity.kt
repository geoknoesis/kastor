package com.geoknoesis.kastor.rdf.shacl.native

import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.TypedLiteral
import com.geoknoesis.kastor.rdf.xsd.XsdLexical
import com.geoknoesis.kastor.rdf.xsd.XsdStrings

/**
 * XSD lexical validity for typed literals used by `sh:datatype`: an ill-formed literal fails even when the RDF
 * term carries the requested datatype IRI. Covers the commonly used XSD built-ins (string types, boolean,
 * decimal, integer and all derived integer types with range checks, float/double, date/time types, Gregorian
 * types, durations, language, hexBinary, base64Binary, the XML name and token types NCName / Name / NMTOKEN(S) /
 * QName / ID / IDREF(S) / ENTITY / ENTITIES, and xsd:anyURI). xsd:normalizedString, xsd:token and xsd:anyURI
 * additionally require every character to be a legal XML 1.0 character; plain xsd:string accepts any Unicode string
 * (W3C SHACL singleLine-001). Non-XSD datatypes are accepted.
 */
internal fun typedLiteralLexicallyValidForShaclDatatype(lit: TypedLiteral): Boolean = literalLexicallyValid(lit)

/**
 * The shared linear-time parsers of `:rdf:core`, which Kastor Gen's generated `sh:datatype` checks use too:
 * [XsdLexical] for numbers (with the integer types' ranges), booleans, dates, times, Gregorian types and durations,
 * [XsdStrings] for the string, binary, name and URI types.
 */
internal fun literalLexicallyValid(lit: Literal): Boolean =
    XsdLexical.isValid(lit.datatype.value, lit.lexical) ?: XsdStrings.isValid(lit.datatype.value, lit.lexical) ?: true
