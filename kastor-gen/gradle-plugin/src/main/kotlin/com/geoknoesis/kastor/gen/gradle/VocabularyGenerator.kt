package com.geoknoesis.kastor.gen.gradle

import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.ShaclShape
import com.geoknoesis.kastor.gen.processor.internal.parsers.JsonLdContextParser
import com.geoknoesis.kastor.gen.processor.internal.parsers.ShaclParser
import com.geoknoesis.kastor.gen.processor.internal.utils.GenerationNames
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import java.io.File

/**
 * Generator for vocabulary files from SHACL shapes and JSON-LD context files.
 *
 * This generator creates Kotlin vocabulary objects following the Kastor pattern,
 * extracting classes and properties from ontology files and generating type-safe
 * vocabulary constants.
 *
 * Output is built with KotlinPoet (so descriptions, quotes and `$` can never break the source) and is
 * deterministic (terms sorted by IRI). Terms inside the vocabulary namespace use `term("local")`; terms
 * from other namespaces (e.g. `dct:title` in a DCAT vocabulary) are emitted as full `Iri("…")` values.
 * When several IRIs map to the same Kotlin name, the in-namespace term keeps the plain name and the others
 * are qualified with their JSON-LD prefix (`dct_title`); remaining clashes fail with a diagnostic.
 */
class VocabularyGenerator(private val logger: KSPLogger) {

    /**
     * Generates a vocabulary file from SHACL and JSON-LD context files.
     *
     * @param shaclFile The SHACL file
     * @param contextFile The JSON-LD context file
     * @param vocabularyName The name of the vocabulary (e.g., "DCAT")
     * @param namespace The namespace URI for the vocabulary
     * @param prefix The prefix for the vocabulary (e.g., "dcat")
     * @param packageName The target package for the generated vocabulary
     * @return The generated vocabulary code
     */
    fun generateVocabulary(
        shaclFile: File,
        contextFile: File,
        vocabularyName: String,
        namespace: String,
        prefix: String,
        packageName: String
    ): String {
        val shapes = shaclFile.inputStream().use(ShaclParser(logger)::parseShacl)
        val context = contextFile.inputStream().use(JsonLdContextParser(logger)::parseContext)
        return generateVocabularyFile(shapes, context, vocabularyName, namespace, prefix, packageName).toString()
    }

    /**
     * Builds the vocabulary [FileSpec] from already-parsed [shapes] and [context].
     *
     * @throws IllegalStateException when distinct IRIs cannot be given distinct Kotlin names
     */
    fun generateVocabularyFile(
        shapes: List<ShaclShape>,
        context: JsonLdContext,
        vocabularyName: String,
        namespace: String,
        prefix: String,
        packageName: String,
    ): FileSpec {
        logger.info("Generating vocabulary for $vocabularyName")
        val classes = extractClasses(shapes, context)
        val properties = extractProperties(shapes, context)
        logger.info("Extracted ${classes.size} classes and ${properties.size} properties")

        val objectName = GenerationNames.typeIdentifier(vocabularyName).uppercase()
        val iri = ClassName("com.geoknoesis.kastor.rdf", "Iri")
        val names = assignNames(classes + properties, namespace, context)

        val type = TypeSpec.objectBuilder(objectName)
            .addSuperinterface(ClassName("com.geoknoesis.kastor.rdf.vocab", "Vocabulary"))
            .addKdoc("%L", kdoc("$vocabularyName vocabulary.\nGenerated from ontology files."))
            .addProperty(
                PropertySpec.builder("namespace", String::class.asTypeName(), KModifier.OVERRIDE)
                    .initializer("%S", namespace).build()
            )
            .addProperty(
                PropertySpec.builder("prefix", String::class.asTypeName(), KModifier.OVERRIDE)
                    .initializer("%S", prefix).build()
            )

        (classes + properties).sortedWith(compareBy({ it.type }, { it.iri })).forEach { term ->
            val local = term.iri.removePrefix(namespace)
            val value = if (term.iri.startsWith(namespace) && local.isNotEmpty()) CodeBlock.of("term(%S)", local)
            else CodeBlock.of("%T(%S)", iri, term.iri)
            val property = PropertySpec.builder(names.getValue(term.iri), iri)
                .delegate(CodeBlock.of("lazy { %L }", value))
            val doc = listOfNotNull(term.description?.takeIf { it.isNotBlank() }, "IRI: ${term.iri}").joinToString("\n")
            property.addKdoc("%L", kdoc(doc))
            type.addProperty(property.build())
        }

        return FileSpec.builder(packageName, objectName)
            .addFileComment("GENERATED FILE - DO NOT EDIT")
            .addType(type.build())
            .build()
    }

    private fun kdoc(text: String): String = text.replace("/*", "&#47;*").replace("*/", "*&#47;")

    private fun localName(iri: String): String = iri.substringAfterLast('#').substringAfterLast('/')

    /** Kotlin-safe identifier preserving the term's case (keywords are escaped by KotlinPoet). */
    private fun identifier(raw: String): String {
        val cleaned = raw.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        return when {
            cleaned.isEmpty() -> "term"
            cleaned.first().isDigit() -> "_$cleaned"
            cleaned in setOf("namespace", "prefix") -> "${cleaned}Term"
            else -> cleaned
        }
    }

    private fun assignNames(terms: List<VocabularyTerm>, namespace: String, context: JsonLdContext): Map<String, String> {
        val byIri = terms.associateBy { it.iri }
        val result = HashMap<String, String>()
        byIri.values.groupBy { identifier(it.name) }.toSortedMap().forEach { (name, group) ->
            if (group.size == 1) {
                result[group.single().iri] = name
                return@forEach
            }
            val inNamespace = group.filter { it.iri.startsWith(namespace) }
            val keeper = inNamespace.singleOrNull()
            group.sortedBy { it.iri }.forEach { term ->
                result[term.iri] = if (term === keeper) name else {
                    val qualifier = context.prefixes.entries
                        .filter { term.iri.startsWith(it.value) }
                        .maxByOrNull { it.value.length }?.key
                    if (qualifier != null) identifier("${qualifier}_$name") else name
                }
            }
        }
        val clashes = result.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        check(clashes.isEmpty()) {
            "vocabulary term name collisions: " + clashes.toSortedMap().entries.joinToString("; ") { (name, iris) ->
                "'$name' <- ${iris.sorted().joinToString { "<$it>" }}"
            }
        }
        return result
    }

    /**
     * Extracts class terms from SHACL shapes and JSON-LD context.
     */
    private fun extractClasses(shapes: List<ShaclShape>, context: JsonLdContext): List<VocabularyTerm> {
        val terms = LinkedHashMap<String, VocabularyTerm>()
        shapes.sortedBy { it.targetClass }.forEach { shape ->
            val contextTerm = context.typeMappings.entries.filter { it.value.value == shape.targetClass }.minByOrNull { it.key }?.key
            terms.getOrPut(shape.targetClass) {
                VocabularyTerm(contextTerm ?: localName(shape.targetClass), shape.targetClass, TermType.CLASS, null)
            }
        }
        context.typeMappings.entries.sortedBy { it.key }.forEach { (term, iri) ->
            terms.getOrPut(iri.value) { VocabularyTerm(term, iri.value, TermType.CLASS, null) }
        }
        return terms.values.filter { it.name.isNotEmpty() }.sortedBy { it.iri }
    }

    /**
     * Extracts property terms from SHACL shapes and JSON-LD context.
     */
    private fun extractProperties(shapes: List<ShaclShape>, context: JsonLdContext): List<VocabularyTerm> {
        val terms = LinkedHashMap<String, VocabularyTerm>()
        shapes.sortedBy { it.targetClass }.flatMap { it.properties }.sortedBy { it.path }.forEach { property ->
            val contextTerm = context.propertyMappings.entries.filter { it.value.id.value == property.path }.minByOrNull { it.key }?.key
            val existing = terms[property.path]
            if (existing == null || existing.description.isNullOrBlank()) {
                terms[property.path] = VocabularyTerm(
                    contextTerm ?: localName(property.path), property.path, TermType.PROPERTY,
                    property.description.takeIf { it.isNotBlank() } ?: existing?.description,
                )
            }
        }
        context.propertyMappings.entries.sortedBy { it.key }.forEach { (term, property) ->
            terms.getOrPut(property.id.value) { VocabularyTerm(term, property.id.value, TermType.PROPERTY, null) }
        }
        return terms.values.filter { it.name.isNotEmpty() }.sortedBy { it.iri }
    }
}

/**
 * Represents a vocabulary term (class or property).
 */
data class VocabularyTerm(
    val name: String,
    val iri: String,
    val type: TermType,
    val description: String?
)

/**
 * Type of vocabulary term.
 */
enum class TermType {
    CLASS,
    PROPERTY
}
