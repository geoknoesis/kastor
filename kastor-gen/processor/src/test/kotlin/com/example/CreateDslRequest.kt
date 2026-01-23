package com.example

import com.geoknoesis.kastor.gen.processor.extensions.dsl
import com.geoknoesis.kastor.gen.processor.extensions.instanceDslRequest
import com.geoknoesis.kastor.gen.processor.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.model.ShaclShape
import com.geoknoesis.kastor.rdf.Iri

/**
 * Sample code demonstrating how to create DSL requests.
 *
 * This file is referenced by @sample tags in the API documentation.
 */

/**
 * Example 1: Using instanceDslRequest with explicit parameters
 */
fun example1() {
    // Create a minimal ontology model
    // In practice, you would load this from files using OntologyFileReader
    val model = OntologyModel(
        shapes = listOf(
            ShaclShape(
                shapeIri = "http://example.org/Concept",
                targetClass = "http://example.org/Concept",
                properties = emptyList()
            )
        ),
        context = JsonLdContext(
            prefixes = emptyMap(),
            propertyMappings = emptyMap(),
            typeMappings = emptyMap()
        )
    )
    
    val request = instanceDslRequest("skos", "com.example") {
        ontologyModel = model
        options {
            validation {
                enabled = true
            }
        }
    }
}

/**
 * Example 2: Using the enhanced dsl function
 */
fun example2() {
    // Create a minimal ontology model
    val model = OntologyModel(
        shapes = emptyList(),
        context = JsonLdContext(
            prefixes = emptyMap(),
            propertyMappings = emptyMap(),
            typeMappings = emptyMap()
        )
    )
    
    val request = dsl("skos", "com.example") {
        ontologyModel(model)
        withOptions {
            validation {
                enabled = true
            }
        }
    }
}

