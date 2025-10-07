package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.SparqlExtensionFunction
import com.geoknoesis.kastor.rdf.vocab.SPARQL12

/**
 * Built-in SPARQL 1.2 functions for service description.
 */
object Sparql12BuiltInFunctions {
    
    val functions = listOf(
        // RDF-star functions
        SparqlExtensionFunction(
            iri = SPARQL12.TRIPLE.value,
            name = "TRIPLE",
            description = "Creates a triple term from subject, predicate, and object",
            argumentTypes = listOf("rdf:Resource", "rdf:Property", "rdf:Resource"),
            returnType = "rdf:TripleTerm",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.isTRIPLE.value,
            name = "isTRIPLE",
            description = "Tests if a term is a triple term",
            argumentTypes = listOf("rdf:Resource"),
            returnType = "xsd:boolean",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.SUBJECT.value,
            name = "SUBJECT",
            description = "Extracts the subject from a triple term",
            argumentTypes = listOf("rdf:TripleTerm"),
            returnType = "rdf:Resource",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.PREDICATE.value,
            name = "PREDICATE",
            description = "Extracts the predicate from a triple term",
            argumentTypes = listOf("rdf:TripleTerm"),
            returnType = "rdf:Property",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.OBJECT.value,
            name = "OBJECT",
            description = "Extracts the object from a triple term",
            argumentTypes = listOf("rdf:TripleTerm"),
            returnType = "rdf:Resource",
            isBuiltIn = true
        ),
        
        // String functions
        SparqlExtensionFunction(
            iri = SPARQL12.replaceAll.value,
            name = "replaceAll",
            description = "Replaces all occurrences of a pattern in a string",
            argumentTypes = listOf("xsd:string", "xsd:string", "xsd:string"),
            returnType = "xsd:string",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.encodeForUri.value,
            name = "encodeForUri",
            description = "Encodes a string for use in URIs",
            argumentTypes = listOf("xsd:string"),
            returnType = "xsd:string",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.decodeForUri.value,
            name = "decodeForUri",
            description = "Decodes a URI-encoded string",
            argumentTypes = listOf("xsd:string"),
            returnType = "xsd:string",
            isBuiltIn = true
        ),
        
        // Language and direction functions
        SparqlExtensionFunction(
            iri = SPARQL12.LANGDIR.value,
            name = "LANGDIR",
            description = "Returns the language direction of a language-tagged string",
            argumentTypes = listOf("rdf:langString"),
            returnType = "xsd:string",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.hasLANG.value,
            name = "hasLANG",
            description = "Tests if a string has a specific language tag",
            argumentTypes = listOf("rdf:langString", "xsd:string"),
            returnType = "xsd:boolean",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.hasLANGDIR.value,
            name = "hasLANGDIR",
            description = "Tests if a string has a specific language direction",
            argumentTypes = listOf("rdf:langString", "xsd:string"),
            returnType = "xsd:boolean",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.STRLANGDIR.value,
            name = "STRLANGDIR",
            description = "Creates a language-tagged string with direction",
            argumentTypes = listOf("xsd:string", "xsd:string", "xsd:string"),
            returnType = "rdf:langString",
            isBuiltIn = true
        ),
        
        // Date/time functions
        SparqlExtensionFunction(
            iri = SPARQL12.now.value,
            name = "now",
            description = "Returns the current date and time",
            argumentTypes = emptyList(),
            returnType = "xsd:dateTime",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.timezone.value,
            name = "timezone",
            description = "Returns the timezone of a dateTime value",
            argumentTypes = listOf("xsd:dateTime"),
            returnType = "xsd:dayTimeDuration",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.dateTime.value,
            name = "dateTime",
            description = "Creates a dateTime value",
            argumentTypes = listOf("xsd:date", "xsd:time"),
            returnType = "xsd:dateTime",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.date.value,
            name = "date",
            description = "Extracts the date part from a dateTime",
            argumentTypes = listOf("xsd:dateTime"),
            returnType = "xsd:date",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.time.value,
            name = "time",
            description = "Extracts the time part from a dateTime",
            argumentTypes = listOf("xsd:dateTime"),
            returnType = "xsd:time",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.tz.value,
            name = "tz",
            description = "Extracts the timezone from a dateTime",
            argumentTypes = listOf("xsd:dateTime"),
            returnType = "xsd:dayTimeDuration",
            isBuiltIn = true
        ),
        
        // Random functions
        SparqlExtensionFunction(
            iri = SPARQL12.rand.value,
            name = "rand",
            description = "Returns a random number between 0 and 1",
            argumentTypes = emptyList(),
            returnType = "xsd:double",
            isBuiltIn = true
        ),
        SparqlExtensionFunction(
            iri = SPARQL12.random.value,
            name = "random",
            description = "Returns a random integer",
            argumentTypes = emptyList(),
            returnType = "xsd:integer",
            isBuiltIn = true
        )
    )
    
    init {
        // Register all built-in functions
        functions.forEach { function -> SparqlExtensionFunctionRegistry.register(function) }
    }
}
