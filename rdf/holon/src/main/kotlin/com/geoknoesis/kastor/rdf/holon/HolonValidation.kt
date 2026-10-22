package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.shacl.*

/** Scope and shapes are retained alongside the normal Kastor SHACL report. */
class HolonValidation internal constructor(
  val resolution: HolonResolution,
  shapeGraphNames: Set<Iri>,
  val effectiveShapes: RdfGraph,
  val entailmentRegime: Iri,
  val report: ValidationReport,
) {
  val shapeGraphNames: Set<Iri> = frozenSet(shapeGraphNames)
}

/**
 * Validate only the explicitly selected scope against H's explicit, single-hop shape imports.
 * [shapes] must be a separately supplied dataset with one shared blank-node scope. It never enters G.
 * Shapes are checked before domain validation; syntax failures are distinct from ordinary violations.
 * This helper supports simple entailment without ontology inputs.
 */
fun HolonContext.validate(
  holon: RdfResource,
  shapes: Map<Iri, RdfGraph>,
  scope: HolonScope = HolonScope.DIRECT,
  entailmentRegime: Iri = Iri("http://www.w3.org/ns/entailment/Simple"),
): HolonValidation {
  if (entailmentRegime != Iri("http://www.w3.org/ns/entailment/Simple")) {
    fail(HolonError.UNSUPPORTED_CAPABILITY, "Scoped validation supports simple entailment")
  }
  val resolution = resolve(holon, scope)
  val names = shapeGraphNames(holon)
  val effective = effectiveShapes(holon, shapes).snapshot()
  // Disable discovery from data and automatic owl:imports; only M selects this effective graph.
  val validator = ShaclValidation.validator(ValidationConfig(
    providerId = "kastor",
    dataset = DatasetValidationConfig(discoverShapesGraphFromData = false),
    imports = ImportConfig(resolveOwlImports = false),
  ))
  try {
    if (!validator.validate(effective, HolonShapeSyntax.graph).isValid) {
      fail(HolonError.ILL_FORMED_SHAPES_GRAPH, "The effective shapes union violates the SHACL syntax shapes")
    }
    validator.validate(emptyGraph(), effective)
    val report = validator.validate(resolution.graph, effective)
    return HolonValidation(resolution, names, effective, entailmentRegime, report)
  } catch (error: ShaclValidationException) {
    val causes = generateSequence<Throwable>(error) { it.cause }.toList()
    when {
      causes.any { it is UnsupportedShaclFeatureException || it is UnsupportedShaclOperationException } ->
        throw HolonException(HolonError.UNSUPPORTED_CAPABILITY, "Unsupported SHACL feature", error)
      causes.any { it is ShapeCompileException } ->
        throw HolonException(HolonError.ILL_FORMED_SHAPES_GRAPH, "The effective shapes union is ill-formed", error)
      else -> throw error // Resource limits or execution failures are not shapes-definition errors.
    }
  }
}

/** Bundled W3C SHACL-for-SHACL; no network resolution or automatic imports. */
private object HolonShapeSyntax {
  val graph: RdfGraph by lazy {
    val text = checkNotNull(HolonContext::class.java.getResourceAsStream("shacl-shacl.ttl")) {
      "Bundled SHACL syntax graph is missing"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    Rdf.parse(text, RdfFormat.TURTLE).snapshot()
  }
}
