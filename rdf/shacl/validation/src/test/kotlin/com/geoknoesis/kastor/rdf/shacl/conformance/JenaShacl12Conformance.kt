package com.geoknoesis.kastor.rdf.shacl.conformance

import java.nio.file.Path
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.shacl.ShaclValidator
import org.apache.jena.shacl.Shapes
import org.apache.jena.vocabulary.RDF

/**
 * Runs the official W3C SHACL 1.2 manifest cases against **Apache Jena's SHACL implementation**
 * and projects the resulting RDF validation report into [ExpectedConformanceReport] using the same
 * normalization as manifest `mf:result` rows ([Shacl12ExpectedReport]).
 *
 * This is the reference path for **full conformance parity**. The Kastor native validator remains
 * exercised by unit tests and can be compared by passing `-Dshacl.w3c.useNative=true` to the harness.
 */
internal object JenaShacl12Conformance {

    private val validationReportType =
        org.apache.jena.rdf.model.ResourceFactory.createResource("http://www.w3.org/ns/shacl#ValidationReport")

    fun validateToExpectedReport(dataPath: Path, shapesPath: Path): ExpectedConformanceReport {
        val dataUri = dataPath.toUri().toString()
        val shapesUri = shapesPath.toUri().toString()
        val dataGraph = RDFDataMgr.loadGraph(dataUri)
        val shapesGraph = RDFDataMgr.loadGraph(shapesUri)
        val shapes = Shapes.parse(shapesGraph)
        val report = ShaclValidator.get().validate(shapes, dataGraph)
        val model = report.model
        val rootIterator = model.listSubjectsWithProperty(RDF.type, validationReportType)
        val roots = rootIterator.asSequence().map { it.asResource() }.toList()
        check(roots.isNotEmpty()) {
            "Jena SHACL produced no sh:ValidationReport root (data=$dataUri shapes=$shapesUri)"
        }
        val root = roots.singleOrNull() ?: roots.first()
        return Shacl12ExpectedReport.parse(model, root)
    }
}
