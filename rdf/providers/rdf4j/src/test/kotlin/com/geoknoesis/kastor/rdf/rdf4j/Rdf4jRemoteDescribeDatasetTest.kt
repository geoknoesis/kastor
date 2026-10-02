package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.DescribesQueryDataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.SparqlDescribeQuery
import com.geoknoesis.kastor.rdf.string
import org.eclipse.rdf4j.query.BooleanQuery
import org.eclipse.rdf4j.query.GraphQuery
import org.eclipse.rdf4j.query.QueryLanguage
import org.eclipse.rdf4j.query.TupleQuery
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.base.RepositoryConnectionWrapper
import org.eclipse.rdf4j.repository.base.RepositoryWrapper
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `Dataset.describe` stays within the graphs of the dataset also when the repository wraps an RDF4J repository that
 * is not evaluated by a Sail (an `HTTPRepository` or a `SPARQLRepository`): such a server computes its own
 * `DESCRIBE`, from whatever graphs it likes, so [Rdf4jRepository] must not claim [DescribesQueryDataset].
 */
class Rdf4jRemoteDescribeDatasetTest {
    private val s = Iri("urn:s")
    private val p = Iri("urn:p")
    private val g1 = Iri("urn:g1")

    /**
     * Stands for a remote repository: its queries are not `SailQuery` objects, and it answers them as RDF4J itself
     * does - a `DESCRIBE` without a dataset reads the union of all graphs.
     */
    private class RemoteLikeRepository(private val store: SailRepository) : RepositoryWrapper(store) {
        override fun getConnection(): RepositoryConnection = object : RepositoryConnectionWrapper(this, store.connection) {
            override fun prepareGraphQuery(ql: QueryLanguage, query: String, baseURI: String?): GraphQuery {
                val prepared = delegate.prepareGraphQuery(ql, query, baseURI)
                return object : GraphQuery by prepared {}
            }

            override fun prepareTupleQuery(ql: QueryLanguage, query: String, baseURI: String?): TupleQuery {
                val prepared = delegate.prepareTupleQuery(ql, query, baseURI)
                return object : TupleQuery by prepared {}
            }

            override fun prepareBooleanQuery(ql: QueryLanguage, query: String, baseURI: String?): BooleanQuery {
                val prepared = delegate.prepareBooleanQuery(ql, query, baseURI)
                return object : BooleanQuery by prepared {}
            }
        }
    }

    private fun remoteLike(): Rdf4jRepository {
        val repo = Rdf4jRepository(RemoteLikeRepository(SailRepository(MemoryStore()).also { it.init() }))
        repo.editDefaultGraph().addTriple(RdfTriple(s, p, string("inside")))
        repo.editGraph(g1).addTriple(RdfTriple(s, p, string("outside")))
        return repo
    }

    private fun Sequence<RdfTriple>.lexicals(): List<String> = mapNotNull { (it.obj as? Literal)?.lexical }.distinct().sorted().toList()

    @Test
    fun `the repository does not promise what a remote server describes`() {
        remoteLike().use { repo ->
            assertFalse((repo as RdfRepository) is DescribesQueryDataset)
            // The server's own description (here: RDF4J's, from every graph) is returned as it is by the repository.
            assertEquals(listOf("inside", "outside"), repo.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).lexicals())
        }
    }

    @Test
    fun `Dataset describe stays within the dataset on a repository that is not evaluated by a Sail`() {
        remoteLike().use { repo ->
            val dataset = Dataset { defaultGraph(repo) }
            assertEquals(listOf("inside"), dataset.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).lexicals())
            assertEquals(listOf("inside"), dataset.describe(SparqlDescribeQuery("DESCRIBE ?x WHERE { ?x <urn:p> ?o }")).lexicals())
        }
    }

    @Test
    fun `Dataset describe on a Sail repository is unchanged by the filter`() {
        Rdf4jRepository.MemoryRepository().use { repo ->
            repo.editDefaultGraph().addTriple(RdfTriple(s, p, string("inside")))
            repo.editGraph(g1).addTriple(RdfTriple(s, p, string("outside")))
            val dataset = Dataset { defaultGraph(repo) }
            assertEquals(listOf("inside"), dataset.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).lexicals())
            assertEquals(repo.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).toList(), dataset.describe(SparqlDescribeQuery("DESCRIBE <urn:s>")).toList())
        }
    }
}
