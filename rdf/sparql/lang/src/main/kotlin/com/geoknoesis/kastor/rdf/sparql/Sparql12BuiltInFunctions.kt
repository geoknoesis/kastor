package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.SparqlExtensionFunction
import com.geoknoesis.kastor.rdf.sparql.KastorSparqlVocabulary.function

/**
 * Built-in SPARQL 1.2 functions known to the extension registry.
 *
 * Their [SparqlExtensionFunction.iri]s are Kastor identifiers in
 * [KastorSparqlVocabulary.FUNCTION_NAMESPACE] (SPARQL built-ins are keywords, not IRIs). Being part
 * of the language, they are not advertised as `sd:extensionFunction` in service descriptions.
 *
 * [SparqlExtensionFunctionRegistry] registers these on first use.
 */
object Sparql12BuiltInFunctions {
    
    val functions = listOf(
        // RDF-star functions
        SparqlExtensionFunction(
            iri = function("TRIPLE").value,
            name = "TRIPLE",
            description = "Creates a triple term from subject, predicate, and object",
            argumentTypes = listOf("rdf:Resource", "rdf:Property", "rdf:Resource"),
            returnType = "rdf:TripleTerm",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = function("isTRIPLE").value,
            name = "isTRIPLE",
            description = "Tests if a term is a triple term",
            argumentTypes = listOf("rdf:Resource"),
            returnType = "xsd:boolean",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = function("SUBJECT").value,
            name = "SUBJECT",
            description = "Extracts the subject from a triple term",
            argumentTypes = listOf("rdf:TripleTerm"),
            returnType = "rdf:Resource",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = function("PREDICATE").value,
            name = "PREDICATE",
            description = "Extracts the predicate from a triple term",
            argumentTypes = listOf("rdf:TripleTerm"),
            returnType = "rdf:Property",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = function("OBJECT").value,
            name = "OBJECT",
            description = "Extracts the object from a triple term",
            argumentTypes = listOf("rdf:TripleTerm"),
            returnType = "rdf:Resource",
            isBuiltIn = true
        ),
        
        // String functions
        SparqlExtensionFunction(
            iri = function("encodeForUri").value,
            name = "encodeForUri",
            description = "Encodes a string for use in URIs",
            argumentTypes = listOf("xsd:string"),
            returnType = "xsd:string",
            isBuiltIn = true
        ),
        
        // RDF 1.2 / SPARQL 1.2 language and direction functions
        SparqlExtensionFunction(
            iri = function("LANGDIR").value,
            name = "LANGDIR",
            description = "Returns the base direction of a directional language-tagged string (rdf:dirLangString)",
            argumentTypes = listOf("rdf:dirLangString"),
            returnType = "xsd:string",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = function("hasLANG").value,
            name = "hasLANG",
            description = "Tests if a literal has a language tag",
            argumentTypes = listOf("rdf:langString"),
            returnType = "xsd:boolean",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = function("hasLANGDIR").value,
            name = "hasLANGDIR",
            description = "Tests if a literal has a base direction",
            argumentTypes = listOf("rdf:dirLangString"),
            returnType = "xsd:boolean",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = function("STRLANGDIR").value,
            name = "STRLANGDIR",
            description = "Constructs an rdf:dirLangString from lexical, language, and direction (RDF 1.2)",
            argumentTypes = listOf("xsd:string", "xsd:string", "xsd:string"),
            returnType = "rdf:dirLangString",
            isBuiltIn = true
        ),
        
        // Date/time functions
        SparqlExtensionFunction(
            iri = function("now").value,
            name = "now",
            description = "Returns the current date and time",
            argumentTypes = emptyList(),
            returnType = "xsd:dateTime",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = function("timezone").value,
            name = "timezone",
            description = "Returns the timezone of a dateTime value",
            argumentTypes = listOf("xsd:dateTime"),
            returnType = "xsd:dayTimeDuration",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = function("tz").value,
            name = "tz",
            description = "Extracts the timezone from a dateTime",
            argumentTypes = listOf("xsd:dateTime"),
            returnType = "xsd:dayTimeDuration",
            isBuiltIn = true
        ),
        
        // Random functions
        SparqlExtensionFunction(
            iri = function("rand").value,
            name = "rand",
            description = "Returns a random number between 0 and 1",
            argumentTypes = emptyList(),
            returnType = "xsd:double",
            isBuiltIn = true
        )
    )
    }









