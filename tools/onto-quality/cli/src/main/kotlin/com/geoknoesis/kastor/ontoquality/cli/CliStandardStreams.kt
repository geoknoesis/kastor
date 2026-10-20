package com.geoknoesis.kastor.ontoquality.cli

import com.github.ajalt.clikt.parameters.options.check
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.Charset

/**
 * [status], unless standard output could not be written: a [PrintStream] swallows [java.io.IOException]s (a closed
 * pipe, a full disk) and only [PrintStream.checkError] tells, so a report that never arrived must not exit 0 (or 1).
 */
internal fun exitStatusAfterFlush(status: Int, out: PrintStream, err: PrintStream): Int {
    val outputLost = out.checkError() // flushes first
    err.flush()
    if (!outputLost) return status
    try {
        err.println("onto-qa: error: could not write to standard output")
        err.flush()
    } catch (_: Throwable) {
        // Nothing more can be reported; the status still tells.
    }
    return EXIT_RUNTIME_ERROR
}

/**
 * Output redirected to a file or a pipe is UTF-8 whatever the platform charset: a Windows code page would turn
 * unmappable characters into `?` (`onto-qa check --format json > report.json`). An interactive console is written in
 * its own charset, or it would show UTF-8 bytes as garbage unless the user had switched it to UTF-8 (`chcp 65001`).
 *
 * The two streams are decided independently ([standardStreamCharsets]): `onto-qa check > report.json` writes the
 * report in UTF-8 and its messages, still on the console, in the console charset.
 */
internal fun installStandardStreams() {
    val charsets = standardStreamCharsets(StandardStreamFacts.ofThisJvm())
    System.setOut(PrintStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out)), true, charsets.out))
    System.setErr(PrintStream(BufferedOutputStream(FileOutputStream(FileDescriptor.err)), true, charsets.err))
}

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
