package com.ageverify.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.ageverify.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class IDScanActivity : AppCompatActivity() {

    private lateinit var vm: VerificationViewModel

    // Views
    private lateinit var previewViewID: PreviewView
    private lateinit var btnCapture: View
    private lateinit var tvProcessing: TextView
    private lateinit var btnBack: TextView
    private lateinit var idGuide: View

    // Camera
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var isCapturing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_id_scan)

        // Get shared ViewModel — already initialised by MainActivity
        vm = AppViewModelStore.get(this)

        bindViews()
        setupClicks()

        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
    }

    private fun bindViews() {
        previewViewID = findViewById(R.id.previewViewID)
        btnCapture    = findViewById(R.id.btnCapture)
        tvProcessing  = findViewById(R.id.tvProcessing)
        btnBack       = findViewById(R.id.btnBack)
        idGuide       = findViewById(R.id.idGuide)
    }

    private fun setupClicks() {
        btnBack.setOnClickListener {
            vm.onIDScanCancelled()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        btnCapture.setOnClickListener {
            if (!isCapturing) captureID()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewViewID.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture!!
                )
            } catch (e: Exception) {
                showToast("Camera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureID() {
        val capture = imageCapture ?: return
        isCapturing = true
        btnCapture.isEnabled = false
        tvProcessing.visibility = View.VISIBLE
        idGuide.alpha = 0.4f
        idGuide.animate().alpha(1f).setDuration(300).start()

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    image.close()

                    // Write to ViewModel — no more companion object
                    vm.onIDScanComplete(bitmap)

                    // Return to MainActivity which is observing pipeline state
                    setResult(Activity.RESULT_OK)
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    tvProcessing.visibility = View.GONE
                    btnCapture.isEnabled = true
                    showToast("Capture failed — try again")
                }
            }
        )
    }

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
