package com.geoknoesis.kastor.rdf.cli

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.time.Duration

/**
 * The arguments of one command: options that take a value (`--name <value>` or `--name=<value>`, anywhere before a
 * `--` terminator), then the positional arguments. [options] maps each option the command knows to the message used
 * when its value is missing; any other argument that starts with `--` is an unknown option.
 */
internal class CommandArguments(rest: List<String>, options: Map<String, String>) {
    val positional = ArrayList<String>()
    private val values = HashMap<String, String>()

    init {
        var i = 0
        while (i < rest.size) {
            val arg = rest[i]
            val name = arg.substringBefore('=')
            when {
                arg == "--" -> {
                    positional += rest.drop(i + 1)
                    break
                }
                arg in options -> {
                    values[arg] = rest.getOrNull(i + 1) ?: throw CliError(options.getValue(arg))
                    i++
                }
                arg.startsWith("--") && '=' in arg && name in options -> values[name] = arg.substringAfter('=')
                arg.startsWith("--") -> throw CliError("Unknown option: $arg")
                else -> positional += arg
            }
            i++
        }
    }

    /** The value of [option], or null when it is not given; the last one wins when it is given twice. */
    fun value(option: String): String? = values[option]

    /** `--base <iri>`: an absolute IRI to resolve relative IRIs of the input against, or null. */
    fun baseIri(): String? {
        val base = value(BASE_OPTION) ?: return null
        val absolute =
            try {
                URI(base).isAbsolute
            } catch (_: URISyntaxException) {
                false
            }
        if (!absolute) throw CliError("--base must be an absolute IRI (with a scheme, such as file:/data/ or http://example.org/), not '${oneLine(base)}'")
        return base
    }

    companion object {
        const val BASE_OPTION = "--base"
        val BASE: Pair<String, String> = BASE_OPTION to "--base requires an absolute IRI"
    }
}

/** `parse` and `to-turtle` arguments: `[--base <iri>] [--] <file> [FORMAT]`. */
internal class FileArguments(rest: List<String>) {
    private val arguments = CommandArguments(rest, mapOf(CommandArguments.BASE))
    val path: Path
    val format: String?
    val baseIri: String? = arguments.baseIri()

    init {
        val positional = arguments.positional
        if (positional.isEmpty()) throw CliError("Missing file path")
        expectAtMost(positional, 2)
        path = requireRegular(Path.of(positional[0]))
        format = positional.getOrNull(1)
    }
}

/**
 * `diff` arguments: `--timeout <seconds>`, `--max-search-states <n>` and `--base <iri>` (or `--option=<value>`)
 * anywhere, then two files and an optional format.
 */
internal class DiffArguments(rest: List<String>) {
    private val arguments =
        CommandArguments(
            rest,
            mapOf(
                TIMEOUT to "--timeout requires a number of seconds (0 = no limit)",
                MAX_SEARCH_STATES to "--max-search-states requires a number (0 = no limit)",
                CommandArguments.BASE,
            ),
        )
    val positional: List<String> = arguments.positional

    /** Wall-clock limit of the isomorphism check; null for none (`--timeout 0`). */
    val timeout: Duration? = arguments.value(TIMEOUT).let { if (it == null) Duration.ofSeconds(DEFAULT_DIFF_TIMEOUT_SECONDS) else seconds(it) }

    /** Backtracking assignments the isomorphism check may try; null for no limit (`--max-search-states 0`). */
    val maxSearchStates: Int? = arguments.value(MAX_SEARCH_STATES).let { if (it == null) DEFAULT_DIFF_SEARCH_STATES else states(it) }

    /** `--base <iri>`, applied to both inputs. */
    val baseIri: String? = arguments.baseIri()

    init {
        if (positional.size < 2) throw CliError("diff requires two file paths")
        expectAtMost(positional, 3)
    }

    private fun seconds(value: String): Duration? {
        val seconds = value.toLongOrNull()?.takeIf { it in 0..MAX_TIMEOUT_SECONDS }
            ?: throw CliError("--timeout must be a whole number of seconds from 0 (no limit) to $MAX_TIMEOUT_SECONDS, not '${oneLine(value)}'")
        return if (seconds == 0L) null else Duration.ofSeconds(seconds)
    }

    private fun states(value: String): Int? {
        val states = value.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw CliError("--max-search-states must be a whole number from 0 (no limit) to ${Int.MAX_VALUE}, not '${oneLine(value)}'")
        return if (states == 0) null else states
    }

    private companion object {
        const val TIMEOUT = "--timeout"
        const val MAX_SEARCH_STATES = "--max-search-states"
        const val MAX_TIMEOUT_SECONDS = 31_536_000L
    }
}
