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
 * @param context the named-graph context, or null for the default graph.
 */
internal class Rdf4jGraph(
    private val repo: Rdf4jRepository,
    private val context: org.eclipse.rdf4j.model.Resource? = null,
) : MutableRdfGraph {

    override fun addTriple(triple: RdfTriple) = repo.withConnection { conn ->
        conn.add(
            Rdf4jTerms.toRdf4jResource(triple.subject),
            Rdf4jTerms.toRdf4jIri(triple.predicate),
            Rdf4jTerms.toRdf4jValue(triple.obj),
            context,
        )
    }

    override fun addTriples(triples: Collection<RdfTriple>) = repo.withConnection { conn ->
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

    override fun removeTriple(triple: RdfTriple): Boolean = repo.withConnection { conn ->
        conn.remove(
            Rdf4jTerms.toRdf4jResource(triple.subject),
            Rdf4jTerms.toRdf4jIri(triple.predicate),
            Rdf4jTerms.toRdf4jValue(triple.obj),
            context,
        )
        true
    }

    override fun removeTriples(triples: Collection<RdfTriple>): Boolean = repo.withConnection { conn ->
        var anyRemoved = false
        triples.forEach { triple ->
            conn.remove(
                Rdf4jTerms.toRdf4jResource(triple.subject),
                Rdf4jTerms.toRdf4jIri(triple.predicate),
                Rdf4jTerms.toRdf4jValue(triple.obj),
                context,
            )
            anyRemoved = true
        }
        anyRemoved
    }

    override fun hasTriple(triple: RdfTriple): Boolean = repo.withConnection { conn ->
        conn.hasStatement(
            Rdf4jTerms.toRdf4jResource(triple.subject),
            Rdf4jTerms.toRdf4jIri(triple.predicate),
            Rdf4jTerms.toRdf4jValue(triple.obj),
            false,
            context,
        )
    }

    override fun getTriples(): List<RdfTriple> = repo.withConnection { conn ->
        val triples = mutableListOf<RdfTriple>()
        // RepositoryResult holds a native cursor that must be closed; `use` guarantees
        // release even if term conversion throws part-way through iteration.
        conn.getStatements(null, null, null, false, context).use { result ->
            while (result.hasNext()) {
                val statement = result.next()
                triples.add(
                    RdfTriple(
                        Rdf4jTerms.fromRdf4jResource(statement.subject),
                        Rdf4jTerms.fromRdf4jIri(statement.predicate),
                        Rdf4jTerms.fromRdf4jValue(statement.`object`),
                    ),
                )
            }
        }
        triples
    }

    override fun clear(): Boolean = repo.withConnection { conn ->
        // Only clear this graph's context; a context-less clear() would wipe every graph.
        val wasEmpty = if (context != null) {
            !conn.hasStatement(null, null, null, false, context)
        } else {
            conn.isEmpty
        }
        if (context != null) conn.clear(context) else conn.clear()
        !wasEmpty
    }

    // size() with no context counts the whole repository; scope to this graph's context.
    override fun size(): Int = repo.withConnection { conn ->
        if (context != null) conn.size(context).toInt() else conn.size().toInt()
    }
}
