import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

plugins {
  kotlin("jvm")
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.jmh)
}

dependencies {
  implementation(project(":rdf:core"))
  implementation(project(":rdf:shacl-validation"))
  implementation(project(":rdf:rdf4j"))
  implementation(project(":rdf:jena"))
  implementation(libs.jena.shacl)
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}

sourceSets {
  named("jmh") {
    resources.srcDir("workloads")
  }
}

jmh {
  jmhVersion.set(libs.versions.jmh.get())
  warmupIterations.set(2)
  iterations.set(3)
  fork.set(1)
  failOnError.set(true)
  warmup.set("1s")
  timeOnIteration.set("1s")
}

val shaclEraCliProject = project(":benchmarks:shacl-era-cli")

tasks.register<JavaExec>("tierAEngineCompare") {
  group = "benchmark"
  description =
      "ERA-style load/validation timings for Tier A workloads: Kastor, Jena, RDF4J, PySHACL (optional)."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.geoknoesis.kastor.benchmarks.shacl.TierAEngineCompareKt")
  workingDir = rootProject.layout.projectDirectory.asFile
}

tasks.register("shaclCompareKastorPyTierA") {
  group = "benchmark"
  description =
      "Runs Kastor shacl-era-cli and PySHACL on minCount-001 (same data+shapes file). " +
          "Requires Python + pyshacl for the second leg (see scripts/pyshacl/README.md)."
  dependsOn(shaclEraCliProject.tasks.named("installDist"))

  doLast {
    val rootDir = rootProject.layout.projectDirectory.asFile
    val data =
        File(
            rootDir,
            "rdf/shacl-validation/src/test/resources/w3c-shacl12-fixture/property/minCount-001.ttl",
        )
    val outDir = layout.buildDirectory.get().asFile
    val repK = File(outDir, "shacl-compare-kastor-report.ttl")
    val repP = File(outDir, "shacl-compare-py-report.ttl")
    outDir.mkdirs()

    val isWin = System.getProperty("os.name").lowercase().contains("windows")
    val binDir =
        shaclEraCliProject.layout.buildDirectory.dir("install/shacl-era-cli/bin").get().asFile
    val eraCli = File(binDir, if (isWin) "shacl-era-cli.bat" else "shacl-era-cli")

    fun capture(cmd: List<String>): String {
      val buf = ByteArrayOutputStream()
      exec {
        commandLine(cmd)
        workingDir = rootDir
        standardOutput = buf
      }
      return buf.toString(StandardCharsets.UTF_8)
    }

    val kastorOut =
        capture(
            listOf(
                eraCli.absolutePath,
                data.absolutePath,
                data.absolutePath,
                repK.absolutePath,
            ),
        )
    println("--- Kastor (shacl-era-cli) ---\n$kastorOut")

    val py =
        System.getenv("KASTOR_PYTHON")?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (isWin) "python" else "python3"
    val script = File(rootDir, "benchmarks/shacl/scripts/pyshacl/validate_era_style.py")
    try {
      val pyOut =
          capture(
              listOf(
                  py,
                  script.absolutePath,
                  data.absolutePath,
                  data.absolutePath,
                  repP.absolutePath,
              ),
          )
      println("--- PySHACL ---\n$pyOut")
    } catch (e: Exception) {
      println(
          "PySHACL leg skipped (${e.javaClass.simpleName}: ${e.message}). " +
              "Set KASTOR_PYTHON or install pyshacl — see benchmarks/shacl/scripts/pyshacl/README.md",
      )
    }
  }
}
