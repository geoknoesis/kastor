package com.geoknoesis.kastor.rdf.rdf4j

import org.eclipse.rdf4j.model.Statement

/**
 * The blank nodes of one parsed document: every label of the document gets an id of the form
 * `genid-<document, 32 hex digits>-<ordinal>`, the same id wherever the label occurs.
 * Full source labels are mapped to unique ordinals, so distinct labels cannot collide.
 *
 * Rio alone does not do that: it renames the labels it reads as terms of a statement, but a triple term that is
 * written as an `urn:rdf4j:triple:` IRI (the form Rio's writers use in every RDF 1.1 syntax, so the form
 * [Rdf4jFormatSupport.serializeGraph] and [Rdf4jFormatSupport.serializeDataset] produce) is decoded with its labels as written. A blank node, or a
 * reifier, that occurs both inside and outside a triple term (an annotation on an annotation) would come back as
 * two nodes, and the label inside the triple term would be shared by every document parsed. The parsers of
 * [Rdf4jFormatSupport] therefore keep the labels of the document and rename them all here.
 */
internal class DocumentBlankNodes {
    private val prefix = "genid-" + java.util.UUID.randomUUID().toString().replace("-", "") + "-"
    private val values = org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance()
    private val renamed = HashMap<String, org.eclipse.rdf4j.model.BNode>()

    private fun node(label: org.eclipse.rdf4j.model.BNode): org.eclipse.rdf4j.model.BNode = renamed.getOrPut(label.id) {
        // The full source label is the map key; generated IDs never overlap with source labels.
        values.createBNode(prefix + renamed.size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <V : org.eclipse.rdf4j.model.Value?> value(value: V): V = when (value) {
        is org.eclipse.rdf4j.model.BNode -> node(value) as V
        is org.eclipse.rdf4j.model.Triple -> {
            val subject = value(value.subject)
            val obj = value(value.`object`)
            if (subject === value.subject && obj === value.`object`) value else values.createTriple(subject, value.predicate, obj) as V
        }
        else -> value
    }

    /** [statement] with the blank nodes of this document; [statement] itself when it has none. */
    fun statement(statement: Statement): Statement {
        val subject = value(statement.subject)
        val obj = value(statement.`object`)
        val context = value(statement.context)
        if (subject === statement.subject && obj === statement.`object` && context === statement.context) return statement
        return if (context == null) values.createStatement(subject, statement.predicate, obj)
        else values.createStatement(subject, statement.predicate, obj, context)
    }

}

/**
 * Blank node labels that every Rio writer writes as they are, inside and outside a triple term.
 *
 * Rio's writers rewrite a blank node id that is not a plain label on their own (the N-Triples / N-Quads writer
 * replaces every character that is not an ASCII letter or digit by its hex code, so `a-b` is written `_:a2db`),
 * but not inside a triple term, which they write as an `urn:rdf4j:triple:` IRI holding the ids unchanged. The
 * blank node `a-b` would be written under two labels. The serializers of [Rdf4jFormatSupport] therefore give every blank
 * node a label that no writer changes:
 * - an id of ASCII letters and digits that starts with a letter and has no `Z` is its own label;
 * - any other id is written as `Z` followed by its characters, with every byte (of its UTF-8 form) that is not an
 *   ASCII letter other than `Z`, or a digit, written as `Z` and two hex digits.
 *
 * The mapping is injective (the two cases cannot produce the same label, and the escape is reversible), so two
 * blank nodes are never merged.
 */
internal object WriterLabels {
    private val values = org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance()

    private fun plain(c: Char): Boolean = (c in 'A'..'Y') || (c in 'a'..'z') || (c in '0'..'9')

    fun label(id: String): String {
        if (id.isNotEmpty() && id[0] !in '0'..'9' && id.all(::plain)) return id
        val out = StringBuilder(id.length + 16).append('Z')
        for (byte in id.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (byte >= 0 && plain(c)) out.append(c) else out.append('Z').append("%02x".format(byte.toInt() and 0xFF))
        }
        return out.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun <V : org.eclipse.rdf4j.model.Value?> value(value: V): V = when (value) {
        is org.eclipse.rdf4j.model.BNode -> label(value.id).let { if (it == value.id) value else values.createBNode(it) as V }
        is org.eclipse.rdf4j.model.Triple -> {
            val subject = value(value.subject)
            val obj = value(value.`object`)
            if (subject === value.subject && obj === value.`object`) value else values.createTriple(subject, value.predicate, obj) as V
        }
        else -> value
    }

    /** [statement] with writer-safe blank node labels; [statement] itself when nothing changes. */
    fun statement(statement: Statement): Statement {
        val subject = value(statement.subject)
        val obj = value(statement.`object`)
        val context = value(statement.context)
        if (subject === statement.subject && obj === statement.`object` && context === statement.context) return statement
        return if (context == null) values.createStatement(subject, statement.predicate, obj)
        else values.createStatement(subject, statement.predicate, obj, context)
    }
}
