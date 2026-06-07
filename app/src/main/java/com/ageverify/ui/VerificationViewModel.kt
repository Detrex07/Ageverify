package com.ageverify.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ageverify.config.VerificationConfig
import com.ageverify.core.AgeExtractionResult
import com.ageverify.core.AgeVerificationEngine
import com.ageverify.core.StepResult
import com.ageverify.core.TokenManager
import com.ageverify.core.VerificationToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VerificationViewModel
 *
 * Single ViewModel shared by all activities in the verification flow.
 * Scoped to the application — created once, survives activity recreation.
 *
 * Responsibilities:
 * - Own the VerificationSessionRepository reference
 * - Run the verification pipeline when both captures are ready
 * - Expose pipeline state as observable StateFlow
 * - Activities read state, they do NOT call engine directly
 *
 * State flow:
 *
 *   IDLE → LIVENESS_PENDING → ID_PENDING → PROCESSING → SUCCESS
 *                                                      → AGE_FAIL
 *                                                      → ERROR
 */
class VerificationViewModel(
    private val engine: AgeVerificationEngine,
    private val config: VerificationConfig
) : ViewModel() {

    private val repo = VerificationSessionRepository

    private val _pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    // Expose session flow directly so activities can observe individual field changes
    val session = repo.session

    // ── Session control ───────────────────────────────────────────────────────

    fun startSession() {
        repo.start()
        _pipelineState.value = PipelineState.LivenessPending
    }

    fun clearSession() {
        repo.clear()
        _pipelineState.value = PipelineState.Idle
    }

    // ── Step completions (called by activities) ───────────────────────────────

    fun onLivenessComplete(bitmap: Bitmap) {
        repo.setLivenessCapture(bitmap)
        _pipelineState.value = PipelineState.IDPending
    }

    fun onLivenessFailed(reason: FailureReason) {
        repo.setFailure(reason)
        _pipelineState.value = PipelineState.Error(
            reason = reason,
            message = reason.toUserMessage()
        )
    }

    fun onIDScanComplete(bitmap: Bitmap) {
        repo.setIDCapture(bitmap)
        runPipeline()
    }

    fun onIDScanCancelled() {
        repo.setFailure(FailureReason.ID_SCAN_CANCELLED)
        _pipelineState.value = PipelineState.IDPending // allow retry
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    private fun runPipeline() {
        val liveFace = repo.liveFace
        val idImage = repo.idImage

        if (liveFace == null || idImage == null) {
            _pipelineState.value = PipelineState.Error(
                reason = FailureReason.UNKNOWN,
                message = "Missing capture data — please restart"
            )
            return
        }

        _pipelineState.value = PipelineState.Processing

        viewModelScope.launch {
            // Step 1: Extract DOB from ID — OCR is blocking IO
            val ageResult = withContext(Dispatchers.IO) {
                engine.extractAgeFromID(idImage)
            }
            when (ageResult) {
                is AgeExtractionResult.Failed -> {
                    repo.setFailure(FailureReason.DOB_NOT_FOUND)
                    _pipelineState.value = PipelineState.Error(
                        reason = FailureReason.DOB_NOT_FOUND,
                        message = ageResult.reason
                    )
                    return@launch
                }
                is AgeExtractionResult.Success -> {
                    repo.setAgeExtractionResult(ageResult.age, ageResult.age.toString())

                    // Step 2: Age threshold check
                    when (val check = engine.checkAgeThreshold(ageResult.age, config)) {
                        is StepResult.Failed -> {
                            repo.setFailure(FailureReason.AGE_BELOW_THRESHOLD)
                            _pipelineState.value = PipelineState.AgeFail(
                                extractedAge = ageResult.age,
                                requiredAge = config.effectiveMinAge
                            )
                            return@launch
                        }
                        is StepResult.Passed -> {
                            // Step 3: Face match
                            if (config.requireFaceMatch) {
                                val match = withContext(Dispatchers.Default) {
                                    engine.matchFaces(liveFace, idImage)
                                }
                                repo.setFaceMatchResult(match.similarity, match.passed)

                                if (!match.passed) {
                                    repo.setFailure(FailureReason.FACE_MATCH_FAILED)
                                    val pct = (match.similarity * 100).toInt()
                                    _pipelineState.value = PipelineState.Error(
                                        reason = FailureReason.FACE_MATCH_FAILED,
                                        message = "Face match failed ($pct%). Please ensure you are using your own ID and have good lighting."
                                    )
                                    return@launch
                                }
                            }

                            // Step 4: Issue token
                            val token = engine.issueVerificationToken(config)
                            repo.setTokenIssued("IN_APP")

                            _pipelineState.value = PipelineState.Success(token = token)
                        }
                    }
                }
            }
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    fun isAlreadyVerified(): Boolean {
        val token = getExistingToken() ?: return false
        return config.acceptsVerifiedAge(token.minimumAgeVerified)
    }

    fun getExistingToken(): VerificationToken? =
        TokenManager(engine.context).getValidToken()

    fun clearStoredVerification() {
        engine.clearVerification()
    }

    override fun onCleared() {
        super.onCleared()
        engine.close()
        repo.clear()
    }
}

// ── Pipeline states ───────────────────────────────────────────────────────────

sealed class PipelineState {

    /** No session active */
    object Idle : PipelineState()

    /** Waiting for liveness to complete */
    object LivenessPending : PipelineState()

    /** Liveness done, waiting for ID scan */
    object IDPending : PipelineState()

    /** Both captures done, engine running */
    object Processing : PipelineState()

    /** All checks passed, token issued */
    data class Success(val token: VerificationToken) : PipelineState()

    /** Age below threshold — terminal, no retry */
    data class AgeFail(val extractedAge: Int, val requiredAge: Int) : PipelineState()

    /** Recoverable or unrecoverable error */
    data class Error(val reason: FailureReason, val message: String) : PipelineState()
}

fun FailureReason.toUserMessage(): String = when (this) {
    FailureReason.LIVENESS_TIMEOUT         -> "Liveness check timed out. Please try again."
    FailureReason.LIVENESS_CANCELLED       -> "Liveness check cancelled."
    FailureReason.ID_SCAN_CANCELLED        -> "ID scan cancelled."
    FailureReason.DOB_NOT_FOUND            -> "Date of birth not found on ID. Make sure it is fully visible and well-lit."
    FailureReason.AGE_BELOW_THRESHOLD      -> "Age requirement not met."
    FailureReason.FACE_MATCH_FAILED        -> "Face does not match ID photo."
    FailureReason.CAMERA_PERMISSION_DENIED -> "Camera access is required for verification."
    FailureReason.UNKNOWN                  -> "Something went wrong. Please try again."
}
