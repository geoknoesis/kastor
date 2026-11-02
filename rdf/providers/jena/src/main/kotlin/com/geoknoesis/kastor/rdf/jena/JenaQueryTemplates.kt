package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.SandboxedSelectQuery
import com.geoknoesis.kastor.rdf.SparqlIriSubstitution
import com.geoknoesis.kastor.rdf.SparqlSelect
import org.apache.jena.graph.Node
import org.apache.jena.graph.NodeFactory
import org.apache.jena.query.Query
import org.apache.jena.query.QueryFactory
import org.apache.jena.riot.system.RiotLib
import org.apache.jena.sparql.core.Var
import org.apache.jena.sparql.syntax.ElementGroup
import org.apache.jena.sparql.syntax.ElementPathBlock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Parsed query templates ([SparqlIriSubstitution]), shared by every execution: a SHACL engine runs a constraint query
 * once per focus node with the focus node written into the text, and parsing that text again for each focus node is
 * most of the cost of a large validation. The template is parsed once; each execution gets a copy of the parsed
 * template with its IRIs substituted ([JenaQueryCopy.substitute]), which shares no pattern element or expression with
 * the template or with any other execution.
 *
 * **Used only where it is the query of the text.** Before a template is used, it is checked once ([usable]): its
 * variables are substituted with fresh canary IRIs, and the result must be the parse of the template text with
 * those IRIs written in its variable tokens (equal as a query and as serialized), and must share nothing with the
 * template. Substitution does not depend on the IRI's value, so a template that passes gives the query of the text
 * for every IRI the parser keeps as written; one that fails (a variable left in a position the copy does not rewrite,
 * the variable spelled inside a string, a template that does not parse) is never used, and its texts are parsed.
 * Each IRI is held to the parser itself ([keptAsWritten]): one it would resolve, refuse or read as a blank node
 * sends the execution to its text. The caller checks the substituted query as it would a parsed text.
 *
 * A substitution without IRIs (the SHACL engine's hint for a text that is the same for every focus node, a blank
 * node focus bound by the provider, say) is the text itself, parsed once.
 *
 * Bounded: at most [MAX_ENTRIES] templates and [MAX_TOTAL_LENGTH] characters of template text in all, least recently
 * used dropped first; a template longer than [MAX_TEMPLATE_LENGTH] is never parsed here. A cached template is never
 * changed: its result variables are computed (Jena does that lazily, on the query) before it is shared.
 */
internal object JenaQueryTemplates {
    const val MAX_ENTRIES = 512
    const val MAX_TEMPLATE_LENGTH = 64 * 1024
    const val MAX_TOTAL_LENGTH = 4L * 1024 * 1024
    const val MAX_IRIS = 65_536

    /** The value of a template that is not used: the text is parsed instead, every time. */
    private val UNUSABLE = Query()

    private data class Key(val text: String, val names: Set<String>)

    private val entries = LinkedHashMap<Key, Query>(16, 0.75f, true)
    private var totalLength = 0L

    /** How many templates were parsed (not taken from the cache): a test seam. */
    @Volatile var parses: Long = 0L
        private set

    /** How many queries were built from a parsed template instead of parsed from their text: a test seam. */
    private val built = AtomicLong()
    val substitutions: Long get() = built.get()

    /**
     * [query] parsed: a sandboxed query written from a template ([SandboxedSelectQuery.substitution]) is built from
     * the template's shared parse when that gives the query of its text ([substituted]), any other is parsed from its
     * text. Either way the text is held to [JenaLiteralBounds.requireBoundedQuery] first.
     */
    fun parse(query: SparqlSelect): Query {
        val bounded = JenaLiteralBounds.requireBoundedQuery(query.sparql)
        return (query as? SandboxedSelectQuery)?.substitution?.let { substituted(it, JenaLiteralBounds::requireBoundedQuery) }
            ?: QueryFactory.create(bounded)
    }

    /**
     * The query that [substitution] wrote, built from its parsed template, or null when the text is to be parsed
     * instead. [bounded] checks a text before it is parsed (as the text itself is).
     */
    fun substituted(substitution: SparqlIriSubstitution, bounded: (String) -> String): Query? {
        val text = substitution.template
        if (text.length > MAX_TEMPLATE_LENGTH) return null
        val template = template(Key(text, substitution.iris.keys), bounded) ?: return null
        val nodes = HashMap<Var, Node>()
        for ((name, iri) in substitution.iris) {
            if (!keptAsWritten(template, iri.value)) return null
            nodes[Var.alloc(name)] = NodeFactory.createURI(iri.value)
        }
        return JenaQueryCopy.substitute(template, nodes).also { built.incrementAndGet() }
    }

    private fun template(key: Key, bounded: (String) -> String): Query? {
        synchronized(this) { entries[key] }?.let { return it.takeUnless { it === UNUSABLE } }
        val parsed = try {
            QueryFactory.create(bounded(key.text)).also { if (it.isSelectType) it.resultVars }.takeIf { usable(it, key, bounded) }
        } catch (e: RuntimeException) {
            null
        }
        synchronized(this) {
            parses++
            keep(key, parsed ?: UNUSABLE)
        }
        return parsed
    }

    /**
     * Whether substituting into [template] gives the query of the text (see the class documentation): checked with a
     * fresh canary IRI for each variable, written into the variable's tokens (`?name`, `$name`) of the text. Writing
     * one inside a string, IRI or comment too only makes the two disagree (or leaves them equal, for a comment), so
     * a doubtful template is refused, never accepted.
     */
    private fun usable(template: Query, key: Key, bounded: (String) -> String): Boolean {
        val canaries = key.names.associateWith { "urn:x-kastor-canary:${UUID.randomUUID()}" }
        var text = key.text
        for ((name, iri) in canaries) text = variableToken(name).replace(text) { "<$iri>" }
        val expected = QueryFactory.create(bounded(text))
        val substituted = JenaQueryCopy.substitute(template, canaries.entries.associate { (name, iri) -> Var.alloc(name) to NodeFactory.createURI(iri) })
        return expected == substituted && expected.toString() == substituted.toString() && JenaQueryCopy.sharesNothing(template, substituted)
    }

    /** A `?name` or `$name` token: not followed by a character that would continue the variable name. */
    private fun variableToken(name: String) = Regex("[?$]" + Regex.escape(name) + "(?![\\p{L}\\p{N}_\\u00B7\\u0300-\\u036F\\u203F\\u2040])")

    private fun keep(key: Key, parsed: Query) {
        entries.put(key, parsed)?.let { totalLength -= key.text.length }
        totalLength += key.text.length
        val eldest = entries.entries.iterator()
        while ((entries.size > MAX_ENTRIES || totalLength > MAX_TOTAL_LENGTH) && eldest.hasNext()) {
            totalLength -= eldest.next().key.text.length
            eldest.remove()
        }
    }

    /**
     * Whether the parser of [template] reads `<iri>` as the IRI [iri] itself: asked of the parser, with the
     * template's base, on a query of that one IRI (the answer depends on nothing else, so it is remembered, for at
     * most [MAX_IRIS] IRIs). An IRI with a character an IRIREF cannot hold is never written so.
     */
    private fun keptAsWritten(template: Query, iri: String): Boolean {
        if (RiotLib.isBNodeIRI(iri) || iri.any { it in "<>\"{}|^`\\" || it.code <= 0x20 }) return false
        val base = template.baseURI
        val key = "$base\u0000$iri"
        synchronized(iris) { iris[key] }?.let { return it }
        val kept = try {
            val parsed = QueryFactory.create("SELECT * WHERE { <$iri> ?p ?o }", base)
            val block = (parsed.queryPattern as? ElementGroup)?.elements?.singleOrNull() as? ElementPathBlock
            block?.pattern?.singleOrNull()?.subject == NodeFactory.createURI(iri)
        } catch (e: RuntimeException) {
            false
        }
        synchronized(iris) {
            iris[key] = kept
            if (iris.size > MAX_IRIS) iris.entries.iterator().let { it.next(); it.remove() }
        }
        return kept
    }

    /** The IRIs [keptAsWritten] has answered for, by base and IRI, least recently used first. */
    private val iris = LinkedHashMap<String, Boolean>(16, 0.75f, true)

    /** Forgets every template: a test seam. */
    fun clear() = synchronized(this) {
        entries.clear()
        totalLength = 0L
        synchronized(iris) { iris.clear() }
    }

    /** The number of templates kept: a test seam. */
    val size: Int get() = synchronized(this) { entries.size }
}
