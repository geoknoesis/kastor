package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SparqlVariantsTest {
  @Test
  fun `sparql repository can be created`() {
    val repo = SparqlRepository("http://dbpedia.org/sparql")
    
    // Test that the repository is created successfully
    assertEquals(false, repo.isClosed())
    assertEquals(true, repo.defaultGraph != null)
  }
}

