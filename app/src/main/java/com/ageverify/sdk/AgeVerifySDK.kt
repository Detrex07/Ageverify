package com.ageverify.sdk

import android.content.Context
import android.content.Intent
import com.ageverify.config.Jurisdiction
import com.ageverify.core.AgeVerificationEngine
import com.ageverify.ui.MainActivity

/**
 * AgeVerify Partner SDK
 *
 * This is the ONLY file partner apps interact with. Everything else is internal.
 *
 * Resolution logic (fully transparent to the partner):
 *
 *   checkAge(18)
 *     │
 *     ├─ Standalone AgeVerify app installed?
 *     │    └─ Yes → query Content Provider → return result if token valid
 *     │
 *     └─ No standalone app / no token there?
 *          └─ Check local in-app token → return result
 *
 *   If neither source has a valid token → NotVerified → launchVerification()
 */
class AgeVerifySDK(
    private val context: Context,
    private val trustedStandaloneFingerprint: String? = null
) {

    private val engine = AgeVerificationEngine(context)

    private val bridge = TokenBridge(
        context = context,
        engine = engine,
        trustedStandaloneFingerprint = trustedStandaloneFingerprint
    )

    suspend fun checkAge(requiredAge: Int): AgeCheckResult {
        return when (val result = bridge.resolve(requiredAge)) {
            is BridgeResult.Verified -> AgeCheckResult.Verified(
                jwt = result.token.jwt,
                jurisdiction = result.token.jurisdiction,
                minimumAgeVerified = result.token.minimumAgeVerified,
                expiresAt = result.token.expiresAt.toString(),
                source = result.source.name
            )
            is BridgeResult.NotVerified -> AgeCheckResult.NotVerified
            is BridgeResult.InsufficientAge -> AgeCheckResult.InsufficientAge(
                verifiedAge = result.verifiedAge,
                requiredAge = result.requiredAge
            )
        }
    }

    fun launchVerification(
        activityContext: Context,
        jurisdiction: Jurisdiction = Jurisdiction.GLOBAL_DEFAULT,
        requireFaceMatch: Boolean = true
    ) {
        if (ProviderSecurity.isStandaloneAppInstalled(activityContext)) {
            launchStandaloneApp(activityContext, jurisdiction, requireFaceMatch)
        } else {
            launchInAppFlow(activityContext, jurisdiction, requireFaceMatch)
        }
    }

    suspend fun checkAgeForCountry(countryCode: String): AgeCheckResult {
        val jurisdiction = Jurisdiction.fromCountryCode(countryCode)
        return checkAge(jurisdiction.minimumAge)
    }

    suspend fun isVerified(): Boolean = bridge.resolve(0) is BridgeResult.Verified

    fun isStandaloneAppAvailable(): Boolean =
        ProviderSecurity.isStandaloneAppInstalled(context)

    fun clearLocalVerification() = engine.clearVerification()

    private fun launchStandaloneApp(
        context: Context,
        jurisdiction: Jurisdiction,
        requireFaceMatch: Boolean
    ) {
        val intent = Intent(ACTION_VERIFY_IN_STANDALONE).apply {
            setPackage(BridgeProtocol.STANDALONE_PACKAGE)
            putExtra(EXTRA_JURISDICTION_CODE, jurisdiction.countryCode)
            putExtra(EXTRA_MIN_AGE, jurisdiction.minimumAge)
            putExtra(EXTRA_REQUIRE_FACE_MATCH, requireFaceMatch)
            putExtra(EXTRA_CALLER_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            launchInAppFlow(context, jurisdiction, requireFaceMatch)
        }
    }

    private fun launchInAppFlow(
        context: Context,
        jurisdiction: Jurisdiction,
        requireFaceMatch: Boolean
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_JURISDICTION_CODE, jurisdiction.countryCode)
            putExtra(EXTRA_MIN_AGE, jurisdiction.minimumAge)
            putExtra(EXTRA_REQUIRE_FACE_MATCH, requireFaceMatch)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        const val EXTRA_JURISDICTION_CODE = "jurisdiction_code"
        const val EXTRA_MIN_AGE = "min_age"
        const val EXTRA_REQUIRE_FACE_MATCH = "require_face_match"
        const val EXTRA_CALLER_PACKAGE = "caller_package"
        const val ACTION_VERIFY_IN_STANDALONE = "com.ageverify.action.VERIFY"
    }
}

sealed class AgeCheckResult {
    data class Verified(
        val jwt: String,
        val jurisdiction: String,
        val minimumAgeVerified: Int,
        val expiresAt: String,
        val source: String = "IN_APP"
    ) : AgeCheckResult()

    object NotVerified : AgeCheckResult()

    data class InsufficientAge(
        val verifiedAge: Int,
        val requiredAge: Int
    ) : AgeCheckResult()
}
