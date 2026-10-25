package com.geoknoesis.kastor.rdf.cli

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset

// The standard streams of the CLI: the charset each one is written in, and the exit status when stdout was lost.

/**
 * [code], unless standard output could not be written: a [PrintStream] swallows [IOException]s (a closed pipe, a full
 * disk) and only [PrintStream.checkError] tells, so a result that never arrived must not exit 0.
 */
internal fun exitCodeAfterFlush(code: Int, out: PrintStream, err: PrintStream): Int {
    val outputLost = out.checkError() // flushes first
    err.flush()
    if (!outputLost) return code
    try {
        err.println("kastor-rdf: error: could not write to standard output")
        err.flush()
    } catch (_: Throwable) {
        // Nothing more can be reported; the status still tells.
    }
    return EXIT_RUNTIME_ERROR
}

/** A line-flushed [PrintStream] over [stream], writing [charset]. */
internal fun printStream(stream: OutputStream, charset: Charset): PrintStream = PrintStream(BufferedOutputStream(stream), true, charset)

/** The charsets of stdout and stderr. */
internal data class StandardStreamCharsets(val out: Charset, val err: Charset)

/**
 * What the JVM knows about its standard streams.
 *
 * @param stdoutTerminalEncoding `sun.stdout.encoding`: set by the JVM only when stdout is a terminal (its encoding).
 * @param stderrTerminalEncoding `sun.stderr.encoding`: the same for stderr.
 * @param consoleCharset the charset of the interactive console ([interactiveConsoleCharset]), null without one.
 */
internal class StandardStreamFacts(
    val stdoutTerminalEncoding: String?,
    val stderrTerminalEncoding: String?,
    val consoleCharset: Charset?,
) {
    companion object {
        fun ofThisJvm(): StandardStreamFacts =
            StandardStreamFacts(
                stdoutTerminalEncoding = property("sun.stdout.encoding"),
                stderrTerminalEncoding = property("sun.stderr.encoding"),
                consoleCharset = interactiveConsoleCharset(),
            )

        private fun property(name: String): String? =
            try {
                System.getProperty(name)?.takeIf { it.isNotBlank() }
            } catch (_: SecurityException) {
                null
            }
    }
}

/**
 * The charset of each standard stream, decided per stream: the console charset when **that** stream is a terminal,
 * UTF-8 when it is redirected.
 *
 * The JVM tells which stream is a terminal through `sun.stdout.encoding` / `sun.stderr.encoding` (it sets each one
 * only when the stream is attached to a terminal). When neither is set but there is an interactive console (a JVM
 * that does not provide the properties), the console only tells that stdin and stdout are terminals; stderr is then
 * written like stdout.
 */
internal fun standardStreamCharsets(facts: StandardStreamFacts): StandardStreamCharsets {
    val perStream = facts.stdoutTerminalEncoding != null || facts.stderrTerminalEncoding != null

    fun charset(terminalEncoding: String?): Charset =
        when {
            terminalEncoding != null -> charsetOrNull(terminalEncoding) ?: facts.consoleCharset ?: Charsets.UTF_8
            perStream -> Charsets.UTF_8
            else -> standardStreamCharset(facts.consoleCharset)
        }
    return StandardStreamCharsets(out = charset(facts.stdoutTerminalEncoding), err = charset(facts.stderrTerminalEncoding))
}

private fun charsetOrNull(name: String): Charset? =
    try {
        Charset.forName(name)
    } catch (_: IllegalArgumentException) {
        null
    }

/** The charset of a stream when only the console is known: the charset of the console when there is one, else UTF-8. */
internal fun standardStreamCharset(consoleCharset: Charset?): Charset = consoleCharset ?: Charsets.UTF_8

/**
 * The charset of the interactive console this JVM is attached to, or null when there is none (or the charset cannot
 * be determined). `System.console()` is null when the standard streams are redirected; on JDK 22 and later it can be
 * non-null even then, and `Console.isTerminal()` (called reflectively, it does not exist before) tells.
 */
internal fun interactiveConsoleCharset(): Charset? {
    val console = System.console() ?: return null
    val terminal =
        try {
            java.io.Console::class.java.getMethod("isTerminal").invoke(console) as? Boolean ?: true
        } catch (_: ReflectiveOperationException) {
            true
        }
    if (!terminal) return null
    return try {
        console.charset()
    } catch (_: RuntimeException) {
        null
    }
}
