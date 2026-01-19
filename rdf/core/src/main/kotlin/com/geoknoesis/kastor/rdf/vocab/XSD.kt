package com.geoknoesis.kastor.rdf.vocab

import com.geoknoesis.kastor.rdf.Iri

/**
 * XSD (XML Schema Definition) datatype constants.
 * Provides commonly used XSD datatype IRIs for RDF literals.
 */
object XSD : Vocabulary {
    override val namespace: String = "http://www.w3.org/2001/XMLSchema#"
    override val prefix: String = "xsd"
    
    // Basic datatypes
    val string: Iri by lazy { term("string") }
    val integer: Iri by lazy { term("integer") }
    val decimal: Iri by lazy { term("decimal") }
    val double: Iri by lazy { term("double") }
    val float: Iri by lazy { term("float") }
    val boolean: Iri by lazy { term("boolean") }
    
    // Date and time datatypes
    val date: Iri by lazy { term("date") }
    val time: Iri by lazy { term("time") }
    val dateTime: Iri by lazy { term("dateTime") }
    val dateTimeStamp: Iri by lazy { term("dateTimeStamp") }
    val gYear: Iri by lazy { term("gYear") }
    val gYearMonth: Iri by lazy { term("gYearMonth") }
    
    // Binary datatypes
    val base64Binary: Iri by lazy { term("base64Binary") }
    
    // Numeric datatypes
    val long: Iri by lazy { term("long") }
    val int: Iri by lazy { term("int") }
    val short: Iri by lazy { term("short") }
    val byte: Iri by lazy { term("byte") }
    val unsignedLong: Iri by lazy { term("unsignedLong") }
    val unsignedInt: Iri by lazy { term("unsignedInt") }
    val unsignedShort: Iri by lazy { term("unsignedShort") }
    val unsignedByte: Iri by lazy { term("unsignedByte") }
    val positiveInteger: Iri by lazy { term("positiveInteger") }
    val nonNegativeInteger: Iri by lazy { term("nonNegativeInteger") }
    val negativeInteger: Iri by lazy { term("negativeInteger") }
    val nonPositiveInteger: Iri by lazy { term("nonPositiveInteger") }
    
    // String datatypes
    val normalizedString: Iri by lazy { term("normalizedString") }
    val token: Iri by lazy { term("token") }
    val language: Iri by lazy { term("language") }
    val Name: Iri by lazy { term("Name") }
    val NCName: Iri by lazy { term("NCName") }
    val ID: Iri by lazy { term("ID") }
    val IDREF: Iri by lazy { term("IDREF") }
    val IDREFS: Iri by lazy { term("IDREFS") }
    val ENTITY: Iri by lazy { term("ENTITY") }
    val ENTITIES: Iri by lazy { term("ENTITIES") }
    val NMTOKEN: Iri by lazy { term("NMTOKEN") }
    val NMTOKENS: Iri by lazy { term("NMTOKENS") }
    
    // URI datatypes
    val anyURI: Iri by lazy { term("anyURI") }
    
    // Other datatypes
    val QName: Iri by lazy { term("QName") }
    val NOTATION: Iri by lazy { term("NOTATION") }
    val hexBinary: Iri by lazy { term("hexBinary") }
}









