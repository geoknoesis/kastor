package com.geoknoesis.kastor.ontoquality.embed

import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * BERT-style sentence embedding from a local ONNX model plus HuggingFace `tokenizer.json`.
 *
 * The bundled preset loads `all-MiniLM-L6-v2`. For domain models (e.g. biomedical), pass your own
 * ONNX export and tokenizer that expose `input_ids`, `attention_mask`, and (if present) `token_type_ids`.
 * The output is selected by name: `last_hidden_state` / `token_embeddings` (rank 3, `[batch, seq, hidden]`,
 * mean-pooled with the attention mask) or `sentence_embedding` (rank 2, `[batch, hidden]`, used as-is).
 *
 * Tokenization overrides the padding/truncation baked into `tokenizer.json`: no fixed padding, truncation
 * to [maxTokens]. Inputs are grouped by token length so each batch pads only to its own longest member.
 *
 * Thread-safe: a single [OrtSession] and tokenizer are shared; inference is serialized.
 */
class OnnxEmbeddingModel private constructor(
    private val modelPath: Path,
    tokenizerPath: Path,
    override val name: String,
    override val dimension: Int,
    override val maxTokens: Int,
    override val tokenizerDescription: String,
    private val knownModelSha256: String? = null,
) : EmbeddingModel, AutoCloseable {
    /** ONNX file used for loading and provenance hashing. */
    val onnxModelPath: Path = modelPath.toAbsolutePath().normalize()

    /**
     * SHA-256 of [onnxModelPath], computed at most once per model instance (the bundled MiniLM reuses the digest
     * already verified by [ModelDownloader]).
     */
    val modelSha256: String by lazy { knownModelSha256 ?: sha256Hex(onnxModelPath) }

    private val log = LoggerFactory.getLogger(OnnxEmbeddingModel::class.java)
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        env.createSession(modelPath.toString(), options)
    }
    private val tokenizer: HuggingFaceTokenizer =
        try {
            HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optAddSpecialTokens(true)
                .optPadding(false)
                .optTruncation(true)
                .optMaxLength(maxTokens)
                .build()
        } catch (e: Throwable) {
            session.close()
            throw e
        }
    private var closed = false

    private val inferenceLock = ReentrantLock()

    private val inputNames: List<String> = session.inputNames.toList().sorted()

    private val outputName: String =
        try {
            EmbeddingBatching.selectOutput(session.outputNames)
        } catch (e: Throwable) {
            try { tokenizer.close() } finally { session.close() }
            throw e
        }

    init {
        log.info(
            "Loaded ONNX embedding model '{}' from {} (inputs: {}, output: {})",
            name,
            onnxModelPath,
            inputNames,
            outputName,
        )
    }

    override fun embed(texts: List<String>): List<FloatArray> = inferenceLock.withLock {
        check(!closed) { "Embedding model is closed" }
        if (texts.isEmpty()) return@withLock emptyList()
        val encodings = texts.map { tokenizer.encode(it, true, false) }
        val results = arrayOfNulls<FloatArray>(texts.size)
        for (batch in EmbeddingBatching.plan(encodings.map { it.ids.size }, BATCH_SIZE)) {
            val vectors = embedBatch(batch.map { encodings[it] })
            batch.forEachIndexed { position, index -> results[index] = vectors[position] }
        }
        results.map { checkNotNull(it) }
    }
    override fun close(): Unit = inferenceLock.withLock {
        if (!closed) {
            closed = true
            try { tokenizer.close() } finally { session.close() }
        }
    }

    private fun embedBatch(encodings: List<Encoding>): List<FloatArray> {
        val seqLen = min(encodings.maxOf { it.ids.size }, maxTokens).coerceAtLeast(1)

        val batch = encodings.size
        val inputIds = Array(batch) { LongArray(seqLen) }
        val typeIds = Array(batch) { LongArray(seqLen) }
        val attnMask = Array(batch) { LongArray(seqLen) }

        for ((i, enc) in encodings.withIndex()) {
            val special = enc.specialTokenMask
            val keepLast = special.isNotEmpty() && special[special.size - 1] == 1L
            inputIds[i] = EmbeddingBatching.fit(enc.ids, seqLen, keepLast)
            typeIds[i] = EmbeddingBatching.fit(enc.typeIds, seqLen, keepLast)
            attnMask[i] = EmbeddingBatching.fit(enc.attentionMask, seqLen, keepLast)
        }

        return runOnnx(batch, seqLen, inputIds, attnMask, typeIds)
    }

    private fun runOnnx(
        batch: Int,
        seqLen: Int,
        inputIds: Array<LongArray>,
        attentionMask: Array<LongArray>,
        tokenTypeIds: Array<LongArray>,
    ): List<FloatArray> {
        val inputs = LinkedHashMap<String, OnnxTensor>()
        try {
            for (inputName in inputNames) {
                val tensor =
                    when {
                        inputName.equals("input_ids", ignoreCase = true) ->
                            OnnxTensor.createTensor(env, inputIds)

                        inputName.equals("attention_mask", ignoreCase = true) ->
                            OnnxTensor.createTensor(env, attentionMask)

                        inputName.equals("token_type_ids", ignoreCase = true) ->
                            OnnxTensor.createTensor(env, tokenTypeIds)

                        else ->
                            when {
                                inputName.contains("attention", ignoreCase = true) ->
                                    OnnxTensor.createTensor(env, attentionMask)

                                inputName.contains("token_type", ignoreCase = true) ->
                                    OnnxTensor.createTensor(env, tokenTypeIds)

                                inputName.contains("input", ignoreCase = true) ->
                                    OnnxTensor.createTensor(env, inputIds)

                                else ->
                                    throw IllegalStateException(
                                        "Unknown ONNX input '$inputName' for model '$name' " +
                                            "(expected input_ids / attention_mask / token_type_ids)",
                                    )
                            }
                    }
                inputs[inputName] = tensor
            }

            session.run(inputs).use { result ->
                val tensor = result.get(outputName).orElseThrow() as OnnxTensor
                val shape = (tensor.info as ai.onnxruntime.TensorInfo).shape
                return EmbeddingBatching.pool(shape, tensor.floatBuffer, attentionMask, batch, seqLen, dimension)
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    companion object {
        const val MODEL_ID_MINILM: String = "all-MiniLM-L6-v2"
        const val MODEL_ID_CUSTOM: String = "custom"

        private const val BATCH_SIZE = 8

        /** Position-embedding limit of BERT-base style models such as MiniLM. */
        private const val MINILM_MAX_POSITIONS = 512

        private const val MINILM_TOKENIZER_NOTE =
            "sentence-transformers/all-MiniLM-L6-v2 (HF tokenizer)"

        /**
         * Loads (or reuses cached) MiniLM files under [cacheRoot], defaulting to [ModelDownloader.resolveCacheRoot].
         *
         * @param maxTokens truncation length (1–512).
         */
        fun fromMiniLm(cacheRoot: Path? = null, maxTokens: Int = MINILM_MAX_POSITIONS): OnnxEmbeddingModel {
            require(maxTokens in 1..MINILM_MAX_POSITIONS) { "maxTokens must be between 1 and $MINILM_MAX_POSITIONS" }
            val files = ModelDownloader.ensureMiniLmFiles(ModelDownloader.resolveCacheRoot(cacheRoot))
            return OnnxEmbeddingModel(
                modelPath = files.first,
                tokenizerPath = files.second,
                name = MODEL_ID_MINILM,
                dimension = 384,
                maxTokens = maxTokens,
                tokenizerDescription = MINILM_TOKENIZER_NOTE,
                knownModelSha256 = ModelDownloader.EXPECTED_MODEL_SHA256,
            )
        }

        /**
         * Loads a user-supplied ONNX embedding model and tokenizer (e.g. BioBERT / PubMedBERT export).
         *
         * @param name Display id for enrichment provenance (`oqsh:model`).
         * @param tokenizerDescription Stored as `oqsh:tokenizer` on the enrichment node.
         */
        fun fromLocalFiles(
            onnxPath: Path,
            tokenizerPath: Path,
            name: String,
            dimension: Int,
            maxTokens: Int = 512,
            tokenizerDescription: String = name,
        ): OnnxEmbeddingModel {
            require(Files.isRegularFile(onnxPath)) { "ONNX path is not a file: $onnxPath" }
            require(Files.isRegularFile(tokenizerPath)) { "Tokenizer path is not a file: $tokenizerPath" }
            require(dimension > 0) { "dimension must be positive" }
            require(maxTokens > 0) { "maxTokens must be positive" }
            return OnnxEmbeddingModel(
                modelPath = onnxPath,
                tokenizerPath = tokenizerPath,
                name = name,
                dimension = dimension,
                maxTokens = maxTokens,
                tokenizerDescription = tokenizerDescription,
            )
        }

        /**
         * Resolves CLI-style options: bundled MiniLM or a local ONNX + tokenizer.
         *
         * @throws IllegalArgumentException with a user-facing message if options are inconsistent.
         */
        fun fromCliOptions(
            modelId: String,
            cacheRoot: Path? = null,
            onnxPath: Path? = null,
            tokenizerPath: Path? = null,
            embeddingDim: Int? = null,
            maxTokens: Int = 512,
            displayName: String? = null,
            tokenizerNote: String? = null,
        ): OnnxEmbeddingModel {
            validateCliOptions(modelId, onnxPath, tokenizerPath, embeddingDim, maxTokens)
            val id = modelId.trim()
            return when {
                id.equals(MODEL_ID_MINILM, ignoreCase = true) -> {
                    require(onnxPath == null && tokenizerPath == null && embeddingDim == null) {
                        "Do not use --onnx, --tokenizer, or --embedding-dim with bundled model $MODEL_ID_MINILM"
                    }
                    fromMiniLm(cacheRoot, maxTokens)
                }

                id.equals(MODEL_ID_CUSTOM, ignoreCase = true) -> {
                    require(onnxPath != null) { "--onnx is required when --model $MODEL_ID_CUSTOM" }
                    require(tokenizerPath != null) { "--tokenizer is required when --model $MODEL_ID_CUSTOM" }
                    require(embeddingDim != null) { "--embedding-dim is required when --model $MODEL_ID_CUSTOM" }
                    val label =
                        displayName?.takeIf { it.isNotBlank() }
                            ?: onnxPath.fileName.toString().removeSuffix(".onnx").removeSuffix(".onnx.gz")
                    val tokNote = tokenizerNote?.takeIf { it.isNotBlank() } ?: label
                    fromLocalFiles(
                        onnxPath = onnxPath,
                        tokenizerPath = tokenizerPath,
                        name = label,
                        dimension = embeddingDim,
                        maxTokens = maxTokens,
                        tokenizerDescription = tokNote,
                    )
                }

                else ->
                    throw IllegalArgumentException(
                        "Unknown --model '$modelId'. Use $MODEL_ID_MINILM (bundled) or $MODEL_ID_CUSTOM " +
                            "with --onnx, --tokenizer, and --embedding-dim.",
                    )
            }
        }

        /**
         * Checks CLI embedding options for consistency without touching the network or the file system, so callers
         * can report configuration errors separately from model download / load failures.
         *
         * @throws IllegalArgumentException with a user-facing message if options are inconsistent.
         */
        fun validateCliOptions(
            modelId: String,
            onnxPath: Path?,
            tokenizerPath: Path?,
            embeddingDim: Int?,
            maxTokens: Int,
        ) {
            val id = modelId.trim()
            when {
                id.equals(MODEL_ID_MINILM, ignoreCase = true) -> {
                    require(onnxPath == null && tokenizerPath == null && embeddingDim == null) {
                        "Do not use --onnx, --tokenizer, or --embedding-dim with bundled model $MODEL_ID_MINILM"
                    }
                    require(maxTokens in 1..MINILM_MAX_POSITIONS) { "maxTokens must be between 1 and $MINILM_MAX_POSITIONS" }
                }
                id.equals(MODEL_ID_CUSTOM, ignoreCase = true) -> {
                    require(onnxPath != null) { "--onnx is required when --model $MODEL_ID_CUSTOM" }
                    require(tokenizerPath != null) { "--tokenizer is required when --model $MODEL_ID_CUSTOM" }
                    require(embeddingDim != null) { "--embedding-dim is required when --model $MODEL_ID_CUSTOM" }
                    require(embeddingDim > 0) { "dimension must be positive" }
                    require(maxTokens > 0) { "maxTokens must be positive" }
                }
                else ->
                    throw IllegalArgumentException(
                        "Unknown --model '$modelId'. Use $MODEL_ID_MINILM (bundled) or $MODEL_ID_CUSTOM " +
                            "with --onnx, --tokenizer, and --embedding-dim.",
                    )
            }
        }

        private fun sha256Hex(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "Model hashing interrupted" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
    }
}
