package com.geoknoesis.kastor.gen.gradle

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import org.gradle.api.logging.Logger

/** Execution-scoped bridge; no parser or logger is serialized into the task cache. */
internal class GradleKspLogger(private val logger: Logger) : KSPLogger {
    override fun logging(message: String, symbol: KSNode?) = logger.info(message)
    override fun info(message: String, symbol: KSNode?) = logger.info(message)
    override fun warn(message: String, symbol: KSNode?) = logger.warn(message)
    override fun error(message: String, symbol: KSNode?) = logger.error(message)
    override fun exception(e: Throwable) = logger.error("Ontology generation failed", e)
}
