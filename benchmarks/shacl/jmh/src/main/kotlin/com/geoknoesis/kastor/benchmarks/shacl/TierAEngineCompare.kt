package com.geoknoesis.kastor.benchmarks.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.shacl.ShaclValidator as JenaShaclValidator
import org.apache.jena.shacl.Shapes
import org.apache.jena.vocabulary.RDF

/**
 * Prints ERA-style timing blocks for each Tier A workload: **Kastor native**, **Apache Jena SHACL**,
 * **Eclipse RDF4J SHACL** (`providerId = rdf4j`), and **PySHACL** (subprocess, optional).
 *
 * TopBraid is manual (see `benchmarks/shacl/jmh/docs/topbraid-self-hosted.md`).
 */
fun main() {
  val repoRoot = resolveRepositoryRoot()
  val workloadJson =
      Json {
        ignoreUnknownKeys = true
        isLenient = true
      }
  val bases = listOf("w3c-minCount-001", "w3c-targetClass-001")

  val isWin = System.getProperty("os.name").lowercase().contains("windows")
  val py =
      System.getenv("KASTOR_PYTHON")?.trim()?.takeIf { it.isNotEmpty() }
          ?: if (isWin) "python" else "python3"
  val pyScript = repoRoot.resolve("benchmarks/shacl/jmh/scripts/pyshacl/validate_era_style.py")

  for (base in bases) {
    val descFile = repoRoot.resolve("benchmarks/shacl/jmh/workloads/tier-a/$base.json")
    val desc = workloadJson.decodeFromString<WorkloadDescriptorJson>(descFile.toFile().readText())
    val dataPath = repoRoot.resolve(desc.dataTurtle).normalize()
    val shapesPath = repoRoot.resolve(desc.shapesTurtle).normalize()

    println()
    println("=== ${desc.id} ($base) ===")
    println("data:   $dataPath")
    println("shapes: $shapesPath")

    // --- Kastor native (same split as ShaclEraCli: load = data parse only) ---
    run {
      val tLoad0 = System.nanoTime()
      val data = Rdf.parseFromFile(dataPath.toString(), "TURTLE")
      val loadS = secondsSince(tLoad0)
      val shapes = Rdf.parseFromFile(shapesPath.toString(), "TURTLE")
      val validator =
          ShaclValidation.validator(
              ValidationConfig(
                  profile = ValidationProfile.SHACL_CORE,
                  providerId = "kastor",
                  parallelValidation = false,
              ),
          )
      val tVal0 = System.nanoTime()
      val report = validator.validate(data, shapes)
      val valS = secondsSince(tVal0)
      println("--- Kastor native ---")
      println("Data graph size: ${data.size()}")
      println("Load time: $loadS")
      println("Validation time: $valS")
      println("isValid: ${report.isValid} (expected test outcome in workload JSON: ${desc.expectedConforms})")
    }

    // --- Apache Jena SHACL ---
    run {
      val tLoad0 = System.nanoTime()
      val dataGraph = RDFDataMgr.loadGraph(dataPath.toUri().toString())
      val loadS = secondsSince(tLoad0)
      val shapesGraph = RDFDataMgr.loadGraph(shapesPath.toUri().toString())
      val shapes = Shapes.parse(shapesGraph)
      val tVal0 = System.nanoTime()
      val jenaReport = JenaShaclValidator.get().validate(shapes, dataGraph)
      val valS = secondsSince(tVal0)
      val model = jenaReport.model
      val vrType =
          org.apache.jena.rdf.model.ResourceFactory.createResource(
              "http://www.w3.org/ns/shacl#ValidationReport",
          )
      val conformsProp =
          org.apache.jena.rdf.model.ResourceFactory.createProperty(
              "http://www.w3.org/ns/shacl#conforms",
          )
      val roots = model.listSubjectsWithProperty(RDF.type, vrType).asSequence().toList()
      val root = roots.singleOrNull() ?: roots.firstOrNull()
      val conforms =
          root?.let { r -> model.listObjectsOfProperty(r, conformsProp).asSequence().singleOrNull()?.asLiteral()?.boolean }
      println("--- Apache Jena SHACL ---")
      println("Data graph size: ${dataGraph.size()}")
      println("Load time: $loadS")
      println("Validation time: $valS")
      if (root == null) {
        println("sh:conforms: <no ValidationReport root>")
      } else {
        println("sh:conforms: $conforms (expected: ${desc.expectedConforms})")
      }
    }

    // --- Eclipse RDF4J SHACL (ShaclSail via ShaclValidatorProvider) ---
    run {
      val tLoad0 = System.nanoTime()
      val data = Rdf.parseFromFile(dataPath.toString(), "TURTLE")
      val loadS = secondsSince(tLoad0)
      val shapes = Rdf.parseFromFile(shapesPath.toString(), "TURTLE")
      val validator =
          ShaclValidation.validator(
              ValidationConfig(
                  profile = ValidationProfile.SHACL_CORE,
                  providerId = "rdf4j",
                  parallelValidation = false,
              ),
          )
      val tVal0 = System.nanoTime()
      val report = validator.validate(data, shapes)
      val valS = secondsSince(tVal0)
      println("--- Eclipse RDF4J SHACL ---")
      println("Data graph size: ${data.size()}")
      println("Load time: $loadS")
      println("Validation time: $valS")
      println("isValid: ${report.isValid} (expected test outcome in workload JSON: ${desc.expectedConforms})")
    }

    // --- PySHACL ---
    if (Files.isRegularFile(pyScript)) {
      val reportTmp = Files.createTempFile("pyshacl-$base-", ".ttl")
      try {
        val pb =
            ProcessBuilder(
                py,
                pyScript.toString(),
                dataPath.toString(),
                shapesPath.toString(),
                reportTmp.toString(),
            )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        println("--- PySHACL ---")
        print(out)
        if (code != 0) {
          println("(PySHACL exit code: $code)")
        }
      } catch (e: Exception) {
        println("--- PySHACL ---")
        println("skipped: ${e.javaClass.simpleName}: ${e.message}")
        println("Install deps: pip install -r benchmarks/shacl/jmh/scripts/pyshacl/requirements.txt")
        println("Or set KASTOR_PYTHON to the right interpreter.")
      } finally {
        Files.deleteIfExists(reportTmp)
      }
    }
  }

  println()
  println(
      "Note: JMH micro-benchmarks in this repo still target Kastor native unless separate JMH " +
          "classes are added for other providers.",
  )
}

private fun secondsSince(startNanos: Long): Double {
  val elapsed = System.nanoTime() - startNanos
  return TimeUnit.NANOSECONDS.toMillis(elapsed) / 1000.0
}
