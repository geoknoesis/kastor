@file:JvmName("KastorRdfCli")

package com.geoknoesis.kastor.rdf.cli

import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.serialize
import com.geoknoesis.kastor.rdf.testing.RdfGraphIsomorphism
import com.geoknoesis.kastor.rdf.testing.RdfGraphSnapshots
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        exitProcess(1)
    }
    when (args[0]) {
        "help", "--help", "-h" -> printUsage()
        "parse" -> cmdParse(args.drop(1))
        "to-turtle" -> cmdToTurtle(args.drop(1))
        "diff" -> cmdDiff(args.drop(1))
        else -> {
            System.err.println("Unknown command: ${args[0]}")
            printUsage()
            exitProcess(1)
        }
    }
}

private fun printUsage() {
    println(
        """
        kastor-rdf — small RDF utilities (requires Jena on the classpath via rdf-cli).

        Usage:
          kastor-rdf help
          kastor-rdf parse <file> [FORMAT]
          kastor-rdf to-turtle <file> [INPUT_FORMAT]
          kastor-rdf diff <file1> <file2> [FORMAT]

        FORMAT defaults from the file extension when omitted (.ttl → TURTLE, .nt → NTRIPLES, .nq → NQUADS, .trig → TRIG, .jsonld → JSON-LD, .rdf/.xml → RDFXML).

        Examples:
          ./gradlew :rdf:cli:run --args="parse data/example.ttl"
          ./gradlew :rdf:cli:run --args="to-turtle data/example.nt NT"
          ./gradlew :rdf:cli:run --args="diff expected.ttl actual.ttl"
        """.trimIndent(),
    )
}

private fun cmdParse(rest: List<String>) {
    val (path, format) = parseFileArgs(rest)
    val graph = Rdf.parseFromInputStream(path.inputStream(), formatOrInfer(path, format))
    println("OK — triples: ${graph.size()}")
}

private fun cmdToTurtle(rest: List<String>) {
    val (path, format) = parseFileArgs(rest)
    val graph = Rdf.parseFromInputStream(path.inputStream(), formatOrInfer(path, format))
    print(graph.serialize(RdfFormat.TURTLE))
}

private fun cmdDiff(rest: List<String>) {
    if (rest.size < 2) {
        System.err.println("diff requires two file paths")
        exitProcess(1)
    }
    val fmt = rest.getOrNull(2)
    val p1 = Path.of(rest[0])
    val p2 = Path.of(rest[1])
    requireRegular(p1)
    requireRegular(p2)
    val f1 = formatOrInfer(p1, fmt)
    val f2 = formatOrInfer(p2, fmt)
    val g1 = Rdf.parseFromInputStream(p1.inputStream(), f1)
    val g2 = Rdf.parseFromInputStream(p2.inputStream(), f2)
    val iso = RdfGraphIsomorphism.isIsomorphic(g1, g2)
    if (iso) {
        println(
            "ISOMORPHIC — graphs match up to blank node relabelling " +
                "(${g1.size()} triples in first file, ${g2.size()} in second).",
        )
        return
    }
    System.err.println("NOT ISOMORPHIC")
    System.err.println("--- ${p1.name} (sorted N-Triples, first lines) ---")
    System.err.println(RdfGraphSnapshots.formatSnippet(g1))
    System.err.println("--- ${p2.name} (sorted N-Triples, first lines) ---")
    System.err.println(RdfGraphSnapshots.formatSnippet(g2))
    exitProcess(2)
}

private fun parseFileArgs(rest: List<String>): Pair<Path, String?> {
    if (rest.isEmpty()) {
        System.err.println("Missing file path")
        exitProcess(1)
    }
    val path = Path.of(rest[0])
    requireRegular(path)
    val format = rest.getOrNull(1)
    return path to format
}

private fun requireRegular(path: Path) {
    if (!path.isRegularFile()) {
        System.err.println("Not a regular file: $path")
        exitProcess(1)
    }
}

private fun formatOrInfer(path: Path, explicit: String?): String {
    if (explicit != null) return explicit.uppercase()
    return when (path.extension.lowercase()) {
        "ttl" -> "TURTLE"
        "nt" -> "NTRIPLES"
        "nq" -> "NQUADS"
        "trig" -> "TRIG"
        "jsonld" -> "JSON-LD"
        "json" -> "JSON-LD"
        "rdf", "owl" -> "RDFXML"
        "xml" -> "RDFXML"
        else -> "TURTLE"
    }
}
