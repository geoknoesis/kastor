package com.geoknoesis.kastor.rdf

import java.io.InputStream
import java.util.regex.Pattern

/**
 * The provider dispatch behind the parse functions of [Rdf]: one loop that hands a stream to the highest-priority
 * provider declaring the input format, and the error mapping every parse function shares.
 *
 * Nothing is buffered. Another provider is tried only when one declines ([UnsupportedOperationException]) before it
 * consumed any input; one that declines after reading fails the parse, since the bytes it read are gone. A failure
 * of the stream itself ([CountingInputStream.failure]) or a URL-loading limit wrapped by the provider
 * ([inputLimitCause]) is surfaced as such, so a dropped connection is not reported as a syntax error.
 */
internal object RdfParsing {
    private val LINE = Pattern.compile("line\\s+(\\d+)", Pattern.CASE_INSENSITIVE)
    private val COLUMN = Pattern.compile("column\\s+(\\d+)", Pattern.CASE_INSENSITIVE)

    fun graph(input: InputStream, format: String, baseIri: String?): MutableRdfGraph =
        dispatch(input, RdfFormat.fromStringOrThrow(format)) { provider, stream, name ->
            provider.parseGraph(stream, name, baseIri)
        }

    fun streaming(input: InputStream, format: String, baseIri: String?): Sequence<RdfTriple> =
        dispatch(input, RdfFormat.fromStringOrThrow(format)) { provider, stream, name ->
            provider.parseStreaming(stream, name, baseIri)
        }

    fun dataset(repository: RdfRepository, input: InputStream, format: String, baseIri: String?) {
        val quads = quadFormat(format, "parseFromInputStream()")
        dispatch(input, quads) { provider, stream, name -> provider.parseDataset(repository, stream, name, baseIri) }
    }

    /**
     * The quad format named [format]; a graph format is rejected with [IllegalArgumentException] that points to
     * [graphAlternative], the function to use for it.
     */
    fun quadFormat(format: String, graphAlternative: String): RdfFormat {
        val parsed = RdfFormat.fromStringOrThrow(format)
        require(RdfFormat.isQuadFormat(parsed)) {
            "Format '${parsed.formatName}' is not a quad format. Use $graphAlternative for graph formats, or use TRIG " +
                "or N-QUADS for datasets."
        }
        return parsed
    }

    /**
     * A new [Rdf.memory] repository filled by [fill]; the repository is closed, and its close failure attached as
     * suppressed, when [fill] fails.
     */
    inline fun newDataset(fill: (RdfRepository) -> Unit): Dataset {
        val repository = Rdf.memory()
        try {
            fill(repository)
        } catch (e: Throwable) {
            runCatching { repository.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        return repository
    }

    private inline fun <T> dispatch(
        inputStream: InputStream,
        format: RdfFormat,
        parse: (RdfProvider, InputStream, String) -> T,
    ): T {
        val providers = RdfProviderRegistry.discoverProviders()
        val input = CountingInputStream(inputStream)
        for (provider in providers) {
            if (!provider.supportsInputFormat(format.formatName)) continue
            try {
                return parse(provider, input, format.formatName)
            } catch (e: UnsupportedOperationException) {
                if (input.count > 0) throw providerConsumedInput(provider, format, e)
            } catch (e: RdfFormatException) {
                throw e.inputLimitCause() ?: input.failure ?: e
            } catch (e: Exception) {
                (e.inputLimitCause() ?: input.failure)?.let { throw it }
                throw RdfFormatException.ParseError(parseErrorDetails(e, format.formatName))
            }
        }
        throw RdfFormatException.UnsupportedFormat(
            format.formatName,
            providers.flatMap { it.getCapabilities().supportedInputFormats }.distinct(),
        )
    }

    /** The parse failure [exception], with the line and column a provider's message names, if it names them. */
    internal fun parseErrorDetails(exception: Throwable, format: String): ParseErrorDetails {
        val message = exception.message ?: "Unknown parsing error"
        fun number(pattern: Pattern): Int? = pattern.matcher(message).takeIf { it.find() }?.group(1)?.toIntOrNull()
        return ParseErrorDetails(message, line = number(LINE), column = number(COLUMN), format = format, cause = exception)
    }

    private fun providerConsumedInput(provider: RdfProvider, format: RdfFormat, cause: Throwable) =
        RdfFormatException.Generic(
            "Provider '${provider.id}' consumed the input before declining ${format.formatName}; no other provider can be tried",
            RdfErrorCode.FORMAT_UNSUPPORTED,
            cause,
        )
}
