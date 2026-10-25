package com.geoknoesis.kastor.gen.gradle

import org.gradle.api.Named
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configuration for a single ontology generation: one SHACL file and an optional JSON-LD context file.
 *
 * Every setting is a lazy Gradle property, forwarded to the `generateOntology<Name>` task as a provider: a value
 * assigned (or derived from another provider) after the task was realized is used, and the configuration is
 * configuration-cache safe. In the Kotlin DSL assign with `=` (Gradle's property assignment):
 *
 * ```kotlin
 * kastorGen {
 *     ontologies {
 *         create("dcat") {
 *             shaclPath = "src/main/resources/dcat-us-shacl.ttl"
 *             interfacePackage = "com.example.dcat"
 *             generateDsl = true
 *             outputDirectory = layout.projectDirectory.dir("generated")
 *         }
 *     }
 * }
 * ```
 *
 * A setting that is not assigned has no value, and the task applies its documented default
 * (see [com.geoknoesis.kastor.gen.gradle.tasks.OntologyGenerationTask]).
 *
 * @param configName the name the configuration was created with in `kastorGen.ontologies`; it names the
 *   `generateOntology<Name>` task and the output directory
 */
abstract class OntologyConfig @Inject constructor(private val configName: String) : Named {

    /** Name of this ontology configuration (fixed at creation). */
    override fun getName(): String = configName

    /** Path to the SHACL file, relative to the project directory or `src/main/resources` (required). */
    abstract val shaclPath: Property<String>

    /** Path to the JSON-LD context file, relative to the project directory or `src/main/resources` (optional). */
    abstract val contextPath: Property<String>

    /** Package name for generated interfaces (required). */
    abstract val interfacePackage: Property<String>

    /** Package name for generated wrappers (default: [interfacePackage]). */
    abstract val wrapperPackage: Property<String>

    /** Package name for generated vocabulary (default: [interfacePackage]). */
    abstract val vocabularyPackage: Property<String>

    /** Whether to generate domain interfaces (default: true). */
    abstract val generateInterfaces: Property<Boolean>

    /** Whether to generate wrapper implementations (default: true). */
    abstract val generateWrappers: Property<Boolean>

    /**
     * Base output directory; the ontology name is appended (default: `<buildDir>/generated/sources/kastor-gen`).
     */
    abstract val outputDirectory: DirectoryProperty

    /**
     * Whether to generate a vocabulary file (default: true when [vocabularyName], [vocabularyNamespace] and
     * [vocabularyPrefix] are all set, otherwise false).
     */
    abstract val generateVocabulary: Property<Boolean>

    /** Name of the vocabulary (e.g., "DCAT"). */
    abstract val vocabularyName: Property<String>

    /** Namespace URI for the vocabulary. */
    abstract val vocabularyNamespace: Property<String>

    /** Prefix for the vocabulary (e.g., "dcat"). */
    abstract val vocabularyPrefix: Property<String>

    /** Whether to generate domain-specific DSL builders (default: false). */
    abstract val generateDsl: Property<Boolean>

    /** Package name for generated DSL code (default: `<interfacePackage>.dsl`). */
    abstract val dslPackage: Property<String>

    /**
     * Name of the DSL (e.g., "dcat", "skos"), used as the top-level DSL function name. If not specified, derived
     * from the JSON-LD context file name.
     */
    abstract val dslName: Property<String>
}
