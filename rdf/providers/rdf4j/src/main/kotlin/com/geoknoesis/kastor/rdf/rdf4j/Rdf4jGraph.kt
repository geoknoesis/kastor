package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Triple
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
 * understand that view. The reifier id resolves to its quoted triple (see [Rdf4jTerms.reifierFor]), so lookups by a
 * reifier are index lookups on the quoted triple rather than scans (see **Lookup cost** below).
 *
 * **Writing the reified view back** into an RDF-star capable (memory) store stores the RDF-star form again: a
 * triple whose subject is a reifier is written with the quoted triple as subject, so a round trip never duplicates
 * annotation statements. Native stores cannot hold quoted triples and store the plain form.
 *
 * **Explicit `rdf:reifies` triples** keep set semantics regardless of write order. A statement about a quoted triple
 * implies `_:r rdf:reifies <<( s p o )>>`; a triple of that form that is *added* is always stored as well, as the
 * plain statement `_:r rdf:reifies <<( s p o )>>` (once per graph, also while it is implied). The store itself is
 * therefore the only record of which `rdf:reifies` triples are explicit: the triple survives the removal of the last
 * statement implying it, a restart of a persistent store, other [Rdf4jRepository] instances (or other code) working
 * on the same RDF4J repository, and concurrent writers, because it is written and removed in the same RDF4J
 * transaction as every other statement. No bookkeeping exists outside the store, so nothing has to be copied before a
 * SPARQL `UPDATE`, and `clear()` removes it like any statement. The cost is one extra stored statement per explicit
 * `rdf:reifies` triple, which SPARQL sees (as it sees every stored statement); reads de-duplicate it against the
 * implied triple. Removing `_:r rdf:reifies <<t>>` while statements about `_:r` remain moves them to the plain
 * reifier node, and later statements about `_:r` are stored there too, so the removed triple is not implied again
 * until it is re-added (or every statement about `_:r` is gone).
 *
 * **Lookup cost** on RDF-star capable stores. Patterns that do not involve the reified form (no reifier blank node,
 * no `rdf:reifies` predicate) are plain index lookups. For the others:
 * - a pattern with a reifier subject, or `(?, rdf:reifies, <<t>>)`, is answered with index lookups on the plain
 *   reifier node and on the quoted triple. Only when the store may nest quoted subjects (or is not tracked, see
 *   [Rdf4jRepository]) *and* those lookups do not already yield the `rdf:reifies` triple, one scan of the graph looks
 *   for the quoted triple nested inside other statements;
 * - `(?, rdf:reifies, ?)` scans the graph when the store may hold quoted subjects (RDF4J has no index on them);
 * - a pattern whose object is a triple term with a reifier in a subject position scans the statements of its
 *   predicate (or the graph) when the store may nest quoted subjects. (A reifier blank node used as an object is an
 *   ordinary blank node: it is stored and looked up as such, and read back as the same blank node);
 * - removing an `rdf:reifies` triple scans the graph when the store may nest quoted subjects.
 * A reifier with a hashed id (a quoted triple too large for an encoded id) is resolved from the repository's index of
 * oversized triples ([HashedReifierIndex]) without touching the store. An id the index does not know costs one scan of
 * the store only when the index may be incomplete: after a SPARQL `UPDATE` that may have written triple values (once,
 * until the next such update), or on a wrapped store that other code may change (once per transaction, so once per
 * batch operation). Otherwise the id is known to be an ordinary blank node.
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
 * **Blank node ids:** a blank node is stored under its id without the `_:` of a Turtle label, as on the Jena provider:
 * `BlankNode("_:a")` and `BlankNode("a")` are one node of the graph in every operation (also inside triple terms), and
 * reads return `BlankNode("a")`.
 *
 * **Identity:** a graph object is a handle, not a copy. Two handles are equal when they denote the same graph (the
 * same context) of the same [Rdf4jRepository] object, so a handle obtained anew for every call
 * (`repository.getGraph(name)`) finds what was stored under an equal one. Handles of two [Rdf4jRepository] objects are
 * never equal, also when both wrap one RDF4J repository.
 *
 * @param context the named-graph context, or null for the default graph.
 */
internal open class Rdf4jGraph(
    protected val repo: Rdf4jRepository,
    private val context: Resource? = null,
) : MutableRdfGraph {

    final override fun equals(other: Any?): Boolean =
        this === other || (other is Rdf4jGraph && other.repo === repo && other.context == context)

    final override fun hashCode(): Int = 31 * System.identityHashCode(repo) + (context?.hashCode() ?: 0)

    override fun toString(): String = "Rdf4jGraph(${context ?: "default graph"})"

    override fun addTriple(triple: RdfTriple) = repo.withWriteConnection { conn -> add(conn, triple) }

    /**
     * Resolves the hashed reifier ids of a pattern (see [Rdf4jTerms.reifierFor]) to their quoted triples, for the
     * functions of [Rdf4jTerms] that take them as `hashed`. The result is held by the operation, so an id resolved
     * here stays resolved until the operation is done. Empty (and free) when the pattern has no hashed id; an id that
     * stands for no triple of the repository is left out and is an ordinary blank node.
     */
    private fun resolveHashedReifiers(conn: RepositoryConnection, subject: RdfResource?, obj: RdfTerm?): Map<String, Triple> {
        // A blank node that is itself the object is an ordinary blank node: nothing to resolve.
        if (subject !is BlankNode && obj !is TripleTerm) return emptyMap()
        val ids = HashSet<String>()
        Rdf4jTerms.hashedReifierIds(subject, ids)
        if (obj is TripleTerm) Rdf4jTerms.hashedReifierIds(obj, ids)
        if (ids.isEmpty()) return emptyMap()
        return repo.resolveHashedReifiers(conn, ids, obj as? TripleTerm)
    }

    override fun addTriples(triples: Collection<RdfTriple>) = addAll(triples.iterator())

    /** Streams [triples] through one connection and one transaction. */
    override fun addTriples(triples: Iterable<RdfTriple>) = addAll(triples.iterator())

    /** Streams [triples] through one connection and one transaction without materialising the sequence. */
    override fun addTriples(triples: Sequence<RdfTriple>) = addAll(triples.iterator())

    private fun addAll(triples: Iterator<RdfTriple>) = repo.withWriteConnection { conn ->
        // Borrow one connection for the whole batch rather than one per triple.
        triples.forEach { add(conn, it) }
    }

    private fun add(conn: RepositoryConnection, written: RdfTriple) {
        val triple = Rdf4jTerms.canonical(written)
        val hashed = resolveHashedReifiers(conn, triple.subject, triple.obj)
        if (triple.obj is TripleTerm) repo.noteTripleValue()
        if (!repo.starCapable || !involvesReifiedForm(triple.subject, triple.predicate, triple.obj, hashed)) {
            val plainObject = Rdf4jTerms.toRdf4jValue(triple.obj)
            repo.noteWrittenValue(plainObject)
            conn.add(Rdf4jTerms.toRdf4jResource(triple.subject), Rdf4jTerms.toRdf4jIri(triple.predicate), plainObject, context)
            return
        }
        val quoted = (triple.subject as? BlankNode)?.let { Rdf4jTerms.quotedTripleOf(it.id, hashed) }
        val obj = Rdf4jTerms.toRdf4jStarValue(triple.obj, hashed)
        repo.noteWrittenValue(obj)
        if (quoted != null) {
            val plainReifier = Rdf4jTerms.toRdf4jResource(triple.subject)
            val reifiesIri = Rdf4jTerms.toRdf4jIri(RDF.reifies)
            if (triple.predicate == RDF.reifies && obj == quoted) {
                // Always stored, also while statements about the quoted triple imply it: the stored statement is what
                // makes the triple explicit, so it survives the removal of the last of them (see the class KDoc).
                conn.add(plainReifier, reifiesIri, obj, context)
                repo.noteQuotedWrite(Rdf4jTerms.quotedLevel(plainReifier, obj))
                return
            }
            if (conn.hasStatement(plainReifier, null, null, false, context) &&
                !conn.hasStatement(plainReifier, reifiesIri, quoted, false, context)
            ) {
                // Its `rdf:reifies` triple was removed while statements about the reifier remained (they were moved to
                // the plain reifier node): keep it that way instead of implying `rdf:reifies` again.
                repo.noteQuotedWrite(Rdf4jTerms.quotedLevel(plainReifier, obj))
                conn.add(plainReifier, Rdf4jTerms.toRdf4jIri(triple.predicate), obj, context)
                return
            }
        }
        val subject = Rdf4jTerms.toRdf4jStarResource(triple.subject, hashed)
        repo.noteWrittenValue(subject)
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

    private fun remove(conn: RepositoryConnection, removed: RdfTriple): Boolean {
        val triple = Rdf4jTerms.canonical(removed)
        val hashed = resolveHashedReifiers(conn, triple.subject, triple.obj)
        if (!involvesReifiedForm(triple.subject, triple.predicate, triple.obj, hashed)) {
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
        candidateStatements(conn, triple.subject, triple.predicate, triple.obj, hashed, false, exhaustive = true) { candidates.add(it) }
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

    override fun hasTriple(triple: RdfTriple): Boolean = hasCanonical(Rdf4jTerms.canonical(triple))

    private fun hasCanonical(triple: RdfTriple): Boolean = repo.withConnection { conn ->
        val hashed = resolveHashedReifiers(conn, triple.subject, triple.obj)
        if (!involvesReifiedForm(triple.subject, triple.predicate, triple.obj, hashed)) {
            val subject = Rdf4jTerms.toRdf4jResource(triple.subject)
            val predicate = Rdf4jTerms.toRdf4jIri(triple.predicate)
            conn.hasStatement(subject, predicate, Rdf4jTerms.toRdf4jValue(triple.obj), repo.inference, context) ||
                (needsTagCaseFallback(triple.obj) &&
                    conn.getStatements(subject, predicate, null, repo.inference, context).use { result ->
                        result.any { sameLiteral(it.`object`, triple.obj) }
                    })
        } else {
            matching(conn, triple.subject, triple.predicate, triple.obj, hashed).isNotEmpty()
        }
    }

    override fun getTriples(): List<RdfTriple> = find()

    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> =
        repo.withConnection { conn -> matching(conn, subject?.let(Rdf4jTerms::canonical), predicate, obj?.let(Rdf4jTerms::canonical)) }

    /** The triples of this graph with the subject [subject], read through [conn] (for `DESCRIBE`). */
    internal fun outgoing(conn: RepositoryConnection, subject: RdfResource): List<RdfTriple> =
        matching(conn, Rdf4jTerms.canonical(subject), null, null)

    /** Whether this graph holds a statement whose subject is the RDF-star triple [quoted] (for `DESCRIBE`). */
    internal fun hasQuotedSubject(conn: RepositoryConnection, quoted: Triple): Boolean =
        conn.hasStatement(quoted, null, null, repo.inference, context)

    /** Triples of the RDF 1.2 view matching the pattern, de-duplicated, in store order. */
    private fun matching(
        conn: RepositoryConnection,
        subject: RdfResource?,
        predicate: Iri?,
        obj: RdfTerm?,
        hashed: Map<String, Triple> = resolveHashedReifiers(conn, subject, obj),
    ): List<RdfTriple> {
        val out = LinkedHashSet<RdfTriple>()
        val skipped = Skipped()
        candidateStatements(conn, subject, predicate, obj, hashed, repo.inference) { statement ->
            convert(statement, skipped)?.forEach { if (matches(it, subject, predicate, obj)) out.add(it) }
        }
        if (out.isEmpty() && obj != null && needsTagCaseFallback(obj) && !involvesReifiedForm(subject, predicate, obj, hashed)) {
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
     * Reifier positions are resolved to their quoted triples and looked up through the store indexes. When the
     * repository knows that quoted-triple subjects are absent or occur only un-nested, that is all there is. Otherwise
     * (nested quoted subjects, or a store whose content is not tracked) a scan is added only for what the indexes
     * cannot answer, see [nestedCandidates]. The class KDoc lists what scans.
     *
     * @param exhaustive feed every statement the matching triples come from (removals), not just enough statements
     *   to produce each matching triple once (reads).
     */
    private fun candidateStatements(
        conn: RepositoryConnection,
        subject: RdfResource?,
        predicate: Iri?,
        obj: RdfTerm?,
        hashed: Map<String, Triple>,
        includeInferred: Boolean,
        exhaustive: Boolean = false,
        consumer: (Statement) -> Unit,
    ) {
        fun query(s: Resource?, p: org.eclipse.rdf4j.model.IRI?, o: Value?) =
            conn.getStatements(s, p, o, includeInferred, context).use { result -> result.forEach(consumer) }

        val plainSubject = subject?.let(Rdf4jTerms::toRdf4jResource)
        val plainPredicate = predicate?.let(Rdf4jTerms::toRdf4jIri)
        val plainObject = obj?.let(Rdf4jTerms::toRdf4jValue)
        val reifiesPattern = predicate == RDF.reifies
        // A triple-term object with an unbound predicate may match a synthesized `rdf:reifies` triple.
        if (!involvesReifiedForm(subject, predicate, obj, hashed) && !(obj is TripleTerm && predicate == null)) {
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
                val starSubject = subject?.let { Rdf4jTerms.toRdf4jStarResource(it, hashed) }
                val starObject = obj?.let { Rdf4jTerms.toRdf4jStarValue(it, hashed) }
                if (!reifiesPattern && (starSubject != plainSubject || starObject != plainObject)) {
                    query(starSubject, plainPredicate, starObject)
                }
                if (predicate == null || reifiesPattern) {
                    // `_:r rdf:reifies <<t>>` comes from the statements whose subject is `t`.
                    val quoted = (starSubject as? org.eclipse.rdf4j.model.Triple)
                        ?: (starObject as? org.eclipse.rdf4j.model.Triple)?.takeIf { obj is TripleTerm }
                    when {
                        quoted != null -> query(quoted, null, null)
                        // Synthesized triples have a reifier subject and a triple-term object: nothing else can match.
                        subject == null && obj == null -> widened(conn, subject, predicate, obj, hashed, includeInferred, consumer)
                    }
                }
            }
            QuotedLevel.NESTED, QuotedLevel.UNKNOWN -> nestedCandidates(conn, subject, predicate, obj, hashed, includeInferred, exhaustive, consumer)
        }
    }

    /**
     * [candidateStatements] for a store whose quoted subjects may nest (or are unknown).
     *
     * A converted statement has the subject `_:r` exactly when its own subject is the plain blank node `_:r` or the
     * quoted triple of `_:r`, so those are index lookups at any nesting. What the indexes cannot answer:
     * - an object that mentions a reifier has one store form per reifier in it (plain blank node or quoted triple);
     * - a synthesized `_:r rdf:reifies <<t>>` comes from any statement in which `t` is a subject, also nested inside
     *   another quoted triple. Reads look `t` up as a top-level subject (and as a stored plain `rdf:reifies`
     *   statement) first: when that already yields the triple, the nested occurrences add nothing. Removals must
     *   rewrite every statement the triple comes from.
     * Those cases fall back to [widened].
     */
    private fun nestedCandidates(
        conn: RepositoryConnection,
        subject: RdfResource?,
        predicate: Iri?,
        obj: RdfTerm?,
        hashed: Map<String, Triple>,
        includeInferred: Boolean,
        exhaustive: Boolean,
        consumer: (Statement) -> Unit,
    ) {
        val reifiesPattern = predicate == RDF.reifies
        val quotedSubject = subject?.let { Rdf4jTerms.toRdf4jStarResource(it, hashed) } as? Triple
        // Whether a synthesized `rdf:reifies` triple (reifier subject, triple-term object) can match the pattern.
        val synthesized = (predicate == null || reifiesPattern) && (obj == null || obj is TripleTerm) &&
            (subject == null || quotedSubject != null)
        if (Rdf4jTerms.objectMentionsStarReifier(obj, hashed) || (synthesized && (exhaustive || (subject == null && obj == null)))) {
            widened(conn, subject, predicate, obj, hashed, includeInferred, consumer)
            return
        }
        val plainSubject = subject?.let(Rdf4jTerms::toRdf4jResource)
        val plainPredicate = predicate?.let(Rdf4jTerms::toRdf4jIri)
        val plainObject = obj?.let(Rdf4jTerms::toRdf4jValue)
        // The quoted triple whose `rdf:reifies` triple the pattern asks for, if any.
        val target = if (synthesized) quotedSubject ?: plainObject as? org.eclipse.rdf4j.model.Triple else null
        val targetReifier = target?.let { Rdf4jTerms.reifierFor(it).id }
        val reifiesIri = Rdf4jTerms.toRdf4jIri(RDF.reifies)
        var implied = false
        val tracking: (Statement) -> Unit = { statement ->
            if (target != null && !implied) {
                implied = statement.subject == target || (statement.predicate == reifiesIri && statement.`object` == target &&
                    (statement.subject as? org.eclipse.rdf4j.model.BNode)?.id == targetReifier)
            }
            consumer(statement)
        }
        conn.getStatements(plainSubject, plainPredicate, plainObject, includeInferred, context).use { it.forEach(tracking) }
        if (quotedSubject != null) {
            conn.getStatements(quotedSubject, plainPredicate, plainObject, includeInferred, context).use { it.forEach(tracking) }
        }
        if (target == null || implied) return
        // One statement about the quoted triple is enough to yield its `rdf:reifies` triple.
        conn.getStatements(target, null, null, includeInferred, context).use { if (it.hasNext()) tracking(it.next()) }
        if (!implied) widened(conn, subject, predicate, obj, hashed, includeInferred, consumer)
    }

    /** Pattern with every position that only the synthesized reified form can satisfy replaced by a wildcard. */
    private fun widened(
        conn: RepositoryConnection,
        subject: RdfResource?,
        predicate: Iri?,
        obj: RdfTerm?,
        hashed: Map<String, Triple>,
        includeInferred: Boolean,
        consumer: (Statement) -> Unit,
    ) {
        val reifiesPattern = predicate == RDF.reifies
        val wideSubject = Rdf4jTerms.mentionsStarReifier(subject, hashed)
        val wideObject = reifiesPattern || Rdf4jTerms.objectMentionsStarReifier(obj, hashed) || (obj is TripleTerm && predicate == null)
        conn.getStatements(
            if (wideSubject) null else subject?.let(Rdf4jTerms::toRdf4jResource),
            if (reifiesPattern) null else predicate?.let(Rdf4jTerms::toRdf4jIri),
            if (wideObject) null else obj?.let(Rdf4jTerms::toRdf4jValue),
            includeInferred,
            context,
        ).use { result -> result.forEach(consumer) }
    }

    private fun involvesReifiedForm(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?, hashed: Map<String, Triple>): Boolean =
        predicate == RDF.reifies || Rdf4jTerms.mentionsStarReifier(subject, hashed) || Rdf4jTerms.objectMentionsStarReifier(obj, hashed)

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
            Rdf4jTerms.triplesOf(statement, null, repo.hashedReifiersRead)
        } else {
            try {
                Rdf4jTerms.triplesOf(statement, null, repo.hashedReifiersRead)
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
     * RDF4J's own statement count for the context is used when the store cannot contain quoted-triple subjects
     * (native stores) or the repository tracks that none exist, and reads are strict or every stored statement is known
     * to be convertible (lenient repositories created by a factory method and never changed by SPARQL `UPDATE`).
     *
     * The statements are counted in one pass over the graph (converting only those that hold a triple value or a
     * reifier blank node as subject, or every statement for a lenient read) in the remaining cases. Statements with a
     * triple-term object are among the converted ones on purpose: a stored `s p <<( _:r q z )>>` and an RDF-star
     * `s p << <<t>> q z >>` are one triple of the graph. Each of these cases needs information RDF4J has no count or
     * index for, so the pass cannot be avoided:
     * - inference repositories: RDF4J's count covers explicit statements only, and reads include the inferred ones;
     * - stores that hold (or may hold) quoted-triple subjects, at any nesting, or whose content is not tracked
     *   (wrapped, externally created RDF-star capable stores, or a tracked store right after a SPARQL update that may
     *   have created quoted subjects, until one scan re-derives the state): every distinct quoted subject adds a
     *   synthesized `rdf:reifies` triple, which may coincide with a stored one, and RDF4J cannot enumerate quoted
     *   subjects;
     * - lenient reads over wrapped stores or after a SPARQL update: unconvertible statements are not counted.
     */
    override fun size(): Int = repo.withConnection { conn ->
        val level = repo.quotedSubjects(conn)
        if (!repo.inference && (!repo.lenientRead || repo.allStatementsConvertible) && level == QuotedLevel.NONE) {
            return@withConnection Math.toIntExact(conn.size(context))
        }
        var plain = 0L
        val involved = HashSet<RdfTriple>()
        val skipped = Skipped()
        conn.getStatements(null, null, null, repo.inference, context).use { result ->
            result.forEach { statement ->
                val mayCollide = level != QuotedLevel.NONE && (
                    statement.subject is org.eclipse.rdf4j.model.Triple || statement.`object` is org.eclipse.rdf4j.model.Triple ||
                        (statement.subject as? org.eclipse.rdf4j.model.BNode)?.let {
                            // A blank node with a hashed reifier id counts whether or not it stands for a triple.
                            Rdf4jTerms.isHashedReifierId(it.id) || Rdf4jTerms.quotedTripleOf(it.id) != null
                        } == true
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

/**
 * A graph of a repository whose content only that repository object changes (see [Rdf4jRepository.versioned]): every
 * write is counted, so the graph carries a modification stamp. The stamp is the one of the repository (a write to any
 * of its graphs changes it), which the contract allows: a changed stamp does not promise changed content.
 */
internal class Rdf4jVersionedGraph(repo: Rdf4jRepository, context: Resource?) : Rdf4jGraph(repo, context), VersionedRdfGraph {
    /** Fails with [IllegalStateException] on a closed repository, like a content read. */
    override val modificationStamp: Long get() = repo.modificationStamp()
}
