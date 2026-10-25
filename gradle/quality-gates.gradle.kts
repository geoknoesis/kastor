// Repository quality gates, applied to the root project by build.gradle.kts.
//
// The Python gates used to run only as CI steps, so a local `./gradlew check` could pass while CI failed
// (for example on the complexity ratchet). They are now root verification tasks wired into `check`:
//
//   ./gradlew verifyQualityGates     # the gates alone (about 30 s)
//   ./gradlew check                  # every module's check, plus the gates
//   ./gradlew check -x verifyQualityGates   # skip them, for example while iterating on one module
//
// Python 3 is required. The interpreter defaults to `python` on Windows and `python3`
// elsewhere; override it with -Pkastor.python=<path> or in ~/.gradle/gradle.properties.
//
// Gates that need build outputs (test counts, dependency advice, staged publications) stay CI steps.

val python: String = providers.gradleProperty("kastor.python").getOrElse(
  if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
)
val scriptsDir = layout.projectDirectory.dir("scripts")

fun registerGate(name: String, summary: String, vararg arguments: String) =
  tasks.register<Exec>(name) {
    group = "verification"
    description = summary
    workingDir = layout.projectDirectory.asFile
    commandLine(listOf(python, *arguments))
  }

val gateScriptTests = registerGate(
  "testQualityGateScripts", "Runs the unit tests of the Python gate scripts in scripts/.",
  "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py",
)
gateScriptTests.configure {
  // Up to date until a script or gate configuration changes.
  inputs.files(scriptsDir.asFileTree.matching { include("*.py", "*.json") })
    .withPropertyName("gateScripts").withPathSensitivity(PathSensitivity.RELATIVE)
  val stamp = layout.buildDirectory.file("quality-gates/script-tests.ok")
  outputs.file(stamp)
  doLast { stamp.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") } }
}

val gates = listOf(
  registerGate("checkDocVersions", "Documented Kastor coordinates match gradle.properties.",
    "scripts/check-doc-versions.py"),
  registerGate("checkBuildPins", "Literal versions in build scripts match the version catalog.",
    "scripts/check-build-pins.py"),
  registerGate("checkReadmeImports", "README Kotlin samples import what they use.",
    "scripts/check-readme-imports.py"),
  registerGate("checkCodeComplexity",
    "Production Kotlin files and functions stay within the reviewed complexity ceilings.",
    "scripts/check-code-complexity.py"),
)
// Report a broken gate script before its (then meaningless) verdict.
gates.forEach { gate -> gate.configure { mustRunAfter(gateScriptTests) } }

val verifyQualityGates = tasks.register("verifyQualityGates") {
  group = "verification"
  description = "Runs every repository-level Python quality gate that CI runs before the module checks."
  dependsOn(gateScriptTests)
  gates.forEach { dependsOn(it) }
}

tasks.register("check") {
  group = "verification"
  description = "Runs the repository quality gates. `./gradlew check` also runs every module's check."
  dependsOn(verifyQualityGates)
}
