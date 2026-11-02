package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Independent bounded-walk oracle against real Jena and RDF4J repositories. */
class NetworkLensCompilerProviderTest {
    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val rdfSubject = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#subject")
    private val rdfPredicate = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#predicate")
    private val rdfObject = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#object")
    private val start = Iri("urn:net:Start")
    private val entity = Iri("urn:net:Entity")
    private val visible = Iri("urn:net:Visible")
    private val allowed = Iri("urn:net:Allowed")
    private val link = Iri("urn:net:link")
    private val status = Iri("urn:net:status")
    private val approved = Iri("urn:net:Approved")
    private val review = Iri("urn:net:review")
    private val verified = Iri("urn:net:Verified")
    private val category = Iri("urn:net:category")
    private val important = Iri("urn:net:Important")
    private val a = Iri("urn:net:a")
    private val b = Iri("urn:net:b")
    private val c = Iri("urn:net:c")
    private val d = Iri("urn:net:d")
    private val e = Iri("urn:net:e")
    private data class E(val s: Iri, val o: Iri)
    private val eligible = listOf(E(a,b),E(b,c),E(a,d),E(c,d),E(b,a))
    private val selectedMetadata = setOf(E(a,b),E(a,d))
    private val allLinks = eligible + listOf(E(b,d),E(a,e))

    private fun fixture(repo: RdfRepository) {
        val g = repo.editDefaultGraph()
        (listOf(a,b,c,d,e)).forEach { g.addTriple(RdfTriple(it,rdfType,entity)) }
        g.addTriple(RdfTriple(a,rdfType,start))
        listOf(a,b,d).forEach { g.addTriple(RdfTriple(it,rdfType,visible)) }
        listOf(a,c,d).forEach { g.addTriple(RdfTriple(it,rdfType,allowed)) }
        allLinks.forEach { g.addTriple(RdfTriple(it.s,link,it.o)) }
        eligible.forEachIndexed { i, edge ->
            val statement = Iri("urn:net:reifier-" + i)
            g.addTriple(RdfTriple(statement,rdfSubject,edge.s))
            g.addTriple(RdfTriple(statement,rdfPredicate,link))
            g.addTriple(RdfTriple(statement,rdfObject,edge.o))
            g.addTriple(RdfTriple(statement,status,approved))
            g.addTriple(RdfTriple(statement,review,verified))
            if (edge in selectedMetadata) g.addTriple(RdfTriple(statement,category,important))
        }
        // These two separate annotations MUST NOT be combined as one matching edge.
        val bogus = E(b,d)
        listOf(status to approved,review to verified).forEachIndexed { index, pair ->
            val statement = Iri("urn:net:split-" + index)
            g.addTriple(RdfTriple(statement,rdfSubject,bogus.s))
            g.addTriple(RdfTriple(statement,rdfPredicate,link))
            g.addTriple(RdfTriple(statement,rdfObject,bogus.o))
            g.addTriple(RdfTriple(statement,pair.first,pair.second))
        }
    }

    private fun lens(
        seed: Iri,
        direction: NetworkDirection,
        depth: Int,
        closure: NetworkClosure,
        visibleOnly: Boolean = false,
        guardedNodes: Boolean = false,
        importantOnly: Boolean = false
    ) = NetworkLens(
        seeds = LensDsl.type(if (seed == a) start.value else "urn:net:Destination"),
        predicate = link.value,
        direction = direction,
        maxDepth = depth,
        traversalNode = if (guardedNodes) LensDsl.type(allowed.value) else LensDsl.type(entity.value),
        outputNode = if (visibleOnly) LensDsl.type(visible.value) else null,
        traversalEdge = listOf(
            EdgeCondition(status.value,Value.Iri(approved.value)),
            EdgeCondition(review.value,Value.Iri(verified.value))
        ),
        outputEdge = if (importantOnly)
            listOf(EdgeCondition(category.value,Value.Iri(important.value))) else emptyList(),
        closure = closure
    )

    private fun expected(
        seed: Iri, direction: NetworkDirection, depth: Int,
        closure: NetworkClosure, visibleOnly: Boolean = false,
        guardedNodes: Boolean = false, importantOnly: Boolean = false
    ): KastorLensProviderBridge.Graph {
        val eligibleNodes = if (guardedNodes) setOf(a,c,d) else setOf(a,b,c,d,e)
        val outputNodes = if (visibleOnly) setOf(a,b,d) else eligibleNodes
        var frontier = setOf(seed).intersect(eligibleNodes)
        val reached = frontier.toMutableSet()
        val witnessed = linkedSetOf<E>()
        repeat(depth) {
            val next = mutableSetOf<Iri>()
            for (edge in eligible) {
                val out = direction != NetworkDirection.IN && edge.s in frontier && edge.o in eligibleNodes
                val inward = direction != NetworkDirection.OUT && edge.o in frontier && edge.s in eligibleNodes
                if (out || inward) {
                    witnessed.add(edge)
                    if (out) next.add(edge.o)
                    if (inward) next.add(edge.s)
                }
            }
            reached.addAll(next)
            frontier = next
        }
        val resultNodes = reached.intersect(outputNodes)
        val all = if (closure == NetworkClosure.TRAVERSED) witnessed else eligible.toSet()
        val resultEdges = all.filter {
            it.s in resultNodes && it.o in resultNodes && (!importantOnly || it in selectedMetadata)
        }.map { KastorLensProviderBridge.Edge(it.s,link,it.o) }.toSet()
        return KastorLensProviderBridge.Graph(resultNodes,resultEdges)
    }

    private fun verify(factory: () -> RdfRepository) {
        factory().use { repo ->
            fixture(repo)
            val bridge = KastorLensProviderBridge(repo)
            val compiler = NetworkLensCompiler()
            for (direction in NetworkDirection.entries) for (depth in 0..3) {
                val seed = if (direction == NetworkDirection.IN) d else a
                // Use Destination class for the backward traversal.
                if (seed == d && !repo.defaultGraph.hasTriple(RdfTriple(d,rdfType,Iri("urn:net:Destination")))) {
                    repo.editDefaultGraph().addTriple(RdfTriple(d,rdfType,Iri("urn:net:Destination")))
                }
                for (closure in NetworkClosure.entries) for (visibleOnly in listOf(false,true)) {
                    val lens = lens(seed,direction,depth,closure,visibleOnly=visibleOnly)
                    val compiled = compiler.compile(lens)
                    val actual = bridge.network(compiled.nodes,compiled.edges)
                    assertEquals(expected(seed,direction,depth,closure,visibleOnly),actual,
                        "direction=" + direction + " depth=" + depth + " closure=" + closure + " visible=" + visibleOnly)
                }
            }

            val constrained = lens(a,NetworkDirection.OUT,3,NetworkClosure.INDUCED,guardedNodes=true)
            val constrainedCompiled = compiler.compile(constrained)
            assertEquals(expected(a,NetworkDirection.OUT,3,NetworkClosure.INDUCED,guardedNodes=true),
                bridge.network(constrainedCompiled.nodes,constrainedCompiled.edges))

            val importantOnly = lens(a,NetworkDirection.OUT,3,NetworkClosure.INDUCED,importantOnly=true)
            val importantCompiled = compiler.compile(importantOnly)
            assertEquals(expected(a,NetworkDirection.OUT,3,NetworkClosure.INDUCED,importantOnly=true),
                bridge.network(importantCompiled.nodes,importantCompiled.edges))

            assertThrows(IllegalStateException::class.java) {
                bridge.network(importantCompiled.nodes,importantCompiled.edges,
                    KastorLensProviderBridge.Limits(maxNodes=1))
            }
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(lens(a,NetworkDirection.OUT,3,NetworkClosure.TRAVERSED)
                    .copy(predicate="urn:net:link> } UNION { ?s ?p ?o"))
            }
            val empty = compiler.compile(lens(a,NetworkDirection.OUT,2,NetworkClosure.INDUCED)
                .copy(seeds=LensDsl.type("urn:net:Nonexistent")))
            assertEquals(KastorLensProviderBridge.Graph(emptySet(),emptySet()),
                bridge.network(empty.nodes,empty.edges))
        }
    }

    @Test fun jenaNetworkLens() = verify { JenaRepository.MemoryRepository() }
    @Test fun rdf4jNetworkLens() = verify { Rdf4jRepository.MemoryRepository() }
}
