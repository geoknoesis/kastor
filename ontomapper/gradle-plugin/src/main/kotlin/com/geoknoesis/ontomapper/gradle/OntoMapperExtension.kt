package com.geoknoesis.ontomapper.gradle

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

/**
 * Extension for configuring OntoMapper plugin.
 * 
 * This extension allows configuring ontology generation through Gradle build scripts
 * without requiring annotations in the source code. Supports both single and multiple
 * ontology configurations.
 */
open class OntoMapperExtension {
    
    /**
     * Container for multiple ontology configurations.
     */
    var ontologies: NamedDomainObjectContainer<OntologyConfig>? = null
    
    
    
    /**
     * DSL method to configure a single ontology.
     * 
     * @param name The name of the ontology configuration
     * @param configure The configuration block
     */
    fun ontology(name: String, configure: OntologyConfig.() -> Unit) {
        val config = ontologies?.create(name)
        config?.configure()
    }
    
    /**
     * DSL method to configure multiple ontologies.
     * 
     * @param configure The configuration block for the ontologies container
     */
    fun ontologies(configure: NamedDomainObjectContainer<OntologyConfig>.() -> Unit) {
        ontologies?.configure()
    }
}
