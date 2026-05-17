// W3C RDF 1.2 conformance harness. Runs the parser/serializer test suites
// shipped at https://github.com/w3c/rdf-tests against Kastor's Jena and RDF4J
// providers. The test data is checked in as a git submodule under
// `rdf/conformance/test-data/`; if the submodule is not initialised the test
// factories skip with a clear message, so a stock `./gradlew test` stays green
// on a fresh clone that omits `--recursive`.

dependencies {
  implementation(project(":rdf:core"))
  implementation(project(":rdf:jena"))
  implementation(project(":rdf:rdf4j"))

  // Use jena-arq directly to parse manifests - keeps the conformance harness
  // independent of Kastor's parsers (which is what we are testing).
  implementation(libs.jena.arq)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// The full W3C corpus is large; it is not part of `check`. Use `conformanceSmokeTest`
// for the bundled fixture, or `:rdf:conformance:test` after initializing the submodule.
tasks.named("check") {
  setDependsOn(dependsOn.filterNot { it.toString().contains("test") })
}

/** Fast harness check: bundled fixture only ([Rdf12ConformanceSmokeTest]), no submodule. */
tasks.register<Test>("conformanceSmokeTest") {
  group = "verification"
  description =
    "Runs RDF 1.2 conformance harness against bundled fixtures only (fast; no W3C submodule)."
  val mainTest = tasks.named<Test>("test").get()
  testClassesDirs = mainTest.testClassesDirs
  classpath = mainTest.classpath
  useJUnitPlatform {
    includeTags("conformance-smoke")
  }
}
