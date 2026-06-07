package com.ageverify.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ageverify.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class IDScanActivity : AppCompatActivity() {

    private lateinit var vm: VerificationViewModel

    // Views
    private lateinit var previewViewID: PreviewView
    private lateinit var btnCapture: View
    private lateinit var tvProcessing: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnFlash: TextView
    private lateinit var idGuide: View
    private lateinit var viewScanLine: View

    // Camera
    private lateinit var cameraExecutor: ExecutorService
    private var camera: androidx.camera.core.Camera? = null
    private var imageCapture: ImageCapture? = null
    private var isFlashOn = false
    private var isCapturing = false
    private val dobExtractor = com.ageverify.core.DOBExtractor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_id_scan)

        // Get shared ViewModel - already initialised by MainActivity
        vm = AppViewModelStore.get(this)

        bindViews()
        setupClicks()

        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
        startScanLineAnimation()
    }

    private fun startScanLineAnimation() {
        val parent = idGuide.parent as View
        parent.post {
            val startY = idGuide.top.toFloat()
            val endY = idGuide.bottom.toFloat()

            android.animation.ObjectAnimator.ofFloat(viewScanLine, View.TRANSLATION_Y, 0f, endY - startY).apply {
                duration = 2000
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun bindViews() {
        previewViewID = findViewById(R.id.previewViewID)
        btnCapture    = findViewById(R.id.btnCapture)
        tvProcessing  = findViewById(R.id.tvProcessing)
        btnBack       = findViewById(R.id.btnBack)
        btnFlash      = findViewById(R.id.btnFlash)
        idGuide       = findViewById(R.id.idGuide)
        viewScanLine  = findViewById(R.id.viewScanLine)
    }

    private fun setupClicks() {
        btnBack.setOnClickListener {
            vm.onIDScanCancelled()
            setResult(RESULT_CANCELED)
            finish()
        }
        btnCapture.setOnClickListener {
            if (!isCapturing) captureID()
        }
        btnFlash.setOnClickListener {
            toggleFlash()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewViewID.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply {
                    setAnalyzer(cameraExecutor) { proxy ->
                        if (!isCapturing) {
                            analyzeFrame(proxy)
                        } else {
                            proxy.close()
                        }
                    }
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture!!
                )
            } catch (e: Exception) {
                showToast("Camera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(proxy: ImageProxy) {
        val bitmap = proxy.toBitmap()
        proxy.close()

        lifecycleScope.launch(Dispatchers.Default) {
            val result = dobExtractor.extractDOB(bitmap)
            if (result is com.ageverify.core.DOBResult.Success) {
                // Auto-capture triggered by valid DOB detection
                withContext(Dispatchers.Main) {
                    if (!isCapturing) {
                        isCapturing = true
                        tvProcessing.visibility = View.VISIBLE
                        btnCapture.isEnabled = false
                        showToast("ID detected! Processing...")
                        onCaptureSuccess(bitmap)
                    }
                }
            }
        }
    }

    private fun toggleFlash() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            isFlashOn = !isFlashOn
            cam.cameraControl.enableTorch(isFlashOn)

            // Update UI
            btnFlash.text = if (isFlashOn) "ON" else "FL"
            btnFlash.setTextColor(
                ContextCompat.getColor(this, if (isFlashOn) R.color.electric else R.color.text_muted)
            )
        } else {
            showToast("Flash not available")
        }
    }

    private fun captureID() {
        val capture = imageCapture ?: return
        isCapturing = true
        btnCapture.isEnabled = false
        tvProcessing.visibility = View.VISIBLE

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    image.close()
                    onCaptureSuccess(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    tvProcessing.visibility = View.GONE
                    btnCapture.isEnabled = true
                    showToast("Capture failed - try again")
                }
            }
        )
    }

    private fun onCaptureSuccess(bitmap: android.graphics.Bitmap) {
        vm.onIDScanComplete(bitmap)
        setResult(RESULT_OK)
        finish()
    }

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        dobExtractor.close()
    }
}
