package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Executes actual SPARQL property paths produced by typed Kotlin and RDF lenses. */
class PropertyPathsProviderTest {
    private val definitions = """
        @prefix lens: <https://example.org/semantic-lens/1.0#> .
        @prefix ex: <urn:path:> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

        ex:companies a lens:ClassLens ; lens:selectClass ex:Company .
        ex:region a lens:SequencePathExpression ;
            lens:pathPredicates (ex:locatedIn ex:regionName) .
        ex:usa a lens:ConstantExpression ;
            lens:literalValue "US"^^xsd:string .
        ex:usaCondition a lens:ComparisonExpression ;
            lens:leftExpression ex:region ; lens:operator lens:Equal ;
            lens:rightExpression ex:usa .
        ex:usaCompanies a lens:FilterLens ;
            lens:inputLens ex:companies ; lens:condition ex:usaCondition .

        ex:permit a lens:InversePathExpression ;
            lens:pathPredicate ex:ownerOf .
        ex:permitA a lens:ConstantExpression ; lens:constantIri ex:permitAInstance .
        ex:permitCondition a lens:ComparisonExpression ;
            lens:leftExpression ex:permit ; lens:operator lens:Equal ;
            lens:rightExpression ex:permitA .
        ex:permitCompanies a lens:FilterLens ;
            lens:inputLens ex:companies ; lens:condition ex:permitCondition .

        ex:years a lens:AlternativePathExpression ;
            lens:pathPredicates (ex:age ex:backupAge) .
        ex:adultLimit a lens:ConstantExpression ;
            lens:literalValue "18"^^xsd:integer .
        ex:adultCondition a lens:ComparisonExpression ;
            lens:leftExpression ex:years ; lens:operator lens:GreaterThan ;
            lens:rightExpression ex:adultLimit .
        ex:adultCompanies a lens:FilterLens ;
            lens:inputLens ex:companies ; lens:condition ex:adultCondition .
    """.trimIndent()
    private val a = Iri("urn:path:a")
    private val b = Iri("urn:path:b")
    private val c = Iri("urn:path:c")
    private val d = Iri("urn:path:d")
    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val xsdInt = Iri("http://www.w3.org/2001/XMLSchema#integer")
    private val xsdStr = Iri("http://www.w3.org/2001/XMLSchema#string")
    private fun fixture(repo: RdfRepository) {
        val g = repo.editDefaultGraph()
        for (company in listOf(a,b,c,d)) {
            g.addTriple(RdfTriple(company,rdfType,Iri("urn:path:Company")))
        }
        val usa = Iri("urn:path:us")
        val eu = Iri("urn:path:eu")
        g.addTriple(RdfTriple(a,Iri("urn:path:locatedIn"),usa))
        g.addTriple(RdfTriple(b,Iri("urn:path:locatedIn"),eu))
        g.addTriple(RdfTriple(c,Iri("urn:path:locatedIn"),usa))
        g.addTriple(RdfTriple(usa,Iri("urn:path:regionName"),lit("US",xsdStr)))
        g.addTriple(RdfTriple(eu,Iri("urn:path:regionName"),lit("EU",xsdStr)))
        g.addTriple(RdfTriple(Iri("urn:path:permitAInstance"),Iri("urn:path:ownerOf"),a))
        g.addTriple(RdfTriple(Iri("urn:path:permitBInstance"),Iri("urn:path:ownerOf"),c))
        g.addTriple(RdfTriple(a,Iri("urn:path:age"),lit("22",xsdInt)))
        g.addTriple(RdfTriple(b,Iri("urn:path:backupAge"),lit("30",xsdInt)))
        g.addTriple(RdfTriple(c,Iri("urn:path:age"),lit("16",xsdInt)))
    }
    private fun verify(factory: () -> RdfRepository) {
        val schema = Rdf.parse(definitions,"TURTLE")
        LensDefinitionShaclValidation.validateOrThrow(schema)
        factory().use { repo ->
            fixture(repo)
            val executor = KastorRdfLensExecutor(repo,schema)
            fun run(id: String) =
                (executor.executeValidated(Iri("urn:path:" + id)) as
                    KastorRdfLensExecutor.Result.Nodes).nodes
            assertEquals(setOf(a,c),run("usaCompanies"))
            assertEquals(setOf(a),run("permitCompanies"))
            assertEquals(setOf(a,b),run("adultCompanies"))

            val path = Filters.sequence("urn:path:locatedIn", "urn:path:regionName")
            val literal = Filters.constant(Value.TypedLiteral("US",xsdStr.value))
            val query = LensDsl.filter(LensDsl.type("urn:path:Company"),
                Filters.compare(path,CompareOperator.EQ,literal))
            assertEquals(setOf(a,c),
                KastorLensProviderBridge(repo).nodes(SparqlCompiler().select(query)))

            // Negation has set-complement semantics: missing paths also match NOT.
            val nonUs = LensDsl.filter(LensDsl.type("urn:path:Company"),
                Filters.not(Filters.compare(path,CompareOperator.EQ,literal)))
            assertEquals(setOf(b,d),
                KastorLensProviderBridge(repo).nodes(SparqlCompiler().select(nonUs)))
            val inverse = LensDsl.filter(LensDsl.type("urn:path:Company"),
                Filters.compare(Filters.inverse("urn:path:ownerOf"),CompareOperator.EQ,
                    Filters.constant(Value.Iri("urn:path:permitAInstance"))))
            assertEquals(setOf(a),
                KastorLensProviderBridge(repo).nodes(SparqlCompiler().select(inverse)))

            assertThrows(IllegalArgumentException::class.java) {
                SparqlCompiler().select(LensDsl.filter(LensDsl.type("urn:path:Company"),
                    Filters.compare(Filters.sequence("urn:path:locatedIn", "urn:path:bad> UNION {"),
                        CompareOperator.EQ,literal)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                SparqlCompiler().select(LensDsl.filter(LensDsl.type("urn:path:Company"),
                    Filters.compare(Filters.alternative(*Array(9){"urn:path:age"}),
                        CompareOperator.GT,Filters.constant(Value.TypedLiteral("18",xsdInt.value)))))
            }
        }

        val invalid = Rdf.parse(definitions + "\n" + """
            @prefix lens: <https://example.org/semantic-lens/1.0#> .
            @prefix ex: <urn:path:> .
            ex:badPath a lens:SequencePathExpression ; lens:pathPredicates () .
            ex:badCondition a lens:ComparisonExpression ;
                lens:leftExpression ex:badPath ; lens:operator lens:Equal ;
                lens:rightExpression ex:usa .
            ex:badFilter a lens:FilterLens ;
                lens:inputLens ex:companies ; lens:condition ex:badCondition .
        """.trimIndent(),"TURTLE")
        assertThrows(IllegalArgumentException::class.java) {
            LensRdfLoader(invalid).read(Iri("urn:path:badFilter"))
        }
        val unsupported = Rdf.parse(definitions +
            "\n<urn:path:region> <https://example.org/semantic-lens/1.0#unbounded> <urn:path:yes> .",
            "TURTLE")
        assertThrows(IllegalArgumentException::class.java) {
            LensRdfLoader(unsupported).read(Iri("urn:path:usaCompanies"))
        }
    }

    @Test fun jenaPropertyPaths() = verify { JenaRepository.MemoryRepository() }
    @Test fun rdf4jPropertyPaths() = verify { Rdf4jRepository.MemoryRepository() }
}
