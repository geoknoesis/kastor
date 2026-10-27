package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.SparqlIriSubstitution
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.irix.IRIException
import org.apache.jena.query.Query
import org.apache.jena.query.QueryFactory
import org.apache.jena.riot.system.RiotLib
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.syntax.syntaxtransform.QueryTransformOps
import java.util.concurrent.atomic.AtomicLong

/**
 * Parsed query templates ([SparqlIriSubstitution]), shared by every execution: a SHACL engine runs a constraint query
 * once per focus node with the focus node written into the text, and parsing that text again for each focus node is
 * most of the cost of a large validation. The template is parsed once; each execution substitutes its IRIs into the
 * parsed template ([QueryTransformOps.replaceVars], Jena's syntactic substitution, which builds a new query and leaves
 * the template as it is).
 *
 * Sound only where the substituted query is the query the text denotes, so [substituted] answers null (the caller
 * parses the text) for an IRI the parser would not keep as written: one it resolves to another IRI (a relative one,
 * or one with dot segments), refuses, or reads as a blank node (`<_:b>`). A template that does not parse is null too,
 * so the text's own parse reports the error. The caller checks the substituted query as it would a parsed text.
 *
 * Bounded: at most [MAX_ENTRIES] templates and [MAX_TOTAL_LENGTH] characters of template text in all, least recently
 * used dropped first; a template longer than [MAX_TEMPLATE_LENGTH] is never parsed here. A cached template is never
 * changed: its result variables are computed (Jena does that lazily, on the query) before it is shared.
 */
internal object JenaQueryTemplates {
    const val MAX_ENTRIES = 512
    const val MAX_TEMPLATE_LENGTH = 64 * 1024
    const val MAX_TOTAL_LENGTH = 4L * 1024 * 1024

    /** The value of a template that does not parse: the text is parsed instead, every time. */
    private val UNPARSABLE = Query()

    private val entries = LinkedHashMap<String, Query>(16, 0.75f, true)
    private var totalLength = 0L

    /** How many templates were parsed (not taken from the cache): a test seam. */
    @Volatile var parses: Long = 0L
        private set

    /** How many queries were built from a parsed template instead of parsed from their text: a test seam. */
    private val built = AtomicLong()
    val substitutions: Long get() = built.get()

    /**
     * The query that [substitution] wrote, built from its parsed template, or null when the text is to be parsed
     * instead. [bounded] checks a text before it is parsed (as the text itself is).
     */
    fun substituted(substitution: SparqlIriSubstitution, bounded: (String) -> String): Query? {
        val text = substitution.template
        if (text.length > MAX_TEMPLATE_LENGTH) return null
        val template = template(text, bounded) ?: return null
        val nodes = HashMap<Var, Node>()
        for ((name, iri) in substitution.iris) {
            if (!keptAsWritten(template, iri.value)) return null
            nodes[Var.alloc(name)] = NodeFactory.createURI(iri.value)
        }
        return QueryTransformOps.replaceVars(template, nodes).also { built.incrementAndGet() }
    }

    private fun template(text: String, bounded: (String) -> String): Query? {
        synchronized(this) { entries[text] }?.let { return it.takeUnless { it === UNPARSABLE } }
        val parsed = try {
            QueryFactory.create(bounded(text)).also { if (it.isSelectType) it.resultVars }
        } catch (e: RuntimeException) {
            null
        }
        synchronized(this) {
            parses++
            keep(text, parsed ?: UNPARSABLE)
        }
        return parsed
    }

    private fun keep(text: String, parsed: Query) {
        entries.put(text, parsed)?.let { totalLength -= text.length }
        totalLength += text.length
        val eldest = entries.entries.iterator()
        while ((entries.size > MAX_ENTRIES || totalLength > MAX_TOTAL_LENGTH) && eldest.hasNext()) {
            totalLength -= eldest.next().key.length
            eldest.remove()
        }
    }

    /** Whether the parser of [template] reads `<iri>` as the IRI [iri] itself (see the class documentation). */
    private fun keptAsWritten(template: Query, iri: String): Boolean {
        if (RiotLib.isBNodeIRI(iri) || iri.contains(' ')) return false
        val base = template.base ?: return true
        return try {
            base.resolve(iri).toString() == iri
        } catch (e: IRIException) {
            false
        }
    }

    /** Forgets every template: a test seam. */
    fun clear() = synchronized(this) {
        entries.clear()
        totalLength = 0L
    }

    /** The number of templates kept: a test seam. */
    val size: Int get() = synchronized(this) { entries.size }
}
