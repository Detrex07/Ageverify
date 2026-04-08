package com.ageverify.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ageverify.R
import com.ageverify.config.Jurisdiction
import com.ageverify.config.VerificationConfig
import com.ageverify.config.VerificationConfigResolver
import com.ageverify.sdk.AgeVerifySDK
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var vm: VerificationViewModel

    // Views
    private lateinit var layoutVerified: View
    private lateinit var layoutNotVerified: View
    private lateinit var tvAgeConfirmed: TextView
    private lateinit var tvJurisdiction: TextView
    private lateinit var tvExpiresAt: TextView
    private lateinit var btnStart: TextView
    private lateinit var btnReverify: TextView
    private lateinit var step1Check: TextView
    private lateinit var step2Check: TextView
    private lateinit var step3Check: TextView
    private lateinit var step1Label: TextView
    private lateinit var step2Label: TextView
    private lateinit var step3Label: TextView
    private lateinit var step1Number: TextView
    private lateinit var step2Number: TextView
    private lateinit var step3Number: TextView

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchLiveness()
        else {
            vm.onLivenessFailed(FailureReason.CAMERA_PERMISSION_DENIED)
            showToast(getString(R.string.error_camera_permission))
        }
    }

    private val livenessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            markStepDone(1)
            startActivity(Intent(this, IDScanActivity::class.java))
        }
        // If cancelled, ViewModel already updated by LivenessActivity
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val config = resolveConfig()
        vm = AppViewModelStore.get(this, config)

        bindViews()
        setupSteps()
        setupClicks()
        observeState()

        // If already verified, show credential card inline — skip full flow
        if (vm.isAlreadyVerified()) {
            showVerifiedState()
        } else {
            showNotVerifiedState()
        }
    }

    private fun bindViews() {
        layoutVerified    = findViewById(R.id.layoutVerified)
        layoutNotVerified = findViewById(R.id.layoutNotVerified)
        tvAgeConfirmed    = findViewById(R.id.tvAgeConfirmed)
        tvJurisdiction    = findViewById(R.id.tvJurisdiction)
        tvExpiresAt       = findViewById(R.id.tvExpiresAt)
        btnStart          = findViewById(R.id.btnStart)
        btnReverify       = findViewById(R.id.btnReverify)

        val s1 = findViewById<View>(R.id.step1)
        val s2 = findViewById<View>(R.id.step2)
        val s3 = findViewById<View>(R.id.step3)
        step1Number = s1.findViewById(R.id.tvStepNumber)
        step2Number = s2.findViewById(R.id.tvStepNumber)
        step3Number = s3.findViewById(R.id.tvStepNumber)
        step1Label  = s1.findViewById(R.id.tvStepLabel)
        step2Label  = s2.findViewById(R.id.tvStepLabel)
        step3Label  = s3.findViewById(R.id.tvStepLabel)
        step1Check  = s1.findViewById(R.id.tvStepCheck)
        step2Check  = s2.findViewById(R.id.tvStepCheck)
        step3Check  = s3.findViewById(R.id.tvStepCheck)
    }

    private fun setupSteps() {
        step1Number.text = "1"; step1Label.text = getString(R.string.main_step_1)
        step2Number.text = "2"; step2Label.text = getString(R.string.main_step_2)
        step3Number.text = "3"; step3Label.text = getString(R.string.main_step_3)
    }

    private fun setupClicks() {
        btnStart.setOnClickListener { requestCameraAndStart() }
        btnReverify.setOnClickListener {
            vm.clearStoredVerification()
            vm.clearSession()
            resetSteps()
            showNotVerifiedState()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            vm.pipelineState.collect { state ->
                when (state) {
                    is PipelineState.IDPending  -> markStepDone(1)
                    is PipelineState.Processing -> markStepDone(2)
                    is PipelineState.Success -> {
                        markStepDone(3)
                        navigateToResult(success = true)
                    }
                    is PipelineState.AgeFail -> navigateToResult(ageCheckFailed = true)
                    is PipelineState.Error   -> showToast(state.message)
                    else -> { /* Idle / LivenessPending — no UI change */ }
                }
            }
        }
    }

    private fun requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            launchLiveness()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchLiveness() {
        vm.startSession()
        livenessLauncher.launch(Intent(this, LivenessActivity::class.java))
    }

    private fun showVerifiedState() {
        layoutVerified.visibility = View.VISIBLE
        layoutNotVerified.visibility = View.GONE
        vm.getExistingToken()?.let { token ->
            tvAgeConfirmed.text = "${token.minimumAgeVerified}+"
            tvJurisdiction.text = token.jurisdiction
            val fmt = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())
            tvExpiresAt.text = fmt.format(token.expiresAt)
        }
    }

    private fun showNotVerifiedState() {
        layoutVerified.visibility = View.GONE
        layoutNotVerified.visibility = View.VISIBLE
    }

    private fun markStepDone(step: Int) {
        val check = when (step) { 1 -> step1Check; 2 -> step2Check; else -> step3Check }
        check.visibility = View.VISIBLE
    }

    private fun resetSteps() {
        listOf(step1Check, step2Check, step3Check).forEach {
            it.visibility = View.INVISIBLE
        }
    }

    private fun navigateToResult(success: Boolean = false, ageCheckFailed: Boolean = false) {
        startActivity(Intent(this, ResultActivity::class.java).apply {
            putExtra("success", success)
            putExtra("age_check_failed", ageCheckFailed)
            putExtra("already_verified", false)
            putExtra("source", VerificationSessionRepository.tokenSource)
        })
        finish()
    }

    private fun resolveConfig(): VerificationConfig {
        return VerificationConfigResolver.fromInputs(
            jurisdictionCode = intent.getStringExtra(AgeVerifySDK.EXTRA_JURISDICTION_CODE),
            minAge = intent.getIntExtra(AgeVerifySDK.EXTRA_MIN_AGE, -1).takeIf { it > 0 },
            requireFaceMatch = intent.getBooleanExtra(
                AgeVerifySDK.EXTRA_REQUIRE_FACE_MATCH,
                true
            )
        )
    }

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
}
