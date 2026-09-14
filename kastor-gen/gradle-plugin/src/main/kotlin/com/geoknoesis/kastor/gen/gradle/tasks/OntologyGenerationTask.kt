package com.geoknoesis.kastor.gen.gradle.tasks

import com.geoknoesis.kastor.gen.annotations.ValidationAnnotations
import com.geoknoesis.kastor.gen.gradle.GradleKspLogger
import com.geoknoesis.kastor.gen.gradle.VocabularyGenerator
import com.geoknoesis.kastor.gen.processor.api.exceptions.GenerationException
import com.geoknoesis.kastor.gen.processor.api.model.DslGenerationOptions
import com.geoknoesis.kastor.gen.processor.api.model.InstanceDslRequest
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.OntologyModel
import com.geoknoesis.kastor.gen.processor.internal.codegen.InstanceDslGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.InterfaceGenerator
import com.geoknoesis.kastor.gen.processor.internal.codegen.OntologyWrapperGenerator
import com.geoknoesis.kastor.gen.processor.internal.parsers.JsonLdContextParser
import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Gradle task for generating domain interfaces and wrappers from SHACL and JSON-LD context files.
 *
 * Defaults: [wrapperPackage] and [vocabularyPackage] default to [interfacePackage] (which is required),
 * [dslPackage] to `<interfacePackage>.dsl`, [generateInterfaces]/[generateWrappers] to true,
 * [generateVocabulary] to true exactly when vocabulary name, namespace and prefix are all set, and
 * [generateDsl] to false. The DSL name defaults to the JSON-LD context file name converted to an identifier.
 *
 * The task is all-or-nothing: every file is generated in memory first; parse errors, name collisions or
 * invalid configuration fail the task *before* the output directory is touched. Only then are the files
 * written by the previous run (recorded in a manifest) deleted and the new ones written, so renames —
 * including case-only renames on case-insensitive file systems — never leave stale or missing files.
 */
@CacheableTask
abstract class OntologyGenerationTask : DefaultTask() {

    /** Name of the ontology configuration (used in diagnostics). */
    @get:Internal
    abstract val ontologyName: Property<String>

    @get:Internal
    abstract val shaclPath: Property<String>

    @get:Internal
    abstract val contextPath: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    open val shaclFile: RegularFileProperty = project.objects.fileProperty()

    /** JSON-LD context; optional (without it type and property names come from IRIs and `sh:name`). */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    open val contextFile: RegularFileProperty = project.objects.fileProperty()

    // Preserve the legacy getters while keeping execution independent of Project.
    @get:Internal
    val shaclInput: File get() = shaclFile.get().asFile
    /** The context file; throws when no context is configured (see [contextFile]). */
    @get:Internal
    val contextInput: File get() = contextFile.get().asFile

    /** Test hook: called before each generated file is written to the staging directory. */
    @get:Internal
    internal var beforeFileWritten: ((File) -> Unit)? = null

    init {
        val root = project.layout.projectDirectory.asFile
        shaclFile.convention(project.layout.file(shaclPath.map { resolveOntologyInput(root, it) }))
        contextFile.convention(project.layout.file(contextPath.map { resolveOntologyInput(root, it) }))
    }

    @get:Input
    @get:Optional
    abstract val interfacePackage: Property<String>

    @get:Input
    @get:Optional
    abstract val wrapperPackage: Property<String>

    @get:Input
    @get:Optional
    abstract val vocabularyPackage: Property<String>

    @get:Input
    @get:Optional
    abstract val generateInterfaces: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val generateWrappers: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val generateVocabulary: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val vocabularyName: Property<String>

    @get:Input
    @get:Optional
    abstract val vocabularyNamespace: Property<String>

    @get:Input
    @get:Optional
    abstract val vocabularyPrefix: Property<String>

    @get:Input
    @get:Optional
    abstract val generateDsl: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val dslPackage: Property<String>

    @get:Input
    @get:Optional
    abstract val dslName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generateOntology() {
        val label = ontologyName.getOrElse(name)
        fun fail(message: String, cause: Throwable? = null): Nothing =
            throw GradleException("kastorGen ontology '$label': $message", cause)

        val shaclFile = this.shaclFile.get().asFile
        val contextFile = this.contextFile.orNull?.asFile
        val kspLogger = GradleKspLogger(logger)
        fun failOnRecordedErrors() {
            if (kspLogger.errors.isNotEmpty()) fail("generation reported errors:\n  " + kspLogger.errors.joinToString("\n  "))
        }

        val interfacePackage = interfacePackage.orNull?.takeIf { it.isNotBlank() }
            ?: fail("interfacePackage must be set")
        val wrapperPackage = wrapperPackage.orNull?.takeIf { it.isNotBlank() } ?: interfacePackage
        val vocabularyPackage = vocabularyPackage.orNull?.takeIf { it.isNotBlank() } ?: interfacePackage
        val dslPackage = dslPackage.orNull?.takeIf { it.isNotBlank() } ?: "$interfacePackage.dsl"
        val generateInterfaces = generateInterfaces.getOrElse(true)
        val generateWrappers = generateWrappers.getOrElse(true)
        val vocabularyName = vocabularyName.getOrElse("")
        val vocabularyNamespace = vocabularyNamespace.getOrElse("")
        val vocabularyPrefix = vocabularyPrefix.getOrElse("")
        // Auto-enable vocabulary generation if vocabulary metadata is provided
        val generateVocabulary = generateVocabulary.getOrElse(
            vocabularyName.isNotBlank() && vocabularyNamespace.isNotBlank() && vocabularyPrefix.isNotBlank()
        )
        val generateDsl = generateDsl.getOrElse(false)

        listOf("interfacePackage" to interfacePackage, "wrapperPackage" to wrapperPackage).forEach { (key, value) ->
            validatePackage(key, value)?.let { fail(it) }
        }
        if (generateVocabulary) validatePackage("vocabularyPackage", vocabularyPackage)?.let { fail(it) }
        if (generateDsl) validatePackage("dslPackage", dslPackage)?.let { fail(it) }

        logger.info("SHACL file: ${shaclFile.absolutePath}")
        logger.info("Context file: ${contextFile?.absolutePath ?: "(none)"}")
        logger.info("Interface package: $interfacePackage, wrapper package: $wrapperPackage")

        // Parse SHACL and JSON-LD context
        val shaclShapes = try {
            shaclFile.inputStream().use(ShaclParser(kspLogger)::parseShacl)
        } catch (e: Exception) {
            fail("cannot read SHACL file ${shaclFile.path}: ${e.message}", e)
        }
        val jsonLdContext = if (contextFile == null) {
            JsonLdContext(prefixes = emptyMap(), typeMappings = emptyMap(), propertyMappings = emptyMap())
        } else {
            try {
                contextFile.inputStream().use(JsonLdContextParser(kspLogger)::parseContext)
            } catch (e: Exception) {
                fail("cannot read JSON-LD context ${contextFile.path}: ${e.message}", e)
            }
        }
        failOnRecordedErrors()
        logger.info("Parsed ${shaclShapes.size} SHACL shapes")

        // Generate against the FULL model once, so cross-type references resolve and unshaped sh:class
        // targets fall back to IRI (String) correctly.
        val fullModel = OntologyModel(shaclShapes, jsonLdContext)
        val files = mutableListOf<FileSpec>()
        try {
            if (generateInterfaces) {
                files += InterfaceGenerator(kspLogger, ValidationAnnotations.NONE)
                    .generateInterfaces(fullModel, interfacePackage, fallbackUnshapedToIri = true).values
            }
            if (generateWrappers) {
                val wrappers = OntologyWrapperGenerator(kspLogger)
                    .generateWrappers(fullModel, wrapperPackage, interfacePackage, fallbackUnshapedToIri = true)
                files += wrappers.values
                if (wrapperPackage != interfacePackage) {
                    // OntoMapper looks for <Interface>Wrapper / <Interface>Factory next to the interface; this stub
                    // loads the wrapper from its own package so it registers itself.
                    wrappers.keys.forEach { wrapper ->
                        val factoryName = wrapper.removeSuffix("Wrapper") + "Factory"
                        files += FileSpec.builder(interfacePackage, factoryName)
                            .addFileComment("GENERATED FILE - DO NOT EDIT")
                            .addType(
                                TypeSpec.objectBuilder(factoryName)
                                    .addModifiers(KModifier.INTERNAL)
                                    .addInitializerBlock(
                                        CodeBlock.builder().addStatement("Class.forName(%S)", "$wrapperPackage.$wrapper").build()
                                    )
                                    .build()
                            )
                            .build()
                    }
                }
            }
            if (generateVocabulary) {
                if (vocabularyName.isBlank()) fail("vocabularyName is required when generateVocabulary is true")
                if (vocabularyNamespace.isBlank()) fail("vocabularyNamespace is required when generateVocabulary is true")
                if (vocabularyPrefix.isBlank()) fail("vocabularyPrefix is required when generateVocabulary is true")
                files += VocabularyGenerator(kspLogger).generateVocabularyFile(
                    shaclShapes, jsonLdContext, vocabularyName, vocabularyNamespace, vocabularyPrefix, vocabularyPackage,
                )
            }
            if (generateDsl) {
                val actualDslName = dslName.orNull?.takeIf { it.isNotBlank() }
                    ?.also { if (!it.matches(DSL_NAME)) fail("dslName '$it' must match ${DSL_NAME.pattern}") }
                    ?: deriveDslName((contextFile ?: shaclFile).nameWithoutExtension)
                files += InstanceDslGenerator(kspLogger).generate(
                    InstanceDslRequest(
                        dslName = actualDslName,
                        ontologyModel = fullModel,
                        packageName = dslPackage,
                        options = DslGenerationOptions(),
                    )
                )
            }
            GenerationNames.checkUniqueFiles(files)
        } catch (e: GenerationException) {
            fail(e.message ?: e.toString(), e)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: e.toString(), e)
        } catch (e: IllegalStateException) {
            fail(e.message ?: e.toString(), e)
        }
        failOnRecordedErrors()

        val outputDir = outputDirectory.get().asFile
        val root = outputDir.toPath().toAbsolutePath().normalize()
        val manifest = File(outputDir, MANIFEST)

        // 1. Validate every entry of the previous manifest before deleting anything.
        val previous = if (manifest.exists()) {
            manifest.readLines().filter { it.isNotBlank() }.map { relative ->
                root.resolve(relative).normalize().also {
                    if (!it.startsWith(root) || it == root) fail("generated-files manifest escapes the output directory: $relative")
                }
            }
        } else {
            emptyList()
        }

        // 2. Write the complete new output to a staging directory: an IO failure here leaves the previous output
        //    and manifest untouched.
        val staging = File(temporaryDir, "staging")
        val generated = try {
            staging.deleteRecursively()
            staging.mkdirs()
            files.map { spec ->
                val relative = spec.packageName.replace('.', '/') + "/" + spec.name + ".kt"
                beforeFileWritten?.invoke(File(staging, relative))
                spec.writeTo(staging)
                relative
            }.sorted()
        } catch (e: Exception) {
            staging.deleteRecursively()
            fail("cannot write generated files: ${e.message}", e)
        }

        // 3. Replace: delete what the previous run wrote BEFORE moving the new files in, so a case-only rename
        //    (Foo.kt -> FOO.kt) does not delete the new file on case-insensitive file systems; then moves (renames
        //    within one file system) and the manifest last.
        previous.forEach { Files.deleteIfExists(it) }
        outputDir.mkdirs()
        generated.forEach { relative ->
            val target = File(outputDir, relative)
            target.parentFile.mkdirs()
            Files.move(File(staging, relative).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        manifest.writeText(generated.joinToString("\n"))
        staging.deleteRecursively()
        logger.info("Ontology generation completed: ${generated.size} files")
    }

    private companion object {
        const val MANIFEST = ".kastor-generated-files"
        val DSL_NAME = Regex("[a-zA-Z][a-zA-Z0-9]*")
        val PACKAGE_SEGMENT = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun validatePackage(key: String, value: String): String? {
            val bad = value.split('.').filter { !it.matches(PACKAGE_SEGMENT) || GenerationNames.isKeyword(it) }
            return if (bad.isEmpty()) null
            else "$key '$value' is not a valid Kotlin package name (invalid segment(s): ${bad.joinToString { "'$it'" }})"
        }

        /** `dcat-us_3.0_context` -> `dcatUs30Context`. */
        fun deriveDslName(fileName: String): String {
            val identifier = GenerationNames.memberIdentifier(fileName).filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
            return when {
                identifier.isEmpty() -> "ontology"
                identifier.first().isLetter() -> identifier
                else -> "dsl$identifier"
            }
        }
    }
}

private fun resolveOntologyInput(root: File, path: String): File {
    require(path.isNotBlank()) { "Ontology input path must not be blank" }
    val requested = File(path)
    if (requested.isAbsolute) return requested
    val projectFile = File(root, path)
    return if (projectFile.exists()) projectFile else File(root, "src/main/resources/$path")
}
