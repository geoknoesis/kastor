package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*

/**
 * A registered holon bound to one immutable processing [context]. The same RDF identifier in
 * another snapshot is a different handle. Operations never edit the original repository.
 */
class Holon internal constructor(val context: HolonContext, val id: RdfResource) {
  /** Direct content, without administrative declarations from M unless explicitly filed. */
  val content: RdfGraph get() = direct().graph

  fun resolve(scope: HolonScope = HolonScope.DIRECT): HolonResolution = context.resolve(id, scope)
  fun direct(): HolonResolution = resolve(HolonScope.DIRECT)
  fun aggregated(): HolonResolution = resolve(HolonScope.AGGREGATED)
  fun visible(): HolonResolution = resolve(HolonScope.VISIBLE)
  /** GLOBAL is a validation/reasoning scope, rather than a query scope. */
  fun global(): HolonResolution = resolve(HolonScope.GLOBAL)

  /** Return an owned query dataset; close it after querying. Provider capabilities still apply. */
  fun asDataset(
    scope: HolonScope = HolonScope.DIRECT,
    attributed: Boolean = false,
    repositoryFactory: () -> RdfRepository = { Rdf.memory() },
  ): Dataset = resolve(scope).asDataset(attributed, repositoryFactory)

  fun effectiveShapes(shapes: Map<Iri, RdfGraph>): RdfGraph = context.effectiveShapes(id, shapes)

  fun validate(
    shapes: Map<Iri, RdfGraph>,
    scope: HolonScope = HolonScope.DIRECT,
    entailmentRegime: Iri = Iri("http://www.w3.org/ns/entailment/Simple"),
  ): HolonValidation = context.validate(id, shapes, scope, entailmentRegime)

  /**
   * Convert the entire context so registry, hierarchy and imports remain meaningful. Inspect the
   * returned preservation report, then select `result.context.holon(id)` for the converted handle.
   */
  fun convert(
    target: HolonProfile,
    preservation: HolonPreservation = HolonPreservation.CONTENT,
  ): HolonConversion = context.convert(target, preservation)

  override fun equals(other: Any?): Boolean = other is Holon && context === other.context && id == other.id
  override fun hashCode(): Int = 31 * System.identityHashCode(context) + id.hashCode()
  override fun toString(): String = "Holon($id, snapshot=${context.snapshotId})"
}

/** Select a registered holon without imposing parent/import checks on a DIRECT request. */
fun HolonContext.holon(id: RdfResource): Holon {
  if (id !in registry) fail(HolonError.UNKNOWN_HOLON, "$id is not explicitly registered in M")
  return Holon(this, id)
}

operator fun HolonContext.get(id: RdfResource): Holon = holon(id)
