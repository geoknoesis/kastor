package com.geoknoesis.kastor.rdf

/**
 * Configuration for RDF repositories.
 */
data class RdfConfig(
    val providerId: String? = null,
    val variantId: String? = null,
    val options: Map<String, String> = emptyMap(),
    val requirements: ProviderRequirements? = null
) {
    fun providerIdTyped(): ProviderId? = providerId?.let(::ProviderId)

    fun variantIdTyped(): VariantId? = variantId?.let(::VariantId)

    companion object {
        fun of(
            providerId: ProviderId?,
            variantId: VariantId? = null,
            options: Map<String, String> = emptyMap(),
            requirements: ProviderRequirements? = null
        ): RdfConfig = RdfConfig(providerId?.value, variantId?.value, options, requirements)
    }
}

/**
 * Typed provider identifiers to avoid stringly-typed APIs.
 */
@JvmInline
value class ProviderId(val value: String)

@JvmInline
value class VariantId(val value: String)

/**
 * Provider variant metadata.
 */
data class RdfVariant(
    val id: String,
    val description: String = "",
    val defaultOptions: Map<String, String> = emptyMap()
)

/**
 * Selection requirements for provider discovery.
 * null means "don't care", true means "must support", false means "must not support".
 */
data class ProviderRequirements(
    val providerCategory: ProviderCategory? = null,
    val supportsInference: Boolean? = null,
    val supportsTransactions: Boolean? = null,
    val supportsNamedGraphs: Boolean? = null,
    val supportsUpdates: Boolean? = null,
    val supportsRdfStar: Boolean? = null,
    val supportsFederation: Boolean? = null,
    val supportsServiceDescription: Boolean? = null
)

/**
 * Represents a SPARQL extension function.
 */
data class SparqlExtensionFunction(
    val iri: String,
    val name: String,
    val description: String,
    val argumentTypes: List<String> = emptyList(),
    val returnType: String? = null,
    val isAggregate: Boolean = false,
    val isBuiltIn: Boolean = true
)

/**
 * Categories of RDF providers.
 */
enum class ProviderCategory {
    RDF_STORE,              // Jena, RDF4J, etc.
    SPARQL_ENDPOINT,        // Remote SPARQL endpoints
    REASONER,              // Inference engines
    SHACL_VALIDATOR,       // SHACL validation
    SERVICE_DESCRIPTION,   // SPARQL service description
    FEDERATION            // Federated query support
}

/**
 * Detailed provider capabilities with extended information.
 */
data class DetailedProviderCapabilities(
    val basic: ProviderCapabilities,
    val providerCategory: ProviderCategory,
    val supportedSparqlFeatures: Map<String, Boolean>,
    val sparqlFeatures: Set<SparqlFeature> = emptySet(),
    val customExtensionFunctions: List<SparqlExtensionFunction>,
    val performanceMetrics: PerformanceMetrics? = null,
    val limitations: List<String> = emptyList()
)

/**
 * Performance metrics for the provider.
 */
data class PerformanceMetrics(
    val maxQueryComplexity: Int,
    val maxResultSize: Long,
    val averageResponseTime: Double,
    val concurrentQueryLimit: Int,
    val memoryUsageLimit: Long
)

/**
 * Enhanced provider capabilities with SPARQL 1.2 support.
 */
data class ProviderCapabilities(
    /**
     * The RDF specification version this provider conforms to. `"1.1"` for
     * the bundled in-memory provider; `"1.2"` for Jena/RDF4J in their current
     * pinned versions. Used by callers that need to make spec-version aware
     * decisions (for example: should we emit `<<( s p o )>>` or use the legacy
     * RDF-star reification model when constructing data).
     */
    val rdfVersion: String = "1.1",
    /**
     * True if the provider supports RDF 1.2 triple terms (`<<( s p o )>>`)
     * including `rdf:reifies`. False on legacy RDF 1.1-only providers.
     */
    val supportsTripleTerms: Boolean = false,
    // Existing capabilities
    val supportsInference: Boolean = false,
    val supportsTransactions: Boolean = false,
    val supportsNamedGraphs: Boolean = false,
    val supportsUpdates: Boolean = false,
    /**
     * True if the provider preserves RDF-star quoted triples (legacy semantics)
     * during round-trip. In RDF 1.2 [supportsTripleTerms] is the relevant flag;
     * this field stays for backwards compatibility.
     */
    val supportsRdfStar: Boolean = false,
    /** True if the provider validates writes against SHACL shapes (e.g. RDF4J `ShaclSail`). */
    val supportsShacl: Boolean = false,
    val maxMemoryUsage: Long = Long.MAX_VALUE,
    
    // SPARQL 1.2 specific capabilities
    val sparqlVersion: String = "1.1",
    val supportsPropertyPaths: Boolean = false,
    val supportsAggregation: Boolean = false,
    val supportsSubSelect: Boolean = false,
    val supportsFederation: Boolean = false,
    val supportsVersionDeclaration: Boolean = false,
    val supportsServiceDescription: Boolean = false,
    
    // Service description capabilities
    val supportedLanguages: List<String> = emptyList(),
    val supportedResultFormats: List<String> = emptyList(),
    val supportedInputFormats: List<String> = emptyList(),
    val supportedOutputFormats: List<String> = emptyList(), // Formats supported for serialization
    val extensionFunctions: List<SparqlExtensionFunction> = emptyList(),
    val entailmentRegimes: List<String> = emptyList(),
    val namedGraphs: List<String> = emptyList(),
    val defaultGraphs: List<String> = emptyList(),
    val sparqlFeatures: Set<SparqlFeature> = emptySet(),
    /**
     * True if the provider stores the RDF 1.2 base direction of `rdf:dirLangString` literals natively,
     * so a [LangString] with a [Direction] round-trips as a directional literal in storage, SPARQL and
     * serializers (Jena, the in-memory provider).
     *
     * False when base direction is not modelled natively. RDF4J has no base-direction model: Kastor
     * encodes the direction into the language tag as `lang--dir` (for example `"...."@ar--rtl`) and
     * decodes it on read, so values round-trip through Kastor, but RDF4J itself sees an ordinary
     * language tag (SPARQL `LANG(?o)` returns `ar--rtl`; `LANGMATCHES` and Rio serializers treat it as
     * a tag). Remote SPARQL endpoints report false because support cannot be assumed.
     */
    val supportsBaseDirection: Boolean = false,
)

/**
 * Canonical SPARQL feature identifiers for typed capability checks.
 */
enum class SparqlFeature {
    RDF_STAR,
    PROPERTY_PATHS,
    AGGREGATION,
    SUBSELECT,
    INFERENCE,
    ENTAILMENT,
    FEDERATION,
    SERVICE_DESCRIPTION,
    VERSION_DECLARATION
}
