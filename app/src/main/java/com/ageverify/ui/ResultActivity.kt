package com.ageverify.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ageverify.R
import com.ageverify.core.TokenManager
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ResultActivity : AppCompatActivity() {

    private lateinit var tvStatusIcon: TextView
    private lateinit var tvResultTitle: TextView
    private lateinit var tvResultSubtitle: TextView
    private lateinit var cardCredential: View
    private lateinit var tvDetailAge: TextView
    private lateinit var tvDetailJurisdiction: TextView
    private lateinit var tvDetailExpires: TextView
    private lateinit var tvTokenPreview: TextView
    private lateinit var tvSourceBadge: TextView
    private lateinit var btnPrimary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        bindViews()

        val success        = intent.getBooleanExtra("success", false)
        val ageCheckFailed = intent.getBooleanExtra("age_check_failed", false)
        val alreadyVerified= intent.getBooleanExtra("already_verified", false)

        // Source comes from the session repository — not from Intent extras —
        // so it reflects the actual source even if the activity was recreated
        val source = VerificationSessionRepository.tokenSource
            .takeIf { it.isNotBlank() } ?: intent.getStringExtra("source") ?: "IN_APP"

        when {
            alreadyVerified || success -> showSuccess(source)
            ageCheckFailed             -> showAgeFail()
            else                       -> showError()
        }

        setupButton(ageCheckFailed)

        // Session is complete — release bitmaps immediately
        // Keep the repository reference for source/metadata but clear images
        if (success || alreadyVerified || ageCheckFailed) {
            VerificationSessionRepository.currentSession?.let { session ->
                session.liveFaceBitmap?.recycle()
                session.idBitmap?.recycle()
            }
        }
    }

    private fun bindViews() {
        tvStatusIcon         = findViewById(R.id.tvStatusIcon)
        tvResultTitle        = findViewById(R.id.tvResultTitle)
        tvResultSubtitle     = findViewById(R.id.tvResultSubtitle)
        cardCredential       = findViewById(R.id.cardCredential)
        tvDetailAge          = findViewById(R.id.tvDetailAge)
        tvDetailJurisdiction = findViewById(R.id.tvDetailJurisdiction)
        tvDetailExpires      = findViewById(R.id.tvDetailExpires)
        tvTokenPreview       = findViewById(R.id.tvTokenPreview)
        tvSourceBadge        = findViewById(R.id.tvSourceBadge)
        btnPrimary           = findViewById(R.id.btnPrimary)
    }

    private fun showSuccess(source: String) {
        tvStatusIcon.text = "✓"
        tvStatusIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_card_success)
        tvStatusIcon.setTextColor(ContextCompat.getColor(this, R.color.success))
        tvResultTitle.text = getString(R.string.result_success_title)
        tvResultSubtitle.text = getString(R.string.result_success_subtitle)
        populateCredential(source)
    }

    private fun showAgeFail() {
        tvStatusIcon.text = "✕"
        tvStatusIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_card_error)
        tvStatusIcon.setTextColor(ContextCompat.getColor(this, R.color.danger))
        tvResultTitle.text = getString(R.string.result_fail_age_title)
        tvResultTitle.setTextColor(ContextCompat.getColor(this, R.color.danger))

        // Pull extracted age from session if available
        val extracted = VerificationSessionRepository.currentSession?.extractedAge ?: 0
        tvResultSubtitle.text = getString(R.string.result_fail_age_subtitle, extracted)

        cardCredential.visibility = View.GONE
        tvSourceBadge.visibility = View.GONE
    }

    private fun showError() {
        tvStatusIcon.text = "!"
        tvStatusIcon.background = ContextCompat.getDrawable(this, R.drawable.bg_card)
        tvStatusIcon.setTextColor(ContextCompat.getColor(this, R.color.amber))
        tvResultTitle.text = getString(R.string.result_error_title)
        tvResultSubtitle.text = getString(R.string.result_error_subtitle)
        cardCredential.visibility = View.GONE
        tvSourceBadge.visibility = View.GONE
        btnPrimary.text = getString(R.string.result_retry)
        btnPrimary.setBackgroundResource(R.drawable.bg_btn_secondary)
        btnPrimary.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
    }

    private fun populateCredential(source: String) {
        val token = TokenManager(this).getValidToken() ?: return
        cardCredential.visibility = View.VISIBLE
        tvSourceBadge.visibility = View.VISIBLE

        tvDetailAge.text = "${token.minimumAgeVerified}+"
        tvDetailJurisdiction.text = token.jurisdiction
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())
        tvDetailExpires.text = fmt.format(token.expiresAt)
        tvTokenPreview.text = if (token.jwt.length > 40) token.jwt.take(40) + "…" else token.jwt

        tvSourceBadge.text = when (source) {
            "STANDALONE_APP" -> "✓  Verified via AgeVerify app"
            else             -> "✓  Verified in this app"
        }
    }

    private fun setupButton(ageCheckFailed: Boolean) {
        btnPrimary.setOnClickListener {
            if (ageCheckFailed) {
                // Terminal — nothing to return to
                AppViewModelStore.clear()
                finishAffinity()
            } else {
                AppViewModelStore.clear()
                setResult(RESULT_OK)
                finish()
            }
        }
    }
}
