package com.geoknoesis.kastor.benchmarks.shacl.era

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.RdfGraph
import com.geoknoesis.kastor.rdf.serialize
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation
import com.geoknoesis.kastor.rdf.shacl.ValidationConfig
import com.geoknoesis.kastor.rdf.shacl.ValidationProfile
import com.geoknoesis.kastor.rdf.shacl.toShaclValidationReportRdf
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.PrintStream
import java.util.Locale
import kotlin.system.exitProcess

/** Exit status: the report was written. */
internal const val EXIT_OK = 0

/** Exit status: the data or shapes file could not be parsed as Turtle. */
internal const val EXIT_INPUT_ERROR = 2

/** Exit status: wrong number of arguments, or an input file that does not exist or is not a regular file. */
internal const val EXIT_USAGE = 4

/** Exit status: validation failed unexpectedly or the report could not be written. */
internal const val EXIT_RUNTIME_ERROR = 5

internal const val USAGE = "Usage: shacl-era-cli <data.ttl> <shapes.ttl> <report.ttl>"

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
 *
 * **Exit status** (same convention as `onto-qa`): 0 success; 2 an input could not be parsed; 4 usage error (wrong
 * arguments, missing input file); 5 runtime error (validation failure, report not writable, internal error). Errors
 * are one sanitised line on stderr (`shacl-era-cli: …`), never a stack trace.
 */
fun main(args: Array<String>) {
  exitProcess(runEraCli(args.toList(), System.out, System.err))
}

/** Runs the CLI and returns its exit status instead of exiting. */
internal fun runEraCli(args: List<String>, out: PrintStream, err: PrintStream): Int {
  if (args.size != 3) {
    err.println(USAGE)
    return EXIT_USAGE
  }
  return try {
    runEraBenchmark(dataPath = args[0], shapesPath = args[1], reportPath = args[2], out = out)
    EXIT_OK
  } catch (e: EraCliException) {
    err.println("shacl-era-cli: ${sanitize(e.message)}")
    e.status
  } catch (e: Exception) {
    err.println("shacl-era-cli: internal error: ${e.javaClass.simpleName}: ${sanitize(e.message)}")
    EXIT_RUNTIME_ERROR
  }
}

/** A failure with its exit [status]; the message is sanitised when printed. */
internal class EraCliException(val status: Int, message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun runEraBenchmark(dataPath: String, shapesPath: String, reportPath: String, out: PrintStream) {
  val loadStart = System.nanoTime()
  val data = loadTurtle(dataPath, "data")
  val shapes = loadTurtle(shapesPath, "shapes")
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
  val report =
      try {
        validator.validate(data, shapes)
      } catch (e: Exception) {
        throw EraCliException(EXIT_RUNTIME_ERROR, "validation failed: ${e.javaClass.simpleName}: ${e.message}", e)
      }
  out.println("Validation time: ${formatSeconds(System.nanoTime() - valStart)}")

  val ttl = report.toShaclValidationReportRdf().serialize(RdfFormat.TURTLE)
  try {
    FileWriter(reportPath).use { it.write(ttl) }
  } catch (e: IOException) {
    throw EraCliException(EXIT_RUNTIME_ERROR, "failed to write report $reportPath: ${e.message}", e)
  }
}

private fun loadTurtle(path: String, role: String): RdfGraph {
  if (!File(path).isFile) throw EraCliException(EXIT_USAGE, "$role file not found or not a regular file: $path")
  return try {
    Rdf.parseFromFile(path, "TURTLE")
  } catch (e: Exception) {
    throw EraCliException(EXIT_INPUT_ERROR, "failed to parse $role file $path as Turtle: ${e.message}", e)
  }
}

/** Nanoseconds as decimal seconds with six fractional digits (locale-independent `.` separator). */
internal fun formatSeconds(nanos: Long): String = String.format(Locale.ROOT, "%.6f", nanos / 1_000_000_000.0)

/**
 * Makes untrusted text (paths, parser messages) safe for a terminal: C0 / C1 control characters other than newline and
 * tab, and Unicode bidi controls (U+202A–U+202E, U+2066–U+2069, U+200E, U+200F, U+061C), are rendered as `\uXXXX`.
 */
internal fun sanitize(text: String?): String {
  val value = text ?: "(no message)"
  val sb = StringBuilder(value.length)
  for (ch in value) {
    val unsafe =
        (ch.isISOControl() && ch != '\n' && ch != '\t') ||
            ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' || ch == '\u200E' || ch == '\u200F' || ch == '\u061C'
    if (unsafe) sb.append("\\u").append(String.format(Locale.ROOT, "%04X", ch.code)) else sb.append(ch)
  }
  return sb.toString()
}
