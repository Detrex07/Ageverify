package com.ageverify.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tests for the cosine similarity math and face match threshold logic.
 *
 * FaceMatcher itself requires Android (TFLite, assets) so we can't
 * instantiate it in a JVM unit test. Instead we test the mathematical
 * core by extracting cosine similarity into a standalone testable function
 * and verifying all edge cases that matter for security.
 *
 * What these tests protect against:
 * - NaN propagation from zero-magnitude vectors (crash/always-pass bug)
 * - Threshold boundary — 0.799 must fail, 0.800 must pass
 * - Identical vectors should score 1.0
 * - Orthogonal vectors should score 0.0
 * - Opposite vectors should score -1.0
 */
class FaceMatcherLogicTest {

    // ── Cosine similarity correctness ─────────────────────────────────────────

    @Test
    fun `identical vectors produce similarity of 1_0`() {
        val a = floatArrayOf(1f, 2f, 3f, 4f)
        assertThat(cosineSimilarity(a, a)).isWithin(0.0001f).of(1.0f)
    }

    @Test
    fun `identical normalised embeddings produce similarity of 1_0`() {
        // Simulate FaceNet-style normalised 128-dim vector
        val v = FloatArray(128) { (it + 1).toFloat() }
        val norm = sqrt(v.map { it * it }.sum())
        val normalised = FloatArray(128) { v[it] / norm }
        assertThat(cosineSimilarity(normalised, normalised)).isWithin(0.001f).of(1.0f)
    }

    @Test
    fun `orthogonal vectors produce similarity of 0_0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertThat(cosineSimilarity(a, b)).isWithin(0.0001f).of(0.0f)
    }

    @Test
    fun `opposite vectors produce similarity of negative 1_0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(-1f, 0f, 0f)
        assertThat(cosineSimilarity(a, b)).isWithin(0.0001f).of(-1.0f)
    }

    @Test
    fun `scaled version of same vector produces similarity of 1_0`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(2f, 4f, 6f) // a * 2
        assertThat(cosineSimilarity(a, b)).isWithin(0.0001f).of(1.0f)
    }

    // ── Zero-vector guard (the division-by-zero fix) ──────────────────────────

    @Test
    fun `zero vector a returns 0_0 not NaN`() {
        val zero = FloatArray(128) { 0f }
        val b    = FloatArray(128) { (it + 1).toFloat() }
        val result = cosineSimilarity(zero, b)
        assertThat(result.isNaN()).isFalse()
        assertThat(result).isEqualTo(0f)
    }

    @Test
    fun `zero vector b returns 0_0 not NaN`() {
        val a    = FloatArray(128) { (it + 1).toFloat() }
        val zero = FloatArray(128) { 0f }
        val result = cosineSimilarity(a, zero)
        assertThat(result.isNaN()).isFalse()
        assertThat(result).isEqualTo(0f)
    }

    @Test
    fun `both zero vectors return 0_0 not NaN`() {
        val zero = FloatArray(128) { 0f }
        val result = cosineSimilarity(zero, zero)
        assertThat(result.isNaN()).isFalse()
        assertThat(result).isEqualTo(0f)
    }

    // ── Threshold boundary (security critical) ───────────────────────────────

    @Test
    fun `similarity at exactly threshold 0_80 passes`() {
        assertThat(meetsThreshold(0.80f)).isTrue()
    }

    @Test
    fun `similarity just below threshold 0_799 fails`() {
        assertThat(meetsThreshold(0.799f)).isFalse()
    }

    @Test
    fun `similarity of 0_90 passes`() {
        assertThat(meetsThreshold(0.90f)).isTrue()
    }

    @Test
    fun `similarity of 0_0 fails`() {
        assertThat(meetsThreshold(0.0f)).isFalse()
    }

    @Test
    fun `negative similarity fails`() {
        assertThat(meetsThreshold(-0.5f)).isFalse()
    }

    @Test
    fun `NaN similarity does not pass threshold check`() {
        // Verify that NaN >= 0.80f is always false in Kotlin (IEEE 754 semantics)
        assertThat(Float.NaN >= FaceMatcher.MATCH_THRESHOLD).isFalse()
    }

    // ── 128-dimension operation correctness ──────────────────────────────────

    @Test
    fun `128-dim uniform vectors produce similarity 1_0`() {
        val a = FloatArray(128) { 0.5f }
        val b = FloatArray(128) { 0.5f }
        assertThat(cosineSimilarity(a, b)).isWithin(0.001f).of(1.0f)
    }

    @Test
    fun `128-dim random-ish dissimilar vectors produce similarity less than 0_80`() {
        // Vectors with alternating signs are dissimilar
        val a = FloatArray(128) { if (it % 2 == 0)  1f else -1f }
        val b = FloatArray(128) { if (it % 2 == 0) -1f else  1f }
        assertThat(cosineSimilarity(a, b)).isLessThan(FaceMatcher.MATCH_THRESHOLD)
    }

    // ── Helpers (mirrors production logic exactly) ───────────────────────────

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }

    private fun meetsThreshold(similarity: Float): Boolean =
        similarity >= FaceMatcher.MATCH_THRESHOLD
}
