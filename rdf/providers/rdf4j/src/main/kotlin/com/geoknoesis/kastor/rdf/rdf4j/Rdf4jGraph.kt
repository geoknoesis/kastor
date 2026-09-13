package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*

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

    private fun remove(conn: org.eclipse.rdf4j.repository.RepositoryConnection, triple: RdfTriple): Boolean {
        val subject = Rdf4jTerms.toRdf4jResource(triple.subject)
        val predicate = Rdf4jTerms.toRdf4jIri(triple.predicate)
        val obj = Rdf4jTerms.toRdf4jValue(triple.obj)
        val existed = conn.hasStatement(subject, predicate, obj, false, context)
        if (existed) conn.remove(subject, predicate, obj, context)
        return existed
    }

    override fun hasTriple(triple: RdfTriple): Boolean = repo.withConnection { conn ->
        conn.hasStatement(
            Rdf4jTerms.toRdf4jResource(triple.subject),
            Rdf4jTerms.toRdf4jIri(triple.predicate),
            Rdf4jTerms.toRdf4jValue(triple.obj),
            repo.inference,
            context,
        )
    }

    override fun getTriples(): List<RdfTriple> = find()
    override fun find(subject: RdfResource?, predicate: Iri?, obj: RdfTerm?): List<RdfTriple> = repo.withConnection { conn ->
        conn.getStatements(subject?.let(Rdf4jTerms::toRdf4jResource), predicate?.let(Rdf4jTerms::toRdf4jIri),
            obj?.let(Rdf4jTerms::toRdf4jValue), repo.inference, context).use { result ->
            result.iterator().asSequence().map { RdfTriple(Rdf4jTerms.fromRdf4jResource(it.subject),
                Rdf4jTerms.fromRdf4jIri(it.predicate), Rdf4jTerms.fromRdf4jValue(it.`object`)) }.toList()
        }
    }
    override fun clear(): Boolean = repo.withWriteConnection { conn ->
        val changed = conn.hasStatement(null, null, null, false, context)
        conn.clear(context)
        changed
    }
    override fun size(): Int = repo.withConnection { conn -> if (repo.inference) find().size else Math.toIntExact(conn.size(context)) }
}
