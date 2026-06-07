package com.ageverify.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Liveness detection using ML Kit Face Detection.
 *
 * Challenge-response approach:
 * 1. Detect face present and centred
 * 2. Issue a random challenge (blink, turn left, turn right, smile)
 * 3. Verify the user completes the challenge within timeout
 * 4. Capture a clean frame upon challenge completion
 *
 * This defeats static photo spoofing without requiring depth sensors.
 * For higher assurance (deepfake resistance), hardware Face ID level
 * depth sensors would be needed — that's Phase 2+.
 */
class LivenessDetector {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.1f) // Even more forgiving for smaller faces
            .enableTracking()
            .build()
    )

    /**
     * Analyse a single camera frame.
     * @param bitmap The frame bitmap
     * @param rotation The rotation degrees (from CameraX ImageInfo)
     */
    suspend fun analyseFrame(bitmap: Bitmap, rotation: Int): FaceFrameResult =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, rotation)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    cont.resume(interpretFaces(faces))
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                }
        }

    private fun interpretFaces(faces: List<Face>): FaceFrameResult {
        if (faces.isEmpty()) return FaceFrameResult.NoFace
        if (faces.size > 1) return FaceFrameResult.MultipleFaces

        val face = faces.first()

        return FaceFrameResult.FaceDetected(
            leftEyeOpenProb = face.leftEyeOpenProbability ?: 1f,
            rightEyeOpenProb = face.rightEyeOpenProbability ?: 1f,
            smilingProb = face.smilingProbability ?: 0f,
            eulerY = face.headEulerAngleY,   // Left/right head rotation
            eulerX = face.headEulerAngleX,   // Up/down head tilt
            eulerZ = face.headEulerAngleZ,   // Roll
            boundingBox = face.boundingBox
        )
    }

    /**
     * Check if a challenge is satisfied given the current face reading.
     */
    fun isChallengeComplete(
        challenge: LivenessChallenge,
        result: FaceFrameResult.FaceDetected
    ): Boolean = when (challenge) {
        LivenessChallenge.BLINK ->
            result.leftEyeOpenProb < BLINK_THRESHOLD &&
            result.rightEyeOpenProb < BLINK_THRESHOLD

        LivenessChallenge.TURN_LEFT ->
            result.eulerY < -TURN_THRESHOLD_DEGREES

        LivenessChallenge.TURN_RIGHT ->
            result.eulerY > TURN_THRESHOLD_DEGREES

        LivenessChallenge.SMILE ->
            result.smilingProb > SMILE_THRESHOLD

        LivenessChallenge.LOOK_STRAIGHT ->
            Math.abs(result.eulerY) < 15f &&
            Math.abs(result.eulerX) < 15f
    }

    /**
     * Pick a random challenge sequence.
     * Using two challenges increases spoofing difficulty.
     */
    fun randomChallengeSequence(): List<LivenessChallenge> {
        val challenges = listOf(
            LivenessChallenge.BLINK,
            LivenessChallenge.TURN_LEFT,
            LivenessChallenge.TURN_RIGHT,
            LivenessChallenge.SMILE
        )
        return challenges.shuffled().take(2)
    }

    companion object {
        private const val BLINK_THRESHOLD = 0.35f        // More forgiving (was 0.25)
        private const val TURN_THRESHOLD_DEGREES = 12f   // Even easier to hit (was 20, then 15)
        private const val SMILE_THRESHOLD = 0.6f         // Slightly easier (was 0.7)
    }
}

enum class LivenessChallenge(val instruction: String) {
    BLINK("Please blink"),
    TURN_LEFT("Turn your head left"),
    TURN_RIGHT("Turn your head right"),
    SMILE("Please smile"),
    LOOK_STRAIGHT("Look straight at the camera")
}

sealed class FaceFrameResult {
    object NoFace : FaceFrameResult()
    object MultipleFaces : FaceFrameResult()
    data class FaceDetected(
        val leftEyeOpenProb: Float,
        val rightEyeOpenProb: Float,
        val smilingProb: Float,
        val eulerY: Float,
        val eulerX: Float,
        val eulerZ: Float,
        val boundingBox: android.graphics.Rect
    ) : FaceFrameResult()
}
