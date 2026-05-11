package com.geoknoesis.kastor.rdf.rdf4j

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.RDF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import org.eclipse.rdf4j.model.vocabulary.RDF4J
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.repository.RepositoryException
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.sail.shacl.ShaclSailValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class Rdf4jVariantsTest {

  @Test
  fun `rdf4j memory works`() {
    val repo = Rdf4jRepository.MemoryRepository()
    repo.use {
      val s = Iri("urn:memory:s")
      val p = Iri("urn:memory:p")
      val o = Literal("test")
      it.editDefaultGraph().addTriple(RdfTriple(s, p, o))
      assertTrue(it.ask(SparqlAskQuery("ASK { <urn:memory:s> <urn:memory:p> 'test' }")))
    }
  }

  @Test
  @org.junit.jupiter.api.Disabled("RDF4J native persistence needs investigation")
  fun `rdf4j native persists data at location`() {
    val dir = Files.createTempDirectory("rdf4j-native-test").toFile()
    dir.deleteOnExit()

    val repo1 = Rdf4jRepository.NativeRepository(dir.absolutePath)
    val s = Iri("urn:native:s")
    val p = Iri("urn:native:p")
    val o = Literal("persist")
    repo1.editDefaultGraph().addTriple(RdfTriple(s, p, o))

    val repo2 = Rdf4jRepository.NativeRepository(dir.absolutePath)
    val ask = repo2.ask(SparqlAskQuery("ASK { <urn:native:s> <urn:native:p> 'persist' }"))
    assertTrue(ask)
  }

  @Test
  fun `memory rdfs variant materializes subClassOf entailments`() {
    Rdf4jRepository.MemoryRdfsRepository().use { repo ->
      val person = Iri("http://example.org/Person")
      val student = Iri("http://example.org/Student")
      val alice = Iri("http://example.org/alice")

      repo.editDefaultGraph().addTriples(listOf(
        RdfTriple(student, RDFS.subClassOf, person),
        RdfTriple(alice, RDF.type, student)
      ))

      // The inferencer should add `alice rdf:type Person` even though it was never asserted.
      val inferred = repo.ask(SparqlAskQuery(
        "ASK { <http://example.org/alice> <${RDF.type.value}> <http://example.org/Person> }"
      ))
      assertTrue(inferred, "RDFS inferencer should infer alice rdf:type Person")
    }
  }

  @Test
  fun `memory shacl variant rejects writes that violate shapes`() {
    Rdf4jRepository.MemoryShaclRepository().use { repo ->
      // Load a minimum-cardinality shape into RDF4J's reserved SHACL_SHAPE_GRAPH.
      val shapeGraphIri = Iri(RDF4J.SHACL_SHAPE_GRAPH.stringValue())
      val shapesTtl = """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        ex:PersonShape
            a sh:NodeShape ;
            sh:targetClass ex:Person ;
            sh:property [
                sh:path ex:name ;
                sh:minCount 1 ;
                sh:datatype xsd:string ;
            ] .
      """.trimIndent()

      val rdf4jRepo = repo.getRdf4jRepository()
      val shapeContext = SimpleValueFactory.getInstance().createIRI(shapeGraphIri.value)
      rdf4jRepo.connection.use { conn ->
        conn.begin()
        conn.add(shapesTtl.byteInputStream(), "http://example.org/", RDFFormat.TURTLE, shapeContext)
        conn.commit()
      }

      val alice = Iri("http://example.org/alice")
      val person = Iri("http://example.org/Person")

      val violation = assertThrows(RepositoryException::class.java) {
        repo.transaction {
          editDefaultGraph().addTriple(RdfTriple(alice, RDF.type, person))
          // Missing required ex:name - commit should fail.
        }
      }
      // The cause chain should contain a ShaclSailValidationException.
      var cause: Throwable? = violation
      var foundShacl = false
      while (cause != null) {
        if (cause is ShaclSailValidationException) {
          foundShacl = true
          break
        }
        cause = cause.cause
      }
      assertTrue(foundShacl, "Expected ShaclSailValidationException in cause chain, got: $violation")
    }
  }

  @Test
  fun `memory star variant round-trips an RDF-star quoted triple`() {
    Rdf4jRepository.MemoryStarRepository().use { repo ->
      // Insert a quoted-triple assertion via SPARQL UPDATE (RDF-star syntax).
      repo.update(UpdateQuery("""
        PREFIX ex: <http://example.org/>
        INSERT DATA {
          <<ex:alice ex:age 30>> ex:certainty "0.9" .
        }
      """.trimIndent()))

      // ASK for the same quoted triple.
      val asked = repo.ask(SparqlAskQuery("""
        PREFIX ex: <http://example.org/>
        ASK { <<ex:alice ex:age 30>> ex:certainty "0.9" }
      """.trimIndent()))
      assertTrue(asked, "RDF-star quoted triple should round-trip via SPARQL")
    }
  }

  @Test
  fun `provider advertises per-variant capabilities`() {
    val provider = Rdf4jProvider()
    assertFalse(provider.getCapabilities("memory").supportsInference)
    assertFalse(provider.getCapabilities("memory").supportsShacl)
    assertTrue(provider.getCapabilities("memory-rdfs").supportsInference)
    assertTrue(provider.getCapabilities("memory-shacl").supportsShacl)
    assertTrue(provider.getCapabilities("memory-star").supportsRdfStar)
  }
}
