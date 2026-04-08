package com.ageverify.ui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.ageverify.config.VerificationConfig
import com.ageverify.config.Jurisdiction
import com.ageverify.core.AgeVerificationEngine

/**
 * Tests for PipelineState sealed class and FailureReason message mapping.
 *
 * ViewModel tests require a coroutine test dispatcher. We test:
 * - Initial state is Idle
 * - startSession() transitions to LivenessPending
 * - onLivenessComplete() transitions to IDPending
 * - onLivenessFailed() transitions to Error with correct reason
 * - onIDScanCancelled() returns to IDPending (allows retry)
 * - All FailureReason values have non-empty user messages
 * - PipelineState type hierarchy is exhaustive
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineStateTest {

    private lateinit var mockEngine: AgeVerificationEngine
    private lateinit var vm: VerificationViewModel

    @Before
    fun setUp() {
        mockEngine = mockk(relaxed = true)
        every { mockEngine.isAlreadyVerified() } returns false
        vm = VerificationViewModel(
            engine = mockEngine,
            config = VerificationConfig(jurisdiction = Jurisdiction.INDIA)
        )
        VerificationSessionRepository.clear()
    }

    @After
    fun tearDown() {
        VerificationSessionRepository.clear()
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `initial pipeline state is Idle`() = runTest {
        assertThat(vm.pipelineState.value).isInstanceOf(PipelineState.Idle::class.java)
    }

    // ── startSession ──────────────────────────────────────────────────────────

    @Test
    fun `startSession transitions to LivenessPending`() = runTest {
        vm.startSession()
        assertThat(vm.pipelineState.value)
            .isInstanceOf(PipelineState.LivenessPending::class.java)
    }

    @Test
    fun `startSession creates a new repository session`() = runTest {
        assertThat(VerificationSessionRepository.currentSession).isNull()
        vm.startSession()
        assertThat(VerificationSessionRepository.currentSession).isNotNull()
    }

    // ── Liveness ──────────────────────────────────────────────────────────────

    @Test
    fun `onLivenessComplete transitions to IDPending`() = runTest {
        vm.startSession()
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        vm.onLivenessComplete(bitmap)
        assertThat(vm.pipelineState.value)
            .isInstanceOf(PipelineState.IDPending::class.java)
    }

    @Test
    fun `onLivenessComplete stores bitmap in repository`() = runTest {
        vm.startSession()
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        vm.onLivenessComplete(bitmap)
        assertThat(VerificationSessionRepository.liveFace)
            .isSameInstanceAs(bitmap)
    }

    @Test
    fun `onLivenessFailed transitions to Error state`() = runTest {
        vm.startSession()
        vm.onLivenessFailed(FailureReason.LIVENESS_TIMEOUT)
        val state = vm.pipelineState.value
        assertThat(state).isInstanceOf(PipelineState.Error::class.java)
        assertThat((state as PipelineState.Error).reason)
            .isEqualTo(FailureReason.LIVENESS_TIMEOUT)
    }

    @Test
    fun `onLivenessFailed with CANCELLED produces Error state`() = runTest {
        vm.startSession()
        vm.onLivenessFailed(FailureReason.LIVENESS_CANCELLED)
        assertThat(vm.pipelineState.value)
            .isInstanceOf(PipelineState.Error::class.java)
    }

    // ── ID Scan ───────────────────────────────────────────────────────────────

    @Test
    fun `onIDScanCancelled returns to IDPending to allow retry`() = runTest {
        vm.startSession()
        val bitmap = mockk<android.graphics.Bitmap>(relaxed = true)
        vm.onLivenessComplete(bitmap)
        vm.onIDScanCancelled()
        // Should be IDPending (retry allowed), not Error
        assertThat(vm.pipelineState.value)
            .isInstanceOf(PipelineState.IDPending::class.java)
    }

    // ── clearSession ──────────────────────────────────────────────────────────

    @Test
    fun `clearSession resets to Idle`() = runTest {
        vm.startSession()
        vm.clearSession()
        assertThat(vm.pipelineState.value).isInstanceOf(PipelineState.Idle::class.java)
    }

    @Test
    fun `clearSession clears repository`() = runTest {
        vm.startSession()
        vm.clearSession()
        assertThat(VerificationSessionRepository.currentSession).isNull()
    }

    // ── FailureReason messages ────────────────────────────────────────────────

    @Test
    fun `all FailureReason values have non-empty user messages`() {
        for (reason in FailureReason.values()) {
            val message = reason.toUserMessage()
            assertWithMessage("${reason.name} should have a user message").that(message)
                .isNotEmpty()
            assertWithMessage("${reason.name} message should be meaningful")
                .that(message.length)
                .isGreaterThan(5)
        }
    }

    @Test
    fun `LIVENESS_TIMEOUT message mentions timeout or retry`() {
        val msg = FailureReason.LIVENESS_TIMEOUT.toUserMessage().lowercase()
        assertThat(msg.contains("timed out") || msg.contains("timeout") || msg.contains("try again"))
            .isTrue()
    }

    @Test
    fun `DOB_NOT_FOUND message mentions ID or date of birth`() {
        val msg = FailureReason.DOB_NOT_FOUND.toUserMessage().lowercase()
        assertThat(msg.contains("date") || msg.contains("id") || msg.contains("birth"))
            .isTrue()
    }

    @Test
    fun `FACE_MATCH_FAILED message mentions face or photo`() {
        val msg = FailureReason.FACE_MATCH_FAILED.toUserMessage().lowercase()
        assertThat(msg.contains("face") || msg.contains("photo") || msg.contains("match"))
            .isTrue()
    }

    // ── PipelineState sealed class completeness ───────────────────────────────

    @Test
    fun `PipelineState Success holds token`() {
        val mockToken = mockk<com.ageverify.core.VerificationToken>(relaxed = true)
        val state = PipelineState.Success(token = mockToken)
        assertThat(state.token).isSameInstanceAs(mockToken)
    }

    @Test
    fun `PipelineState AgeFail holds extracted and required ages`() {
        val state = PipelineState.AgeFail(extractedAge = 16, requiredAge = 18)
        assertThat(state.extractedAge).isEqualTo(16)
        assertThat(state.requiredAge).isEqualTo(18)
    }

    @Test
    fun `PipelineState Error holds reason and message`() {
        val state = PipelineState.Error(
            reason = FailureReason.FACE_MATCH_FAILED,
            message = "Face does not match"
        )
        assertThat(state.reason).isEqualTo(FailureReason.FACE_MATCH_FAILED)
        assertThat(state.message).isEqualTo("Face does not match")
    }

    @Test
    fun `when expression over PipelineState is exhaustive`() {
        // If a new state is added without updating this test, the when
        // expression below will fail to compile — keeping the test in sync.
        val states: List<PipelineState> = listOf(
            PipelineState.Idle,
            PipelineState.LivenessPending,
            PipelineState.IDPending,
            PipelineState.Processing,
            PipelineState.Success(mockk(relaxed = true)),
            PipelineState.AgeFail(16, 18),
            PipelineState.Error(FailureReason.UNKNOWN, "msg")
        )

        for (state in states) {
            // This when expression must handle all sealed subtypes
            val label = when (state) {
                is PipelineState.Idle             -> "idle"
                is PipelineState.LivenessPending  -> "liveness"
                is PipelineState.IDPending        -> "id"
                is PipelineState.Processing       -> "processing"
                is PipelineState.Success          -> "success"
                is PipelineState.AgeFail          -> "age_fail"
                is PipelineState.Error            -> "error"
            }
            assertThat(label).isNotEmpty()
        }
    }
}
