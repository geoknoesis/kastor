package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
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
 * understand that view; removing only the `rdf:reifies` triple keeps the annotations on the plain blank node.
 *
 * @param context the named-graph context, or null for the default graph.
 */
internal class Rdf4jGraph(
    private val repo: Rdf4jRepository,
    private val context: org.eclipse.rdf4j.model.Resource? = null,
) : MutableRdfGraph {

    override fun addTriple(triple: RdfTriple) = repo.withWriteConnection { conn ->
        conn.add(
            Rdf4jTerms.toRdf4jResource(triple.subject),
            Rdf4jTerms.toRdf4jIri(triple.predicate),
            Rdf4jTerms.toRdf4jValue(triple.obj),
            context,
        )
    }

    override fun addTriples(triples: Collection<RdfTriple>) = addAll(triples.iterator())

    /** Streams [triples] through one connection and one transaction. */
    override fun addTriples(triples: Iterable<RdfTriple>) = addAll(triples.iterator())

    /** Streams [triples] through one connection and one transaction without materialising the sequence. */
    override fun addTriples(triples: Sequence<RdfTriple>) = addAll(triples.iterator())

    private fun addAll(triples: Iterator<RdfTriple>) = repo.withWriteConnection { conn ->
        // Borrow one connection for the whole batch rather than one per triple.
        triples.forEach { triple ->
            conn.add(
                Rdf4jTerms.toRdf4jResource(triple.subject),
                Rdf4jTerms.toRdf4jIri(triple.predicate),
                Rdf4jTerms.toRdf4jValue(triple.obj),
                context,
            )
        }
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
            val existed = conn.hasStatement(subject, predicate, obj, false, context)
            if (existed) conn.remove(subject, predicate, obj, context)
            return existed
        }
        var changed = false
        val candidates = candidateStatements(conn, triple.subject, triple.predicate, triple.obj, false) { it.toList() }
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
            conn.hasStatement(
                Rdf4jTerms.toRdf4jResource(triple.subject),
                Rdf4jTerms.toRdf4jIri(triple.predicate),
                Rdf4jTerms.toRdf4jValue(triple.obj),
                repo.inference,
                context,
            )
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
        candidateStatements(conn, subject, predicate, obj, repo.inference) { statements ->
            statements.forEach { statement ->
                Rdf4jTerms.triplesOf(statement).forEach { if (matches(it, subject, predicate, obj)) out.add(it) }
            }
        }
        return out.toList()
    }

    /**
     * Runs [block] over the store statements that can produce a pattern match. Pattern positions that can only
     * be satisfied by the synthesized reified form (a reifier subject, `rdf:reifies`, a triple-term object) are
     * widened to wildcards; callers filter the converted triples.
     */
    private fun <T> candidateStatements(
        conn: RepositoryConnection,
        subject: RdfResource?,
        predicate: Iri?,
        obj: RdfTerm?,
        includeInferred: Boolean,
        block: (Sequence<org.eclipse.rdf4j.model.Statement>) -> T,
    ): T {
        val reifiesPattern = predicate == RDF.reifies
        val wideSubject = Rdf4jTerms.mentionsStarReifier(subject)
        val wideObject = reifiesPattern || Rdf4jTerms.mentionsStarReifier(obj) || (obj is TripleTerm && predicate == null)
        return conn.getStatements(
            if (wideSubject) null else subject?.let(Rdf4jTerms::toRdf4jResource),
            if (reifiesPattern) null else predicate?.let(Rdf4jTerms::toRdf4jIri),
            if (wideObject) null else obj?.let(Rdf4jTerms::toRdf4jValue),
            includeInferred,
            context,
        ).use { result -> block(result.iterator().asSequence()) }
    }

    private fun involvesReifiedForm(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): Boolean =
        predicate == RDF.reifies || Rdf4jTerms.mentionsStarReifier(subject) || Rdf4jTerms.mentionsStarReifier(obj)

    private fun matches(triple: RdfTriple, subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): Boolean =
        (subject == null || triple.subject == subject) &&
            (predicate == null || triple.predicate == predicate) &&
            (obj == null || triple.obj == obj || sameLiteralIgnoringTagCase(triple.obj, obj))

    /** RDF4J compares language tags ignoring case; keep the Kastor-side filter consistent with the store. */
    private fun sameLiteralIgnoringTagCase(stored: RdfTerm, requested: RdfTerm): Boolean =
        stored is LangString && requested is LangString && stored.lexical == requested.lexical &&
            stored.direction == requested.direction && stored.lang.equals(requested.lang, ignoreCase = true)

    override fun clear(): Boolean = repo.withWriteConnection { conn ->
        val changed = conn.hasStatement(null, null, null, false, context)
        conn.clear(context)
        changed
    }

    /** Agrees with [getTriples]: statements with RDF-star subjects count their extra `rdf:reifies` triples. */
    override fun size(): Int = repo.withConnection { conn ->
        if (repo.inference) {
            matching(conn, null, null, null).size
        } else {
            var count = 0L
            var reified = false
            conn.getStatements(null, null, null, false, context).use { result ->
                result.forEach { statement ->
                    count++
                    if (!reified && Rdf4jTerms.hasQuotedSubject(statement)) reified = true
                }
            }
            if (reified) matching(conn, null, null, null).size else Math.toIntExact(count)
        }
    }
}
