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
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.writeBytes

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
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx"
    private const val TOKENIZER_REL_URL =
        "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json"

    /** Expected SHA-256 of `model.onnx` as distributed at calibration time. */
    const val EXPECTED_MODEL_SHA256 = "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452"

    /** Expected SHA-256 of `tokenizer.json`. */
    const val EXPECTED_TOKENIZER_SHA256 =
        "be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037"

    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build()

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

    fun ensureMiniLmFiles(cacheRoot: Path = resolveCacheRoot()): Pair<Path, Path> {
        val dir = resolveMiniLmDir(cacheRoot)
        if (dir.notExists()) dir.createDirectories()

        val modelPath = dir.resolve("model.onnx")
        val tokenizerPath = dir.resolve("tokenizer.json")

        ensureFile(modelPath, URI.create(MODEL_REL_URL), EXPECTED_MODEL_SHA256, "model.onnx")
        ensureFile(
            tokenizerPath,
            URI.create(TOKENIZER_REL_URL),
            EXPECTED_TOKENIZER_SHA256,
            "tokenizer.json",
        )
        return modelPath to tokenizerPath
    }

    private fun ensureFile(target: Path, uri: URI, expectedSha256: String, label: String) {
        if (target.exists()) {
            val hash = sha256Hex(target)
            require(hash.equals(expectedSha256, ignoreCase = true)) {
                "SHA-256 mismatch for cached $label at $target (got $hash, expected $expectedSha256). Delete the file to re-download."
            }
            return
        }
        log.info("Downloading {} from {} …", label, uri)
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} downloading $label" }
        val bytes = response.body()
        val hash = sha256Hex(bytes)
        require(hash.equals(expectedSha256, ignoreCase = true)) {
            "SHA-256 mismatch for downloaded $label (got $hash, expected $expectedSha256)"
        }
        Files.write(target, bytes)
        log.info("Wrote {} ({} bytes, sha256={})", target, bytes.size, hash)
    }

    private fun sha256Hex(path: Path): String = sha256Hex(Files.readAllBytes(path))

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
