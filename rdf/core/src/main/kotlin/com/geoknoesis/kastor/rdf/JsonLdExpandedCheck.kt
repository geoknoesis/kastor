package com.geoknoesis.kastor.rdf

import jakarta.json.JsonArray
import jakarta.json.JsonObject
import jakarta.json.JsonString
import jakarta.json.JsonValue

/**
 * Finds what JSON-LD's toRdf step would drop from an **expanded** JSON-LD document, so that a parse can fail instead
 * of losing statements without an error. Both providers expand the document with the options of their conversion
 * and run this check before converting it to RDF.
 *
 * toRdf (JSON-LD 1.1, as Titanium and the RDF4J fork implement it) skips, logging a warning at most:
 * - a statement whose subject, object or graph name is neither a blank node nor an absolute IRI;
 * - a statement whose property is a blank node (no generalized RDF) or not an absolute IRI;
 * - an `rdf:type` statement whose type is not an absolute IRI;
 * - a value whose datatype is not an absolute IRI (nor `@json`), or whose language tag is not well-formed.
 *
 * The check looks at the expanded form, so it sees each IRI as the conversion does, whatever the document's contexts
 * do: `"@base": null`, a relative `@base` or `@vocab`, scoped contexts, keyword aliases, `@reverse`, `@nest`,
 * `@included` and containers have all been applied. An IRI must pass both the processor's own test ([Processor]) and
 * Kastor's ([Iri]); a language tag must pass both the processor's and [LiteralValidation.isWellFormedLanguageTag].
 *
 * A key that does not expand to an IRI (an undefined term with no `@vocab`, or one under a `@vocab` that stays
 * relative) is removed by expansion itself, so it is not in the expanded form: the providers expand with Titanium's
 * undefined-terms policy set to fail, and report that failure with [undefinedTermProblem]. A top-level value or list
 * object (with no subject) has no statement, by the JSON-LD specification's design.
 */
@InternalKastorApi
object JsonLdExpandedCheck {
    /** What the JSON-LD processor that converts the document accepts; its toRdf step drops anything else. */
    interface Processor {
        fun isAbsoluteIri(iri: String): Boolean
        fun isWellFormedLanguageTag(tag: String): Boolean
    }

    private enum class Role(val label: String) {
        NODE("a node identifier"),
        TYPE("a type"),
        PROPERTY("a property"),
        DATATYPE("a datatype"),
    }

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /**
     * The first term of [expanded] that toRdf would drop, described for a parse error, or null when there is none.
     * The walk is iterative, so the nesting depth of the document does not matter.
     */
    fun problem(expanded: JsonValue, processor: Processor): String? {
        val pending = ArrayDeque<JsonValue>().apply { add(expanded) }
        while (pending.isNotEmpty()) {
            when (val item = pending.removeLast()) {
                is JsonArray -> pending.addAll(item)
                is JsonObject -> (if (item.containsKey("@value")) valueProblem(item, processor) else nodeProblem(item, processor, pending))
                    ?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    /** A node object (or list object): checks its identifier, types and properties, and queues what it holds. */
    private fun nodeProblem(node: JsonObject, processor: Processor, pending: ArrayDeque<JsonValue>): String? {
        node.string("@id")?.let { id -> iriProblem(id, Role.NODE, processor)?.let { return it } }
        (node["@type"] as? JsonArray)?.forEach { type ->
            (type as? JsonString)?.let { iriProblem(it.string, Role.TYPE, processor)?.let { problem -> return problem } }
        }
        for ((key, value) in node) {
            when (key) {
                "@graph", "@included", "@list" -> pending.add(value)
                "@reverse" -> (value as? JsonObject)?.forEach { (property, values) ->
                    iriProblem(property, Role.PROPERTY, processor)?.let { return it }
                    pending.add(values)
                }
                else -> if (!key.startsWith("@")) {
                    iriProblem(key, Role.PROPERTY, processor)?.let { return it }
                    pending.add(value)
                }
            }
        }
        return null
    }

    /** A value object: its datatype and language tag. Its `@value` is data (any JSON, for `@json`), never walked. */
    private fun valueProblem(value: JsonObject, processor: Processor): String? {
        value.string("@type")?.takeIf { it != "@json" }?.let { type -> iriProblem(type, Role.DATATYPE, processor)?.let { return it } }
        val tag = value.string("@language") ?: return null
        if (processor.isWellFormedLanguageTag(tag) && LiteralValidation.isWellFormedLanguageTag(tag)) return null
        return "the JSON-LD document uses the ill-formed language tag '$tag'; JSON-LD would drop the value that has it"
    }

    private fun iriProblem(iri: String, role: Role, processor: Processor): String? {
        if (iri.startsWith("_:")) {
            if (role == Role.NODE || role == Role.TYPE) return null
            return "the JSON-LD document uses the blank node '$iri' as ${role.label}, which RDF does not allow; " +
                "JSON-LD would drop the statements that use it"
        }
        if (!SCHEME.containsMatchIn(iri)) {
            return "the JSON-LD document uses the relative IRI reference '$iri' as ${role.label}, and it does not " +
                "resolve to an absolute IRI: no base IRI was given, or the document's context sets \"@base\" to null " +
                "(or \"@vocab\" to a relative IRI). JSON-LD would drop the statements that use it; give a base IRI " +
                "(kastor-rdf: --base <iri>) or use absolute IRIs"
        }
        if (processor.isAbsoluteIri(iri) && isValidIri(iri)) return null
        return "the JSON-LD document uses '$iri' as ${role.label}, which is not a well-formed absolute IRI; " +
            "JSON-LD would drop the statements that use it"
    }

    /**
     * The parse error for a key that expands to no IRI, from the message of Titanium's `UNDEFINED_TERM` error
     * (`An undefined term has been found [<key>]. ...`).
     */
    fun undefinedTermProblem(processorMessage: String?): String {
        val key = processorMessage?.substringAfter('[', "")?.substringBefore(']', "").orEmpty()
        return "the JSON-LD document uses the key '$key', which its context does not map to an absolute IRI: it has " +
            "no term definition, and \"@vocab\" is not set or is a relative IRI that no base IRI resolves. JSON-LD " +
            "would drop its values; define the term in the context, or set \"@vocab\" (or a base IRI)"
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonString)?.string
}
