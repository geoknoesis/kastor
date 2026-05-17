package com.geoknoesis.kastor.benchmarks.shacl.era

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.serialize
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import com.geoknoesis.kastor.rdf.shacl.toShaclValidationReportRdf
import java.io.FileWriter
import java.util.concurrent.TimeUnit

/**
 * CLI compatible with [ERA-SHACL-Benchmark](https://github.com/oeg-upm/ERA-SHACL-Benchmark):
 *
 * ```
 * <data.ttl> <shapes.ttl> <report.ttl>
 * Load time: <seconds>
 * Validation time: <seconds>
 * ```
 *
 * **Timing note:** Matches the reference Jena split: **Load time** is data graph parse only; shapes are
 * loaded before the validation timer; **Validation time** is the full native `validate(data, shapes)` call
 * (compile + execute). For strict parity with Jena’s second metric (execute-only), see design doc Section 12.4.2.
 */
fun main(args: Array<String>) {
  if (args.size != 3) {
    System.err.println("Usage: ShaclEraCli <data.ttl> <shapes.ttl> <report.ttl>")
    kotlin.system.exitProcess(2)
  }
  val dataPath = args[0]
  val shapesPath = args[1]
  val reportPath = args[2]

  val loadStart = System.nanoTime()
  val data = Rdf.parseFromFile(dataPath, "TURTLE")
  val loadSeconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loadStart) / 1000.0
  println("Data graph size: ${data.size()}")
  println("Load time: $loadSeconds")

  val shapes = Rdf.parseFromFile(shapesPath, "TURTLE")

  val validator =
      ShaclValidation.validator(
          ValidationConfig(
              profile = ValidationProfile.SHACL_CORE,
              providerId = "kastor",
              parallelValidation = false,
          ),
      )
  val valStart = System.nanoTime()
  val report = validator.validate(data, shapes)
  val valSeconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - valStart) / 1000.0
  println("Validation time: $valSeconds")

  val reportGraph = report.toShaclValidationReportRdf()
  val ttl = reportGraph.serialize(RdfFormat.TURTLE)
  FileWriter(reportPath).use { it.write(ttl) }
}
