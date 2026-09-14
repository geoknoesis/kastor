/*
 * Compiled mirror of the Kotlin samples in the repository README.md.
 *
 * Every Kotlin snippet in README.md has a function here with the same code, so API drift breaks
 * the build instead of silently rotting the documentation (CONTRIBUTING.md → Documentation).
 * The domain-mapping sample lives in ReadmeDomainSnippets.kt (the @Rdf annotation clashes with the
 * Rdf object when both are imported). The functions are compiled by `./gradlew :examples:hello-world:compileKotlin`; they are not run.
 */
@file:Suppress("unused", "UNUSED_VARIABLE")

import com.geoknoesis.kastor.rdf.Direction
import com.geoknoesis.kastor.rdf.MutableRdfGraph
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.add
import com.geoknoesis.kastor.rdf.iri
import com.geoknoesis.kastor.rdf.lit
import com.geoknoesis.kastor.rdf.string
import com.geoknoesis.kastor.rdf.jena.toJenaModel
import com.geoknoesis.kastor.rdf.jena.toKastorGraph
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.XSD
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.eclipse.rdf4j.repository.Repository

// README: "Example: Using Existing Jena Infrastructure"
fun readmeJenaInterop() {
    val jenaModel: Model = ModelFactory.createDefaultModel()
    // Reads are strict: a statement Kastor cannot represent (e.g. xml:lang="en_US") fails the read.
    // Use JenaBridge.fromJenaModel(jenaModel, strictRead = false) to skip such statements instead.
    val graph: MutableRdfGraph = jenaModel.toKastorGraph()
    graph.add {
        val person = iri("http://example.org/alice")
        person has FOAF.name with "Alice"
        person has FOAF.age with 30
    }
    val underlyingModel = graph.toJenaModel()
    val statement = underlyingModel.listStatements().next()
}

// README: "Example: Using Existing RDF4J Repository"
fun readmeRdf4jInterop(rdf4jRepo: Repository) {
    // Graph reads are strict; pass Rdf4jRepository(rdf4jRepo, inference = false, lenientRead = true)
    // to skip statements Kastor cannot represent instead of failing.
    val repo = com.geoknoesis.kastor.rdf.rdf4j.Rdf4jRepository(rdf4jRepo)
    repo.add {
        val person = iri("http://example.org/alice")
        person has FOAF.name with "Alice"
    }
    val jenaRepo = Rdf.repository {
        providerId = "jena"
        variantId = "tdb2"
        location = "/data/tdb2"
    }
}

// README: "Quick Start → Basic RDF Operations"
fun readmeBasicOperations() {
    val repo = Rdf.memory()

    repo.add {
        prefixes {
            put("ex", "http://example.org/")
        }

        val alice = qname("ex:alice")
        val bob = iri("http://example.org/bob")

        alice - RDF.type - FOAF.Person
        bob `is` FOAF.Person
        alice - FOAF.name - "Alice"
        alice has FOAF.age with 30
        alice[FOAF.knows] = bob
    }

    val results = repo.select(SparqlSelectQuery("""
        PREFIX foaf: <http://xmlns.com/foaf/0.1/>
        SELECT ?name WHERE { ?person a foaf:Person ; foaf:name ?name }
    """))
    results.forEach { row -> println(row.get("name")) }
}

// README: "Prefixes and QNames"
fun readmePrefixesAndQNames(repo: RdfRepository) {
    repo.add {
        prefixes {
            put("foaf", "http://xmlns.com/foaf/0.1/")
        }
        prefix("ex", "http://example.org/")

        val person = qname("ex:person")

        person - RDF.type - qname("foaf:Person")
        person - qname("rdfs:label") - "Person"
        person - qname("foaf:homepage") - iri("http://example.org/profile")
    }
}

// README: "Prefixes and QNames → Explicit literals"
fun readmeExplicitLiterals(repo: RdfRepository) {
    repo.add {
        val person = iri("http://example.org/person")

        person - FOAF.name - string("foaf:Person")
        person - FOAF.name - lang("Alice", "en")
        person - FOAF.name - lang("مرحبا", "ar", Direction.RTL)
        person - FOAF.age - lit("30", XSD.integer)
    }
}
