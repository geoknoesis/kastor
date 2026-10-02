package com.geoknoesis.kastor.gen.processor.internal.parsers

import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContainer
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdContext
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdProperty
import com.geoknoesis.kastor.gen.processor.api.model.JsonLdType
import com.google.devtools.ksp.processing.KSPLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import com.geoknoesis.kastor.rdf.Iri as RdfIri

/**
 * Parser for JSON-LD context files.
 * Extracts prefixes, type mappings and property definitions for code generation.
 *
 * ## What is understood (JSON-LD 1.1 context definitions)
 * - a context object, or an array of context objects processed in order (`null` resets the context);
 * - `@base`, `@vocab` (`@version`, `@language`, `@direction`, `@protected`, `@propagate` are accepted and have no
 *   effect on code generation);
 * - **keyword aliases**: `"id": "@id"`, `"type": "@type"`, `"type": {"@id": "@type", "@container": "@set"}` are
 *   recorded in [JsonLdContext.keywordAliases]; they are neither types nor properties;
 * - simple term definitions (`"name": "http://xmlns.com/foaf/0.1/name"`, `"Person": "foaf:Person"`) and prefixes
 *   (a simple term whose IRI ends with `#` or `/`, or an expanded definition with `"@prefix": true`);
 * - expanded term definitions with `@id` (optional: a term without one is resolved against `@vocab`, a compact IRI
 *   against its prefix), `@reverse`, `@type` (`@id`, `@vocab`, `@json`, `@none` or a datatype IRI), `@container`
 *   (a keyword or an array of keywords, e.g. `["@set", "@language"]`) and a scoped `@context`, which is resolved
 *   against the context that declares it and kept in [JsonLdProperty.scopedContext];
 * - terms may refer to terms and prefixes that the same context defines further down; `"term": null` removes a term.
 *
 * ## What is rejected (with a message that names the term)
 * Remote contexts (a context given as a URL) and `@import`: the processor does not download anything, put the term
 * definitions in the context file. Unknown keywords in a term definition, `@reverse` combined with `@id`, values of
 * the wrong JSON type, compact IRIs with an undeclared prefix, cyclic definitions.
 */
public class JsonLdContextParser(private val logger: KSPLogger) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses a JSON-LD context file.
     *
     * @param inputStream The JSON-LD context file input stream
     * @return Parsed JSON-LD context
     */
    public fun parseContext(inputStream: InputStream): JsonLdContext {
        val content = inputStream.bufferedReader().use { it.readText() }
        return parseContextContent(content)
    }

    /**
     * Parses JSON-LD context content from a string.
     *
     * @param content The JSON-LD context content as string
     * @return Parsed JSON-LD context
     * @throws IllegalArgumentException when the document has no `@context` or the context uses a construct that is
     *   not supported (see the class documentation)
     */
    public fun parseContextContent(content: String): JsonLdContext {
        val document = json.parseToJsonElement(content).jsonObject
        val element = document["@context"] ?: throw IllegalArgumentException("No @context found")
        val active = Active()
        process(element, active)
        return active.toContext()
    }

    /** The context built so far. */
    private class Active(
        var base: String? = null,
        var vocab: String? = null,
        val prefixes: LinkedHashMap<String, String> = LinkedHashMap(),
        val types: LinkedHashMap<String, RdfIri> = LinkedHashMap(),
        val properties: LinkedHashMap<String, JsonLdProperty> = LinkedHashMap(),
        val aliases: LinkedHashMap<String, String> = LinkedHashMap(),
    ) {
        fun copy(): Active =
            Active(base, vocab, LinkedHashMap(prefixes), LinkedHashMap(types), LinkedHashMap(properties), LinkedHashMap(aliases))

        fun reset() {
            base = null
            vocab = null
            prefixes.clear()
            types.clear()
            properties.clear()
            aliases.clear()
        }

        fun remove(term: String) {
            prefixes.remove(term)
            types.remove(term)
            properties.remove(term)
            aliases.remove(term)
        }

        fun iriOf(term: String): String? = types[term]?.value ?: properties[term]?.id?.value ?: prefixes[term]

        fun toContext(): JsonLdContext = JsonLdContext(
            prefixes = LinkedHashMap(prefixes),
            baseIri = base?.let(::RdfIri),
            vocabIri = vocab?.let(::RdfIri),
            typeMappings = LinkedHashMap(types),
            propertyMappings = LinkedHashMap(properties),
            keywordAliases = LinkedHashMap(aliases),
        )
    }

    private fun process(element: JsonElement, active: Active) {
        when (element) {
            is JsonNull -> active.reset()
            is JsonObject -> Definitions(element, active).run()
            is JsonArray -> element.forEach { item ->
                if (item is JsonArray) fail("a context array must not contain an array")
                process(item, active)
            }
            is JsonPrimitive -> {
                if (!element.isString) fail("a context must be an object, an array of objects or null, got $element")
                fail("remote context \"${element.content}\" is not supported: put its term definitions in the context file")
            }
        }
    }

    /** The term definitions of one context object, applied to [active]. */
    private inner class Definitions(private val context: JsonObject, private val active: Active) {
        /** Terms of this object: absent = not looked at yet, false = being defined, true = defined. */
        private val defined = HashMap<String, Boolean>()
        private val chain = ArrayList<String>()
        private val scoped = ArrayList<Pair<String, JsonElement>>()

        fun run() {
            context["@import"]?.let { import ->
                fail("@import is not supported: put the term definitions of $import in the context file")
            }
            context["@base"]?.let { active.base = stringOrNull("@base", it) }
            context["@vocab"]?.let { value ->
                val vocab = stringOrNull("@vocab", value)
                active.vocab = when {
                    vocab == null -> null
                    vocab.isEmpty() -> active.base
                    else -> expandVocab(vocab)
                }
            }
            context.keys.forEach { key ->
                if (key.startsWith("@")) {
                    if (key !in CONTEXT_KEYWORDS) logger.warn("JSON-LD context: ignoring the unknown keyword $key")
                } else {
                    define(key)
                }
            }
            // A scoped context sees the whole context that declares it, also the terms written after it.
            scoped.forEach { (term, element) ->
                val property = active.properties[term] ?: return@forEach
                val inner = active.copy()
                process(element, inner)
                active.properties[term] = property.copy(scopedContext = inner.toContext())
            }
        }

        private fun stringOrNull(keyword: String, value: JsonElement): String? = when {
            value is JsonNull -> null
            value is JsonPrimitive && value.isString -> value.content
            else -> fail("$keyword must be a string or null, got $value")
        }

        private fun expandVocab(vocab: String): String {
            val colon = vocab.indexOf(':')
            if (colon > 0) {
                val prefix = vocab.substring(0, colon)
                if (prefix in context && !prefix.startsWith("@")) define(prefix)
                active.prefixes[prefix]?.let { return it + vocab.substring(colon + 1) }
            }
            return vocab
        }

        private fun define(term: String) {
            when (defined[term]) {
                true -> return
                false -> fail("term \"${chain.first()}\": cyclic definition (${(chain + term).joinToString(" -> ")})")
                null -> Unit
            }
            defined[term] = false
            chain += term
            active.remove(term)
            val value = context.getValue(term)
            when {
                value is JsonNull -> logger.info("JSON-LD context: term $term is removed")
                value is JsonPrimitive && value.isString -> simple(term, value.content)
                value is JsonObject -> expanded(term, value)
                else -> fail("term \"$term\": a term definition must be a string, an object or null, got $value")
            }
            chain.removeAt(chain.size - 1)
            defined[term] = true
        }

        private fun simple(term: String, target: String) {
            if (target in KEYWORDS) {
                active.aliases[term] = target
                logger.info("Extracted keyword alias: $term -> $target")
                return
            }
            if (target.startsWith("@")) fail("term \"$term\": \"$target\" is not a JSON-LD keyword that a term can stand for")
            // A compact IRI or an IRI used as a term carries no name for a type; it only fixes its own expansion.
            if (term.contains(":")) return
            val iri = expand(term, target)
            if (isPrefixDefinition(iri)) {
                active.prefixes[term] = iri
                logger.info("Extracted prefix: $term -> $iri")
            } else {
                active.types[term] = RdfIri(iri)
                logger.info("Extracted type mapping: $term -> $iri")
            }
        }

        private fun expanded(term: String, definition: JsonObject) {
            definition.keys.firstOrNull { it.startsWith("@") && it !in TERM_KEYWORDS }?.let { keyword ->
                fail("term \"$term\": unsupported keyword $keyword in the term definition")
            }
            val idElement = definition["@id"]
            val reverseElement = definition["@reverse"]
            if (reverseElement != null && idElement != null) fail("term \"$term\": @reverse cannot be combined with @id")
            if (idElement is JsonNull) {
                logger.info("JSON-LD context: term $term is explicitly not mapped")
                return
            }
            val reverse = reverseElement != null
            val source = reverseElement ?: idElement
            val target: String? = when {
                source == null -> null
                source is JsonPrimitive && source.isString -> source.content
                else -> fail("term \"$term\": ${if (reverse) "@reverse" else "@id"} must be a string, got $source")
            }

            val containers = containers(term, definition["@container"])
            if (target != null && target in KEYWORDS) {
                if (reverse) fail("term \"$term\": @reverse must be an IRI, got the keyword $target")
                active.aliases[term] = target
                logger.info("Extracted keyword alias: $term -> $target")
                return
            }
            if (target != null && target.startsWith("@")) fail("term \"$term\": \"$target\" is not a JSON-LD keyword that a term can stand for")

            // Without @id the term stands for itself: a compact IRI for its expansion, a plain term for vocab + term.
            val iri = expand(term, target ?: term)
            val type = when (val element = definition["@type"]) {
                null, is JsonNull -> null
                is JsonPrimitive -> {
                    if (!element.isString) fail("term \"$term\": @type must be a string, got $element")
                    when (val name = element.content) {
                        "@id" -> JsonLdType.Id
                        "@vocab" -> JsonLdType.Vocab
                        "@json" -> JsonLdType.Json
                        "@none" -> JsonLdType.None
                        else -> {
                            if (name.startsWith("@")) fail("term \"$term\": @type must be @id, @vocab, @json, @none or a datatype IRI, got $name")
                            JsonLdType.Iri(RdfIri(expand(term, name)))
                        }
                    }
                }
                else -> fail("term \"$term\": @type must be a string, got $element")
            }
            val primary = containers.firstOrNull { it != JsonLdContainer.Set } ?: containers.firstOrNull()
            active.properties[term] = JsonLdProperty(
                id = RdfIri(iri),
                type = type,
                container = primary,
                containers = containers,
                reverse = reverse,
            )
            val prefixFlag = definition["@prefix"]
            if (prefixFlag is JsonPrimitive && !prefixFlag.isString && prefixFlag.content == "true" && !term.contains(":")) {
                active.prefixes[term] = iri
                logger.info("Extracted prefix: $term -> $iri")
            }
            definition["@context"]?.let { scoped += term to it }
            logger.info("Extracted property: $term -> $iri (type: $type, container: $primary${if (reverse) ", reverse" else ""})")
        }

        private fun containers(term: String, element: JsonElement?): List<JsonLdContainer> {
            fun invalid(): Nothing = fail("term \"$term\": @container must be a keyword or an array of keywords, got $element")
            fun one(item: JsonElement): JsonLdContainer =
                if (item is JsonPrimitive && item.isString) resolveContainer(item.content) else invalid()
            return when (element) {
                null, is JsonNull -> emptyList()
                is JsonPrimitive -> listOf(one(element))
                is JsonArray -> element.map(::one)
                else -> invalid()
            }
        }

        /**
         * The IRI that [value] stands for in the definition of [term]: another term of the context, a compact IRI,
         * an absolute IRI, or a name in the vocabulary (else relative to the base).
         */
        private fun expand(term: String, value: String): String {
            if (value != term) {
                if (value in context && !value.startsWith("@")) define(value)
                active.iriOf(value)?.let { return it }
            }
            val colon = value.indexOf(':')
            if (colon > 0) {
                val prefix = value.substring(0, colon)
                val suffix = value.substring(colon + 1)
                if (prefix == "_") fail("term \"$term\": the blank node identifier \"$value\" cannot name a class or a property")
                if (suffix.startsWith("//")) return value
                if (prefix != term && prefix in context && !prefix.startsWith("@")) define(prefix)
                active.prefixes[prefix]?.let { return it + suffix }
                if (isAbsoluteIri(value)) return value
                fail("term \"$term\": unknown prefix \"$prefix\" in \"$value\" (Unknown prefix: $prefix)")
            }
            active.vocab?.let { return it + value }
            active.base?.let { return it + value }
            fail("term \"$term\": cannot expand \"$value\": it has no prefix and the context has no @vocab or @base")
        }
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException("JSON-LD context: $message")

    private fun isPrefixDefinition(uri: String): Boolean = uri.endsWith("#") || uri.endsWith("/")

    private fun resolveContainer(value: String): JsonLdContainer = when (value) {
        "@list" -> JsonLdContainer.List
        "@set" -> JsonLdContainer.Set
        "@index" -> JsonLdContainer.Index
        "@language" -> JsonLdContainer.Language
        "@id" -> JsonLdContainer.Id
        "@type" -> JsonLdContainer.Type
        "@graph" -> JsonLdContainer.Graph
        else -> JsonLdContainer.Unknown(value)
    }

    private fun isAbsoluteIri(term: String): Boolean =
        term.contains("://") || ABSOLUTE_SCHEMES.any { term.startsWith("$it:") }

    private companion object {
        /** Keywords a term can be an alias of. */
        val KEYWORDS: Set<String> = setOf(
            "@id", "@type", "@value", "@language", "@direction", "@graph", "@list", "@set", "@index", "@included",
            "@nest", "@none", "@reverse", "@json",
        )

        /** Keywords of a context object. */
        val CONTEXT_KEYWORDS: Set<String> =
            setOf("@base", "@vocab", "@version", "@language", "@direction", "@protected", "@propagate", "@import")

        /** Keywords of an expanded term definition. */
        val TERM_KEYWORDS: Set<String> = setOf(
            "@id", "@reverse", "@type", "@container", "@context", "@language", "@direction", "@index", "@nest",
            "@prefix", "@protected", "@propagate",
        )

        /** Schemes without `//` that are IRIs in their own right, not compact IRIs with an undeclared prefix. */
        val ABSOLUTE_SCHEMES: Set<String> = setOf("urn", "mailto", "tel", "did", "doi", "tag", "info", "geo")
    }
}
