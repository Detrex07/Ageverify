package com.ageverify.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * ProviderSecurity
 *
 * Verifies that the app querying the Content Provider is a legitimate
 * partner app, not a malicious app trying to steal age tokens.
 *
 * TWO layers of protection:
 *
 * Layer 1 — Android Permission System
 *   The Content Provider declares READ_PERMISSION as readPermission.
 *   Any app without that permission in their manifest gets an instant
 *   SecurityException from Android OS before our code even runs.
 *
 * Layer 2 — Allowlist (Optional, for high-assurance deployments)
 *   Provider can optionally check the calling app's signing certificate
 *   against a list of trusted SHA-256 fingerprints. This means even if
 *   a malicious app declares the permission, it fails the cert check.
 *
 * Used by:
 * - AgeVerifyContentProvider (standalone app) — validates incoming callers
 * - TokenBridge (SDK in partner apps) — validates the standalone app before trusting its token
 */
object ProviderSecurity {

    private const val TAG = "ProviderSecurity"

    /**
     * Verify that the standalone AgeVerify app is installed and
     * its signing certificate matches the expected fingerprint.
     *
     * This prevents a fake "AgeVerify" app from returning false tokens.
     *
     * @param context Partner app context
     * @param expectedFingerprint SHA-256 hex fingerprint of AgeVerify's signing cert.
     *                            Pass null to skip cert check (development only).
     */
    fun isStandaloneAppTrusted(
        context: Context,
        expectedFingerprint: String? = null
    ): Boolean {
        return try {
            val pm = context.packageManager

            // Check the package exists
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    BridgeProtocol.STANDALONE_PACKAGE,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    BridgeProtocol.STANDALONE_PACKAGE,
                    PackageManager.GET_SIGNATURES
                )
            }

            // If no fingerprint provided, just confirm package exists
            if (expectedFingerprint == null) {
                Log.w(TAG, "Skipping cert check — development mode only")
                return true
            }

            // Verify signing certificate
            val fingerprint = getSigningFingerprint(context, BridgeProtocol.STANDALONE_PACKAGE)
            val trusted = fingerprint?.equals(expectedFingerprint, ignoreCase = true) == true

            if (!trusted) {
                Log.e(TAG,
                    "AgeVerify app fingerprint mismatch. " +
                    "Expected: $expectedFingerprint Got: $fingerprint — " +
                    "Possible fake app installed."
                )
            }

            trusted
        } catch (e: PackageManager.NameNotFoundException) {
            // App not installed
            false
        }
    }

    /**
     * Verify the calling app (the one querying our provider) is
     * an allowed partner. Called from within the Content Provider.
     *
     * @param context Provider's context
     * @param callingUid UID of the calling process (from Binder.getCallingUid())
     * @param allowedFingerprints Optional set of trusted cert fingerprints.
     *                             If empty, any app with the permission is allowed.
     */
    fun isCallerTrusted(
        context: Context,
        callingUid: Int,
        allowedFingerprints: Set<String> = emptySet()
    ): Boolean {
        // Get package names for this UID
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(callingUid) ?: return false

        // If no allowlist, any app with the permission can proceed
        // (Android already enforced READ_PERMISSION before this point)
        if (allowedFingerprints.isEmpty()) return true

        // Check if any of the caller's packages have a trusted fingerprint
        return packages.any { pkg ->
            val fingerprint = getSigningFingerprint(context, pkg)
            fingerprint != null && allowedFingerprints.contains(fingerprint.uppercase())
        }
    }

    /**
     * Get SHA-256 fingerprint of the signing certificate for a package.
     * Returns uppercase hex string, e.g. "A1:B2:C3:..."
     */
    fun getSigningFingerprint(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            val sig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures?.firstOrNull()
            }

            sig?.let {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(it.toByteArray())
                hash.joinToString(":") { byte -> "%02X".format(byte) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get fingerprint for $packageName: ${e.message}")
            null
        }
    }

    /**
     * Check if the standalone AgeVerify app is installed (regardless of cert).
     * Quick check before attempting a Content Provider query.
     */
    fun isStandaloneAppInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(BridgeProtocol.STANDALONE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
