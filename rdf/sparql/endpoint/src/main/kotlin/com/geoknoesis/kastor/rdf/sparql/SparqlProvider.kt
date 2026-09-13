package com.geoknoesis.kastor.rdf.sparql

import com.geoknoesis.kastor.rdf.*

/**
 * SPARQL provider implementation for the RDF API.
 *
 * Repository options are read by [SparqlEndpointConfig.fromOptions] (`location`, timeouts, size
 * limits, credentials, `header.<Name>`, request methods, batch size).
 */
class SparqlProvider : RdfProvider {

    override val id: String = "sparql"

    override val name: String = "SPARQL Repository"

    override val version: String = "1.0.0"

    override fun variants(): List<RdfVariant> {
        return listOf(RdfVariant("sparql", "Remote SPARQL endpoint"))
    }

    override fun createRepository(variantId: String, config: RdfConfig): RdfRepository {
        return when (variantId) {
            "sparql" -> SparqlRepository(SparqlEndpointConfig.fromOptions(config.options))
            else -> throw IllegalArgumentException("Unsupported SPARQL repository variant: $variantId")
        }
    }

    /**
     * Capabilities of this HTTP adapter (SPARQL 1.1 Protocol, JSON results, no RDF 1.2 terms,
     * no transactions). The remote server may support more; it is not probed.
     */
    override fun getCapabilities(variantId: String?): ProviderCapabilities = SPARQL_ENDPOINT_CAPABILITIES

    override fun getProviderCategory(): ProviderCategory = ProviderCategory.SPARQL_ENDPOINT

    /** Feature flags derived from [getCapabilities], so both views always agree. */
    override fun getDetailedCapabilities(variantId: String?): DetailedProviderCapabilities {
        val basic = getCapabilities(variantId)
        return DetailedProviderCapabilities(
            basic = basic,
            providerCategory = getProviderCategory(),
            supportedSparqlFeatures = mapOf(
                "RDF-star" to basic.supportsRdfStar,
                "Federation" to basic.supportsFederation,
                "Property Paths" to basic.supportsPropertyPaths,
                "Aggregation" to basic.supportsAggregation,
                "Subqueries" to basic.supportsSubSelect,
                "Named Graphs" to basic.supportsNamedGraphs,
                "Updates" to basic.supportsUpdates,
                "Transactions" to basic.supportsTransactions,
            ),
            customExtensionFunctions = emptyList(),
            limitations = listOf(
                "CONSTRUCT and DESCRIBE are not supported (no RDF parser in this adapter)",
                "No transactions",
                "RDF 1.2 triple terms and directional literals are not decoded from results",
            ),
        )
    }
}
