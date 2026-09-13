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

    override fun addTriples(triples: Collection<RdfTriple>) = repo.withWriteConnection { conn ->
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

    override fun removeTriple(triple: RdfTriple): Boolean = repo.withWriteConnection { conn ->
        val existed = conn.hasStatement(Rdf4jTerms.toRdf4jResource(triple.subject),
            Rdf4jTerms.toRdf4jIri(triple.predicate), Rdf4jTerms.toRdf4jValue(triple.obj), false, context)
        conn.remove(
            Rdf4jTerms.toRdf4jResource(triple.subject),
            Rdf4jTerms.toRdf4jIri(triple.predicate),
            Rdf4jTerms.toRdf4jValue(triple.obj),
            context,
        )
        existed
    }

    override fun removeTriples(triples: Collection<RdfTriple>): Boolean = repo.withWriteConnection {
        var changed = false
        triples.forEach { if (removeTriple(it)) changed = true }
        changed
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
