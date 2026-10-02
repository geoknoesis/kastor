package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph

/** Compile-cache digest algorithm (architecture §9.3). */
enum class ShapesDigestMode {
    /** Deterministic structural digest of the shapes triples: the only implemented mode. */
    SHAPES_STRUCTURAL_DIGEST_V1,

    /**
     * Not implemented: RDF dataset canonicalization (RDFC-1.0) of the shapes graph was planned and never built.
     * Selecting it makes every validation fail with a [ShaclValidationException] ("not implemented"); nothing is
     * validated. Use [SHAPES_STRUCTURAL_DIGEST_V1].
     */
    @Deprecated("Not implemented: every validation fails. Use SHAPES_STRUCTURAL_DIGEST_V1. It is kept for binary compatibility and will be removed.")
    SHAPES_RDF_CANONICAL_DIGEST,
}

/** Handling of recognised SHACL features that the native engine cannot evaluate (see [ValidationConfig.unsupportedFeatures]). */
enum class UnsupportedFeatureHandling {
    /** Fail with [ShaclValidationException] ("Unsupported SHACL feature ..."). */
    FAIL,
    /** Ignore the construct and add a [ValidationWarning] to the report. */
    IGNORE_WITH_WARNING,
}

/** Policy when per-focus buffers overflow in streaming mode (architecture §9.4). */
enum class StreamingBufferPolicy {
    BATCH_FALLBACK,
    SKIP_WITH_WARNING,
}

/**
 * Shape graph compile cache (architecture §9.3).
 *
 * When [shapesGraphVersion] is set, the native engine verifies the structural digest
 * against the last compiled entry; mismatch raises [StaleShapesGraphTagException].
 */
data class CacheConfig(
    val shapesGraphVersion: String? = null,
    val shapesDigestMode: ShapesDigestMode = ShapesDigestMode.SHAPES_STRUCTURAL_DIGEST_V1,
)

/**
 * `owl:imports` closure on the shapes graph (architecture §9.2).
 *
 * With [resolveOwlImports], the native engine follows `owl:imports` of the shapes graph up to [maxImportDepth] levels,
 * **offline**: an import is resolved when [DatasetValidationConfig.auxiliaryGraphs] has a graph for its IRI. An import
 * that is not resolved, or that lies beyond [maxImportDepth], is skipped and reported as a report-level
 * [ValidationWarning] (`ksh:warning` in RDF; see [ValidationConfig.includeWarnings]): the shapes it would contribute
 * are not validated.
 */
data class ImportConfig(
    val resolveOwlImports: Boolean = false,
    val maxImportDepth: Int = 32,
    /**
     * Fetching imports over the network was planned and never implemented: nothing is ever fetched. The only effect
     * of `true` is that an import without a graph in [DatasetValidationConfig.auxiliaryGraphs] makes validation fail
     * with a [ShapesGraphAccessException] instead of being skipped with a warning.
     */
    @Deprecated("Network fetch is not implemented: true only turns an unresolved import into a ShapesGraphAccessException. It is kept for binary compatibility and will be removed.")
    val allowImportFetch: Boolean = false,
)

/**
 * Streaming / buffering controls (architecture §9.4). No engine implements streaming validation, so these values are
 * never read ([ValidationConfig.streaming] is deprecated).
 */
data class StreamingConfigExtension(
    val maxPerFocusBuffer: Int = 10_000,
    val streamingBufferPolicy: StreamingBufferPolicy = StreamingBufferPolicy.BATCH_FALLBACK,
)

/**
 * Dataset-oriented validation wiring (architecture §9.2).
 *
 * [auxiliaryGraphs] supplies IRIs not present on the given [com.geoknoesis.kastor.rdf.Dataset]
 * (offline closure). [shapesGraphNamedGraph] selects shapes from the dataset when non-null.
 */
data class DatasetValidationConfig(
    val shapesGraphNamedGraph: Iri? = null,
    val auxiliaryGraphs: Map<Iri, RdfGraph> = emptyMap(),
    val discoverShapesGraphFromData: Boolean = true,
    /**
     * Optional dataset used to resolve [shapesGraphNamedGraph] and `sh:shapesGraph` targets
     * when calling [ShaclValidator.validate]; [ShaclValidator.validateDataset] passes its argument here implicitly.
     */
    val validationDataset: Dataset? = null,
)
