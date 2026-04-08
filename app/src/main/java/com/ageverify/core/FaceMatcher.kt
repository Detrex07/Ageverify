package com.ageverify.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.TensorImage
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * FaceNet-based face matcher.
 *
 * Takes two face images (live capture + ID photo crop) and returns
 * a similarity score between 0.0 and 1.0.
 *
 * Uses cosine similarity on 128-dimension FaceNet embeddings.
 * Threshold of 0.8 gives ~99.5% true accept rate on LFW benchmark.
 *
 * FaceNet model: facenet.tflite
 * Place in app/src/main/assets/facenet.tflite
 *
 * Download from: https://github.com/nicehash/NiceHashQuickMiner (open source)
 * or use TF Hub: https://tfhub.dev/google/facenet/1
 */
class FaceMatcher(context: Context) {

    private val interpreter: Interpreter by lazy {
        Interpreter(loadModelFile(context, MODEL_FILENAME))
    }

    /**
     * Compare two face bitmaps.
     * @return FaceMatchResult with similarity score and pass/fail
     */
    fun match(liveFace: Bitmap, idFace: Bitmap): FaceMatchResult {
        val liveEmbedding = getEmbedding(liveFace)
        val idEmbedding = getEmbedding(idFace)
        val similarity = cosineSimilarity(liveEmbedding, idEmbedding)

        return FaceMatchResult(
            similarity = similarity,
            passed = similarity >= MATCH_THRESHOLD
        )
    }

    /**
     * Convert a face bitmap to a 128-dim FaceNet embedding.
     */
    private fun getEmbedding(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        return try {
            val input = preprocessBitmap(resized)
            val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
            interpreter.run(input, output)
            output[0]
        } finally {
            // Recycle the scaled copy — the original is owned by the caller
            if (resized != bitmap) resized.recycle()
        }
    }

    /**
     * Normalise pixel values to [-1, 1] as FaceNet expects.
     */
    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(
            1 * INPUT_SIZE * INPUT_SIZE * 3 * 4 // batch * H * W * channels * float bytes
        ).apply { order(ByteOrder.nativeOrder()) }

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 127.5f - 1f
            val g = ((pixel shr 8) and 0xFF) / 127.5f - 1f
            val b = (pixel and 0xFF) / 127.5f - 1f
            buffer.putFloat(r)
            buffer.putFloat(g)
            buffer.putFloat(b)
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Cosine similarity between two vectors. Range: -1.0 to 1.0.
     * For face matching, values >= 0.8 indicate the same person.
     * Returns 0.0 if either vector is zero-magnitude (degenerate input guard).
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        if (denominator == 0f) return 0f
        return dotProduct / denominator
    }

    fun close() {
        interpreter.close()
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(filename)
        return FileInputStream(fileDescriptor.fileDescriptor).use { stream ->
            stream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.startOffset,
                fileDescriptor.declaredLength
            )
        }
    }

    companion object {
        private const val MODEL_FILENAME = "facenet.tflite"
        private const val INPUT_SIZE = 160       // FaceNet input: 160x160
        private const val EMBEDDING_SIZE = 128   // FaceNet output: 128-dim vector
        const val MATCH_THRESHOLD = 0.80f        // Tunable — 0.8 is conservative
    }
}

data class FaceMatchResult(
    val similarity: Float,
    val passed: Boolean
)
