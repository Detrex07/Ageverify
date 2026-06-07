package com.ageverify.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.ageverify.config.VerificationConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.android.gms.tasks.Tasks
import java.time.LocalDate

/**
 * Central orchestration engine for the verification pipeline.
 */
class AgeVerificationEngine(val context: Context) : AutoCloseable {

    private val dobExtractor = DOBExtractor()
    private val faceMatcher  = FaceMatcher(context)
    private val tokenManager = TokenManager(context)

    // High-accuracy detector for the final matching step
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    override fun close() {
        faceMatcher.close()
        dobExtractor.close()
        detector.close()
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
     * Step 4: Match live face against ID face crop with "Beard/Moustache" tolerance.
     */
    suspend fun matchFaces(liveFace: Bitmap, idImage: Bitmap): FaceMatchStepResult {
        // Detect and crop face from live capture
        val liveFaceCrop = detectAndCropFace(liveFace) ?: liveFace

        // Detect and crop face from ID scan
        val idFaceCrop = detectAndCropFace(idImage) ?: idImage

        val result = faceMatcher.match(liveFaceCrop, idFaceCrop)

        // Clean up temporary crops if they were created
        if (liveFaceCrop != liveFace) liveFaceCrop.recycle()
        if (idFaceCrop != idImage) idFaceCrop.recycle()

        val statusMessage = when (result.status) {
            MatchStatus.SUCCESS -> "Face match confirmed (${(result.similarity * 100).toInt()}%)"
            MatchStatus.TENTATIVE -> "Match likely (${(result.similarity * 100).toInt()}%). Low quality ID or facial changes detected."
            MatchStatus.FAILED -> "Face does not match ID photo (${(result.similarity * 100).toInt()}%). Ensure your face is clearly visible."
        }

        return FaceMatchStepResult(
            similarity = result.similarity,
            passed = result.passed,
            message = statusMessage
        )
    }

    private suspend fun detectAndCropFace(bitmap: Bitmap): Bitmap? {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        return try {
            val faces = Tasks.await(detector.process(inputImage))
            if (faces.isNotEmpty()) {
                val face = faces.first()
                val bounds = face.boundingBox

                // Add padding (20%) around the face
                val paddingX = (bounds.width() * 0.2f).toInt()
                val paddingY = (bounds.height() * 0.2f).toInt()

                val left = (bounds.left - paddingX).coerceAtLeast(0)
                val top = (bounds.top - paddingY).coerceAtLeast(0)
                val right = (bounds.right + paddingX).coerceAtMost(bitmap.width)
                val bottom = (bounds.bottom + paddingY).coerceAtMost(bitmap.height)

                Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
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
