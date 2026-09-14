package com.geoknoesis.kastor.rdf.jena

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.RdfFormatException
import com.geoknoesis.kastor.rdf.RdfResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.FilterInputStream

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
  fun `parseStreaming is eager - the result is detached from the input stream`() {
    val input = turtle.byteInputStream()
    val triples = JenaProvider().parseStreaming(input, "TURTLE")
    input.close()
    assertEquals(3, triples.count())
    assertEquals(3, triples.count(), "a materialised sequence can be iterated again")
  }

  @Test
  fun `openTripleStream is lazy - the first triple arrives before the input is read to the end`() {
    val lines = (0 until 200_000).joinToString("\n") { "<http://example.org/s$it> <http://example.org/p> \"$it\" ." }.toByteArray()
    var bytesRead = 0L
    val counting = object : FilterInputStream(ByteArrayInputStream(lines)) {
      override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
      override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytesRead += it }
    }
    JenaProvider().openTripleStream(counting, "N-TRIPLES").use { stream ->
      assertEquals(Iri("http://example.org/s0"), stream.iterator().next().subject)
      Thread.sleep(300) // let the background parser read ahead as far as it will
      assertTrue(bytesRead < lines.size / 2, "read-ahead must be bounded: read $bytesRead of ${lines.size} bytes")
    }
  }

  @Test
  fun `base IRI helpers resolve relative IRIs`() {
    val relative = "<s> <p> <o> ."
    val streamed = JenaProvider().openTripleStreamWithBase(relative.byteInputStream(), "TURTLE", "http://example.org/").use { it.toList() }
    assertEquals(Iri("http://example.org/s"), streamed.single().subject)
    val eager = JenaProvider().parseStreamingWithBase(relative.byteInputStream(), "TURTLE", "http://example.org/").toList()
    assertEquals(Iri("http://example.org/o"), eager.single().obj)
    assertThrows(RdfFormatException::class.java) {
      JenaProvider().parseStreamingWithBase(relative.byteInputStream(), "TURTLE", null).toList()
    }
  }
}
