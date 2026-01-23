package com.example

import com.geoknoesis.kastor.gen.processor.extensions.dsl
import com.google.devtools.ksp.processing.KSPLogger

/**
 * Sample code demonstrating the fluent DSL API.
 *
 * This file is referenced by @sample tags in the API documentation.
 */

/**
 * Example: Using the fully fluent DSL API with method-based configuration
 */
fun example(logger: KSPLogger) {
    val request = dsl {
        name("skos")
        packageName("com.example")
        fromOntology("shapes.ttl", "context.jsonld", logger)
        withOptions {
            validation {
                enabled = true
                mode = com.geoknoesis.kastor.gen.annotations.ValidationMode.EMBEDDED
            }
            naming {
                strategy = com.geoknoesis.kastor.gen.processor.model.NamingStrategy.CAMEL_CASE
            }
            output {
                supportLanguageTags = true
                defaultLanguage = "en"
            }
        }
    }
}

