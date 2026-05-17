package com.geoknoesis.kastor.benchmarks.shacl

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ShaclValidator
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * Helpers for JMH benchmarks (Java-callable via [ShaclBenchmarkSupport] object).
 */
object ShaclBenchmarkSupport {

  private val workloadJson =
      Json {
        ignoreUnknownKeys = true
        isLenient = true
      }

  /** Native Kastor engine, deterministic defaults for micro-benchmarks. */
  @JvmStatic
  fun nativeValidator(): ShaclValidator =
      ShaclValidation.validator(
          ValidationConfig(
              profile = ValidationProfile.SHACL_CORE,
              providerId = "kastor",
              parallelValidation = false,
          ),
      )

  /** Load Turtle from the classpath (leading slash, e.g. `/jmh-workload/data.ttl`). */
  @JvmStatic
  fun loadGraphFromResource(resourcePath: String): RdfGraph {
    val stream =
        ShaclBenchmarkSupport::class.java.getResourceAsStream(resourcePath)
            ?: error("Classpath resource missing: $resourcePath")
    return stream.use { Rdf.parseFromInputStream(it, "TURTLE") }
  }

  /**
   * Load Tier **A** workload graphs from `workloads/tier-a/<baseName>.json` on the JMH classpath
   * (see `sourceSets.jmh.resources.srcDir("workloads")` in build script).
   */
  @JvmStatic
  fun loadTierAWorkloadGraphs(jsonBaseName: String): LoadedWorkload {
    val resourcePath = "/tier-a/$jsonBaseName.json"
    val stream =
        ShaclBenchmarkSupport::class.java.getResourceAsStream(resourcePath)
            ?: error("Classpath resource missing: $resourcePath")
    val desc =
        stream.use { input ->
          workloadJson.decodeFromString<WorkloadDescriptorJson>(
              input.readBytes().decodeToString(),
          )
        }
    val repoRoot = resolveRepositoryRootForBenchmark()
    val dataPath = repoRoot.resolve(desc.dataTurtle).normalize()
    val shapesPath = repoRoot.resolve(desc.shapesTurtle).normalize()
    val data = Rdf.parseFromFile(dataPath.toString(), "TURTLE")
    val shapes = Rdf.parseFromFile(shapesPath.toString(), "TURTLE")
    return LoadedWorkload(data, shapes)
  }

  private fun resolveRepositoryRootForBenchmark(): Path {
    System.getProperty("kastor.repo.root")?.trim()?.takeIf { it.isNotEmpty() }?.let {
      return Path.of(it).toAbsolutePath().normalize()
    }
    return resolveRepositoryRoot()
  }
}

/** Pair of graphs for Java JMH benchmarks. */
data class LoadedWorkload(val data: RdfGraph, val shapes: RdfGraph)