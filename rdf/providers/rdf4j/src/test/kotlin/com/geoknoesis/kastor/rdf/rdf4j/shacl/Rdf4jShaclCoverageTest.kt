package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedFeatureHandling
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclFeatureException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the bridge claims is what RDF4J's `ShaclSail` does: for every SHACL Core constraint component, target and
 * path, and for the SHACL-SPARQL features, data that violates a shape is either reported as non-conforming, or the
 * shapes graph is rejected as using a feature `ShaclSail` does not implement. It never silently conforms.
 */
class Rdf4jShaclCoverageTest {
  private val prefixes =
      """
      @prefix sh: <http://www.w3.org/ns/shacl#> .
      @prefix ex: <http://example.org/> .
      @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
      """.trimIndent()

  private class Case(val name: String, val shapes: String, val data: String)

  private fun property(constraint: String, data: String, name: String = constraint) =
      Case(name, "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; $constraint ] .", data)

  private fun path(path: String, data: String) =
      Case("path $path", "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path $path ; sh:maxCount 0 ] .", data)

  /** Violated shapes that `ShaclSail` evaluates. */
  private val supported: List<Case> =
      listOf(
          property("sh:class ex:C", "ex:a ex:p ex:b ."),
          property("sh:datatype xsd:integer", "ex:a ex:p \"x\" ."),
          property("sh:nodeKind sh:IRI", "ex:a ex:p \"x\" ."),
          property("sh:minCount 1", "ex:a ex:q 1 ."),
          property("sh:maxCount 1", "ex:a ex:p 1, 2 ."),
          property("sh:minExclusive 5", "ex:a ex:p 5 ."),
          property("sh:minInclusive 5", "ex:a ex:p 4 ."),
          property("sh:maxExclusive 5", "ex:a ex:p 5 ."),
          property("sh:maxInclusive 5", "ex:a ex:p 6 ."),
          property("sh:minLength 3", "ex:a ex:p \"ab\" ."),
          property("sh:maxLength 1", "ex:a ex:p \"ab\" ."),
          property("sh:pattern \"^b\"", "ex:a ex:p \"ab\" ."),
          property("sh:pattern \"^AB\" ; sh:flags \"i\"", "ex:a ex:p \"xab\" ."),
          property("sh:languageIn ( \"en\" )", "ex:a ex:p \"x\"@fr ."),
          property("sh:uniqueLang true", "ex:a ex:p \"x\"@en, \"y\"@en ."),
          property("sh:equals ex:q", "ex:a ex:p 1 ; ex:q 2 ."),
          property("sh:disjoint ex:q", "ex:a ex:p 1 ; ex:q 1 ."),
          property("sh:lessThan ex:q", "ex:a ex:p 2 ; ex:q 1 ."),
          property("sh:lessThanOrEquals ex:q", "ex:a ex:p 2 ; ex:q 1 ."),
          property("sh:not [ sh:datatype xsd:string ]", "ex:a ex:p \"x\" ."),
          property("sh:and ( [ sh:datatype xsd:string ] [ sh:minLength 3 ] )", "ex:a ex:p \"x\" ."),
          property("sh:or ( [ sh:datatype xsd:integer ] [ sh:nodeKind sh:IRI ] )", "ex:a ex:p \"x\" ."),
          property("sh:node [ sh:property [ sh:path ex:q ; sh:minCount 1 ] ]", "ex:a ex:p ex:b ."),
          property("sh:qualifiedValueShape [ sh:class ex:C ] ; sh:qualifiedMinCount 1", "ex:a ex:p ex:b ."),
          property("sh:qualifiedValueShape [ sh:nodeKind sh:IRI ] ; sh:qualifiedMaxCount 0", "ex:a ex:p ex:b ."),
          property("sh:hasValue ex:v", "ex:a ex:p ex:b ."),
          property("sh:in ( ex:v ex:w )", "ex:a ex:p ex:b ."),
          Case("sh:closed", "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:closed true ; sh:property [ sh:path ex:p ] .", "ex:a ex:p 1 ; ex:q 2 ."),
          Case(
              "sh:closed with sh:ignoredProperties",
              "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:closed true ; sh:ignoredProperties ( rdf:type ) ; sh:property [ sh:path ex:p ] .",
              "ex:a a ex:C ; ex:p 1 ; ex:q 2 .",
          ),
          Case("a constraint on the focus node itself", "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeKind sh:Literal .", "ex:a ex:p 1 ."),
          Case("sh:targetClass", "ex:S a sh:NodeShape ; sh:targetClass ex:C ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .", "ex:a a ex:C ."),
          Case(
              "sh:targetClass through rdfs:subClassOf",
              "ex:S a sh:NodeShape ; sh:targetClass ex:C ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .",
              "ex:D rdfs:subClassOf ex:C . ex:a a ex:D .",
          ),
          Case("sh:targetSubjectsOf", "ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:q ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .", "ex:a ex:q 1 ."),
          Case("sh:targetObjectsOf", "ex:S a sh:NodeShape ; sh:targetObjectsOf ex:q ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .", "ex:b ex:q ex:a ."),
          Case("an implicit class target", "ex:C a sh:NodeShape, rdfs:Class ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .", "ex:a a ex:C ."),
          Case("a property shape with a target", "ex:P a sh:PropertyShape ; sh:targetNode ex:a ; sh:path ex:p ; sh:minCount 1 .", "ex:a ex:q 1 ."),
          path("[ sh:inversePath ex:p ]", "ex:b ex:p ex:a ."),
          path("( ex:p ex:q )", "ex:a ex:p ex:b . ex:b ex:q ex:c ."),
          path("[ sh:alternativePath ( ex:p ex:q ) ]", "ex:a ex:q ex:b ."),
          path("[ sh:inversePath ( ex:p ex:q ) ]", "ex:c ex:p ex:b . ex:b ex:q ex:a ."),
          Case("a shape without rdf:type", "ex:S sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .", "ex:a ex:q 1 ."),
          Case(
              "a SPARQL target (SHACL-AF)",
              """
              ex:S a sh:NodeShape ;
                sh:target [ a sh:SPARQLTarget ; sh:select "SELECT ?this WHERE { ?this <http://example.org/q> 1 }" ] ;
                sh:property [ sh:path ex:p ; sh:minCount 1 ] .
              """.trimIndent(),
              "ex:a ex:q 1 .",
          ),
          Case(
              "sh:sparql (SHACL-SPARQL constraint)",
              """
              ex:S a sh:NodeShape ; sh:targetNode ex:a ;
                sh:sparql [ a sh:SPARQLConstraint ; sh:message "p" ;
                  sh:select "SELECT ${'$'}this ?value WHERE { ${'$'}this <http://example.org/p> ?value }" ] .
              """.trimIndent(),
              "ex:a ex:p 1 .",
          ),
          Case(
              "sh:sparql with sh:prefixes",
              """
              ex: a <http://www.w3.org/2002/07/owl#Ontology> ; sh:declare [ sh:prefix "ex" ; sh:namespace "http://example.org/"^^xsd:anyURI ] .
              ex:S a sh:NodeShape ; sh:targetNode ex:a ;
                sh:sparql [ a sh:SPARQLConstraint ; sh:prefixes ex: ;
                  sh:select "SELECT ${'$'}this ?value WHERE { ${'$'}this ex:p ?value }" ] .
              """.trimIndent(),
              "ex:a ex:p 1 .",
          ),
      )

  /** Violated shapes that `ShaclSail` would ignore: the bridge must reject them instead of reporting conformance. */
  private val unsupported: List<Case> =
      listOf(
          property("sh:xone ( [ sh:datatype xsd:string ] [ sh:minLength 1 ] )", "ex:a ex:p \"x\" ."),
          Case("sh:xone on a node shape", "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:xone ( [ sh:nodeKind sh:IRI ] [ sh:nodeKind sh:BlankNodeOrIRI ] ) .", "ex:a ex:p 1 ."),
          path("[ sh:zeroOrMorePath ex:p ]", "ex:a ex:p ex:b ."),
          path("[ sh:oneOrMorePath ex:p ]", "ex:a ex:p ex:b . ex:b ex:p ex:c ."),
          path("[ sh:zeroOrOnePath ex:p ]", "ex:a ex:p ex:b ."),
          path("( ex:p [ sh:zeroOrMorePath ex:q ] )", "ex:a ex:p ex:b ."),
          path("[ sh:inversePath [ sh:oneOrMorePath ex:p ] ]", "ex:b ex:p ex:a ."),
          path("[ sh:alternativePath ( ex:q [ sh:zeroOrOnePath ex:p ] ) ]", "ex:a ex:p ex:b ."),
          Case(
              "sh:qualifiedValueShapesDisjoint",
              """
              ex:S a sh:NodeShape ; sh:targetNode ex:a ;
                sh:property [ sh:path ex:p ; sh:qualifiedValueShape [ sh:nodeKind sh:IRI ] ; sh:qualifiedMinCount 1 ; sh:qualifiedValueShapesDisjoint true ] ,
                  [ sh:path ex:p ; sh:qualifiedValueShape [ sh:class ex:C ] ; sh:qualifiedMinCount 1 ] .
              """.trimIndent(),
              "ex:a ex:p ex:b . ex:b a ex:C .",
          ),
          Case(
              "a nested shape with an unsupported constraint",
              "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:node [ sh:or ( [ sh:xone ( [ sh:nodeKind sh:IRI ] ) ] ) ] ] .",
              "ex:a ex:p ex:b .",
          ),
          Case(
              "a target that is not a SPARQL target",
              "ex:S a sh:NodeShape ; sh:target [ a ex:CustomTarget ; ex:arg 1 ] ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .",
              "ex:a ex:q 1 .",
          ),
          Case("a SHACL-AF rule", "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:rule [ a sh:TripleRule ; sh:subject sh:this ; sh:predicate ex:z ; sh:object ex:o ] .", "ex:a ex:p 1 ."),
          Case(
              "a SPARQL-based constraint component",
              """
              ex:Component a sh:ConstraintComponent ;
                sh:parameter [ sh:path ex:forbidden ] ;
                sh:validator [ a sh:SPARQLAskValidator ; sh:ask "ASK { FILTER(${'$'}value != ${'$'}forbidden) }" ] .
              ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; ex:forbidden 1 ] .
              """.trimIndent(),
              "ex:a ex:p 1 .",
          ),
          Case(
              "sh:sparql with sh:ask",
              "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:sparql [ sh:ask \"ASK { ${'$'}this <http://example.org/p> ?v }\" ] .",
              "ex:a ex:p 1 .",
          ),
          Case(
              "a SHACL 1.2 list constraint (sh:memberShape)",
              "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:memberShape [ sh:datatype xsd:integer ] ] .",
              "ex:a ex:p ( \"x\" ) .",
          ),
          Case(
              "a SHACL 1.2 node expression (sh:values)",
              "ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:values ex:v ; sh:maxCount 0 ] .",
              "ex:a ex:q 1 .",
          ),
      )

  private fun graph(body: String) = Rdf.parse(prefixes + "\n" + body, "TURTLE")

  private fun validator(handling: UnsupportedFeatureHandling = UnsupportedFeatureHandling.FAIL) =
      Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j", unsupportedFeatures = handling))

  @TestFactory
  fun `violations of what ShaclSail implements are reported`(): List<DynamicTest> =
      supported.map { case ->
        DynamicTest.dynamicTest(case.name) {
          val report = validator().validate(graph(case.data), graph(case.shapes))
          assertFalse(report.isValid, "${case.name}: the data violates the shape")
          assertTrue(report.violations.isNotEmpty(), case.name)
        }
      }

  @TestFactory
  fun `shapes that ShaclSail would ignore are rejected`(): List<DynamicTest> =
      unsupported.map { case ->
        DynamicTest.dynamicTest(case.name) {
          val error = assertFailsWith<ShaclValidationException> { validator().validate(graph(case.data), graph(case.shapes)) }
          assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is UnsupportedShaclFeatureException }, error.toString())
          assertTrue(error.message!!.contains("IGNORE_WITH_WARNING"), error.message)
        }
      }

  @Test
  fun `an unsupported construct does not hide the violations of the shapes ShaclSail evaluates`() {
    val shapes =
        graph(
            """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:xone ( [ sh:nodeKind sh:IRI ] [ sh:nodeKind sh:BlankNodeOrIRI ] ) ;
              sh:sparql [ sh:ask "ASK { ?s ?p ?o }" ] ;
              sh:property [ sh:path ex:p ; sh:minCount 1 ] .
            """.trimIndent(),
        )
    val data = graph("ex:a ex:q 1 .")
    val report = validator(UnsupportedFeatureHandling.IGNORE_WITH_WARNING).validate(data, shapes)
    assertFalse(report.isValid)
    assertEquals(listOf(ConstraintType.MIN_COUNT), report.violations.map { it.constraint.constraintType })
    assertEquals(2, report.warnings.size, report.warnings.toString())
    val silent = Rdf4jShaclValidator(
        ValidationConfig(providerId = "rdf4j", unsupportedFeatures = UnsupportedFeatureHandling.IGNORE_WITH_WARNING, includeWarnings = false),
    ).validate(data, shapes)
    assertEquals(emptyList(), silent.warnings)
    assertFalse(silent.isValid)
  }

  @Test
  fun `a shapes graph that ShaclSail cannot parse fails as a validation exception`() {
    // Two sh:path values: ShaclSail raises its own parsing exception at commit.
    val shapes = graph("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p, ex:q ; sh:minCount 1 ] .")
    assertFailsWith<ShaclValidationException> { validator().validate(graph("ex:a ex:q 1 ."), shapes) }
  }

  @TestFactory
  fun `shapes that ShaclSail would ignore are skipped with a warning when asked to`(): List<DynamicTest> =
      unsupported.map { case ->
        DynamicTest.dynamicTest(case.name) {
          val report = validator(UnsupportedFeatureHandling.IGNORE_WITH_WARNING).validate(graph(case.data), graph(case.shapes))
          assertEquals(1, report.warnings.count { it.message.startsWith("Unsupported SHACL feature ignored") }, report.warnings.toString())
        }
      }
}
