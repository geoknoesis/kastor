package com.geoknoesis.kastor.gen.validation.rdf4j

import com.geoknoesis.kastor.gen.runtime.ShaclSeverity
import com.geoknoesis.kastor.gen.runtime.ShaclViolation
import com.geoknoesis.kastor.gen.runtime.ValidationResult
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.LangString
import com.geoknoesis.kastor.rdf.Literal
import com.geoknoesis.kastor.rdf.Rdf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Regression tests asserting real SHACL semantics (not a hard-coded foaf:name check),
 * with shapes supplied separately from the data graph.
 */
class Rdf4jShaclSemanticsTest {

  private val ex = "http://example.org/"
  private val sh = "http://www.w3.org/ns/shacl#"
  private val alice = Iri("${ex}alice")
  private val bob = Iri("${ex}bob")

  private val shapesTtl = """
    @prefix sh: <http://www.w3.org/ns/shacl#> .
    @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
    @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
    @prefix ex: <http://example.org/> .

    ex:EmployeeShape a sh:NodeShape ;
      sh:targetClass ex:Employee ;
      sh:property [ sh:path ex:id ; sh:minCount 1 ; sh:message "Employee id is required" ] ;
      sh:property [ sh:path ex:age ; sh:datatype xsd:integer ] ;
      sh:property [ sh:path ex:email ; sh:pattern "^[^@]+@[^@]+$" ] ;
      sh:property [ sh:path ex:worksFor ; sh:class ex:Organization ] ;
      sh:property [ sh:path ex:nickname ; sh:maxCount 1 ] ;
      sh:property [ sh:path ex:label ; sh:datatype rdf:langString ; sh:languageIn ( "en" ) ] ;
      sh:property [ sh:path ex:hobby ; sh:minCount 1 ; sh:severity sh:Warning ] .
  """.trimIndent()

  private val prefixes = """
    @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
    @prefix ex: <http://example.org/> .
  """.trimIndent()

  private fun validation() = Rdf4jValidation(Rdf.parse(shapesTtl))

  private fun data(body: String) = Rdf.parse("$prefixes\n$body")

  private val validEmployee = """
    ex:acme a ex:Organization .
    ex:alice a ex:Employee ;
      ex:id "A1" ;
      ex:age 30 ;
      ex:email "alice@example.org" ;
      ex:worksFor ex:acme ;
      ex:nickname "Al" ;
      ex:label "Alice"@en ;
      ex:hobby "chess" .
  """.trimIndent()

  private fun violationsOf(result: ValidationResult): List<ShaclViolation> {
    assertTrue(result is ValidationResult.Violations, "expected violations but was $result")
    return (result as ValidationResult.Violations).items
  }

  private fun component(name: String) = Iri("$sh${name}ConstraintComponent")

  @Test
  fun `conforming data with separate shapes graph returns Ok`() {
    assertEquals(ValidationResult.Ok, validation().validate(data(validEmployee), alice))
  }

  @Test
  fun `sh datatype violation is reported with path value and component`() {
    val g = data(validEmployee.replace("ex:age 30", "ex:age \"thirty\""))
    val v = violationsOf(validation().validate(g, alice)).single()
    assertEquals(alice, v.focusNode)
    assertEquals(component("Datatype"), v.constraintIri)
    assertEquals(Iri("${ex}age"), v.path)
    assertEquals(Literal("thirty"), v.actualValue)
    assertEquals(Iri("${ex}EmployeeShape"), v.shapeIri)
    assertEquals(ShaclSeverity.Violation, v.severity)
  }

  @Test
  fun `sh pattern violation`() {
    val g = data(validEmployee.replace("alice@example.org", "not-an-email"))
    val v = violationsOf(validation().validate(g, alice)).single()
    assertEquals(component("Pattern"), v.constraintIri)
    assertEquals(Iri("${ex}email"), v.path)
  }

  @Test
  fun `sh class violation`() {
    val g = data(validEmployee.replace("ex:acme a ex:Organization .", ""))
    val v = violationsOf(validation().validate(g, alice)).single()
    assertEquals(component("Class"), v.constraintIri)
    assertEquals(Iri("${ex}acme"), v.actualValue)
  }

  @Test
  fun `sh minCount on a non foaf property uses sh message`() {
    val g = data(validEmployee.replace("ex:id \"A1\" ;", ""))
    val v = violationsOf(validation().validate(g, alice)).single()
    assertEquals(component("MinCount"), v.constraintIri)
    assertEquals(Iri("${ex}id"), v.path)
    assertTrue(v.message.contains("Employee id is required"), v.message)
  }

  @Test
  fun `sh maxCount violation`() {
    val g = data(validEmployee.replace("ex:nickname \"Al\" ;", "ex:nickname \"Al\", \"Ally\" ;"))
    val v = violationsOf(validation().validate(g, alice)).single()
    assertEquals(component("MaxCount"), v.constraintIri)
  }

  @Test
  fun `severity is mapped from sh severity`() {
    val g = data(validEmployee.replace("ex:hobby \"chess\" .", "ex:label \"Alice\"@en ."))
    val v = violationsOf(validation().validate(g, alice)).single()
    assertEquals(component("MinCount"), v.constraintIri)
    assertEquals(ShaclSeverity.Warning, v.severity)
  }

  @Test
  fun `language tagged literal is validated faithfully`() {
    val ok = data(validEmployee)
    assertEquals(ValidationResult.Ok, validation().validate(ok, alice))

    val g = data(validEmployee.replace("\"Alice\"@en", "\"Alice\"@fr"))
    val v = violationsOf(validation().validate(g, alice)).single()
    assertEquals(component("LanguageIn"), v.constraintIri)
    assertEquals(LangString("Alice", "fr"), v.actualValue)
  }

  @Test
  fun `large xsd integer is valid for sh datatype xsd integer`() {
    val g = data(validEmployee.replace("ex:age 30", "ex:age 123456789012345678901234567890"))
    assertEquals(ValidationResult.Ok, validation().validate(g, alice))
  }

  @Test
  fun `results are scoped to the requested focus node`() {
    val g = data(
      validEmployee + "\n" + """
        ex:bob a ex:Employee ; ex:age "old" ; ex:hobby "golf" .
      """.trimIndent()
    )
    assertEquals(ValidationResult.Ok, validation().validate(g, alice))
    val items = violationsOf(validation().validate(g, bob))
    assertEquals(setOf(component("MinCount"), component("Datatype")), items.map { it.constraintIri }.toSet())
    assertTrue(items.all { it.focusNode == bob })
  }

  @Test
  fun `shapes embedded in the data graph are still honoured by the no-arg constructor`() {
    val g = Rdf.parse(shapesTtl + "\n" + validEmployee.replace("ex:id \"A1\" ;", ""))
    val v = violationsOf(Rdf4jValidation().validate(g, alice)).single()
    assertEquals(component("MinCount"), v.constraintIri)
  }

  @Test
  fun `data without any shapes is Ok for the no-arg constructor`() {
    assertEquals(ValidationResult.Ok, Rdf4jValidation().validate(data(validEmployee), alice))
  }

  @Test
  fun `reused instance does not retain data between calls`() {
    Rdf4jValidation.fromTurtle(shapesTtl).use { v ->
      val invalid = data(validEmployee.replace("ex:id \"A1\" ;", ""))
      assertTrue(v.validate(invalid, alice) is ValidationResult.Violations)
      assertEquals(ValidationResult.Ok, v.validate(data(validEmployee), alice))
      // An empty graph must not see alice's triples from earlier calls.
      assertEquals(ValidationResult.Ok, v.validate(data(""), alice))
      assertTrue(v.validate(invalid, alice) is ValidationResult.Violations)
    }
  }

  @Test
  fun `literal focus is rejected instead of silently passing`() {
    assertThrows<IllegalArgumentException> {
      validation().validate(data(validEmployee), Literal("x"))
    }
  }
}
