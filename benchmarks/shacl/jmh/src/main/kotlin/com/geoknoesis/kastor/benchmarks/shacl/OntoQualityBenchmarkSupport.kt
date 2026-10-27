package com.geoknoesis.kastor.benchmarks.shacl

import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.QualityReport
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import java.time.Duration

/**
 * The default `onto-qa check` workload: [QualityChecker.default] (every bundled catalogue, most of whose detectors
 * are SHACL-SPARQL constraints, many with `REGEX` / `REPLACE`) on a generated ontology of a given number of classes in
 * a ternary tree, labelled and commented, with 200 object properties, one lower-case class name and one subclass
 * cycle (so the SPARQL and REGEX detectors produce findings, not only scans).
 */
object OntoQualityBenchmarkSupport {
    private const val PREFIXES = """@prefix ex: <http://example.org/o#> .
@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix dct: <http://purl.org/dc/terms/> .
<http://example.org/o> a owl:Ontology ; rdfs:label "O" ; rdfs:comment "An ontology" ; dct:creator "me" ; owl:versionInfo "1" ; dct:license <https://creativecommons.org/licenses/by/4.0/> .
"""

    /** The ontology of [classes] classes (at least 5). */
    @JvmStatic
    fun ontology(classes: Int): RdfGraph {
        require(classes >= 5) { "at least 5 classes" }
        val text = StringBuilder(PREFIXES)
        for (i in 0 until classes) {
            val superclass = if (i == 0) "" else " ; rdfs:subClassOf ex:C${(i - 1) / 3}"
            text.append("ex:C$i a owl:Class ; rdfs:label \"Class number $i\"@en ; rdfs:comment \"Desc $i\"@en$superclass .\n")
        }
        for (j in 0 until minOf(200, classes - 1)) {
            text.append("ex:p$j a owl:ObjectProperty ; rdfs:label \"p $j\" ; rdfs:domain ex:C$j ; rdfs:range ex:C${j + 1} .\n")
        }
        text.append("ex:lowerCase a owl:Class ; rdfs:label \"lower case\"@en ; rdfs:comment \"A badly named class.\"@en ; rdfs:subClassOf ex:C0 .\n")
        text.append("ex:C1 rdfs:subClassOf ex:C4 .\n")
        return Rdf.parse(text.toString(), RdfFormat.TURTLE)
    }

    /** The default checker, without a result cap and with a time limit no benchmark size reaches. */
    @JvmStatic
    fun checker(): QualityChecker =
        QualityChecker.default(ShaclValidation.validator(ValidationConfig(maxViolations = Int.MAX_VALUE, timeout = Duration.ofHours(1))))

    @JvmStatic
    fun check(checker: QualityChecker, ontology: RdfGraph): QualityReport = checker.check(ontology)
}
