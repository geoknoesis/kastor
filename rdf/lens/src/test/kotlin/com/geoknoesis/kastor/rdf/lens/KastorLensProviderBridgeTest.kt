package com.geoknoesis.kastor.rdf.lens
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class KastorLensProviderBridgeTest {
    private val kind = Iri("urn:lens:Company")
    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val link = Iri("urn:lens:related")
    private val a = Iri("urn:lens:a")
    private val b = Iri("urn:lens:b")
    private val c = Iri("urn:lens:c")
    private val graph = Iri("urn:lens:tenant")
    private fun verify(factory: () -> RdfRepository) {
        factory().use { repo ->
            val g = repo.editDefaultGraph()
            for (node in listOf(a, b)) g.addTriple(RdfTriple(node, rdfType, kind))
            g.addTriple(RdfTriple(a, link, b))
            g.addTriple(RdfTriple(b, link, a))
            g.addTriple(RdfTriple(b, link, c))
            val nodeQuery = "SELECT DISTINCT ?node WHERE { ?node a <" + kind.value + "> }"
            val edgeQuery = "SELECT DISTINCT ?subject ?predicate ?object WHERE { " +
                "?subject ?predicate ?object . FILTER(?predicate = <" + link.value + ">) " +
                "FILTER(?subject IN (<" + a.value + ">, <" + b.value + ">)) " +
                "FILTER(?object IN (<" + a.value + ">, <" + b.value + ">)) }"
            val executor = KastorLensProviderBridge(repo)
            val result = executor.network(nodeQuery, edgeQuery,
                KastorLensProviderBridge.Limits(timeout = Duration.ofSeconds(10)))
            assertEquals(setOf(a, b), result.nodes)
            assertEquals(setOf(
                KastorLensProviderBridge.Edge(a, link, b),
                KastorLensProviderBridge.Edge(b, link, a)), result.edges)
            assertThrows(IllegalStateException::class.java) {
                executor.network(nodeQuery, edgeQuery, KastorLensProviderBridge.Limits(maxNodes = 1))
            }
            repo.createGraph(graph)
            repo.editGraph(graph).addTriple(RdfTriple(c, rdfType, kind))
            val scoped = "SELECT ?node FROM <" + graph.value + "> WHERE { ?node a <" + kind.value + "> }"
            assertEquals(setOf(c), executor.nodes(scoped))
            val absent = "SELECT ?node FROM <urn:lens:absent> WHERE { ?node a <" + kind.value + "> }"
            val missing = runCatching { executor.nodes(absent) }
            if (missing.isSuccess) assertEquals(emptySet<Iri>(), missing.getOrThrow())
        }
    }
    @Test fun jenaProviderIntegration() = verify { JenaRepository.MemoryRepository() }
    @Test fun rdf4jProviderIntegration() = verify { Rdf4jRepository.MemoryRepository() }
}
