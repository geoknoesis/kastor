package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.LensRdfLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Validates executable RDF definitions with native Kastor SHACL, then real providers. */
class LensShaclProfileProviderTest {
    private val text = """
        @prefix lens: <https://example.org/semantic-lens/1.0#> .
        @prefix ex: <urn:profile:> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        ex:all a lens:ClassLens ; lens:selectClass ex:Company .
        ex:eligible a lens:ClassLens ; lens:selectClass ex:Eligible .
        ex:union a lens:UnionLens ; lens:operands (ex:all ex:eligible) .
        ex:intersection a lens:IntersectionLens ; lens:operands (ex:all ex:eligible) .
        ex:difference a lens:DifferenceLens ;
          lens:leftOperand ex:all ; lens:rightOperand ex:eligible .
        ex:edge a lens:EdgeLens ; lens:predicate ex:link .
        ex:network a lens:NetworkLens ; lens:focusLens ex:all ;
          lens:edgeLens ex:edge ; lens:nodeLens ex:all ;
          lens:maxDepth 1 ; lens:direction lens:Outgoing ;
          lens:closureMode lens:Traversed .
        ex:ego a lens:EgoNetworkLens ; lens:seedLens ex:all ;
          lens:predicate ex:link ; lens:maxDepth 1 .
        ex:age a lens:BoundPathExpression ; lens:pathPredicate ex:age .
        ex:limit a lens:ConstantExpression ; lens:literalValue "18"^^xsd:integer .
        ex:adultCondition a lens:ComparisonExpression ;
          lens:leftExpression ex:age ; lens:operator lens:GreaterThan ;
          lens:rightExpression ex:limit .
        ex:filter a lens:FilterLens ; lens:inputLens ex:all ;
          lens:condition ex:adultCondition .
        ex:adultGroup a lens:BooleanExpression ;
          lens:operator lens:And ; lens:operands (ex:adultCondition) .
    """.trimIndent()
    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val integer = Iri("http://www.w3.org/2001/XMLSchema#integer")
    private val a = Iri("urn:profile:a")
    private val b = Iri("urn:profile:b")

    private fun verify(factory: () -> RdfRepository) {
        val definitions = Rdf.parse(text, "TURTLE")
        LensDefinitionShaclValidation.validateOrThrow(definitions) // bundled shapes
        factory().use { repo ->
            val g = repo.editDefaultGraph()
            for (node in listOf(a, b)) {
                g.addTriple(RdfTriple(node,rdfType,Iri("urn:profile:Company")))
            }
            g.addTriple(RdfTriple(a,rdfType,Iri("urn:profile:Eligible")))
            g.addTriple(RdfTriple(a,Iri("urn:profile:age"),lit("21",integer)))
            g.addTriple(RdfTriple(b,Iri("urn:profile:age"),lit("12",integer)))
            g.addTriple(RdfTriple(a,Iri("urn:profile:link"),b))

            val executor = KastorRdfLensExecutor(repo, definitions)
            fun nodeSet(local: String): Set<Iri> =
                (executor.executeValidated(Iri("urn:profile:" + local))
                    as KastorRdfLensExecutor.Result.Nodes).nodes
            assertEquals(setOf(a),nodeSet("intersection"))
            assertEquals(setOf(a,b),nodeSet("union"))
            assertEquals(setOf(b),nodeSet("difference"))
            assertEquals(setOf(a),nodeSet("filter"))
            val network = executor.executeValidated(Iri("urn:profile:network"))
                as KastorRdfLensExecutor.Result.Network
            assertEquals(setOf(a,b),network.graph.nodes)
            assertEquals(setOf(KastorLensProviderBridge.Edge(a,Iri("urn:profile:link"),b)),
                network.graph.edges)
        }

        fun reject(bad: String) {
            val graph = Rdf.parse(text + "\n" + bad, "TURTLE")
            assertThrows(IllegalArgumentException::class.java) {
                LensDefinitionShaclValidation.validateOrThrow(graph)
            }
        }
        reject("ex:badClass a lens:ClassLens .")
        reject("ex:badUnion a lens:UnionLens .")
        reject("ex:badDifference a lens:DifferenceLens ; lens:leftOperand ex:all .")
        reject("""ex:badNetwork a lens:NetworkLens ; lens:focusLens ex:all ;
            lens:edgeLens ex:edge ; lens:maxDepth 4 ; lens:closureMode lens:Traversed .""")
        reject("""ex:ambiguous a lens:NetworkLens ; lens:focusLens ex:all ;
            lens:edgeLens ex:edge ; lens:predicate ex:link ; lens:maxDepth 1 ;
            lens:closureMode lens:Traversed .""")
        reject("""ex:noEdge a lens:NetworkLens ; lens:focusLens ex:all ;
            lens:maxDepth 1 ; lens:closureMode lens:Traversed .""")
        reject("""ex:badGroup a lens:BooleanExpression ; lens:operator lens:GreaterThan ;
            lens:operands (ex:adultCondition) .""")
        reject("""ex:badComparison a lens:ComparisonExpression ;
            lens:operator lens:And ; lens:leftExpression ex:age ;
            lens:rightExpression ex:limit .""")
        reject("ex:badFilter a lens:FilterLens ; lens:inputLens ex:all .")

        val both = Rdf.parse(text + "\n" + """
            ex:ambiguous a lens:NetworkLens ; lens:focusLens ex:all ;
                lens:edgeLens ex:edge ; lens:predicate ex:link ;
                lens:maxDepth 1 ; lens:closureMode lens:Traversed .
        """.trimIndent(), "TURTLE")
        assertThrows(IllegalArgumentException::class.java) {
            LensRdfLoader(both).read(Iri("urn:profile:ambiguous"))
        }
    }

    @Test fun jenaShaclProfile() = verify { JenaRepository.MemoryRepository() }
    @Test fun rdf4jShaclProfile() = verify { Rdf4jRepository.MemoryRepository() }
}
