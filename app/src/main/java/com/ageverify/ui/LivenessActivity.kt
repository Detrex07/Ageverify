package com.ageverify.ui

import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ageverify.R
import com.ageverify.core.FaceFrameResult
import com.ageverify.core.LivenessChallenge
import com.ageverify.core.LivenessDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LivenessActivity : AppCompatActivity() {

    private lateinit var vm: VerificationViewModel

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var tvChallengeLabel: TextView
    private lateinit var tvChallenge: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvTimer: TextView
    private lateinit var timerProgress: View
    private lateinit var btnBack: TextView

    // Core
    private val detector = LivenessDetector()
    private lateinit var cameraExecutor: ExecutorService

    // State
    private var challenges = listOf<LivenessChallenge>()
    private var currentChallengeIndex = 0
    private var isProcessingFrame = false
    private var challengeCompleted = false
    private var timeoutTimer: CountDownTimer? = null
    private val totalTimeMs = 30_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_liveness)

        // Get the shared ViewModel — already initialised by MainActivity
        vm = AppViewModelStore.get(this)

        bindViews()
        setupClicks()

        cameraExecutor = Executors.newSingleThreadExecutor()
        challenges = detector.randomChallengeSequence()

        startCamera()
        showCurrentChallenge()
        startTimeout()
    }

    private fun bindViews() {
        previewView      = findViewById(R.id.previewView)
        tvChallengeLabel = findViewById(R.id.tvChallengeLabel)
        tvChallenge      = findViewById(R.id.tvChallenge)
        tvProgress       = findViewById(R.id.tvProgress)
        tvTimer          = findViewById(R.id.tvTimer)
        timerProgress    = findViewById(R.id.timerProgress)
        btnBack          = findViewById(R.id.btnBack)
    }

    private fun setupClicks() {
        btnBack.setOnClickListener {
            vm.onLivenessFailed(FailureReason.LIVENESS_CANCELLED)
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(cameraExecutor) { proxy ->
                        if (!isProcessingFrame && !challengeCompleted) processFrame(proxy)
                        else proxy.close()
                    }
                }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                runOnUiThread { showToast("Camera failed: ${e.message}") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(proxy: ImageProxy) {
        isProcessingFrame = true
        val bitmap = proxy.toBitmap()
        proxy.close()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { detector.analyseFrame(bitmap) }
            withContext(Dispatchers.Main) {
                handleResult(result, bitmap)
                isProcessingFrame = false
            }
        }
    }

    private fun handleResult(result: FaceFrameResult, bitmap: android.graphics.Bitmap) {
        when (result) {
            is FaceFrameResult.NoFace -> {
                tvChallengeLabel.text = "LOOKING FOR FACE"
                tvChallenge.text = getString(R.string.liveness_no_face)
            }
            is FaceFrameResult.MultipleFaces -> {
                tvChallengeLabel.text = "TOO MANY FACES"
                tvChallenge.text = getString(R.string.liveness_multiple_faces)
            }
            is FaceFrameResult.FaceDetected -> {
                val challenge = challenges[currentChallengeIndex]
                tvChallengeLabel.text = "NOW:"
                tvChallenge.text = challenge.instruction
                if (detector.isChallengeComplete(challenge, result)) {
                    onChallengeCompleted(bitmap)
                }
            }
        }
    }

    private fun onChallengeCompleted(bitmap: android.graphics.Bitmap) {
        currentChallengeIndex++
        if (currentChallengeIndex >= challenges.size) {
            // All challenges passed
            challengeCompleted = true
            timeoutTimer?.cancel()

            // Write to ViewModel — the single point of truth
            vm.onLivenessComplete(bitmap)

            runOnUiThread {
                tvChallengeLabel.text = "DONE"
                tvChallenge.text = getString(R.string.liveness_success)
                tvChallenge.setTextColor(ContextCompat.getColor(this, R.color.success))
                tvChallenge.postDelayed({
                    setResult(Activity.RESULT_OK)
                    finish()
                }, 800)
            }
        } else {
            runOnUiThread {
                tvProgress.text = getString(
                    R.string.liveness_progress,
                    currentChallengeIndex + 1,
                    challenges.size
                )
                tvChallenge.text = challenges[currentChallengeIndex].instruction
            }
        }
    }

    private fun showCurrentChallenge() {
        if (currentChallengeIndex < challenges.size) {
            tvChallenge.text = challenges[currentChallengeIndex].instruction
            tvProgress.text = getString(R.string.liveness_progress, 1, challenges.size)
        }
    }

    private fun startTimeout() {
        timeoutTimer = object : CountDownTimer(totalTimeMs, 100) {
            override fun onTick(remaining: Long) {
                tvTimer.text = "${remaining / 1000}s"
                timerProgress.scaleX = remaining.toFloat() / totalTimeMs.toFloat()
                timerProgress.pivotX = 0f
                if (remaining / 1000 <= 10) {
                    timerProgress.setBackgroundColor(
                        ContextCompat.getColor(this@LivenessActivity, R.color.amber))
                    tvTimer.setTextColor(
                        ContextCompat.getColor(this@LivenessActivity, R.color.amber))
                }
            }
            override fun onFinish() {
                if (!challengeCompleted) {
                    vm.onLivenessFailed(FailureReason.LIVENESS_TIMEOUT)
                    tvChallenge.text = getString(R.string.liveness_timeout)
                    tvChallenge.setTextColor(ContextCompat.getColor(
                        this@LivenessActivity, R.color.danger))
                    tvChallenge.postDelayed({
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }, 1500)
                }
            }
        }.start()
    }

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        timeoutTimer?.cancel()
    }
}
