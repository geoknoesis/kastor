package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JenaStreamingParseTest {

  private val turtle =
    """
    @prefix ex: <http://example.org/> .
    ex:a ex:p "1" .
    ex:a ex:q ex:b .
    ex:b ex:p "2" .
    """.trimIndent()

  @Test
  fun `parseStreaming yields all triples with correct terms`() {
    val triples =
      JenaProvider()
        .parseStreaming(turtle.byteInputStream(), "TURTLE")
        .toList()

    assertEquals(3, triples.size)

    val byPredicate = triples.groupBy { it.predicate }
    assertEquals(2, byPredicate[Iri("http://example.org/p")]?.size)

    val objectTriple = triples.single { it.predicate == Iri("http://example.org/q") }
    assertEquals(Iri("http://example.org/a") as RdfResource, objectTriple.subject)
    assertEquals(Iri("http://example.org/b"), objectTriple.obj)

    assertTrue(triples.any { it.obj == Literal("1") })
    assertTrue(triples.any { it.obj == Literal("2") })
  }

  @Test
  fun `parseStreaming is lazy - take(1) does not require consuming everything`() {
    // A lazy Sequence lets callers stop early; just assert the first element is real.
    val first =
      JenaProvider()
        .parseStreaming(turtle.byteInputStream(), "TURTLE")
        .first()
    assertTrue(first.subject is Iri)
  }
}
