package com.ageverify.core

import android.content.Context
import android.graphics.Bitmap
import com.ageverify.config.VerificationConfig
import java.time.LocalDate

/**
 * Central orchestration engine for the verification pipeline.
 *
 * Pipeline:
 * Liveness Check → ID Scan → DOB Extraction → Age Check → Face Match → Token Issue
 *
 * Each step can fail independently with a clear reason.
 * The engine is stateless — callers manage session state.
 */
class AgeVerificationEngine(val context: Context) : AutoCloseable {

    private val dobExtractor = DOBExtractor()
    private val faceMatcher  = FaceMatcher(context)
    private val tokenManager = TokenManager(context)

    /**
     * Release native resources held by TFLite interpreter and ML Kit recognizer.
     * Call when the engine is no longer needed (e.g. ViewModel.onCleared).
     */
    override fun close() {
        faceMatcher.close()
        dobExtractor.close()
    }

    /**
     * Step 1: Validate liveness result from LivenessActivity.
     * Liveness itself is handled in the camera flow — this just validates
     * the captured frame before proceeding.
     */
    fun validateLivenessCapture(
        liveFaceBitmap: Bitmap,
        challengeCompleted: Boolean
    ): StepResult {
        if (!challengeCompleted) {
            return StepResult.Failed("Liveness challenge not completed")
        }
        if (liveFaceBitmap.width < 100 || liveFaceBitmap.height < 100) {
            return StepResult.Failed("Face capture too small — try again in better lighting")
        }
        return StepResult.Passed
    }

    /**
     * Step 2: Extract DOB from ID image.
     */
    suspend fun extractAgeFromID(idBitmap: Bitmap): AgeExtractionResult {
        return when (val result = dobExtractor.extractDOB(idBitmap)) {
            is DOBResult.Success -> {
                val age = dobExtractor.computeAge(result.dob)
                AgeExtractionResult.Success(age, result.dob)
            }
            is DOBResult.NotFound -> {
                AgeExtractionResult.Failed(
                    "Could not find date of birth on the ID. " +
                    "Make sure the full ID is visible and well-lit."
                )
            }
        }
    }

    /**
     * Step 3: Check extracted age against jurisdiction threshold.
     */
    fun checkAgeThreshold(
        extractedAge: Int,
        config: VerificationConfig
    ): StepResult {
        return if (extractedAge >= config.effectiveMinAge) {
            StepResult.Passed
        } else {
            StepResult.Failed(
                "Age $extractedAge does not meet minimum age " +
                "${config.effectiveMinAge} for ${config.jurisdiction.name}"
            )
        }
    }

    /**
     * Step 4: Match live face against ID face crop.
     */
    fun matchFaces(liveFace: Bitmap, idFace: Bitmap): FaceMatchStepResult {
        val result = faceMatcher.match(liveFace, idFace)
        return FaceMatchStepResult(
            similarity = result.similarity,
            passed = result.passed,
            message = when {
                result.passed -> "Face match confirmed (${(result.similarity * 100).toInt()}%)"
                else -> "Face does not match ID photo (${(result.similarity * 100).toInt()}% — needs 80%)"
            }
        )
    }

    /**
     * Step 5: Issue signed token after all checks pass.
     */
    fun issueVerificationToken(config: VerificationConfig): VerificationToken {
        return tokenManager.issueToken(
            jurisdiction = config.jurisdiction.countryCode,
            minimumAgeVerified = config.effectiveMinAge
        )
    }

    /**
     * Query current verification status — used by SDK.
     */
    fun queryVerificationStatus(requiredAge: Int): QueryResult {
        val token = tokenManager.getValidToken()
            ?: return QueryResult.NotVerified

        return if (token.minimumAgeVerified >= requiredAge) {
            QueryResult.Verified(token)
        } else {
            QueryResult.InsufficientAge(
                verifiedAge = token.minimumAgeVerified,
                requiredAge = requiredAge
            )
        }
    }

    fun isAlreadyVerified(): Boolean = tokenManager.isVerified()
    fun clearVerification() = tokenManager.clearToken()
}

// Result types

sealed class StepResult {
    object Passed : StepResult()
    data class Failed(val reason: String) : StepResult()
}

sealed class AgeExtractionResult {
    data class Success(val age: Int, val dob: LocalDate) : AgeExtractionResult()
    data class Failed(val reason: String) : AgeExtractionResult()
}

data class FaceMatchStepResult(
    val similarity: Float,
    val passed: Boolean,
    val message: String
)

sealed class QueryResult {
    data class Verified(val token: VerificationToken) : QueryResult()
    object NotVerified : QueryResult()
    data class InsufficientAge(
        val verifiedAge: Int,
        val requiredAge: Int
    ) : QueryResult()
}
