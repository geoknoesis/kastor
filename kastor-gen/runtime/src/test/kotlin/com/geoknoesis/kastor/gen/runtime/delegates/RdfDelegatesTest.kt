package com.geoknoesis.kastor.gen.runtime.delegates

import com.geoknoesis.kastor.gen.runtime.DefaultRdfHandle
import com.geoknoesis.kastor.gen.runtime.RdfBacked
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfTriple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RdfDelegatesTest {

  private val s = Iri("http://example.org/s")
  private val pTitle = Iri("http://example.org/title")
  private val pAge = Iri("http://example.org/age")

  @Test
  fun `rdfString and rdfInt read literals`() {
    val g = Rdf.graph { }
    g.addTriple(RdfTriple(s, pTitle, Literal("hi")))
    g.addTriple(RdfTriple(s, pAge, Literal(7)))

    val view = object : RdfBacked {
      override val rdf = DefaultRdfHandle(s, g, known = setOf(pTitle, pAge))
      val title by rdfString(pTitle)
      val age by rdfInt(pAge)
    }

    assertEquals("hi", view.title)
    assertEquals(7, view.age)
  }

  @Test
  fun `rdfStringOrNull returns null when absent`() {
    val g = Rdf.graph { }
    val view = object : RdfBacked {
      override val rdf = DefaultRdfHandle(s, g, known = setOf(pTitle))
      val title by rdfStringOrNull(pTitle)
    }
    assertNull(view.title)
  }

  @Test
  fun `rdfStrings collects multiple literals`() {
    val g = Rdf.graph { }
    g.addTriple(RdfTriple(s, pTitle, Literal("a")))
    g.addTriple(RdfTriple(s, pTitle, Literal("b")))

    val view = object : RdfBacked {
      override val rdf = DefaultRdfHandle(s, g, known = setOf(pTitle))
      val titles by rdfStrings(pTitle)
    }

    assertEquals(listOf("a", "b"), view.titles)
  }
}
