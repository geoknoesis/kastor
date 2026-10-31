package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * RDF source is parsed using Kastor; lens definitions use the actual draft v0.2 vocabulary.
 * The loader never dereferences owl:imports or arbitrary remote source IRIs.
 */
class LensRdfLoaderProviderTest {
    private val turtle = """
        @prefix lens: <https://example.org/semantic-lens/1.0#> .
        @prefix ex: <urn:lens:> .

        ex:all a lens:ClassLens ; lens:selectClass ex:Company .
        ex:healthcare a lens:ClassLens ; lens:selectClass ex:Healthcare .
        ex:restricted a lens:ClassLens ; lens:selectClass ex:Restricted .
        ex:joined a lens:IntersectionLens ; lens:operands ( ex:all ex:healthcare ) .
        ex:either a lens:UnionLens ; lens:operands ( ex:joined ex:restricted ) .
        ex:difference a lens:DifferenceLens ;
            lens:leftOperand ex:joined ; lens:rightOperand ex:restricted .
        ex:seed a lens:ClassLens ; lens:selectClass ex:Start .
        ex:eligible a lens:ClassLens ; lens:selectClass ex:Company .
        ex:edgeDef a lens:EdgeLens ; lens:predicate ex:link .
        ex:network a lens:NetworkLens ; lens:focusLens ex:seed ;
            lens:nodeLens ex:eligible ; lens:edgeLens ex:edgeDef ;
            lens:maxDepth 2 ; lens:direction lens:Outgoing ;
            lens:closureMode lens:Traversed .
        ex:ego a lens:EgoNetworkLens ; lens:seedLens ex:seed ;
            lens:predicate ex:link ; lens:maxDepth 1 ;
            lens:direction lens:Outgoing .
        ex:duplicate a lens:ClassLens ; lens:selectClass ex:Company, ex:Restricted .
        ex:missing a lens:ClassLens .
        ex:badType a lens:FilterLens ; lens:inputLens ex:all .
        ex:cycle a lens:DifferenceLens ;
            lens:leftOperand ex:cycle ; lens:rightOperand ex:all .
        ex:invalidList a lens:UnionLens ; lens:operands _:bad .
        _:bad a <http://www.w3.org/1999/02/22-rdf-syntax-ns#List> ;
            <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> ex:all ;
            <http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> _:bad .
        ex:wrongDepth a lens:NetworkLens ; lens:focusLens ex:seed ;
            lens:edgeLens ex:edgeDef ; lens:maxDepth 4 ;
            lens:closureMode lens:Traversed .
        ex:ignoredCondition a lens:NetworkLens ; lens:focusLens ex:seed ;
            lens:edgeLens ex:edgeDef ; lens:maxDepth 1 ;
            lens:closureMode lens:Traversed ;
            lens:traversalNodeCondition ex:expression .
    """.trimIndent()
    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val a = Iri("urn:lens:a")
    private val b = Iri("urn:lens:b")
    private val c = Iri("urn:lens:c")
    private val d = Iri("urn:lens:d")
    private fun verify(factory: () -> RdfRepository) {
        val definitions = Rdf.parse(turtle, "TURTLE")
        val loader = LensRdfLoader(definitions)
        factory().use { repo ->
            val g = repo.editDefaultGraph()
            listOf(a,b,c,d).forEach { g.addTriple(RdfTriple(it,rdfType,Iri("urn:lens:Company"))) }
            listOf(a,b).forEach { g.addTriple(RdfTriple(it,rdfType,Iri("urn:lens:Healthcare"))) }
            g.addTriple(RdfTriple(b,rdfType,Iri("urn:lens:Restricted")))
            g.addTriple(RdfTriple(a,rdfType,Iri("urn:lens:Start")))
            val link = Iri("urn:lens:link")
            listOf(a to b, b to c, c to d, b to d).forEach {
                g.addTriple(RdfTriple(it.first,link,it.second))
            }
            val port = KastorLensProviderBridge(repo)
            val compiler = SparqlCompiler()
            val facade = KastorRdfLensExecutor(repo, definitions)
            assertEquals(KastorRdfLensExecutor.Result.Nodes(setOf(a)),
                facade.execute(Iri("urn:lens:difference")))
            assertEquals(
                setOf(a,b,c,d),
                (facade.execute(Iri("urn:lens:network")) as KastorRdfLensExecutor.Result.Network).graph.nodes
            )

            fun parsed(name: String): Lens =
                (loader.read(Iri("urn:lens:" + name)) as LensRdfLoader.Loaded.Node).expression
            assertEquals(setOf(a,b),port.nodes(compiler.select(parsed("joined"))))
            assertEquals(setOf(a,b),port.nodes(compiler.select(parsed("either"))))
            assertEquals(setOf(a),port.nodes(compiler.select(parsed("difference"))))

            val network = (loader.read(Iri("urn:lens:network")) as LensRdfLoader.Loaded.Network).expression
            val networkQuery = NetworkLensCompiler().compile(network)
            val graph = port.network(networkQuery.nodes,networkQuery.edges)
            assertEquals(setOf(a,b,c,d),graph.nodes)
            assertEquals(setOf(
                KastorLensProviderBridge.Edge(a,link,b),
                KastorLensProviderBridge.Edge(b,link,c),
                KastorLensProviderBridge.Edge(b,link,d)
            ),graph.edges)

            val ego = (loader.read(Iri("urn:lens:ego")) as LensRdfLoader.Loaded.Network).expression
            val egoQuery = NetworkLensCompiler().compile(ego)
            assertEquals(setOf(a,b),port.network(egoQuery.nodes,egoQuery.edges).nodes)
        }
        for (invalid in listOf("duplicate","missing","badType","cycle","invalidList",
            "wrongDepth","ignoredCondition")) {
            assertThrows(Exception::class.java) {
                val value = loader.read(Iri("urn:lens:" + invalid))
                if (value is LensRdfLoader.Loaded.Network) NetworkLensCompiler().compile(value.expression)
            }
        }
    }
    @Test fun jenaRdfLensDefinitions() = verify { JenaRepository.MemoryRepository() }
    @Test fun rdf4jRdfLensDefinitions() = verify { Rdf4jRepository.MemoryRepository() }
}
