package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ShapeCompileException
import com.geoknoesis.kastor.rdf.shacl.SparqlPreBindingRestrictionException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeatureException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import java.nio.file.Path
import org.apache.jena.rdf.model.ModelFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

object Shacl12W3cCaseRunner {

    private const val MF = "http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#"
    private const val SHT = "http://www.w3.org/ns/shacl-test#"

    fun run(case: ShaclValidateCase) {
        val manifestPath = case.manifestPath
        val baseUri = manifestPath.toUri().toString()
        val model =
            ModelFactory.createDefaultModel().also { m ->
                manifestPath.toFile().inputStream().use { stream ->
                    m.read(stream, baseUri, "TURTLE")
                }
            }

        val entry = model.getResource(case.entryUri)
        check(entry.listProperties().hasNext()) { "manifest entry not found: ${case.entryUri}" }

        val action =
            entry.getPropertyResourceValue(model.createProperty(MF + "action"))
                ?: error("mf:action missing for ${case.entryUri}")

        val dataGraphProp = model.createProperty(SHT + "dataGraph")
        val shapesGraphProp = model.createProperty(SHT + "shapesGraph")

        val dataUri =
            action.getRequiredProperty(dataGraphProp).`object`.takeIf { it.isURIResource }?.asResource()?.uri
                ?: error("sht:dataGraph missing or not a URI resource")
        val shapesUri =
            action.getRequiredProperty(shapesGraphProp).`object`.takeIf { it.isURIResource }?.asResource()?.uri
                ?: error("sht:shapesGraph missing or not a URI resource")

        val dataPath =
            Shacl12ManifestParser.graphUriToPath(dataUri, manifestPath)
                ?: error("cannot resolve data graph URI $dataUri (manifest=$manifestPath)")
        val shapesPath =
            Shacl12ManifestParser.graphUriToPath(shapesUri, manifestPath)
                ?: error("cannot resolve shapes graph URI $shapesUri (manifest=$manifestPath)")

        val data = loadW3cGraph(dataPath)
        // Most W3C cases use one document for data and shapes; share it so blank nodes coincide.
        val shapes = if (shapesPath == dataPath) data else loadW3cGraph(shapesPath)

        val resultNode = entry.getProperty(model.createProperty(MF + "result"))?.`object`
            ?: error("mf:result missing for ${case.entryUri}")
        val expectsFailure = resultNode.isURIResource && resultNode.asResource().uri == SHT + "Failure"

        val useNative = System.getProperty("shacl.w3c.useNative") != "false"
        if (useNative) {
            W3cKnownDeviations.deviationFor(manifestPath)?.let { deviation ->
                val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig.default())
                val error = assertThrows(ShaclValidationException::class.java, { validator.validate(data, shapes) }, "${case.displayName}: known deviation must fail explicitly (${deviation.reason})")
                val unsupported = error.causes().filterIsInstance<UnsupportedShaclFeatureException>().firstOrNull()
                assertEquals(setOf(deviation.category), unsupported?.features, "${case.displayName}: unexpected failure $error")
                return
            }
            if (expectsFailure) {
                val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig.default())
                val error = assertThrows(ShaclValidationException::class.java, { validator.validate(data, shapes) }, "${case.displayName}: expected sht:Failure")
                W3cExpectedFailures.assertCategory(manifestPath, error, case.displayName)
                return
            }
            val expected = Shacl12ExpectedReport.parse(model, resultNode.asResource())
            // sh:conformanceDisallows of the expected report is a validation parameter: the engine decides conformance.
            val config = ValidationConfig.default().copy(
                conformanceDisallows = expected.conformanceDisallowsSeverityIrises?.mapTo(LinkedHashSet()) { Iri(it) },
            )
            val report = NativeShaclValidatorProvider().createValidator(config).validate(data, shapes)
            assertMatchesW3cExpected(report, expected, case.displayName)
            assertNoUnexpectedWarnings(report, case.displayName)
        } else {
            if (expectsFailure) return
            val expected = Shacl12ExpectedReport.parse(model, resultNode.asResource())
            val actual = JenaShacl12Conformance.validateToExpectedReport(dataPath, shapesPath)
            org.junit.jupiter.api.Assertions.assertEquals(expected.conforms, actual.conforms, "${case.displayName}: sh:conforms (Jena)")
            assertResultGraphsIsomorphic(expected.results, actual.results, case.displayName)
        }
    }
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

/**
 * Expected failure category of each `sht:Failure` case. A failure must be the engine rejecting the shapes graph for the
 * documented reason: never an unrelated error (timeout, missing provider, I/O) and never an unsupported-feature
 * deviation (those are listed in [W3cKnownDeviations]).
 */
internal object W3cExpectedFailures {
    private val categories: List<Pair<String, Class<out ShapeCompileException>>> = listOf(
        "sparql/pre-binding/unsupported-sparql-" to SparqlPreBindingRestrictionException::class.java,
    )

    fun assertCategory(manifestPath: Path, error: Throwable, label: String) {
        val file = manifestPath.toString().replace(java.io.File.separatorChar, '/')
        val expected = categories.firstOrNull { (prefix, _) -> file.contains("/$prefix") }?.second ?: ShapeCompileException::class.java
        val causes = error.causes().toList()
        assertTrue(causes.none { it is UnsupportedShaclFeatureException }, "$label: sht:Failure must not be an unsupported-feature deviation: $error")
        assertTrue(causes.any { expected.isInstance(it) }, "$label: expected ${expected.simpleName}, got ${causes.map { it.javaClass.simpleName }}: $error")
    }
}
