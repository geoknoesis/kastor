package com.geoknoesis.kastor.rdf.shacl.providers

import com.geoknoesis.kastor.rdf.RdfRepository
import com.geoknoesis.kastor.rdf.shacl.PerformanceProfile
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator
import com.geoknoesis.kastor.rdf.shacl.ShaclValidatorProvider
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import com.geoknoesis.kastor.rdf.shacl.ValidatorCapabilities
import com.geoknoesis.kastor.rdf.shacl.native.SparqlConstraintEvaluator

/**
 * SPI provider for the Kastor native SHACL 1.2 Core engine (`getType()` = `kastor`).
 */
class NativeShaclValidatorProvider() : ShaclValidatorProvider {

    /** Internal seam: whether a SPARQL engine for SHACL-SPARQL is available. */
    private var sparqlEngineAvailable: () -> Boolean = SparqlConstraintEvaluator::engineAvailable

    /** Internal seam: repository factory used by SHACL-SPARQL sessions. */
    private var sparqlRepositoryFactory: () -> RdfRepository = SparqlConstraintEvaluator.defaultRepositoryFactory

    /** Test seam: simulate the presence/absence of a SPARQL-capable provider without changing the classpath. */
    internal constructor(sparqlEngineAvailable: () -> Boolean, sparqlRepositoryFactory: () -> RdfRepository) : this() {
        this.sparqlEngineAvailable = sparqlEngineAvailable
        this.sparqlRepositoryFactory = sparqlRepositoryFactory
    }

    override fun priority(): Int = 10

    override fun getType(): String = "kastor"

    override val name: String = "Kastor Native SHACL Validator"

    override val version: String = "1.0.0"

    override fun createValidator(config: ValidationConfig): ShaclValidator = NativeShaclValidator(config, sparqlRepositoryFactory)

    /**
     * `supportsShaclSparql` (and the [ValidationProfile.SHACL_SPARQL] profile) reflect whether a SPARQL-capable
     * provider (`rdf-jena` or `rdf-rdf4j`) is on the runtime classpath: the native engine delegates `sh:sparql`
     * queries to it.
     */
    override fun getCapabilities(): ValidatorCapabilities =
        ValidatorCapabilities(
            supportsShaclCore = true,
            supportsShaclSparql = sparqlEngineAvailable(),
            supportsShaclJs = false,
            supportsShaclPy = false,
            supportsShaclDash = false,
            supportsCustomConstraints = false,
            supportsParallelValidation = false,
            supportsStreamingValidation = false,
            supportsIncrementalValidation = false,
            supportsRdf12TripleTermsInData = true,
            supportsRdf12TripleTermsInShapeParameters = true,
            maxGraphSize = Long.MAX_VALUE,
            performanceProfile = PerformanceProfile.FAST,
        )

    override fun getSupportedProfiles(): List<ValidationProfile> =
        listOfNotNull(
            ValidationProfile.SHACL_CORE,
            ValidationProfile.SHACL_SPARQL.takeIf { sparqlEngineAvailable() },
            ValidationProfile.PERMISSIVE,
            ValidationProfile.STRICT,
        )

    override fun isSupported(profile: ValidationProfile): Boolean = profile in getSupportedProfiles()
}
