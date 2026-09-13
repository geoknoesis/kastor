package com.geoknoesis.kastor.gen.gradle

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import org.gradle.api.logging.Logger

/**
 * Execution-scoped bridge; no parser or logger is serialized into the task cache.
 *
 * Errors are recorded (not just logged) so the task can fail after generation instead of silently writing
 * partial output; see [errors].
 */
internal class GradleKspLogger(private val logger: Logger) : KSPLogger {
    private val recorded = mutableListOf<String>()

    /** Error messages reported so far (via [error] or [exception]). */
    val errors: List<String> get() = recorded.toList()

    override fun logging(message: String, symbol: KSNode?) = logger.info(message)
    override fun info(message: String, symbol: KSNode?) = logger.info(message)
    override fun warn(message: String, symbol: KSNode?) = logger.warn(message)
    override fun error(message: String, symbol: KSNode?) {
        recorded += message
        logger.error(message)
    }
    override fun exception(e: Throwable) {
        recorded += (e.message ?: e.toString())
        logger.error("Ontology generation failed", e)
    }
}
