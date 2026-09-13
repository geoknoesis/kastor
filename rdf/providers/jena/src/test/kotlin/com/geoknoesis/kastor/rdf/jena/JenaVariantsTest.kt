package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.io.CleanupMode

class JenaVariantsTest {
  @Test
  fun `jena tdb2 persists data at location`(@TempDir(cleanup = CleanupMode.NEVER) dir: Path) {
    val s = Iri("urn:tdb2:s")
    val p = Iri("urn:tdb2:p")
    val o = Literal("persist")
    JenaRepository.Tdb2Repository(dir.toString()).use { repo ->
        repo.editDefaultGraph().addTriple(RdfTriple(s, p, o))
    }

    // New session against same location should see data
    JenaRepository.Tdb2Repository(dir.toString()).use { repo2 ->
      val ask = repo2.ask(SparqlAskQuery("ASK { <urn:tdb2:s> <urn:tdb2:p> 'persist' }"))
      assertTrue(ask)
    }
  }
}
