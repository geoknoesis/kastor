package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.ShaclViolation
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential: `Rdf4jValidation()` validates untrusted embedded shapes on the Kastor SHACL validator, and
 * `Rdf4jValidation.trustingEmbeddedShapes()` on ShaclSail. Over a corpus of SHACL Core shapes the two give the same
 * results (focus node, source shape, constraint component, path, value, expected value, severity). The cases where
 * they differ are listed in [knownDifferences] with the reason: each one is a decision, not hidden.
 */
class Rdf4jEmbeddedEngineParityTest {
    private val prefixes = "@prefix sh: <http://www.w3.org/ns/shacl#> . @prefix ex: <http://ex/> . " +
        "@prefix xsd: <http://www.w3.org/2001/XMLSchema#> . @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> . " +
        "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
    private val focus = Iri("http://ex/a")

    private val corpus: Map<String, String> = linkedMapOf(
        "minCount" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:q ; sh:minCount 1 ] .\nex:a ex:p 1 .",
        "maxCount" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:maxCount 1 ] .\nex:a ex:p 1, 2 .",
        "datatype" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:datatype xsd:string ] .\nex:a ex:p 1, \"x\" .",
        "ill-formed integer" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:datatype xsd:integer ] .\nex:a ex:p \"abc\"^^xsd:integer .",
        "class with subclass" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:class ex:C ] .\nex:a ex:p ex:x, ex:y . ex:x a ex:D . ex:D rdfs:subClassOf ex:C .",
        "targetClass" to "ex:S a sh:NodeShape ; sh:targetClass ex:C ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .\nex:a a ex:C .",
        "implicit class target" to "ex:C a rdfs:Class, sh:NodeShape ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .\nex:a a ex:C .",
        "targetSubjectsOf" to "ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:p ; sh:property [ sh:path ex:q ; sh:minCount 1 ] .\nex:a ex:p 1 .",
        "targetObjectsOf" to "ex:S a sh:NodeShape ; sh:targetObjectsOf ex:r ; sh:property [ sh:path ex:q ; sh:minCount 1 ] .\nex:b ex:r ex:a . ex:a ex:p 1 .",
        "nodeKind" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:nodeKind sh:IRI ] .\nex:a ex:p \"x\", ex:b .",
        "numeric ranges" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:minInclusive 2 ; sh:maxExclusive 5 ] .\nex:a ex:p 1, 3, 5 .",
        "string lengths" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:minLength 2 ; sh:maxLength 3 ] .\nex:a ex:p \"a\", \"abc\", \"abcd\" .",
        "languageIn and uniqueLang" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:languageIn ( \"en\" ) ; sh:uniqueLang true ] .\nex:a ex:p \"a\"@en, \"b\"@en, \"c\"@fr .",
        "in" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:in ( \"x\" ex:b 1 ) ] .\nex:a ex:p \"x\", \"y\", 1, 2 .",
        "hasValue" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:hasValue \"z\" ] .\nex:a ex:p \"x\" .",
        "equals and disjoint" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:equals ex:q ; sh:disjoint ex:r ] .\nex:a ex:p 1, 2 ; ex:q 1 ; ex:r 2 .",
        "lessThan" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:lessThan ex:q ; sh:lessThanOrEquals ex:r ] .\nex:a ex:p 3 ; ex:q 2 ; ex:r 3 .",
        "not" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:not [ sh:class ex:C ] .\nex:a a ex:C .",
        "and" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:and ( [ sh:class ex:C ] [ sh:class ex:D ] ) .\nex:a a ex:C .",
        "or" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:or ( [ sh:class ex:C ] [ sh:class ex:D ] ) .\nex:a a ex:E .",
        "xone" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:xone ( [ sh:class ex:C ] [ sh:class ex:D ] ) .\nex:a a ex:C, ex:D .",
        "node" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:node ex:T ] .\nex:T a sh:NodeShape ; sh:property [ sh:path ex:q ; sh:minCount 1 ] .\nex:a ex:p ex:b .",
        "closed" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:closed true ; sh:ignoredProperties ( rdf:type ) ; sh:property [ sh:path ex:p ] .\nex:a a ex:C ; ex:p 1 ; ex:q 2 .",
        "inverse path" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path [ sh:inversePath ex:p ] ; sh:minCount 2 ] .\nex:b ex:p ex:a .",
        "sequence path" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ( ex:p ex:q ) ; sh:datatype xsd:integer ] .\nex:a ex:p ex:b . ex:b ex:q \"x\" .",
        "alternative path" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path [ sh:alternativePath ( ex:p ex:q ) ] ; sh:maxCount 1 ] .\nex:a ex:p 1 ; ex:q 2 .",
        "zeroOrMore path" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path [ sh:zeroOrMorePath ex:p ] ; sh:nodeKind sh:IRI ] .\nex:a ex:p ex:b . ex:b ex:p \"x\" .",
        "qualified" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:qualifiedValueShape [ sh:class ex:C ] ; sh:qualifiedMinCount 2 ] .\nex:a ex:p ex:b . ex:b a ex:C .",
        "severity and message" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:q ; sh:minCount 1 ; sh:severity sh:Warning ; sh:message \"q needed\" ] .\nex:a ex:p 1 .",
        "deactivated" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:deactivated true ; sh:property [ sh:path ex:q ; sh:minCount 1 ] .\nex:a ex:p 1 .",
        "blank focus shape" to "[] a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:q ; sh:minCount 1 ] .\nex:a ex:p 1 .",
        "conforming" to "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:minCount 1 ; sh:datatype xsd:integer ] .\nex:a ex:p 1 .",
        "no shapes" to "ex:a ex:p 1 .",
    )

    /** Cases where ShaclSail and the Kastor SHACL validator give different results, with the reason. */
    private val knownDifferences: Map<String, String> = mapOf(
        // ShaclSail silently skips sh:xone and the *OrMore / zeroOrOne paths (see the ShaclSailFeatures of
        // rdf-rdf4j); the Kastor validator evaluates them as SHACL specifies, so it reports the violation.
        "xone" to "ShaclSail does not evaluate sh:xone",
        "zeroOrMore path" to "ShaclSail does not evaluate sh:zeroOrMorePath",
    )

    private fun key(v: ShaclViolation) = listOf(v.focusNode, v.shapeIri, v.constraintIri, v.path, v.actualValue, v.expectedValue, v.severity).toString()

    private fun keys(result: ValidationResult): List<String> = when (result) {
        ValidationResult.Ok -> emptyList()
        is ValidationResult.Violations -> result.items.map(::key).sorted()
    }

    @Test
    fun `the Kastor validator and ShaclSail agree on SHACL Core embedded shapes`() {
        val differences = LinkedHashMap<String, String>()
        Rdf4jValidation().use { native ->
            Rdf4jValidation.trustingEmbeddedShapes().use { sail ->
                for ((name, turtle) in corpus) {
                    val graph = Rdf.parse(prefixes + turtle, "TURTLE")
                    val a = keys(native.validate(graph, focus))
                    val b = keys(sail.validate(graph, focus))
                    if (a != b) differences[name] = "kastor=$a\nshaclsail=$b"
                }
            }
        }
        assertEquals(knownDifferences.keys, differences.keys, differences.entries.joinToString("\n\n") { "${it.key}:\n${it.value}" })
        // In the known differences the Kastor validator reports the violation that ShaclSail misses.
        for (name in knownDifferences.keys) assertTrue(differences.getValue(name).startsWith("kastor=[["), differences.getValue(name))
    }

    @Test
    fun `an sh message of the shape is the violation message on both engines`() {
        val graph = Rdf.parse(prefixes + corpus.getValue("severity and message"), "TURTLE")
        for (validator in listOf(Rdf4jValidation(), Rdf4jValidation.trustingEmbeddedShapes())) {
            validator.use { v ->
                val items = (v.validate(graph, focus) as ValidationResult.Violations).items
                assertEquals(listOf("q needed"), items.map { it.message })
            }
        }
    }
}
