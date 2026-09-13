package com.geoknoesis.kastor.ontoquality.embed

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class DownloadBudget(timeout: Duration) {
    private val started = System.nanoTime()
    private val allowed = timeout.toNanos().also { require(it > 0) }
    fun check() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Model initialization cancelled")
        if (System.nanoTime() - started >= allowed) throw SocketTimeoutException("Model initialization timed out")
    }
    fun remaining(): Duration {
        check()
        return Duration.ofNanos((allowed - (System.nanoTime() - started)).coerceAtLeast(1))
    }
    fun expired(): Boolean = System.nanoTime() - started >= allowed
}

/** Polling avoids uninterruptible monitor/file-lock waits and coordinates across processes. */
internal fun <T> withModelCacheLock(directory: Path, budget: DownloadBudget, action: () -> T): T {
    FileChannel.open(directory.resolve(".download.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        while (true) {
            budget.check()
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock != null) return lock.use { budget.check(); action() }
            try { Thread.sleep(10) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                budget.check()
            }
        }
    }
}

/** One daemon services all active body-read deadlines; cancelled tasks are immediately removed. */
internal val modelDownloadWatchdog = ScheduledThreadPoolExecutor(1) { runnable ->
    Thread(runnable, "kastor-model-download-deadline").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }
