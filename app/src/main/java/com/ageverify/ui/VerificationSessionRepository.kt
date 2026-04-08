package com.ageverify.ui

import android.graphics.Bitmap
import com.ageverify.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * VerificationSessionRepository
 *
 * Single in-memory store for the active verification session.
 * Shared across all activities in the verification flow via the ViewModel.
 *
 * Why a singleton repository instead of static companion objects:
 *
 * Problem with companion objects:
 *   - Bitmaps stored statically leak memory if activity is destroyed mid-flow
 *   - No observable state — activities poll or check booleans manually
 *   - No clear ownership or lifecycle — nothing clears them reliably
 *   - Multiple activities all writing to different static fields creates
 *     implicit coupling that breaks silently
 *
 * This approach:
 *   - Single owner: the repository
 *   - Observable via StateFlow — activities react to changes, don't poll
 *   - Explicit session lifecycle: start() → update steps → complete() / fail()
 *   - clear() is called when flow ends, releasing bitmap memory immediately
 *   - Thread-safe via StateFlow / update()
 */
object VerificationSessionRepository {

    private const val TAG = "SessionRepository"

    private val _session = MutableStateFlow<VerificationSession?>(null)
    val session: StateFlow<VerificationSession?> = _session.asStateFlow()

    val currentSession: VerificationSession?
        get() = _session.value

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Start a new session. Clears any previous session first. */
    fun start() {
        clear()
        _session.value = VerificationSession()
        AppLog.d(TAG, "Session started")
    }

    /** Clear session and release bitmap memory. */
    fun clear() {
        _session.value?.let { session ->
            release(session.liveFaceBitmap)
            release(session.idBitmap)
        }
        _session.value = null
        AppLog.d(TAG, "Session cleared")
    }

    // ── Step updates ──────────────────────────────────────────────────────────

    fun setLivenessCapture(bitmap: Bitmap) {
        var updated = false
        _session.update { current ->
            current?.copy(
                liveFaceBitmap = bitmap,
                livenessCompleted = true
            )?.also {
                updated = true
            } ?: run {
                AppLog.w(TAG, "setLivenessCapture called with no active session")
                null
            }
        }
        if (updated) AppLog.d(TAG, "Liveness capture saved (${bitmapSize(bitmap)})")
    }

    fun setIDCapture(bitmap: Bitmap) {
        var updated = false
        _session.update { current ->
            current?.copy(
                idBitmap = bitmap,
                idScanCompleted = true
            )?.also {
                updated = true
            } ?: run {
                AppLog.w(TAG, "setIDCapture called with no active session")
                null
            }
        }
        if (updated) AppLog.d(TAG, "ID capture saved (${bitmapSize(bitmap)})")
    }

    fun setAgeExtractionResult(age: Int, rawText: String) {
        _session.update { it?.copy(extractedAge = age, dobRawText = rawText) }
        AppLog.d(TAG, "Age extracted: $age")
    }

    fun setFaceMatchResult(score: Float, passed: Boolean) {
        _session.update { it?.copy(faceMatchScore = score, faceMatchPassed = passed) }
        AppLog.d(TAG, "Face match: score=$score passed=$passed")
    }

    fun setTokenIssued(source: String = "IN_APP") {
        _session.update { it?.copy(
            tokenIssued = true,
            tokenSource = source,
            tokenIssuedAt = java.time.Instant.now()
        )}
        AppLog.d(TAG, "Token issued via $source")
    }

    fun setFailure(reason: FailureReason) {
        _session.update { it?.copy(failureReason = reason) }
        AppLog.d(TAG, "Session failed: $reason")
    }

    // ── Convenience accessors ─────────────────────────────────────────────────

    val liveFace: Bitmap?
        get() = _session.value?.liveFaceBitmap

    val idImage: Bitmap?
        get() = _session.value?.idBitmap

    val hasBothCaptures: Boolean
        get() = _session.value?.hasBothCaptures == true

    val tokenSource: String
        get() = _session.value?.tokenSource ?: "IN_APP"

    private fun release(bitmap: Bitmap?) {
        runCatching {
            bitmap?.recycle()
        }
    }

    private fun bitmapSize(bitmap: Bitmap): String {
        return runCatching { "${bitmap.width}x${bitmap.height}" }
            .getOrDefault("unknown size")
    }
}
