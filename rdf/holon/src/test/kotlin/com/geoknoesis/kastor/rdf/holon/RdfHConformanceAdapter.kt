package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD
import kotlinx.serialization.json.*

/** stdin/stdout adapter for the project-authored RDF-H conversion and validation suite. */
object RdfHConformanceAdapter {
  @JvmStatic fun main(args: Array<String>) {
    val request = Json.parseToJsonElement(System.`in`.bufferedReader().readText()).jsonObject
    println(respond(request))
  }

  internal fun respond(request: JsonObject): JsonObject {
    if (request.text("language") != "rdf-h") return buildJsonObject { put("status", "unsupported") }
    return try {
      val source = source(request)
      val result = when (request.text("operation")) {
        "convert" -> conversion(source.convert(
          when (request.text("targetRepresentation")) {
            "dataset" -> HolonProfile.DATASET
            "reifier" -> HolonProfile.REIFIER
            else -> throw IllegalArgumentException("Unknown target representation")
          },
          when (request.text("preservationLevel")) {
            "content" -> HolonPreservation.CONTENT
            "assertion" -> HolonPreservation.ASSERTION
            else -> throw IllegalArgumentException("Unknown preservation level")
          },
        ))
        "validate" -> {
          Rdf.parseDataset(request.getValue("shapesDataset").jsonObject.text("text"), RdfFormat.TRIG).use { shapes ->
            val validation = source.validate(Iri(request.text("holon")), shapes.namedGraphs,
              HolonScope.valueOf(request.text("scope").uppercase()))
            buildJsonObject { put("conforms", validation.report.isValid) }
          }
        }
        else -> return buildJsonObject { put("status", "unsupported") }
      }
      buildJsonObject { put("status", "ok"); put("result", result) }
    } catch (error: HolonException) {
      buildJsonObject {
        put("status", "rejected"); put("category", error.condition.id)
        put("condition", error.condition.id); put("message", error.message)
      }
    }
  }

  private fun source(request: JsonObject): HolonContext {
    val metadata = request["metadataContext"]?.jsonObject?.text("text")?.let { Rdf.parse(it, RdfFormat.TURTLE) }
    return when (request.text("representation")) {
      "dataset" -> Rdf.parseDataset(request.getValue("sourceDataset").jsonObject.text("text"), RdfFormat.TRIG).use {
        HolonContext.fromDataset(it, metadata)
      }
      "reifier" -> {
        val input = request.getValue("sourceGraph").jsonObject
        val graph = decodeGraph(input.getValue("terms"))
        val m = input["metadataTerms"]?.let(::decodeGraph) ?: metadata
          ?: throw IllegalArgumentException("Reifier input requires M")
        HolonContext.of(graph, metadata = m, profile = HolonProfile.REIFIER)
      }
      else -> fail(HolonError.UNSUPPORTED_CAPABILITY, "Unsupported source representation")
    }
  }

  private fun conversion(value: HolonConversion): JsonObject = buildJsonObject {
    val context = value.context
    put("representation", context.profile.name.lowercase())
    put("metadataGraph", encodeGraph(context.metadata))
    if (context.profile == HolonProfile.REIFIER) put("graph", encodeGraph(context.defaultGraph)) else {
      put("defaultGraph", encodeGraph(context.defaultGraph))
      put("namedGraphs", JsonArray(context.namedGraphs.map { (name, graph) -> buildJsonObject {
        put("name", encodeTerm(name)); put("graph", encodeGraph(graph))
      } }))
    }
    put("reports", buildJsonArray {
      if (value.discardedAssertions.size() > 0) add("Discarded assertions: ${value.discardedAssertions.getTriples()}")
      value.derivedMappings.forEach { (h, name) -> add("Derived h:contentGraph mapping: $h -> $name") }
    })
  }

  private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
  private fun decodeGraph(value: JsonElement): RdfGraph = HolonGraph(value.jsonArray.map {
    val row = it.jsonArray
    RdfTriple(decodeTerm(row[0]) as RdfResource, decodeTerm(row[1]) as Iri, decodeTerm(row[2]))
  })
  private fun decodeTerm(value: JsonElement): RdfTerm {
    val term = value.jsonObject
    return when (term.text("type")) {
      "uri" -> Iri(term.text("value"))
      "bnode" -> BlankNode(term.text("value"))
      "literal" -> when {
        term["direction"] != null -> lang(term.text("value"), term.text("xml:lang"), checkNotNull(Direction.fromToken(term.text("direction"))))
        term["xml:lang"] != null -> lang(term.text("value"), term.text("xml:lang"))
        else -> Literal(term.text("value"), term["datatype"]?.jsonPrimitive?.content?.let(::Iri) ?: XSD.string)
      }
      "triple" -> term.getValue("value").jsonObject.let {
        TripleTerm(RdfTriple(decodeTerm(it.getValue("subject")) as RdfResource, decodeTerm(it.getValue("predicate")) as Iri, decodeTerm(it.getValue("object"))))
      }
      else -> throw IllegalArgumentException("Unknown RDF term")
    }
  }
  private fun encodeGraph(graph: RdfGraph): JsonArray = JsonArray(graph.getTriples().map { triple ->
    JsonArray(listOf(encodeTerm(triple.subject), encodeTerm(triple.predicate), encodeTerm(triple.obj)))
  })
  private fun encodeTerm(term: RdfTerm): JsonObject = buildJsonObject {
    when (term) {
      is Iri -> { put("type", "uri"); put("value", term.value) }
      is BlankNode -> { put("type", "bnode"); put("value", term.id) }
      is TripleTerm -> {
        put("type", "triple")
        put("value", buildJsonObject {
          put("subject", encodeTerm(term.triple.subject)); put("predicate", encodeTerm(term.triple.predicate)); put("object", encodeTerm(term.triple.obj))
        })
      }
      is Literal -> {
        put("type", "literal"); put("value", term.lexical)
        if (term is LangString) {
          put("xml:lang", term.lang)
          term.direction?.let { put("direction", it.token) }
        } else put("datatype", term.datatype.value)
      }
      else -> throw IllegalArgumentException("Only RDF data terms can be serialized")
    }
  }
}
