package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.repository.RepositoryConnection

/**
 * Internal RDF4J adapter for [RdfGraph].
 *
 * Holds no connection of its own. Every operation borrows a connection from the owning
 * [Rdf4jRepository] (via `withConnection`), which transparently reuses the current
 * thread's transaction connection inside a `transaction { }` block and otherwise uses a
 * fresh per-operation connection. This keeps graph access thread-safe.
 *
 * **Write cost:** outside a `transaction { }`, every [addTriple]/[removeTriple] call borrows a connection
 * and runs its own begin/commit (and, on a SHACL store, its own validation). [addTriples] and
 * [removeTriples] use a single connection and a single transaction for the whole batch; prefer them
 * (or an explicit `transaction { }`) for bulk changes.
 *
 * **Streaming-write atomicity:** unlike the core default for `addTriples(Sequence)` / `removeTriples(Sequence)`
 * (which commits in chunks), these overrides apply the whole sequence in **one** transaction: a failure
 * part-way rolls everything back, and the store holds the uncommitted changes until the sequence ends.
 *
 * **RDF-star subjects:** RDF4J can hold statements whose subject is a quoted triple, which RDF 1.2 cannot
 * represent. They are read as the RDF 1.2 reified form (see [Rdf4jTerms.triplesOf]): the quoted triple
 * becomes a deterministic reifier blank node `_:r` with `_:r rdf:reifies <<( s p o )>>`. Lookups and removals
 * understand that view. The reifier id encodes its quoted triple (see [Rdf4jTerms.reifierFor]), so lookups by a
 * reifier are index lookups on the quoted triple rather than scans.
 *
 * **Writing the reified view back** into an RDF-star capable (memory) store stores the RDF-star form again: a
 * triple whose subject is a reifier is written with the quoted triple as subject, so a round trip never duplicates
 * statements. A `_:r rdf:reifies <<( s p o )>>` triple is implied by any statement about the quoted triple and is
 * only stored (as a plain blank-node statement) when no such statement exists. Native stores cannot hold quoted
 * triples and store the plain form.
 *
 * **Language tags on native stores:** Kastor compares language tags ignoring case, but RDF4J's `NativeStore`
 * resolves a literal to its stored id with exact tag bytes whenever its small value-id cache misses (after a
 * restart, or once the entry is evicted). Lookups and removals by a [LangString] therefore fall back to scanning
 * the `(subject, predicate, *)` statements on native stores when the exact lookup misses (for [find] with both
 * subject and predicate unbound, that fallback scans the graph).
 *
 * **Lenient reads** ([Rdf4jRepository.lenientRead]): statements that cannot be converted to Kastor terms are skipped
 * with a logged warning instead of failing the read, and [size] counts only convertible statements.
 *
 * @param context the named-graph context, or null for the default graph.
 */
internal class Rdf4jGraph(
    private val repo: Rdf4jRepository,
    private val context: Resource? = null,
) : MutableRdfGraph {

    override fun addTriple(triple: RdfTriple) = repo.withWriteConnection { conn -> add(conn, triple) }

    override fun addTriples(triples: Collection<RdfTriple>) = addAll(triples.iterator())

    /** Streams [triples] through one connection and one transaction. */
    override fun addTriples(triples: Iterable<RdfTriple>) = addAll(triples.iterator())

    /** Streams [triples] through one connection and one transaction without materialising the sequence. */
    override fun addTriples(triples: Sequence<RdfTriple>) = addAll(triples.iterator())

    private fun addAll(triples: Iterator<RdfTriple>) = repo.withWriteConnection { conn ->
        // Borrow one connection for the whole batch rather than one per triple.
        triples.forEach { add(conn, it) }
    }

    private fun add(conn: RepositoryConnection, triple: RdfTriple) {
        if (!repo.starCapable || !involvesReifiedForm(triple.subject, triple.predicate, triple.obj)) {
            conn.add(
                Rdf4jTerms.toRdf4jResource(triple.subject),
                Rdf4jTerms.toRdf4jIri(triple.predicate),
                Rdf4jTerms.toRdf4jValue(triple.obj),
                context,
            )
            return
        }
        val quoted = (triple.subject as? BlankNode)?.let { Rdf4jTerms.quotedTripleOf(it.id) }
        val obj = Rdf4jTerms.toRdf4jStarValue(triple.obj)
        if (quoted != null && triple.predicate == RDF.reifies && obj == quoted) {
            // `_:r rdf:reifies <<( s p o )>>` is implied by every statement about the quoted triple.
            if (conn.hasStatement(quoted, null, null, false, context)) return
            conn.add(Rdf4jTerms.toRdf4jResource(triple.subject), Rdf4jTerms.toRdf4jIri(RDF.reifies), obj, context)
            repo.noteQuotedWrite(Rdf4jTerms.quotedLevel(Rdf4jTerms.toRdf4jResource(triple.subject), obj))
            return
        }
        val subject = Rdf4jTerms.toRdf4jStarResource(triple.subject)
        repo.noteQuotedWrite(Rdf4jTerms.quotedLevel(subject, obj))
        conn.add(subject, Rdf4jTerms.toRdf4jIri(triple.predicate), obj, context)
    }

    override fun removeTriple(triple: RdfTriple): Boolean = repo.withWriteConnection { conn -> remove(conn, triple) }

    override fun removeTriples(triples: Collection<RdfTriple>): Boolean = removeAll(triples.iterator())

    /** Streams removals through one connection and one transaction. */
    override fun removeTriples(triples: Iterable<RdfTriple>): Boolean = removeAll(triples.iterator())

    /** Streams removals through one connection and one transaction without materialising the sequence. */
    override fun removeTriples(triples: Sequence<RdfTriple>): Boolean = removeAll(triples.iterator())

    private fun removeAll(triples: Iterator<RdfTriple>): Boolean = repo.withWriteConnection { conn ->
        var changed = false
        triples.forEach { if (remove(conn, it)) changed = true }
        changed
    }

    private fun remove(conn: RepositoryConnection, triple: RdfTriple): Boolean {
        if (!involvesReifiedForm(triple.subject, triple.predicate, triple.obj)) {
            val subject = Rdf4jTerms.toRdf4jResource(triple.subject)
            val predicate = Rdf4jTerms.toRdf4jIri(triple.predicate)
            val obj = Rdf4jTerms.toRdf4jValue(triple.obj)
            var existed = conn.hasStatement(subject, predicate, obj, false, context)
            if (existed) conn.remove(subject, predicate, obj, context)
            if (needsTagCaseFallback(triple.obj)) {
                // Also remove variants stored under a differently cased tag (separate ids in a NativeStore).
                val variants = ArrayList<Value>()
                conn.getStatements(subject, predicate, null, false, context).use { result ->
                    result.forEach { if (sameLiteral(it.`object`, triple.obj)) variants.add(it.`object`) }
                }
                variants.forEach { conn.remove(subject, predicate, it, context) }
                existed = existed || variants.isNotEmpty()
            }
            return existed
        }
        var changed = false
        val candidates = LinkedHashSet<Statement>()
        candidateStatements(conn, triple.subject, triple.predicate, triple.obj, false) { candidates.add(it) }
        for (statement in candidates) {
            val mapped = Rdf4jTerms.triplesOf(statement)
            val converted = mapped.first()
            when {
                converted == triple -> {
                    conn.remove(statement.subject, statement.predicate, statement.`object`, context)
                    changed = true
                }
                mapped.size > 1 && triple in mapped.subList(1, mapped.size) -> {
                    // Removing only `_:r rdf:reifies <<( s p o )>>`: keep the statement on the plain reifier node.
                    conn.remove(statement.subject, statement.predicate, statement.`object`, context)
                    conn.add(
                        Rdf4jTerms.toRdf4jResource(converted.subject),
                        Rdf4jTerms.toRdf4jIri(converted.predicate),
                        Rdf4jTerms.toRdf4jValue(converted.obj),
                        context,
                    )
                    changed = true
                }
            }
        }
        return changed
    }

    override fun hasTriple(triple: RdfTriple): Boolean = repo.withConnection { conn ->
        if (!involvesReifiedForm(triple.subject, triple.predicate, triple.obj)) {
            val subject = Rdf4jTerms.toRdf4jResource(triple.subject)
            val predicate = Rdf4jTerms.toRdf4jIri(triple.predicate)
            conn.hasStatement(subject, predicate, Rdf4jTerms.toRdf4jValue(triple.obj), repo.inference, context) ||
                (needsTagCaseFallback(triple.obj) &&
                    conn.getStatements(subject, predicate, null, repo.inference, context).use { result ->
                        result.any { sameLiteral(it.`object`, triple.obj) }
                    })
        } else {
            matching(conn, triple.subject, triple.predicate, triple.obj).isNotEmpty()
        }
    }

    override fun getTriples(): List<RdfTriple> = find()

    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> =
        repo.withConnection { conn -> matching(conn, subject, predicate, obj) }

    /** Triples of the RDF 1.2 view matching the pattern, de-duplicated, in store order. */
    private fun matching(conn: RepositoryConnection, subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> {
        val out = LinkedHashSet<RdfTriple>()
        val skipped = Skipped()
        candidateStatements(conn, subject, predicate, obj, repo.inference) { statement ->
            convert(statement, skipped)?.forEach { if (matches(it, subject, predicate, obj)) out.add(it) }
        }
        if (out.isEmpty() && obj != null && needsTagCaseFallback(obj) && !involvesReifiedForm(subject, predicate, obj)) {
            conn.getStatements(subject?.let(Rdf4jTerms::toRdf4jResource), predicate?.let(Rdf4jTerms::toRdf4jIri), null, repo.inference, context)
                .use { result ->
                    result.forEach { statement ->
                        if (sameLiteral(statement.`object`, obj)) convert(statement, skipped)?.forEach { out.add(it) }
                    }
                }
        }
        skipped.report()
        return out.toList()
    }

    /**
     * Feeds [consumer] the store statements that can produce a pattern match (callers filter the converted triples).
     *
     * When the repository knows that quoted-triple subjects are absent or occur only un-nested, reifier positions are
     * resolved to their quoted triples and looked up through the store indexes. Otherwise (nested quoted subjects, or
     * a store whose content is not tracked) positions that only the synthesized reified form can satisfy are widened.
     */
    private fun candidateStatements(
        conn: RepositoryConnection,
        subject: RdfResource?,
        predicate: Iri?,
        obj: RdfTerm?,
        includeInferred: Boolean,
        consumer: (Statement) -> Unit,
    ) {
        fun query(s: Resource?, p: org.eclipse.rdf4j.model.IRI?, o: Value?) =
            conn.getStatements(s, p, o, includeInferred, context).use { result -> result.forEach(consumer) }

        val plainSubject = subject?.let(Rdf4jTerms::toRdf4jResource)
        val plainPredicate = predicate?.let(Rdf4jTerms::toRdf4jIri)
        val plainObject = obj?.let(Rdf4jTerms::toRdf4jValue)
        val reifiesPattern = predicate == RDF.reifies
        // A triple-term object with an unbound predicate may match a synthesized `rdf:reifies` triple.
        if (!involvesReifiedForm(subject, predicate, obj) && !(obj is TripleTerm && predicate == null)) {
            query(plainSubject, plainPredicate, plainObject)
            return
        }
        when (repo.quotedSubjects(conn)) {
            QuotedLevel.NONE -> {
                // No quoted subjects: the reified form can only exist as plain blank-node statements.
                query(plainSubject, plainPredicate, plainObject)
            }
            QuotedLevel.FLAT -> {
                query(plainSubject, plainPredicate, plainObject)
                val starSubject = subject?.let(Rdf4jTerms::toRdf4jStarResource)
                val starObject = obj?.let(Rdf4jTerms::toRdf4jStarValue)
                if (!reifiesPattern && (starSubject != plainSubject || starObject != plainObject)) {
                    query(starSubject, plainPredicate, starObject)
                }
                if (predicate == null || reifiesPattern) {
                    // `_:r rdf:reifies <<t>>` comes from the statements whose subject is `t`.
                    val quoted = (starSubject as? org.eclipse.rdf4j.model.Triple)
                        ?: (starObject as? org.eclipse.rdf4j.model.Triple)?.takeIf { obj is TripleTerm }
                    when {
                        quoted != null -> query(quoted, null, null)
                        reifiesPattern || obj is TripleTerm -> widened(conn, subject, predicate, obj, includeInferred, consumer)
                    }
                }
            }
            QuotedLevel.NESTED, QuotedLevel.UNKNOWN -> widened(conn, subject, predicate, obj, includeInferred, consumer)
        }
    }

    /** Pattern with every position that only the synthesized reified form can satisfy replaced by a wildcard. */
    private fun widened(
        conn: RepositoryConnection,
        subject: RdfResource?,
        predicate: Iri?,
        obj: RdfTerm?,
        includeInferred: Boolean,
        consumer: (Statement) -> Unit,
    ) {
        val reifiesPattern = predicate == RDF.reifies
        val wideSubject = Rdf4jTerms.mentionsStarReifier(subject)
        val wideObject = reifiesPattern || Rdf4jTerms.mentionsStarReifier(obj) || (obj is TripleTerm && predicate == null)
        conn.getStatements(
            if (wideSubject) null else subject?.let(Rdf4jTerms::toRdf4jResource),
            if (reifiesPattern) null else predicate?.let(Rdf4jTerms::toRdf4jIri),
            if (wideObject) null else obj?.let(Rdf4jTerms::toRdf4jValue),
            includeInferred,
            context,
        ).use { result -> result.forEach(consumer) }
    }

    private fun involvesReifiedForm(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): Boolean =
        predicate == RDF.reifies || Rdf4jTerms.mentionsStarReifier(subject) || Rdf4jTerms.mentionsStarReifier(obj)

    private fun matches(triple: RdfTriple, subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): Boolean =
        (subject == null || triple.subject == subject) &&
            (predicate == null || triple.predicate == predicate) &&
            (obj == null || triple.obj == obj)

    /** Only NativeStore compares stored language tags by exact bytes; memory stores already ignore tag case. */
    private fun needsTagCaseFallback(obj: RdfTerm): Boolean = obj is LangString && repo.nativeBase

    /** [stored] is a language-tagged literal equal to [requested] under Kastor's (tag-case-insensitive) equality. */
    private fun sameLiteral(stored: Value, requested: RdfTerm): Boolean =
        stored is org.eclipse.rdf4j.model.Literal && stored.language.isPresent &&
            runCatching { Rdf4jTerms.fromRdf4jValue(stored) }.getOrNull() == requested

    /** Counts statements a lenient read skipped; reports them once per read. */
    private inner class Skipped {
        var count = 0
        var first: String? = null
        fun report() {
            if (count > 0) LOG.warn("Skipped {} RDF4J statement(s) that are not valid RDF terms for Kastor; first: {}", count, first)
        }
    }

    /** Converted RDF 1.2 triples of [statement]; null when a lenient read skips it. */
    private fun convert(statement: Statement, skipped: Skipped): List<RdfTriple>? =
        if (!repo.lenientRead) {
            Rdf4jTerms.triplesOf(statement)
        } else {
            try {
                Rdf4jTerms.triplesOf(statement)
            } catch (e: IllegalArgumentException) {
                if (skipped.count++ == 0) skipped.first = "$statement (${e.message})"
                null
            }
        }

    override fun clear(): Boolean = repo.withWriteConnection { conn ->
        val changed = conn.hasStatement(null, null, null, false, context)
        conn.clear(context)
        changed
    }

    /**
     * Agrees with [getTriples]: statements with RDF-star subjects count their extra `rdf:reifies` triples.
     *
     * When the store cannot contain quoted-triple subjects (native stores) or the repository tracks that none exist,
     * this is RDF4J's own statement count for the context; otherwise the statements are counted in one pass, converting
     * only those that involve quoted subjects or reifier blank nodes.
     */
    override fun size(): Int = repo.withConnection { conn ->
        val level = repo.quotedSubjects(conn)
        if (!repo.inference && !repo.lenientRead && level == QuotedLevel.NONE) {
            return@withConnection Math.toIntExact(conn.size(context))
        }
        var plain = 0L
        val involved = HashSet<RdfTriple>()
        val skipped = Skipped()
        conn.getStatements(null, null, null, repo.inference, context).use { result ->
            result.forEach { statement ->
                val mayCollide = level != QuotedLevel.NONE && (
                    statement.subject is org.eclipse.rdf4j.model.Triple || statement.`object` is org.eclipse.rdf4j.model.Triple ||
                        (statement.subject as? org.eclipse.rdf4j.model.BNode)?.let { Rdf4jTerms.quotedTripleOf(it.id) } != null
                    )
                when {
                    mayCollide -> convert(statement, skipped)?.let { involved.addAll(it) }
                    !repo.lenientRead || convert(statement, skipped) != null -> plain++
                }
            }
        }
        Math.toIntExact(plain + involved.size)
    }

    private companion object {
        val LOG: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(Rdf4jGraph::class.java)
    }
}
