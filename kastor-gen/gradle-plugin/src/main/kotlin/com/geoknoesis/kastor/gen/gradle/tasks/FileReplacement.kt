package com.geoknoesis.kastor.gen.gradle.tasks

import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File operations used to replace generated output, tolerant of transient locks.
 *
 * On Windows a file that an IDE, indexer or virus scanner has open cannot be deleted or replaced for a moment
 * (`AccessDeniedException` or a `FileSystemException` "being used by another process"); such operations are retried
 * with exponential backoff. Missing files are not retried.
 *
 * @param mover renames a file within one file store, replacing the target (test hook)
 * @param sleeper waits between attempts (test hook)
 */
internal class FileReplacement(
    private val mover: (Path, Path) -> Unit = { source, target -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val attempts: Int = 6,
    private val initialBackoffMillis: Long = 50,
) {

    /** Runs [action], retrying transient [FileSystemException]s (other than [NoSuchFileException]) with backoff. */
    fun <T> retrying(action: () -> T): T {
        var backoff = initialBackoffMillis
        repeat(attempts - 1) {
            try {
                return action()
            } catch (e: NoSuchFileException) {
                throw e
            } catch (_: FileSystemException) {
                sleeper(backoff)
                backoff *= 2
            }
        }
        return action()
    }

    fun delete(path: Path) {
        retrying { Files.deleteIfExists(path) }
    }

    /**
     * Moves [source] to [target], replacing it. Within one file store this is a rename. Across file stores (e.g. the
     * build directory and the output directory on different drives) the file is first copied next to the target and
     * then renamed over it, so the target is never left half-written, and the source is deleted last. On failure the
     * previous target is left in place.
     */
    fun move(source: Path, target: Path, sameFileStore: Boolean = sameFileStore(source, target)) {
        if (sameFileStore) {
            retrying { mover(source, target) }
            return
        }
        val staged = target.resolveSibling(".${target.fileName}.kastor-tmp")
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING)
            retrying { mover(staged, target) }
        } finally {
            Files.deleteIfExists(staged)
        }
        Files.deleteIfExists(source)
    }

    private fun sameFileStore(source: Path, target: Path): Boolean = try {
        Files.getFileStore(source) == Files.getFileStore(target.parent)
    } catch (_: IOException) {
        false
    }
}
