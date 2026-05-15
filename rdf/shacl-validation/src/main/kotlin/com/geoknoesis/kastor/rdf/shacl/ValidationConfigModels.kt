package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Dataset
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.RdfGraph

/** Compile-cache digest algorithm (architecture §9.3). */
enum class ShapesDigestMode {
    SHAPES_STRUCTURAL_DIGEST_V1,
    SHAPES_RDF_CANONICAL_DIGEST,
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

/** `owl:imports` closure on the shapes graph (architecture §9.2). */
data class ImportConfig(
    val resolveOwlImports: Boolean = false,
    val maxImportDepth: Int = 32,
    val allowImportFetch: Boolean = false,
)

/** Streaming / buffering controls (architecture §9.4). */
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
