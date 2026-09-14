package com.geoknoesis.kastor.ontoquality.embed

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.exists
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * Downloads `all-MiniLM-L6-v2` ONNX assets on first use.
 *
 * Cache root: system property [MODEL_CACHE_PROPERTY], env [MODEL_CACHE_ENV], or
 * `~/.kastor/onto-quality/models` by default. Files live under
 * `<cache>/all-MiniLM-L6-v2/`.
 */
object ModelDownloader {
    private val log = LoggerFactory.getLogger(ModelDownloader::class.java)

    const val MODEL_CACHE_PROPERTY = "kastor.onto-quality.model-cache"
    const val MODEL_CACHE_ENV = "KASTOR_MODEL_CACHE"

    private const val MODEL_REL_URL =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/1110a243fdf4706b3f48f1d95db1a4f5529b4d41/onnx/model.onnx"
    private const val TOKENIZER_REL_URL =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/1110a243fdf4706b3f48f1d95db1a4f5529b4d41/tokenizer.json"

    /** Expected SHA-256 of `model.onnx` as distributed at calibration time. */
    const val EXPECTED_MODEL_SHA256 = "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452"

    /** Expected SHA-256 of `tokenizer.json`. */
    const val EXPECTED_TOKENIZER_SHA256 =
        "be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037"

    /** How many times a caller re-attempts after *another* caller's in-flight attempt failed. */
    private const val MAX_SHARED_FAILURE_RETRIES = 3

    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).followRedirects(HttpClient.Redirect.NORMAL).build()

    internal data class ModelAsset(val fileName: String, val uri: URI, val sha256: String)

    fun resolveCacheRoot(overrideCacheRoot: Path? = null): Path {
        if (overrideCacheRoot != null) return overrideCacheRoot.normalize()
        System.getProperty(MODEL_CACHE_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return Path.of(it).normalize()
        }
        System.getenv(MODEL_CACHE_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return Path.of(it).normalize()
        }
        return Path.of(System.getProperty("user.home"), ".kastor", "onto-quality", "models")
    }

    fun resolveMiniLmDir(cacheRoot: Path = resolveCacheRoot()): Path =
        cacheRoot.resolve("all-MiniLM-L6-v2")

    private val inFlight = ConcurrentHashMap<Path, CompletableFuture<List<Path>>>()

    fun ensureMiniLmFiles(cacheRoot: Path = resolveCacheRoot()): Pair<Path, Path> =
        ensureMiniLmFiles(cacheRoot, Duration.ofMinutes(10))

    /** Concurrent callers share only an in-flight verification; digests are never trusted indefinitely. */
    fun ensureMiniLmFiles(cacheRoot: Path, timeout: Duration): Pair<Path, Path> {
        val files =
            ensureAssets(
                resolveMiniLmDir(cacheRoot),
                listOf(
                    ModelAsset("model.onnx", URI.create(MODEL_REL_URL), EXPECTED_MODEL_SHA256),
                    ModelAsset("tokenizer.json", URI.create(TOKENIZER_REL_URL), EXPECTED_TOKENIZER_SHA256),
                ),
                timeout,
            )
        return files[0] to files[1]
    }

    /**
     * Ensures every asset exists in [directory] with its expected digest, downloading as needed.
     *
     * Concurrent callers for the same directory wait on one in-flight attempt, but only its **success** is
     * shared: if that attempt fails (for example because its caller had a much shorter budget), each waiter
     * retries under its own [timeout], at most [MAX_SHARED_FAILURE_RETRIES] times.
     */
    internal fun ensureAssets(directory: Path, assets: List<ModelAsset>, timeout: Duration): List<Path> {
        val budget = DownloadBudget(timeout)
        val dir = directory.toAbsolutePath().normalize()
        Files.createDirectories(dir)
        val key = dir.toRealPath()
        var sharedFailures = 0
        while (true) {
            val future = CompletableFuture<List<Path>>()
            val existing = inFlight.putIfAbsent(key, future)
            if (existing == null) return downloadAsOwner(key, assets, budget, future)
            try {
                return existing.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); budget.check(); throw e
            } catch (e: TimeoutException) {
                throw java.net.SocketTimeoutException("Model initialization wait timed out")
            } catch (e: ExecutionException) {
                // Do not inherit another caller's failure: retry under this caller's own budget.
                inFlight.remove(key, existing)
                budget.check()
                if (++sharedFailures > MAX_SHARED_FAILURE_RETRIES) throw e.cause ?: e
                log.info("Concurrent model initialization failed ({}); retrying with this caller's budget", e.cause?.toString())
            }
        }
    }

    private fun downloadAsOwner(
        key: Path,
        assets: List<ModelAsset>,
        budget: DownloadBudget,
        future: CompletableFuture<List<Path>>,
    ): List<Path> {
        try {
            val result = withModelCacheLock(key, budget) {
                assets.map { asset ->
                    val target = key.resolve(asset.fileName)
                    ensureFile(target, asset.uri, asset.sha256, asset.fileName, budget)
                    target
                }
            }
            future.complete(result)
            return result
        } catch (e: Throwable) { future.completeExceptionally(e); throw e }
        finally { inFlight.remove(key, future) }
    }

    internal fun ensureFile(target: Path, uri: URI, expectedSha256: String, label: String, budget: DownloadBudget = DownloadBudget(Duration.ofMinutes(10))) {
        budget.check()
        if (target.exists()) {
            val hash = if (Files.size(target) <= 512L * 1024 * 1024) sha256Hex(target, budget) else null
            if (hash != null && hash.equals(expectedSha256, ignoreCase = true)) return
            // A truncated or tampered cache entry must not block every later run: discard it and download once.
            log.warn(
                "Cached {} at {} is corrupt ({}); deleting it and downloading again",
                label,
                target,
                if (hash == null) "larger than 512 MiB" else "SHA-256 $hash, expected $expectedSha256",
            )
            Files.deleteIfExists(target)
        }
        log.info("Downloading {} from {} …", label, uri)
        val request = HttpRequest.newBuilder(uri).timeout(budget.remaining()).GET().build()
        val response = try { httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); budget.check(); throw e }
        response.body().use { input ->
            val owner = Thread.currentThread()
            val watchdog = modelDownloadWatchdog.scheduleAtFixedRate({
                if (owner.isInterrupted || budget.expired()) runCatching { input.close() }
            }, 0, 10, TimeUnit.MILLISECONDS)
            try {
            require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} downloading $label" }
            val temporary = Files.createTempFile(target.parent, ".download-", ".tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        budget.check()
                        val n = input.read(buffer)
                        budget.check()
                        if (n < 0) break
                        count += n
                        require(count <= 512L * 1024 * 1024) { "Model asset exceeds 512 MiB" }
                        digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                require(hash.equals(expectedSha256, true)) { "SHA-256 mismatch downloading $label" }
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(temporary) }
            } catch (e: Exception) { budget.check(); throw e }
            finally { watchdog.cancel(false) }
        }
    }

    private fun sha256Hex(path: Path, budget: DownloadBudget): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { budget.check(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

}
