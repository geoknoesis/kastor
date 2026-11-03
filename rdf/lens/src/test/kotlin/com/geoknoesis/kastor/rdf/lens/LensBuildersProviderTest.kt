package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The builder DSL must produce the model the RDF loader reads, and run on real providers. */
class LensBuildersProviderTest {
    private fun ex(local: String) = "urn:dsl:$local"
    private val xsd = "http://www.w3.org/2001/XMLSchema#"
    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")

    private val usCompanies = lens {
        type(ex("Company"))
        where {
            sequence(ex("locatedIn"), ex("regionName")) eq "US"
            not { path(ex("age")) lt 18 }
        }
        exclude { type(ex("Restricted")) }
    }
    private val permitted = lens {
        anyOf {
            type(ex("Supplier"))
            allOf {
                type(ex("Company"))
                where { inverse(ex("ownerOf")) eq Iri(ex("permitA")) }
            }
        }
    }
    private val supplyChain = network {
        seeds { type(ex("Start")) }
        traversalNode { type(ex("Company")) }
        predicate = ex("suppliesTo")
        maxDepth = 2
        closure = NetworkClosure.INDUCED
    }

    @Test fun builderProducesTheExpectedModel() {
        val company = Lens.Type(ex("Company"))
        val expected = Lens.Difference(
            Lens.Filter(company, BooleanPredicate.All(listOf(
                BooleanPredicate.Compare(
                    ValueExpression.SequencePath(listOf(ex("locatedIn"), ex("regionName"))),
                    CompareOperator.EQ,
                    ValueExpression.Constant(Value.TypedLiteral("US", xsd + "string"))),
                BooleanPredicate.Not(BooleanPredicate.Compare(
                    ValueExpression.Path(ex("age")),
                    CompareOperator.LT,
                    ValueExpression.Constant(Value.TypedLiteral("18", xsd + "integer"))))
            ))),
            Lens.Type(ex("Restricted"))
        )
        assertEquals(expected, usCompanies)
        assertEquals(Lens.Intersect(listOf(company, Lens.Type(ex("Large")))),
            lens { type(ex("Company")); type(ex("Large")) })
        assertEquals(Lens.Equals(company, ex("status"), Value.Iri(ex("Active"))),
            lens { type(ex("Company")); has(ex("status"), Iri(ex("Active"))) })
    }

    @Test fun builderRejectsIncompleteDefinitions() {
        assertThrows(IllegalArgumentException::class.java) { lens { } }
        assertThrows(IllegalArgumentException::class.java) { lens { type(ex("A")); where { } } }
        assertThrows(IllegalArgumentException::class.java) {
            lens { type(ex("A")); where { path(ex("p")) gt Iri(ex("x")) } }
        }
        assertThrows(IllegalArgumentException::class.java) { network { seeds { type(ex("A")) } } }
        assertThrows(IllegalArgumentException::class.java) {
            network { seeds { type(ex("A")) }; predicate = ex("p"); maxDepth = 4 }
        }
    }

    @Test fun writtenDefinitionsRoundTripAndPassShacl() {
        val definitions = lensDefinitions {
            define(ex("us"), usCompanies)
            define(ex("permitted"), permitted)
            define(ex("chain"), supplyChain)
        }
        LensDefinitionShaclValidation.validateOrThrow(definitions)
        val loader = LensRdfLoader(definitions)
        assertEquals(LensRdfLoader.Loaded.Node(usCompanies), loader.read(Iri(ex("us"))))
        assertEquals(LensRdfLoader.Loaded.Node(permitted), loader.read(Iri(ex("permitted"))))
        assertEquals(LensRdfLoader.Loaded.Network(supplyChain), loader.read(Iri(ex("chain"))))
    }

    @Test fun writerFailsClosedOnFeaturesWithoutAnRdfForm() {
        assertThrows(IllegalArgumentException::class.java) {
            lensDefinitions { define(ex("x")) { type(ex("A")); has(ex("p"), Iri(ex("v"))) } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            lensDefinitions { define(ex("x")) { type(ex("A")); has(ex("p")) { type(ex("B")) } } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            lensDefinitions {
                network(ex("x")) {
                    seeds { type(ex("A")) }; predicate = ex("p"); maxDepth = 1
                    outputNode { type(ex("B")) }
                }
            }
        }
    }

    private fun fixture(repo: RdfRepository) {
        val g = repo.editDefaultGraph()
        fun add(s: String, p: String, o: RdfTerm) = g.addTriple(RdfTriple(Iri(ex(s)), Iri(ex(p)), o))
        fun type(s: String, c: String) = g.addTriple(RdfTriple(Iri(ex(s)), rdfType, Iri(ex(c))))
        for (c in listOf("a", "b", "c", "d")) type(c, "Company")
        type("d", "Restricted")
        type("s", "Supplier")
        add("a", "locatedIn", Iri(ex("us")))
        add("c", "locatedIn", Iri(ex("us")))
        add("d", "locatedIn", Iri(ex("us")))
        add("b", "locatedIn", Iri(ex("eu")))
        add("us", "regionName", Literal("US"))
        add("eu", "regionName", Literal("EU"))
        add("c", "age", Literal("16", XSD.integer))
        add("permitA", "ownerOf", Iri(ex("b")))
        type("a", "Start")
        add("a", "suppliesTo", Iri(ex("b")))
        add("b", "suppliesTo", Iri(ex("c")))
        add("c", "suppliesTo", Iri(ex("d")))
        add("c", "suppliesTo", Iri(ex("a")))
    }

    private fun verify(factory: () -> RdfRepository) = factory().use { repo ->
        fixture(repo)
        val executor = KastorRdfLensExecutor(repo, lensDefinitions {
            define(ex("us"), usCompanies)
            define(ex("permitted"), permitted)
            define(ex("chain"), supplyChain)
        })
        fun nodes(id: String) = (executor.executeValidated(Iri(ex(id))) as KastorRdfLensExecutor.Result.Nodes).nodes
        fun iris(vararg locals: String) = locals.map { Iri(ex(it)) }.toSet()

        // c is under 18 and d is excluded; a has no age, so NOT(age < 18) holds.
        assertEquals(iris("a"), nodes("us"))
        assertEquals(iris("b", "s"), nodes("permitted"))
        assertEquals(nodes("us"),
            KastorLensProviderBridge(repo).nodes(SparqlCompiler().select(usCompanies)))

        val chain = (executor.executeValidated(Iri(ex("chain"))) as KastorRdfLensExecutor.Result.Network).graph
        assertEquals(iris("a", "b", "c"), chain.nodes)
        val supplies = Iri(ex("suppliesTo"))
        assertEquals(setOf(
            KastorLensProviderBridge.Edge(Iri(ex("a")), supplies, Iri(ex("b"))),
            KastorLensProviderBridge.Edge(Iri(ex("b")), supplies, Iri(ex("c"))),
            KastorLensProviderBridge.Edge(Iri(ex("c")), supplies, Iri(ex("a")))
        ), chain.edges)
    }

    @Test fun jenaExecutesDslDefinitions() = verify { JenaRepository.MemoryRepository() }
    @Test fun rdf4jExecutesDslDefinitions() = verify { Rdf4jRepository.MemoryRepository() }
}
