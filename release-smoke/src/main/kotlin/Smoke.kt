import com.geoknoesis.kastor.rdf.*
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
    println("Staged Kastor BOM and both providers: OK")
}
