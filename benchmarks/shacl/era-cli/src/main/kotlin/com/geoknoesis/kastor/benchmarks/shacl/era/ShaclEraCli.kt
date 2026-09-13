package com.geoknoesis.kastor.benchmarks.shacl.era

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.serialize
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import com.geoknoesis.kastor.rdf.shacl.toShaclValidationReportRdf
import java.io.FileWriter
import java.io.PrintStream
import java.util.Locale

/**
 * CLI compatible with [ERA-SHACL-Benchmark](https://github.com/oeg-upm/ERA-SHACL-Benchmark):
 *
 * ```
 * <data.ttl> <shapes.ttl> <report.ttl>
 * Load time: <seconds>
 * Validation time: <seconds>
 * ```
 *
 * Standard output contains exactly those two lines (ERA `run_benchmark.sh` parses them; see this module's
 * README). Seconds are printed with microsecond resolution (six decimals).
 *
 * **Timing:** **Load time** covers parsing both the data graph and the shapes graph (all input RDF loading).
 * **Validation time** is the full `validate(data, shapes)` call (shape compilation + execution). Writing the
 * report file is outside both timers.
 */
fun main(args: Array<String>) {
  if (args.size != 3) {
    System.err.println("Usage: ShaclEraCli <data.ttl> <shapes.ttl> <report.ttl>")
    kotlin.system.exitProcess(2)
  }
  runEraBenchmark(dataPath = args[0], shapesPath = args[1], reportPath = args[2], out = System.out)
}

internal fun runEraBenchmark(dataPath: String, shapesPath: String, reportPath: String, out: PrintStream) {
  val loadStart = System.nanoTime()
  val data = Rdf.parseFromFile(dataPath, "TURTLE")
  val shapes = Rdf.parseFromFile(shapesPath, "TURTLE")
  out.println("Load time: ${formatSeconds(System.nanoTime() - loadStart)}")

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
  out.println("Validation time: ${formatSeconds(System.nanoTime() - valStart)}")

  val reportGraph = report.toShaclValidationReportRdf()
  val ttl = reportGraph.serialize(RdfFormat.TURTLE)
  FileWriter(reportPath).use { it.write(ttl) }
}

/** Nanoseconds as decimal seconds with six fractional digits (locale-independent `.` separator). */
internal fun formatSeconds(nanos: Long): String = String.format(Locale.ROOT, "%.6f", nanos / 1_000_000_000.0)
