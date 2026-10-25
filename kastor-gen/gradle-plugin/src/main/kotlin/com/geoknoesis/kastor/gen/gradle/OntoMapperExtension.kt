package com.geoknoesis.kastor.gen.gradle

import org.gradle.api.NamedDomainObjectContainer

/**
 * Extension for configuring Kastor Gen plugin.
 * 
 * This extension allows configuring ontology generation through Gradle build scripts
 * without requiring annotations in the source code. Supports both single and multiple
 * ontology configurations.
 */
open class OntoMapperExtension {
    
    /**
     * Container for multiple ontology configurations; set by the `com.geoknoesis.kastor.gen` plugin when it creates
     * this extension (`null` only for an extension created without the plugin).
     */
    var ontologies: NamedDomainObjectContainer<OntologyConfig>? = null

    private fun container(): NamedDomainObjectContainer<OntologyConfig> = checkNotNull(ontologies) {
        "kastorGen has no ontologies container: apply the com.geoknoesis.kastor.gen plugin instead of creating " +
            "OntoMapperExtension yourself"
    }

    /**
     * DSL method to configure a single ontology.
     * 
     * @param name The name of the ontology configuration
     * @param configure The configuration block
     * @throws IllegalStateException when the extension was not created by the plugin
     */
    fun ontology(name: String, configure: OntologyConfig.() -> Unit) {
        container().create(name).configure()
    }
    
    /**
     * DSL method to configure multiple ontologies.
     * 
     * @param configure The configuration block for the ontologies container
     * @throws IllegalStateException when the extension was not created by the plugin
     */
    fun ontologies(configure: NamedDomainObjectContainer<OntologyConfig>.() -> Unit) {
        container().configure()
    }
}
