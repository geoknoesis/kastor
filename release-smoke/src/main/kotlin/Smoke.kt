import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.holon.get
import com.geoknoesis.kastor.rdf.holon.holons
import com.geoknoesis.kastor.rdf.jena.JenaRepository
import com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository

fun main() {
    for (repository in listOf(JenaRepository.MemoryRepository(), Rdf4jRepository.MemoryRepository())) {
        repository.use { repo ->
            val triple = RdfTriple(Iri("urn:smoke:s"), Iri("urn:smoke:p"), Literal("ready"))
            repo.transaction { editDefaultGraph().addTriple(triple) }
            check(repo.defaultGraph.find(triple.subject, triple.predicate) == listOf(triple))
            val count = repo.withSelectRows(SparqlSelectQuery("SELECT ?s WHERE { ?s <urn:smoke:p> ?o }")) { it.count() }
            check(count == 1)
        }
    }
    // rdf-holon: a child's content aggregates into its parent's scope, without any provider.
    val city = Iri("urn:smoke:city")
    val district = Iri("urn:smoke:district")
    val context = holons {
        holon(city) { content { city[Iri("urn:smoke:name")] = "City" } }
        holon(district) { parent(city); content { district[Iri("urn:smoke:name")] = "District" } }
    }
    check(context[city].aggregated().graph.size() == 2)
    check(context[city].content.size() == 1)
    println("Staged Kastor BOM, both providers and rdf-holon: OK")
}
