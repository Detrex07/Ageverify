package com.ageverify.ui

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for VerificationSession data class and VerificationSessionRepository.
 *
 * These are pure JVM tests — no Android runtime required.
 * Repository is a singleton so we reset it between tests.
 */
class VerificationSessionTest {

    @Before
    fun setUp() {
        VerificationSessionRepository.clear()
    }

    @After
    fun tearDown() {
        VerificationSessionRepository.clear()
    }

    // ── VerificationSession computed properties ───────────────────────────────

    @Test
    fun `fresh session has no captures`() {
        val session = VerificationSession()
        assertThat(session.hasLiveFace).isFalse()
        assertThat(session.hasIDScan).isFalse()
        assertThat(session.hasBothCaptures).isFalse()
    }

    @Test
    fun `session with liveness only does not have both captures`() {
        val session = VerificationSession(
            liveFaceBitmap = mockk(relaxed = true),
            livenessCompleted = true
        )
        assertThat(session.hasLiveFace).isTrue()
        assertThat(session.hasIDScan).isFalse()
        assertThat(session.hasBothCaptures).isFalse()
    }

    @Test
    fun `session with both captures reports hasBothCaptures true`() {
        val session = VerificationSession(
            liveFaceBitmap    = mockk(relaxed = true),
            livenessCompleted = true,
            idBitmap          = mockk(relaxed = true),
            idScanCompleted   = true
        )
        assertThat(session.hasBothCaptures).isTrue()
    }

    @Test
    fun `session is complete when token issued`() {
        val session = VerificationSession(tokenIssued = true)
        assertThat(session.isComplete).isTrue()
    }

    @Test
    fun `session is complete when failure reason set`() {
        val session = VerificationSession(failureReason = FailureReason.AGE_BELOW_THRESHOLD)
        assertThat(session.isComplete).isTrue()
    }

    @Test
    fun `fresh session is not complete`() {
        assertThat(VerificationSession().isComplete).isFalse()
    }

    @Test
    fun `hasLiveFace requires both bitmap and completed flag`() {
        // Bitmap present but flag false
        val session1 = VerificationSession(
            liveFaceBitmap    = mockk(relaxed = true),
            livenessCompleted = false
        )
        assertThat(session1.hasLiveFace).isFalse()

        // Flag true but no bitmap
        val session2 = VerificationSession(
            liveFaceBitmap    = null,
            livenessCompleted = true
        )
        assertThat(session2.hasLiveFace).isFalse()
    }

    // ── VerificationSessionRepository lifecycle ───────────────────────────────

    @Test
    fun `start creates a new session`() {
        assertThat(VerificationSessionRepository.currentSession).isNull()
        VerificationSessionRepository.start()
        assertThat(VerificationSessionRepository.currentSession).isNotNull()
    }

    @Test
    fun `clear removes the session`() {
        VerificationSessionRepository.start()
        VerificationSessionRepository.clear()
        assertThat(VerificationSessionRepository.currentSession).isNull()
    }

    @Test
    fun `start clears previous session first`() {
        VerificationSessionRepository.start()
        val first = VerificationSessionRepository.currentSession
        VerificationSessionRepository.start()
        val second = VerificationSessionRepository.currentSession
        // Different objects
        assertThat(second).isNotSameInstanceAs(first)
    }

    @Test
    fun `setLivenessCapture updates session correctly`() {
        VerificationSessionRepository.start()
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        VerificationSessionRepository.setLivenessCapture(bitmap)

        val session = VerificationSessionRepository.currentSession!!
        assertThat(session.livenessCompleted).isTrue()
        assertThat(session.liveFaceBitmap).isSameInstanceAs(bitmap)
    }

    @Test
    fun `setIDCapture updates session correctly`() {
        VerificationSessionRepository.start()
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        VerificationSessionRepository.setIDCapture(bitmap)

        val session = VerificationSessionRepository.currentSession!!
        assertThat(session.idScanCompleted).isTrue()
        assertThat(session.idBitmap).isSameInstanceAs(bitmap)
    }

    @Test
    fun `setAgeExtractionResult updates extracted age`() {
        VerificationSessionRepository.start()
        VerificationSessionRepository.setAgeExtractionResult(25, "raw text")

        val session = VerificationSessionRepository.currentSession!!
        assertThat(session.extractedAge).isEqualTo(25)
        assertThat(session.dobRawText).isEqualTo("raw text")
    }

    @Test
    fun `setFaceMatchResult updates match fields`() {
        VerificationSessionRepository.start()
        VerificationSessionRepository.setFaceMatchResult(0.87f, true)

        val session = VerificationSessionRepository.currentSession!!
        assertThat(session.faceMatchScore).isWithin(0.001f).of(0.87f)
        assertThat(session.faceMatchPassed).isTrue()
    }

    @Test
    fun `setTokenIssued marks token and sets source`() {
        VerificationSessionRepository.start()
        VerificationSessionRepository.setTokenIssued("STANDALONE_APP")

        val session = VerificationSessionRepository.currentSession!!
        assertThat(session.tokenIssued).isTrue()
        assertThat(session.tokenSource).isEqualTo("STANDALONE_APP")
        assertThat(session.tokenIssuedAt).isNotNull()
    }

    @Test
    fun `setFailure records failure reason`() {
        VerificationSessionRepository.start()
        VerificationSessionRepository.setFailure(FailureReason.FACE_MATCH_FAILED)

        val session = VerificationSessionRepository.currentSession!!
        assertThat(session.failureReason).isEqualTo(FailureReason.FACE_MATCH_FAILED)
    }

    @Test
    fun `setLivenessCapture with no active session does not crash`() {
        // No session started — should log warning and return gracefully
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        VerificationSessionRepository.setLivenessCapture(bitmap) // must not throw
    }

    @Test
    fun `tokenSource defaults to IN_APP when no session`() {
        assertThat(VerificationSessionRepository.tokenSource).isEqualTo("IN_APP")
    }

    @Test
    fun `hasBothCaptures convenience property reflects session state`() {
        assertThat(VerificationSessionRepository.hasBothCaptures).isFalse()
        VerificationSessionRepository.start()
        assertThat(VerificationSessionRepository.hasBothCaptures).isFalse()

        val bm = mockk<android.graphics.Bitmap>(relaxed = true)
        VerificationSessionRepository.setLivenessCapture(bm)
        assertThat(VerificationSessionRepository.hasBothCaptures).isFalse()

        VerificationSessionRepository.setIDCapture(bm)
        assertThat(VerificationSessionRepository.hasBothCaptures).isTrue()
    }
}
