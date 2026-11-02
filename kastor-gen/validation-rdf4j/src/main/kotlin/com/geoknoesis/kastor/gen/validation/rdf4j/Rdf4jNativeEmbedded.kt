package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.EmbeddedShapesRefusedException
import com.geoknoesis.kastor.gen.runtime.GraphStateCache
import com.geoknoesis.kastor.gen.runtime.KastorGenInternalApi
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.RdfResource
import com.geoknoesis.kastor.rdf.RdfTerm
import com.geoknoesis.kastor.rdf.RdfTriple
import com.geoknoesis.kastor.rdf.provider.MemoryGraph
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.toShaclValidationReportRdf
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import java.time.Duration

/**
 * The validation of untrusted embedded shapes by `Rdf4jValidation()`: on the **Kastor SHACL validator**
 * (`rdf-shacl-validation`, provider `kastor`), which runs under a deadline and its own work limits, instead of
 * ShaclSail, whose cost grows with the data without a bound (nested property shapes over nodes with many values:
 * 51 s for 4 levels of 100 values per node). The shapes are the ones ShaclSail would load (the same extraction from
 * the data graph), admitted by the same rules ([Rdf4jEmbeddedShapes]); the report is mapped exactly as ShaclSail's
 * report is ([toResult]).
 *
 * Each data graph is kept, with its extracted shapes, in a [GraphStateCache] of [maxCachedGraphs] entries with the
 * same change detection as the ShaclSail stores.
 */
@OptIn(KastorGenInternalApi::class)
internal class Rdf4jNativeEmbedded(
    maxCachedGraphs: Int,
    assumeImmutable: Boolean,
    probe: GraphStateCache.Probe,
    private val toStatements: (List<RdfTriple>) -> List<Statement>,
    private val extractShapes: (List<Statement>) -> List<Statement>,
    private val toResult: (Model, Resource, Model) -> ValidationResult,
    private val timeout: Duration = TIMEOUT,
) : AutoCloseable {
    companion object {
        /** The deadline of the validation of one focus node against embedded shapes. */
        val TIMEOUT: Duration = Duration.ofSeconds(5)

        /** Results kept per validation; ShaclSail's path reports every result. */
        private const val MAX_VIOLATIONS = 100_000
    }

    /** A data graph, the shapes embedded in it (null when there are none) and those shapes as RDF4J statements. */
    private class Prepared(val data: RdfGraph, val shapes: RdfGraph?, val shapesModel: Model)

    private val cache = GraphStateCache<Prepared>(
        maxEntries = maxCachedGraphs,
        // A prepared copy is only read, so validations of one graph share it.
        exclusive = false,
        load = { triples, _ -> prepare(triples) },
        release = { },
        owner = "Rdf4jValidation",
        settings = GraphStateCache.Settings(assumeImmutable = assumeImmutable, probe = probe),
    )

    private fun prepare(triples: List<RdfTriple>): Prepared {
        val statements = toStatements(triples)
        val extracted = extractShapes(statements)
        if (extracted.isEmpty()) return Prepared(MemoryGraph(triples), null, LinkedHashModel())
        Rdf4jEmbeddedShapes.requireAdmissible(extracted)
        // toStatements maps each triple to one statement: the shapes are the triples of the extracted statements.
        val tripleOf = HashMap<Statement, RdfTriple>(statements.size * 2)
        statements.forEachIndexed { i, statement -> tripleOf.putIfAbsent(statement, triples[i]) }
        return Prepared(MemoryGraph(triples), MemoryGraph(extracted.map { tripleOf.getValue(it) }), LinkedHashModel(extracted))
    }

    private fun config() = ValidationConfig(providerId = "kastor", timeout = timeout, maxViolations = MAX_VIOLATIONS)

    /** Validates each of [focuses] (Kastor term to RDF4J value) against the shapes embedded in [data]. */
    fun validateAll(data: RdfGraph, focuses: Map<RdfTerm, Resource>): Map<RdfTerm, ValidationResult> =
        cache.use(data) { prepared ->
            val shapes = prepared.shapes ?: return@use focuses.mapValuesTo(LinkedHashMap()) { ValidationResult.Ok }
            val validator = ShaclValidation.validator(config())
            focuses.mapValuesTo(LinkedHashMap()) { (term, value) ->
                val report = try {
                    validator.validateResource(prepared.data, shapes, term as RdfResource)
                } catch (e: ShaclValidationException) {
                    throw EmbeddedShapesRefusedException(
                        "Rdf4jValidation stopped validating against the SHACL shapes embedded in the data graph: " +
                            "${e.message} Embedded shapes are untrusted and are validated by the Kastor SHACL " +
                            "validator within ${timeout.toMillis()} ms; only when the data graph is trusted, create " +
                            "the validator with Rdf4jValidation.trustingEmbeddedShapes() (ShaclSail, no deadline).",
                        e,
                    )
                }
                if (report.isValid && report.violations.isEmpty()) {
                    ValidationResult.Ok
                } else {
                    val rdf = report.toShaclValidationReportRdf()
                    toResult(LinkedHashModel(toStatements(rdf.getTriples())), value, prepared.shapesModel)
                }
            }
        }

    override fun close() {
        cache.close()
    }
}
