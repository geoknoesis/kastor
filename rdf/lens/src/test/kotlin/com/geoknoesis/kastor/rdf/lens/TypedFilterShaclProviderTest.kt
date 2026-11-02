package com.geoknoesis.kastor.rdf.lens

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository
import org.geoknoesis.lens.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Typed expression semantics and fail-closed SHACL validation against actual
 * Jena and RDF4J-backed Kastor repositories.
 */
class TypedFilterShaclProviderTest {
    private val definitions = """
        @prefix lens: <https://example.org/semantic-lens/1.0#> .
        @prefix ex: <urn:filter:> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

        ex:companies a lens:ClassLens ; lens:selectClass ex:Company .
        ex:age a lens:BoundPathExpression ; lens:pathPredicate ex:age .
        ex:name a lens:BoundPathExpression ; lens:pathPredicate ex:name .
        ex:limit a lens:ConstantExpression ; lens:literalValue "18"^^xsd:integer .
        ex:alice a lens:ConstantExpression ; lens:literalValue "Alice"^^xsd:string .
        ex:adultCondition a lens:ComparisonExpression ;
            lens:leftExpression ex:age ; lens:operator lens:GreaterThan ;
            lens:rightExpression ex:limit .
        ex:aliceCondition a lens:ComparisonExpression ;
            lens:leftExpression ex:name ; lens:operator lens:Equal ;
            lens:rightExpression ex:alice .
        ex:adult a lens:FilterLens ;
            lens:inputLens ex:companies ; lens:condition ex:adultCondition .
        ex:andCondition a lens:BooleanExpression ;
            lens:operator lens:And ; lens:operands (ex:adultCondition ex:aliceCondition) .
        ex:andFilter a lens:FilterLens ;
            lens:inputLens ex:companies ; lens:condition ex:andCondition .
        ex:notCondition a lens:BooleanExpression ;
            lens:operator lens:Not ; lens:operands (ex:adultCondition) .
        ex:notFilter a lens:FilterLens ;
            lens:inputLens ex:companies ; lens:condition ex:notCondition .
        ex:orCondition a lens:BooleanExpression ;
            lens:operator lens:Or ; lens:operands (ex:adultCondition ex:aliceCondition) .
        ex:orFilter a lens:FilterLens ;
            lens:inputLens ex:companies ; lens:condition ex:orCondition .
    """.trimIndent()

    private val xsdInteger = Iri("http://www.w3.org/2001/XMLSchema#integer")
    private val company = Iri("urn:filter:Company")
    private val rdfType = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val age = Iri("urn:filter:age")
    private val name = Iri("urn:filter:name")
    private val a = Iri("urn:filter:a")
    private val b = Iri("urn:filter:b")
    private val c = Iri("urn:filter:c")
    private val d = Iri("urn:filter:d")
    private val e = Iri("urn:filter:e")

    private fun verify(factory: () -> RdfRepository) {
        val schema = Rdf.parse(definitions, "TURTLE")
        val shapesText = checkNotNull(javaClass.getResource("/lens-execution-shapes.ttl")) {
            "SHACL execution profile resource must be packaged"
        }.readText()
        val shapes = Rdf.parse(shapesText, "TURTLE")
        factory().use { repo ->
            val g = repo.editDefaultGraph()
            for (resource in listOf(a,b,c,d,e)) g.addTriple(RdfTriple(resource, rdfType, company))
            for ((resource, years) in listOf(
                a to listOf(21), b to listOf(16), d to listOf(5,25), e to listOf(25)
            )) for (year in years) {
                g.addTriple(RdfTriple(resource,age,lit(year.toString(),xsdInteger)))
            }
            for ((resource, value) in listOf(a to "Alice",b to "Bob",
                c to "Alice",d to "Alice",e to "Bob")) {
                g.addTriple(RdfTriple(resource,name,string(value)))
            }

            val executor = KastorRdfLensExecutor(repo,schema)
            fun result(id: String) =
                (executor.executeValidated(Iri("urn:filter:" + id), shapes) as
                    KastorRdfLensExecutor.Result.Nodes).nodes

            assertEquals(setOf(a,d,e),result("adult"))
            assertEquals(setOf(a,d),result("andFilter"))
            assertEquals(setOf(b,c),result("notFilter"))
            assertEquals(setOf(a,c,d,e),result("orFilter"))

            val comparison = Filters.compare(
                Filters.path(age.value),
                CompareOperator.GT,
                Filters.constant(Value.TypedLiteral("18",xsdInteger.value))
            )
            val directlyBuilt = LensDsl.filter(LensDsl.type(company.value),comparison)
            assertEquals(setOf(a,d,e),
                KastorLensProviderBridge(repo).nodes(SparqlCompiler().select(directlyBuilt)))

            assertThrows(IllegalArgumentException::class.java) {
                SparqlCompiler().select(LensDsl.filter(
                    LensDsl.type(company.value),
                    Filters.compare(
                        Filters.path("urn:filter:age> } UNION { ?a ?b ?c"),
                        CompareOperator.EQ,
                        Filters.constant(Value.Iri("urn:filter:test"))
                    )
                ))
            }
        }
        val invalid = Rdf.parse(
            definitions + "\n<urn:filter:invalid> a <https://example.org/semantic-lens/1.0#ClassLens> .",
            "TURTLE"
        )
        assertThrows(IllegalArgumentException::class.java) {
            LensDefinitionShaclValidation.validateOrThrow(invalid,shapes)
        }
        val unsupported = Rdf.parse(
            definitions + """
                @prefix lens: <https://example.org/semantic-lens/1.0#> .
                @prefix ex: <urn:filter:> .
                ex:unknown a lens:ComparisonExpression ;
                  lens:leftExpression ex:age ; lens:rightExpression ex:limit ;
                  lens:operator lens:UnsafeFunction .
                ex:unknownFilter a lens:FilterLens ;
                  lens:inputLens ex:companies ; lens:condition ex:unknown .
            """.trimIndent(), "TURTLE"
        )
        assertThrows(Exception::class.java) {
            LensRdfLoader(unsupported).read(Iri("urn:filter:unknownFilter"))
        }
    }

    @Test fun jenaTypedAndShacl() = verify { JenaRepository.MemoryRepository() }
    @Test fun rdf4jTypedAndShacl() = verify { Rdf4jRepository.MemoryRepository() }
}
