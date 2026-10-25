package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.RdfErrorCode
import com.geoknoesis.kastor.rdf.RdfException
import com.geoknoesis.kastor.rdf.RdfExtensionException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class HolonExceptionTest {
  @Test fun `holon failures are RdfExceptions, caught by a catch of the core hierarchy`() {
    val cause = IllegalStateException("inner")
    val caught = try {
      throw HolonException(HolonError.UNKNOWN_HOLON, "no such holon", cause)
    } catch (e: RdfException) {
      e
    }
    assertIs<RdfExtensionException>(caught)
    assertEquals(HolonError.UNKNOWN_HOLON, (caught as HolonException).condition)
    assertEquals(RdfErrorCode.EXTENSION_ERROR, caught.errorCode)
    assertEquals("unknown-holon: no such holon", caught.message)
    assertSame(cause, caught.cause)
  }
}
