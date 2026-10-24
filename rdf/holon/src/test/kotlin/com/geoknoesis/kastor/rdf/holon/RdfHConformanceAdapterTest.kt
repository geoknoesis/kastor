package com.geoknoesis.kastor.rdf.holon

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RdfHConformanceAdapterTest {
  private fun request(vararg extra: Pair<String, String>, graph: JsonObject = buildJsonObject { put("terms", JsonArray(emptyList())) }) =
    buildJsonObject {
      put("language", "rdf-h"); put("operation", "convert"); put("representation", "reifier")
      put("targetRepresentation", "dataset"); put("preservationLevel", "content")
      put("sourceGraph", graph)
      extra.forEach { (key, value) -> put(key, value) }
    }

  private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

  @Test fun `unknown operation parameters are unsupported rather than a crash`() {
    assertEquals("unsupported", RdfHConformanceAdapter.respond(request("targetRepresentation" to "mixed")).text("status"))
    assertEquals("unsupported", RdfHConformanceAdapter.respond(request("preservationLevel" to "exact")).text("status"))
  }

  @Test fun `reifier input without M is rejected with its RDF-H condition`() {
    val response = RdfHConformanceAdapter.respond(request())
    assertEquals("rejected", response.text("status"))
    assertEquals(HolonError.UNSUPPORTED_CAPABILITY.id, response.text("condition"))
  }
}
