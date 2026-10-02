package com.geoknoesis.kastor.rdf.rdf4j.shacl

import com.geoknoesis.kastor.rdf.BlankNode
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.ConstraintType
import com.geoknoesis.kastor.rdf.shacl.ShaclConstraint
import com.geoknoesis.kastor.rdf.shacl.ShaclShape
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ShaclValidationException
import com.geoknoesis.kastor.rdf.shacl.UnsupportedShaclOperationException
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.SHACL
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The RDF4J SHACL bridge says what it supports and honours the options of [ValidationConfig]. */
class Rdf4jShaclBridgeConfigTest {
  private val prefixes =
      """
      @prefix sh: <http://www.w3.org/ns/shacl#> .
      @prefix ex: <http://example.org/> .
      @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
      """.trimIndent()

  private fun graph(body: String): RdfGraph = Rdf.parse(prefixes + "\n" + body, "TURTLE")

  private val minCountShape = "ex:S a sh:NodeShape ; sh:targetSubjectsOf ex:q ; sh:property [ sh:path ex:p ; sh:minCount 1 ] ."
  private fun subjects(count: Int): String = (1..count).joinToString("\n") { "ex:n$it ex:q 1 ." }
  private val ex = "http://example.org/"

  @Test
  fun `the capabilities and the profiles agree`() {
    val provider = Rdf4jShaclValidatorProvider()
    val capabilities = provider.getCapabilities()
    assertEquals(capabilities.supportsShaclCore, provider.isSupported(ValidationProfile.SHACL_CORE))
    assertEquals(capabilities.supportsShaclSparql, provider.isSupported(ValidationProfile.SHACL_SPARQL))
    assertTrue(capabilities.supportsShaclSparql)
    assertEquals(provider.getSupportedProfiles().toSet(), ValidationProfile.entries.filter(provider::isSupported).toSet())
    for (profile in listOf(ValidationProfile.SHACL_JS, ValidationProfile.SHACL_PY, ValidationProfile.SHACL_DASH, ValidationProfile.CUSTOM, ValidationProfile.COMPREHENSIVE)) {
      assertFalse(provider.isSupported(profile), profile.name)
    }
    assertFalse(capabilities.supportsParallelValidation)
    assertFalse(capabilities.supportsStreamingValidation)
  }

  @Test
  fun `the SHACL-SPARQL profile resolves to the bridge and evaluates sh-sparql constraints`() {
    val validator = ShaclValidation.validator(ValidationConfig(profile = ValidationProfile.SHACL_SPARQL, providerId = "rdf4j"))
    val shapes =
        graph(
            """
            ex:S a sh:NodeShape ; sh:targetNode ex:a ;
              sh:sparql ex:constraint .
            ex:constraint a sh:SPARQLConstraint ; sh:message "no p" ;
              sh:select "SELECT ${'$'}this ?value WHERE { ${'$'}this <http://example.org/p> ?value }" .
            """.trimIndent(),
        )
    val report = validator.validate(graph("ex:a ex:p 1 ."), shapes)
    assertFalse(report.isValid)
    val violation = report.violations.single()
    assertEquals(ConstraintType.SPARQL_CONSTRAINT_COMPONENT, violation.constraint.constraintType)
    assertEquals(Iri(ex + "constraint"), violation.sourceConstraint, "sh:sourceConstraint")
    assertTrue(validator.validate(graph("ex:a ex:q 1 ."), shapes).isValid)
  }

  @Test
  fun `operations and options the bridge does not implement are rejected with the domain exception`() {
    val validator = Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j"))
    val data = graph("ex:a ex:q 1 .")
    assertFailsWith<UnsupportedShaclOperationException> { validator.validate(data, listOf(ShaclShape(shapeUri = ex + "S"))) }
    assertFailsWith<UnsupportedShaclOperationException> { validator.validateConstraints(data, listOf(ShaclConstraint(ConstraintType.MIN_COUNT))) }
    // Empty lists validate against no shapes, as before.
    assertTrue(validator.validate(data, emptyList<ShaclShape>()).isValid)
    assertTrue(validator.validateConstraints(data, emptyList()).isValid)
    val parallel = assertFailsWith<UnsupportedShaclOperationException> { Rdf4jShaclValidator(ValidationConfig(parallelValidation = true)) }
    assertTrue(parallel.message!!.contains("parallelValidation"), parallel.message)
    val streaming = assertFailsWith<UnsupportedShaclOperationException> { Rdf4jShaclValidator(ValidationConfig(streamingMode = true)) }
    assertTrue(streaming.message!!.contains("streamingMode"), streaming.message)
    assertFailsWith<UnsupportedShaclOperationException> {
      ShaclValidation.validator(ValidationConfig(providerId = "rdf4j", parallelValidation = true))
    }
    assertFailsWith<IllegalArgumentException> { Rdf4jShaclValidator(ValidationConfig(timeout = Duration.ZERO)) }
    assertFailsWith<IllegalArgumentException> { Rdf4jShaclValidator(ValidationConfig(maxViolations = 0)) }
  }

  @Test
  fun `a validation that does not finish within the timeout fails`() {
    val release = CountDownLatch(1)
    val started = CountDownLatch(1)
    val interrupted = CountDownLatch(1)
    val validator =
        Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j", timeout = Duration.ofMillis(50))) {
          // The ShaclSail phase "hangs" until the test lets it go (or the bridge interrupts it).
          started.countDown()
          try {
            release.await()
          } catch (e: InterruptedException) {
            interrupted.countDown()
            throw e
          }
        }
    try {
      val error = assertFailsWith<ShaclValidationException> { validator.validate(graph(subjects(1)), graph(minCountShape)) }
      assertTrue(error.message!!.contains("timed out"), error.message)
      assertEquals(0, started.count, "the validation had started")
      // The worker is told to stop.
      interrupted.await()
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `a validation within the timeout is not affected`() {
    val report = Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j", timeout = Duration.ofMinutes(5))).validate(graph(subjects(2)), graph(minCountShape))
    assertEquals(2, report.violations.size)
  }

  @Test
  fun `sh-resultPath is reported for predicate paths and for path structures`() {
    val validator = Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j"))
    val p = Iri(ex + "p")
    val simple = validator.validate(graph("ex:a ex:q 1 ."), graph("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path ex:p ; sh:minCount 1 ] .")).violations.single()
    assertEquals(listOf(p), simple.path)
    assertEquals(p, simple.resultPathNode)
    assertEquals(p.value, simple.constraint.path)
    assertEquals(emptyList(), simple.resultPathTriples)
    assertNull(simple.sourceConstraint)

    val inverse =
        validator.validate(
            graph("ex:a ex:q 1 ."),
            graph("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:property [ sh:path [ sh:inversePath ex:p ] ; sh:minCount 1 ] ."),
        ).violations.single()
    assertNull(inverse.path, "an inverse path is not a list of predicates")
    val root = assertNotNull(inverse.resultPathNode)
    assertTrue(root is BlankNode, root.toString())
    assertEquals(
        listOf(Triple(root, Iri("http://www.w3.org/ns/shacl#inversePath"), p)),
        inverse.resultPathTriples.map { Triple(it.subject, it.predicate, it.obj) },
    )

    // A constraint on the focus node itself has no path.
    val node = validator.validate(graph("ex:a ex:q 1 ."), graph("ex:S a sh:NodeShape ; sh:targetNode ex:a ; sh:nodeKind sh:Literal .")).violations.single()
    assertNull(node.path)
    assertNull(node.resultPathNode)
  }

  @Test
  fun `the result limit follows maxViolations and truncation is reported`() {
    val shapes = graph(minCountShape)
    // More results of one constraint than RDF4J's own default limit per constraint (1000).
    val data = graph(subjects(1200))
    val all = Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j", maxViolations = 5000)).validate(data, shapes)
    assertEquals(1200, all.violations.size, "every result is reported while the report has room")
    assertFalse(all.violationsTruncated)
    assertFalse(all.isValid)

    val capped = Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j", maxViolations = 1000)).validate(data, shapes)
    assertEquals(1000, capped.violations.size)
    assertTrue(capped.violationsTruncated)
    assertFalse(capped.isValid)

    val exact = Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j", maxViolations = 1200)).validate(data, shapes)
    assertEquals(1200, exact.violations.size)
    assertFalse(exact.violationsTruncated, "a report that holds every result is not truncated")

    val few = Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j", maxViolations = 2)).validate(graph(subjects(5)), shapes)
    assertEquals(2, few.violations.size)
    assertTrue(few.violationsTruncated)
  }

  @Test
  fun `conformance is decided on every result also when the result limit hides the blocking one`() {
    val violationSeverity = Iri("http://www.w3.org/ns/shacl#Violation")
    val shapes =
        graph(
            """
            ex:Info a sh:NodeShape ; sh:targetSubjectsOf ex:q ; sh:property [ sh:path ex:p ; sh:minCount 1 ; sh:severity sh:Info ] .
            ex:Hard a sh:NodeShape ; sh:targetNode ex:n3 ; sh:property [ sh:path ex:r ; sh:minCount 1 ] .
            """.trimIndent(),
        )
    val config = ValidationConfig(providerId = "rdf4j", maxViolations = 1, conformanceDisallows = setOf(violationSeverity))
    val report = Rdf4jShaclValidator(config).validate(graph(subjects(5)), shapes)
    assertFalse(report.isValid, "one of the six results is a sh:Violation")
    assertTrue(report.violationsTruncated)
    assertEquals(1, report.violations.size)
    // Without the blocking result the same truncated report conforms.
    val infoOnly = graph("ex:Info a sh:NodeShape ; sh:targetSubjectsOf ex:q ; sh:property [ sh:path ex:p ; sh:minCount 1 ; sh:severity sh:Info ] .")
    val allowed = Rdf4jShaclValidator(config).validate(graph(subjects(5)), infoOnly)
    assertTrue(allowed.isValid)
    assertTrue(allowed.violationsTruncated)
  }

  @Test
  fun `validateResource is not cut short by the result limit`() {
    val report =
        Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j", maxViolations = 1))
            .validateResource(graph(subjects(5)), graph(minCountShape), Iri(ex + "n4"))
    assertFalse(report.isValid)
    assertEquals(listOf<Any>(Iri(ex + "n4")), report.violations.map { it.focusNode })
    assertFalse(report.violationsTruncated)
  }

  @Test
  fun `strict mode fails on a result the report cannot represent`() {
    val vf = SimpleValueFactory.getInstance()
    val report = vf.createBNode("report")
    val unreadable = vf.createBNode("unreadable")
    val readable = vf.createBNode("readable")
    val model = LinkedHashModel()
    model.add(report, RDF.TYPE, SHACL.VALIDATION_REPORT)
    model.add(report, SHACL.RESULT, unreadable)
    model.add(unreadable, SHACL.RESULT_MESSAGE, vf.createLiteral("a result without a focus node"))
    model.add(report, SHACL.RESULT, readable)
    model.add(readable, SHACL.FOCUS_NODE, vf.createIRI(ex + "a"))

    val lenient = Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j")).violationsFromReport(model)
    assertEquals(listOf<Any>(Iri(ex + "a")), lenient.map { it.focusNode })
    val error = assertFailsWith<ShaclValidationException> {
      Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j", strictMode = true)).violationsFromReport(model)
    }
    assertTrue(error.message!!.contains("strictMode"), error.message)
    // Strict mode changes nothing for an ordinary report.
    val strict = Rdf4jShaclValidator(ValidationConfig(providerId = "rdf4j", strictMode = true)).validate(graph(subjects(2)), graph(minCountShape))
    assertEquals(2, strict.violations.size)
  }
}
