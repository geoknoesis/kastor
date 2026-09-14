package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import org.apache.jena.rdf.model.ModelFactory
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
            val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig.default())
            W3cKnownDeviations.reasonFor(manifestPath)?.let { reason ->
                val error = assertThrows(ShaclValidationException::class.java, { validator.validate(data, shapes) }, "${case.displayName}: known deviation must fail explicitly ($reason)")
                assertTrue(error.message.orEmpty().contains("Unsupported SHACL feature"), "${case.displayName}: unexpected failure ${error.message}")
                return
            }
            if (expectsFailure) {
                assertThrows(ShaclValidationException::class.java, { validator.validate(data, shapes) }, "${case.displayName}: expected sht:Failure")
                return
            }
            val expected = Shacl12ExpectedReport.parse(model, resultNode.asResource())
            val report = validator.validate(data, shapes)
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
