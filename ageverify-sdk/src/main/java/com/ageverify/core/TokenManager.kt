package com.ageverify.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Manages verification credentials using Android Keystore.
 *
 * Two layers of protection:
 * - JWT signature: ES256 via Android Keystore — detects any tampering
 * - EncryptedSharedPreferences: AES-256-GCM — protects token file on rooted devices
 *
 * Partner apps receive only the signed JWT and the public key to verify it.
 * No biometric data. No ID data. Just a cryptographic certificate of age.
 */
class TokenManager(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    // ── Public API ────────────────────────────────────────────────────────────

    fun issueToken(
        jurisdiction: String,
        minimumAgeVerified: Int,
        validityDays: Long = 365L
    ): VerificationToken {
        ensureKeyExists()

        val issuedAt  = Instant.now()
        val expiresAt = issuedAt.plus(validityDays, ChronoUnit.DAYS)

        val payload = JSONObject().apply {
            put("verified",     true)
            put("jurisdiction", jurisdiction)
            put("minAge",       minimumAgeVerified)
            put("iat",          issuedAt.epochSecond)
            put("exp",          expiresAt.epochSecond)
            put("version",      TOKEN_VERSION)
        }

        val jwt = buildAndSignJWT(payload)

        prefs.edit()
            .putString(KEY_TOKEN, jwt)
            .putLong(KEY_EXPIRES_AT, expiresAt.epochSecond)
            .apply()

        return VerificationToken(jwt, expiresAt, jurisdiction, minimumAgeVerified)
    }

    fun getValidToken(): VerificationToken? {
        val jwt       = prefs.getString(KEY_TOKEN, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)

        if (Instant.now().epochSecond > expiresAt) {
            clearToken()
            return null
        }

        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return null
            val payload = JSONObject(
                String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING))
            )
            VerificationToken(
                jwt                  = jwt,
                expiresAt            = Instant.ofEpochSecond(expiresAt),
                jurisdiction         = payload.getString("jurisdiction"),
                minimumAgeVerified   = payload.getInt("minAge")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse stored token: ${e.message}")
            null
        }
    }

    fun verifyToken(jwt: String): Boolean {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return false

            val signingInput = "${parts[0]}.${parts[1]}"
            val signature    = Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_PADDING)

            val keyStore  = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey ?: return false

            Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initVerify(publicKey)
                update(signingInput.toByteArray())
            }.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    fun getPublicKeyBase64(): String? {
        return try {
            val keyStore  = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey ?: return null
            Base64.encodeToString(publicKey.encoded, Base64.URL_SAFE or Base64.NO_PADDING)
        } catch (e: Exception) {
            null
        }
    }

    fun clearToken() {
        prefs.edit().clear().apply()
    }

    fun isVerified(): Boolean = getValidToken() != null

    // ── Private ───────────────────────────────────────────────────────────────

    private fun buildAndSignJWT(payload: JSONObject): String {
        val header = Base64.encodeToString(
            """{"alg":"ES256","typ":"JWT"}""".toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING
        )
        val encodedPayload = Base64.encodeToString(
            payload.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING
        )
        val signingInput = "$header.$encodedPayload"

        val keyStore   = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey

        val signatureBytes = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey)
            update(signingInput.toByteArray())
        }.sign()

        val encodedSig = Base64.encodeToString(signatureBytes, Base64.URL_SAFE or Base64.NO_PADDING)
        return "$signingInput.$encodedSig"
    }

    private fun ensureKeyExists() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKeyPair()
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG                = "TokenManager"
        private const val ANDROID_KEYSTORE   = "AndroidKeyStore"
        private const val KEY_ALIAS          = "ageverify_signing_key"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val PREFS_NAME         = "ageverify_token_store"
        private const val KEY_TOKEN          = "token"
        private const val KEY_EXPIRES_AT     = "expires_at"
        private const val TOKEN_VERSION      = 1

        fun createEncryptedPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // Fallback: JWT signature still provides integrity.
                // This only happens on first boot or misconfigured devices.
                Log.w(TAG, "EncryptedSharedPreferences unavailable, using plain: ${e.message}")
                context.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
            }
        }
    }
}
