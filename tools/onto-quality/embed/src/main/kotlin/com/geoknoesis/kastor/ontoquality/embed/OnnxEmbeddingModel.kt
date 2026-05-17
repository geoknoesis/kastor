package com.geoknoesis.kastor.ontoquality.embed

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import kotlin.math.sqrt

/**
 * BERT-style sentence embedding from a local ONNX model plus HuggingFace `tokenizer.json`.
 *
 * The bundled preset loads [all-MiniLM-L6-v2]. For domain models (e.g. biomedical), pass your own
 * ONNX export and tokenizer that expose `input_ids`, `attention_mask`, and (if present) `token_type_ids`,
 * with a rank-3 float output (batch × sequence × hidden).
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
) : EmbeddingModel, AutoCloseable {
    /** ONNX file used for loading and provenance hashing. */
    val onnxModelPath: Path = modelPath.toAbsolutePath().normalize()

    private val log = LoggerFactory.getLogger(OnnxEmbeddingModel::class.java)
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession =
        env.createSession(
            modelPath.toString(),
            OrtSession.SessionOptions().also { opts ->
                opts.setIntraOpNumThreads(1)
            },
        )

    private val tokenizer: HuggingFaceTokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath)

    private val inferenceLock = ReentrantLock()

    private val inputNames: List<String> = session.inputNames.toList().sorted()

    init {
        log.info(
            "Loaded ONNX embedding model '{}' from {} (inputs: {})",
            name,
            onnxModelPath,
            inputNames,
        )
    }

    override fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return texts.chunked(BATCH_SIZE).flatMap { batch -> embedBatch(batch) }
    }

    override fun close() {
        session.close()
    }

    private fun embedBatch(texts: List<String>): List<FloatArray> {
        val encodings = texts.map { tokenizer.encode(it, true, true) }
        val seqLen = min(encodings.maxOf { it.getIds().size }, maxTokens).coerceAtLeast(1)

        val batch = texts.size
        val inputIds = Array(batch) { LongArray(seqLen) }
        val typeIds = Array(batch) { LongArray(seqLen) }
        val attnMask = Array(batch) { LongArray(seqLen) }

        for ((i, enc) in encodings.withIndex()) {
            copyTruncated(enc.getIds(), inputIds[i], seqLen)
            copyTruncated(enc.getTypeIds(), typeIds[i], seqLen)
            copyTruncated(enc.getAttentionMask(), attnMask[i], seqLen)
        }

        return inferenceLock.withLock {
            runOnnx(batch, seqLen, inputIds, attnMask, typeIds)
        }
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
                val outName = session.outputNames.iterator().next()
                val onnxValue = result.get(outName).orElseThrow()
                val tensor = onnxValue as OnnxTensor
                val info = tensor.info as ai.onnxruntime.TensorInfo
                val shape = info.shape
                val buf = tensor.floatBuffer
                require(shape.size == 3) {
                    "Expected rank-3 last_hidden_state, got dims=${shape.contentToString()}"
                }
                val hidden = shape[2].toInt()
                require(hidden == dimension) {
                    "Expected hidden=$dimension (from --embedding-dim / model config), got $hidden"
                }

                val vectors = MutableList(batch) { FloatArray(dimension) }
                for (b in 0 until batch) {
                    val pooled = FloatArray(hidden)
                    var count = 0
                    for (s in 0 until seqLen) {
                        if (attentionMask[b][s] == 0L) continue
                        count++
                        val base = (b * seqLen + s) * hidden
                        for (h in 0 until hidden) {
                            pooled[h] += buf.get(base + h)
                        }
                    }
                    if (count > 0) {
                        for (h in 0 until hidden) pooled[h] /= count.toFloat()
                    }
                    l2Normalize(pooled)
                    vectors[b] = pooled
                }
                return vectors
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun copyTruncated(src: LongArray, dest: LongArray, seqLen: Int) {
        val n = min(src.size, seqLen)
        for (i in 0 until n) dest[i] = src[i]
    }

    companion object {
        const val MODEL_ID_MINILM: String = "all-MiniLM-L6-v2"
        const val MODEL_ID_CUSTOM: String = "custom"

        private const val BATCH_SIZE = 8

        private const val MINILM_TOKENIZER_NOTE =
            "sentence-transformers/all-MiniLM-L6-v2 (HF tokenizer)"

        /**
         * Loads (or reuses cached) MiniLM files under [cacheRoot], defaulting to [ModelDownloader.resolveCacheRoot].
         */
        fun fromMiniLm(cacheRoot: Path? = null): OnnxEmbeddingModel {
            val files = ModelDownloader.ensureMiniLmFiles(ModelDownloader.resolveCacheRoot(cacheRoot))
            return OnnxEmbeddingModel(
                modelPath = files.first,
                tokenizerPath = files.second,
                name = MODEL_ID_MINILM,
                dimension = 384,
                maxTokens = 512,
                tokenizerDescription = MINILM_TOKENIZER_NOTE,
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
            val id = modelId.trim()
            return when {
                id.equals(MODEL_ID_MINILM, ignoreCase = true) -> {
                    require(onnxPath == null && tokenizerPath == null && embeddingDim == null) {
                        "Do not use --onnx, --tokenizer, or --embedding-dim with bundled model $MODEL_ID_MINILM"
                    }
                    fromMiniLm(cacheRoot)
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

        private fun l2Normalize(v: FloatArray) {
            var sum = 0.0
            for (x in v) sum += x * x
            val norm = sqrt(sum).toFloat().coerceAtLeast(1e-12f)
            for (i in v.indices) v[i] /= norm
        }
    }
}
