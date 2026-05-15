package com.geoknoesis.kastor.rdf.shacl.conformance

import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.providers.NativeShaclValidatorProvider
import com.geoknoesis.kastor.rdf.jena.JenaProvider
import org.apache.jena.rdf.model.ModelFactory

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

        val graphLoader = JenaProvider()
        val data =
            dataPath.toFile().inputStream().use { stream ->
                graphLoader.parseGraph(stream, "TURTLE", dataPath.toUri().toString())
            }
        val shapes =
            shapesPath.toFile().inputStream().use { stream ->
                graphLoader.parseGraph(stream, "TURTLE", shapesPath.toUri().toString())
            }

        val expectedReportNode =
            entry.getPropertyResourceValue(model.createProperty(MF + "result"))
                ?: error("mf:result missing for ${case.entryUri}")

        val expected = Shacl12ExpectedReport.parse(model, expectedReportNode)

        val useNative = System.getProperty("shacl.w3c.useNative") != "false"
        if (useNative) {
            val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig.default())
            val report = validator.validate(data, shapes)
            assertMatchesW3cExpected(report, expected, case.displayName)
            assertNoUnexpectedWarnings(report, case.displayName)
            assertValidMeansZeroViolations(report, case.displayName)
        } else {
            val actual =
                try {
                    JenaShacl12Conformance.validateToExpectedReport(dataPath, shapesPath)
                } catch (_: Exception) {
                    val validator = NativeShaclValidatorProvider().createValidator(ValidationConfig.default())
                    validator.validate(data, shapes).toExpectedConformanceReport()
                }
            assertMatchesW3cExpected(actual, expected, case.displayName)
            assertValidMeansZeroViolations(actual, case.displayName)
        }
    }
}
