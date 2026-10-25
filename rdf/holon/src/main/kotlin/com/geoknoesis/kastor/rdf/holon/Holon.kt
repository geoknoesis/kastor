package com.geoknoesis.kastor.rdf.holon

import com.geoknoesis.kastor.rdf.*

/**
 * A registered holon bound to one immutable processing [context]. The same RDF identifier in
 * another snapshot is a different handle. Operations never edit the original repository.
 */
class Holon internal constructor(val context: HolonContext, val id: RdfResource) {
  /** Direct content, without administrative declarations from M unless explicitly filed. */
  val content: RdfGraph get() = direct().graph

  /** Resolve this holon in [scope]. @throws HolonException if the context's declarations are invalid for it */
  @JvmOverloads
  @Throws(HolonException::class)
  fun resolve(scope: HolonScope = HolonScope.DIRECT): HolonResolution = context.resolve(id, scope)
  @Throws(HolonException::class)
  fun direct(): HolonResolution = resolve(HolonScope.DIRECT)
  @Throws(HolonException::class)
  fun aggregated(): HolonResolution = resolve(HolonScope.AGGREGATED)
  @Throws(HolonException::class)
  fun visible(): HolonResolution = resolve(HolonScope.VISIBLE)
  /** GLOBAL is a validation/reasoning scope, rather than a query scope. */
  @Throws(HolonException::class)
  fun global(): HolonResolution = resolve(HolonScope.GLOBAL)

  /**
   * Return an owned query dataset; close it after querying. Provider capabilities still apply.
   *
   * @throws HolonException for the GLOBAL scope or a selection the query provider cannot represent
   * @throws RdfException if [repositoryFactory] or the copy into its repository fails
   */
  @JvmOverloads
  @Throws(RdfException::class)
  fun asDataset(
    scope: HolonScope = HolonScope.DIRECT,
    attributed: Boolean = false,
    repositoryFactory: () -> RdfRepository = { Rdf.memory() },
  ): Dataset = resolve(scope).asDataset(attributed, repositoryFactory)

  /** The union of this holon's shape imports. @throws HolonException if an imported shapes graph is missing */
  @Throws(HolonException::class)
  fun effectiveShapes(shapes: Map<Iri, RdfGraph>): RdfGraph = context.effectiveShapes(id, shapes)

  /** Validate this holon's [scope]; see [HolonContext.validate]. @throws HolonException as described there */
  @JvmOverloads
  @Throws(HolonException::class)
  fun validate(
    shapes: Map<Iri, RdfGraph>,
    scope: HolonScope = HolonScope.DIRECT,
    entailmentRegime: Iri = Iri("http://www.w3.org/ns/entailment/Simple"),
  ): HolonValidation = context.validate(id, shapes, scope, entailmentRegime)

  /**
   * Convert the entire context so registry, hierarchy and imports remain meaningful. Inspect the
   * returned preservation report, then select `result.context.holon(id)` for the converted handle.
   *
   * @throws HolonException if the target cannot represent the context (see [HolonContext.convert])
   */
  @JvmOverloads
  @Throws(HolonException::class)
  fun convert(
    target: HolonProfile,
    preservation: HolonPreservation = HolonPreservation.CONTENT,
  ): HolonConversion = context.convert(target, preservation)

  override fun equals(other: Any?): Boolean = other is Holon && context === other.context && id == other.id
  override fun hashCode(): Int = 31 * System.identityHashCode(context) + id.hashCode()
  override fun toString(): String = "Holon($id, snapshot=${context.snapshotId})"
}

/**
 * Select a registered holon without imposing parent/import checks on a DIRECT request.
 *
 * @throws HolonException with [HolonError.UNKNOWN_HOLON] if [id] is not registered in M
 */
@Throws(HolonException::class)
fun HolonContext.holon(id: RdfResource): Holon {
  requireKnown(id)
  return Holon(this, id)
}

/** [holon]. @throws HolonException with [HolonError.UNKNOWN_HOLON] if [id] is not registered in M */
@Throws(HolonException::class)
operator fun HolonContext.get(id: RdfResource): Holon = holon(id)
