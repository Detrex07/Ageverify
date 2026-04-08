package com.ageverify.ui

import android.graphics.Bitmap
import java.time.Instant

/**
 * VerificationSession
 *
 * Immutable snapshot of one end-to-end verification attempt.
 * Held in-memory only. Never written to disk. Cleared after use.
 *
 * Replaces the fragile static companion object bitmaps that were
 * passed between activities. The repository holds a single mutable
 * reference to the current session.
 */
data class VerificationSession(

    // ── Step 1: Liveness ─────────────────────────────────────────────────────
    val liveFaceBitmap: Bitmap? = null,
    val livenessCompleted: Boolean = false,

    // ── Step 2: ID Scan ───────────────────────────────────────────────────────
    val idBitmap: Bitmap? = null,
    val idScanCompleted: Boolean = false,

    // ── Step 3: Extraction results ────────────────────────────────────────────
    val extractedAge: Int? = null,
    val dobRawText: String? = null,

    // ── Step 4: Face match ────────────────────────────────────────────────────
    val faceMatchScore: Float? = null,
    val faceMatchPassed: Boolean? = null,

    // ── Step 5: Token ─────────────────────────────────────────────────────────
    val tokenIssued: Boolean = false,
    val tokenSource: String = "IN_APP", // "IN_APP" | "STANDALONE_APP"
    val tokenIssuedAt: Instant? = null,

    // ── Session metadata ──────────────────────────────────────────────────────
    val startedAt: Instant = Instant.now(),
    val failureReason: FailureReason? = null

) {
    val isComplete: Boolean
        get() = tokenIssued || failureReason != null

    val hasLiveFace: Boolean
        get() = liveFaceBitmap != null && livenessCompleted

    val hasIDScan: Boolean
        get() = idBitmap != null && idScanCompleted

    val hasBothCaptures: Boolean
        get() = hasLiveFace && hasIDScan
}

enum class FailureReason {
    LIVENESS_TIMEOUT,
    LIVENESS_CANCELLED,
    ID_SCAN_CANCELLED,
    DOB_NOT_FOUND,
    AGE_BELOW_THRESHOLD,
    FACE_MATCH_FAILED,
    CAMERA_PERMISSION_DENIED,
    UNKNOWN
}
