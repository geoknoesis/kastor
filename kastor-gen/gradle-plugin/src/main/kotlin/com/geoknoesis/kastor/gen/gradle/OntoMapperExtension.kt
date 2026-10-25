package com.geoknoesis.kastor.gen.gradle

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer

/**
 * The `kastorGen` extension: ontology generation configured in the build script, without annotations in the
 * source code. Each entry of [ontologies] gets a `generateOntology<Name>` task.
 */
abstract class OntoMapperExtension {

    /** The ontology configurations, by name (a Gradle-managed container). */
    abstract val ontologies: NamedDomainObjectContainer<OntologyConfig>

    /**
     * Adds and configures the ontology [name].
     *
     * @param name The name of the ontology configuration
     * @param configure The configuration block
     */
    fun ontology(name: String, configure: Action<in OntologyConfig>) {
        ontologies.create(name, configure)
    }

    /**
     * Configures the [ontologies] container.
     *
     * @param configure The configuration block for the ontologies container
     */
    fun ontologies(configure: Action<in NamedDomainObjectContainer<OntologyConfig>>) {
        configure.execute(ontologies)
    }
}
