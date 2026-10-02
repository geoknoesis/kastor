package com.geoknoesis.kastor.rdf.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.shacl.conformance.JenaShacl12Conformance
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ensures Kastor native, RDF4J (`:rdf:rdf4j` on the test classpath), and Jena agree on **`sh:conforms`**
 * for small hand-written graphs (no relative `<>` IRIs — RDF4J Turtle parsing in tests).
 */
class CrossEngineShaclSmokeTest {

  @Test
  fun minCountViolation_allEnginesReportInvalid() {
    assertInvalid("/cross-engine/mincount-violation.ttl")
  }

  @Test
  fun minCountValid_allEnginesReportValid() {
    assertValid("/cross-engine/mincount-valid.ttl")
  }

  @Test
  fun targetClassMaxCountViolation_allEnginesReportInvalid() {
    assertInvalid("/cross-engine/targetClass-violation.ttl")
  }

  @Test
  fun targetClassMaxCountValid_allEnginesReportValid() {
    assertValid("/cross-engine/targetClass-valid.ttl")
  }

  // --- sh:pattern ---------------------------------------------------------------------------------------------------

  /**
   * One `sh:pattern` (with optional `sh:flags`) applied to one value. [native] says whether the value conforms on the
   * Kastor native engine; [rdf4j] and [jena] say the same for the bridge engines and default to [native]. A case
   * where they differ documents a **known divergence** ([divergence] explains it): the native engine evaluates
   * patterns as XPath / XML Schema regular expressions, the bridge engines hand them to `java.util.regex`.
   * `java.util.regex` idioms the native engine translates with their intended meaning (a leading inline flag group,
   * script names) are among the agreed cases.
   */
  private class PatternCase(
      val pattern: String,
      val flags: String?,
      val value: String,
      val native: Boolean,
      val rdf4j: Boolean = native,
      val jena: Boolean = native,
      val divergence: String? = null,
  )

  private val arabicIndicDigits = String(charArrayOf(Char(0x661), Char(0x662), Char(0x663)))
  private val jose = "Jos" + Char(0xE9)
  private val lineSeparator = Char(0x2028).toString()
  private val verticalTab = Char(0x0B).toString()
  private val alpha = Char(0x3B1).toString()
  private val greekExtended = Char(0x1F00).toString()
  private val eAcute = Char(0xE9).toString()
  private val eAcuteUpper = Char(0xC9).toString()
  private val emailPattern = "^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$"

  private val agreedPatternCases =
      listOf(
          // The owner's decision: `\w` accepts the underscore on every engine, and `\W` rejects it.
          PatternCase("^\\w+$", null, "john_doe", native = true),
          PatternCase(emailPattern, null, "john_doe@example.org", native = true),
          PatternCase(emailPattern, "i", "John.Doe-x@mail.example.com", native = true),
          PatternCase(emailPattern, null, "john doe@example.org", native = false),
          PatternCase("^\\W+$", null, "_", native = false),
          PatternCase("^[\\w]+$", null, "a_b", native = true),
          PatternCase("^[^\\W]+$", null, "a_b", native = true),
          PatternCase("^\\w+$", null, "a-b", native = false),
          // Everyday patterns: classes, quantifiers, flags, groups, back-references, lazy quantifiers.
          PatternCase("^[A-Z]{2}[0-9]{2}$", null, "AB12", native = true),
          PatternCase("^[A-Z]{2}[0-9]{2}$", null, "ab12", native = false),
          PatternCase("^[A-Z]{2}[0-9]{2}$", "i", "ab12", native = true),
          PatternCase("^\\d+$", null, "42", native = true),
          PatternCase("^\\d+$", null, "4x2", native = false),
          PatternCase("^a.c$", null, "abc", native = true),
          PatternCase("^a.c$", null, "a\nc", native = false),
          PatternCase("^a.c$", "s", "a\nc", native = true),
          PatternCase("^b$", "m", "a\nb", native = true),
          PatternCase("^b$", null, "a\nb", native = false),
          PatternCase("^(a|b)\\1$", null, "aa", native = true),
          PatternCase("^(a|b)\\1$", null, "ab", native = false),
          PatternCase("^<.+?>$", null, "<a>", native = true),
          PatternCase("^\\s+$", null, " \t", native = true),
          PatternCase("b", null, "abc", native = true),
          // A leading inline flag group, as shapes written for java.util.regex engines use it, means the same on the
          // native engine (it is read as sh:flags).
          PatternCase("(?i)^abc$", null, "ABC", native = true),
          PatternCase("(?i)^abc$", null, "abd", native = false),
          PatternCase("^abc$", null, "ABC", native = false),
          PatternCase("(?i)^[a-c]+$", null, "AbC", native = true),
          PatternCase("(?s)^a.c$", null, "a\nc", native = true),
          PatternCase("(?m)^b$", null, "a\nb", native = true),
          PatternCase("(?im)^B$", null, "a\nb", native = true),
          PatternCase("(?i)^b$", "m", "a\nB", native = true),
          PatternCase("(?x)^ a b $", null, "ab", native = true),
          // A script name: not XML Schema, but meant as java.util.regex reads it.
          PatternCase("^\\p{IsLatin}+$", null, "abc", native = true),
          PatternCase("^\\p{IsLatin}+$", null, alpha, native = false),
      )

  /**
   * The remaining differences from `java.util.regex` engines, kept explicit so that a change on either side is
   * noticed. In every case the native engine follows XPath `fn:matches` (which SHACL prescribes through SPARQL
   * `REGEX`) and the bridge engines follow `java.util.regex` defaults.
   */
  private val divergentPatternCases =
      listOf(
          PatternCase("^\\d+$", null, arabicIndicDigits, native = true, rdf4j = false, jena = false,
              divergence = "\\d is any Unicode decimal digit (Nd) in XML Schema, ASCII [0-9] in java.util.regex"),
          PatternCase("^\\w+$", null, jose, native = true, rdf4j = false, jena = false,
              divergence = "\\w covers Unicode letters, marks and digits in XML Schema, ASCII [A-Za-z0-9_] in java.util.regex"),
          PatternCase("^\\w+$", null, "a+b", native = true, rdf4j = false, jena = false,
              divergence = "XML Schema \\w excludes only punctuation, separators and other: symbols such as + are word characters"),
          PatternCase("^\\d+$", null, "123\n", native = false, rdf4j = true, jena = true,
              divergence = "$ matches only at the end of the value in XPath; java.util.regex also matches before a final line terminator"),
          PatternCase("^a.b$", null, "a" + lineSeparator + "b", native = true, rdf4j = false, jena = false,
              divergence = ". excludes only line feed and carriage return in XPath; java.util.regex also excludes U+0085, U+2028, U+2029"),
          PatternCase("^b", "m", "a\rb", native = false, rdf4j = true, jena = true,
              divergence = "with flag m, ^ matches after a line feed only in XPath; java.util.regex also after CR, U+0085, U+2028, U+2029"),
          PatternCase("^\\s$", null, verticalTab, native = false, rdf4j = true, jena = true,
              divergence = "\\s is space, tab, line feed and carriage return in XML Schema; java.util.regex adds U+000B and U+000C"),
          PatternCase("(?i)^" + eAcute + "$", null, eAcuteUpper, native = true, rdf4j = false, jena = false,
              divergence = "a leading (?i) is the XPath i flag, which is Unicode-aware; in java.util.regex (?i) alone is US-ASCII"),
          PatternCase("^\\p{IsGreek}$", null, greekExtended, native = false, rdf4j = true, jena = true,
              divergence = "\\p{IsGreek} is the block U+0370..U+03FF in XML Schema; java.util.regex reads it as the script, which has U+1F00"),
          PatternCase("^\\p{Lu}$", "i", "a", native = false, rdf4j = true, jena = true,
              divergence = "XPath: the i flag does not affect category escapes; java.util.regex makes \\p{Lu} match every cased letter"),
          PatternCase("^[[:alpha:]]+$", null, "b", native = true, rdf4j = false, jena = false,
              divergence = "a POSIX bracket expression is translated; java.util.regex reads a nested class of the characters ':', a, l, p, h"),
      )

  /** A Turtle string literal for [text], ASCII only (control and non-ASCII characters as numeric escapes). */
  private fun turtleString(text: String): String {
    val out = StringBuilder("\"")
    for (c in text) {
      when {
        c == '\\' -> out.append("\\\\")
        c == '"' -> out.append("\\\"")
        c == '\n' -> out.append("\\n")
        c == '\r' -> out.append("\\r")
        c == '\t' -> out.append("\\t")
        c.code < 0x20 || c.code > 0x7E -> out.append('\\').append('u').append(c.code.toString(16).uppercase().padStart(4, '0'))
        else -> out.append(c)
      }
    }
    return out.append('"').toString()
  }

  private val patternNamespace = "http://kastor-cross-engine.example/pattern/"

  /** One document with a shape and a target node per case (shapes and data in the same graph, as in the other cases). */
  private fun patternDocument(cases: List<PatternCase>): String {
    val sb = StringBuilder("@prefix sh: <http://www.w3.org/ns/shacl#> .\n@prefix ex: <$patternNamespace> .\n")
    cases.forEachIndexed { i, case ->
      val flags = case.flags?.let { " ; sh:flags ${turtleString(it)}" }.orEmpty()
      sb.append("ex:S$i a sh:NodeShape ; sh:targetNode ex:n$i ; sh:property [ sh:path ex:v ; sh:pattern ${turtleString(case.pattern)}$flags ] .\n")
      sb.append("ex:n$i ex:v ${turtleString(case.value)} .\n")
    }
    return sb.toString()
  }

  /** For every engine, the indices of the cases whose value does **not** conform. The same document is given to all. */
  private fun failingCases(cases: List<PatternCase>, directory: Path): Map<String, Set<Int>> {
    val path = directory.resolve("patterns.ttl")
    java.nio.file.Files.writeString(path, patternDocument(cases))
    val graph = Rdf.parseFromFile(path.toString(), "TURTLE")
    fun index(node: Any?): Int = node.toString().substringAfter(patternNamespace + "n").takeWhile { it.isDigit() }.toInt()
    fun viaKastorApi(providerId: String): Set<Int> {
      val validator = ShaclValidation.validator(ValidationConfig(profile = ValidationProfile.SHACL_CORE, providerId = providerId))
      return validator.validate(graph, graph).violations.map { index(it.focusNode) }.toSet()
    }
    val jena = JenaShacl12Conformance.validateToExpectedReport(path, path).results.getTriples()
        .filter { it.predicate == com.geoknoesis.kastor.rdf.vocab.SHACL.focusNode }.map { index(it.obj) }.toSet()
    return linkedMapOf("kastor" to viaKastorApi("kastor"), "rdf4j" to viaKastorApi("rdf4j"), "jena" to jena)
  }

  private fun assertPatternCases(cases: List<PatternCase>, directory: Path) {
    val failing = failingCases(cases, directory)
    val mismatches = ArrayList<String>()
    cases.forEachIndexed { i, case ->
      val expected = mapOf("kastor" to case.native, "rdf4j" to case.rdf4j, "jena" to case.jena)
      for ((engine, conforms) in expected) {
        val actual = i !in failing.getValue(engine)
        if (actual != conforms) {
          mismatches.add("$engine: pattern ${turtleString(case.pattern)} flags ${case.flags} value ${turtleString(case.value)}: expected conforms=$conforms, got $actual")
        }
      }
    }
    assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n", prefix = "\n"))
  }

  @Test
  fun patterns_allEnginesAgree(@org.junit.jupiter.api.io.TempDir directory: Path) {
    assertTrue(agreedPatternCases.all { it.native == it.rdf4j && it.native == it.jena && it.divergence == null })
    assertPatternCases(agreedPatternCases, directory)
  }

  @Test
  fun patterns_knownDivergencesFromJavaRegexEngines(@org.junit.jupiter.api.io.TempDir directory: Path) {
    // Every case here must really diverge and say why; if a bridge engine changes, this test says so.
    assertTrue(divergentPatternCases.all { it.divergence != null && (it.native != it.rdf4j || it.native != it.jena) })
    assertPatternCases(divergentPatternCases, directory)
  }

  private fun assertInvalid(resource: String) {
    val path = resourcePath(resource)
    val graph = Rdf.parseFromFile(path.toString(), "TURTLE")

    val jenaExpected = JenaShacl12Conformance.validateToExpectedReport(path, path)
    assertFalse(jenaExpected.conforms, "jena baseline $resource")

    val kastor =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "kastor",
                parallelValidation = false,
            ),
        )
    assertFalse(kastor.validate(graph, graph).isValid, "kastor $resource")

    val rdf4j =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "rdf4j",
                parallelValidation = false,
            ),
        )
    assertFalse(rdf4j.validate(graph, graph).isValid, "rdf4j $resource")
  }

  private fun assertValid(resource: String) {
    val path = resourcePath(resource)
    val graph = Rdf.parseFromFile(path.toString(), "TURTLE")

    val jenaExpected = JenaShacl12Conformance.validateToExpectedReport(path, path)
    assertTrue(jenaExpected.conforms, "jena baseline $resource")

    val kastor =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "kastor",
                parallelValidation = false,
            ),
        )
    assertTrue(kastor.validate(graph, graph).isValid, "kastor $resource")

    val rdf4j =
        ShaclValidation.validator(
            ValidationConfig(
                profile = ValidationProfile.SHACL_CORE,
                providerId = "rdf4j",
                parallelValidation = false,
            ),
        )
    assertTrue(rdf4j.validate(graph, graph).isValid, "rdf4j $resource")
  }

  private fun resourcePath(resource: String): Path {
    val url =
        CrossEngineShaclSmokeTest::class.java.getResource(resource)
            ?: error("Missing test resource: $resource")
    return Path.of(url.toURI())
  }
}
