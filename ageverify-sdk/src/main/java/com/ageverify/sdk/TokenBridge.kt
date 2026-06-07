package com.ageverify.sdk

import android.content.Context
import android.net.Uri
import android.util.Log
import com.ageverify.config.Jurisdiction
import com.ageverify.config.VerificationConfig
import com.ageverify.core.AgeVerificationEngine
import com.ageverify.core.QueryResult
import com.ageverify.core.VerificationToken
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TokenBridge
 *
 * The central resolver that decides WHERE to get the verification token from.
 */
class TokenBridge(
    private val context: Context,
    private val engine: AgeVerificationEngine,
    private val trustedStandaloneFingerprint: String? = null
) {

    companion object {
        private const val TAG = "TokenBridge"
    }

    /**
     * Resolve verification status for the given required age.
     * Suspend function — safe to call from any coroutine context.
     */
    suspend fun resolve(requiredAge: Int): BridgeResult {
        // Path 1: Try standalone app
        if (ProviderSecurity.isStandaloneAppInstalled(context)) {
            Log.d(TAG, "Standalone app detected — querying Content Provider")

            if (ProviderSecurity.isStandaloneAppTrusted(
                    context, trustedStandaloneFingerprint
                )) {
                val result = queryContentProvider(requiredAge)
                if (result != null) {
                    Log.d(TAG, "Content Provider returned result: $result")
                    return result
                }
            } else {
                Log.w(TAG, "Standalone app cert check failed — skipping, using local token")
            }
        }

        // Path 2: Fall back to local token
        Log.d(TAG, "Falling back to local TokenManager")
        return resolveFromLocal(requiredAge)
    }

    /**
     * Query the standalone app's Content Provider on IO dispatcher.
     */
    private suspend fun queryContentProvider(requiredAge: Int): BridgeResult? =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(BridgeProtocol.TOKEN_URI)
                .buildUpon()
                .appendQueryParameter(
                    BridgeProtocol.PARAM_REQUIRED_AGE, requiredAge.toString()
                )
                .appendQueryParameter(
                    BridgeProtocol.PARAM_CALLER_PACKAGE, context.packageName
                )
                .build()

            try {
                val cursor = context.contentResolver.query(
                    uri, null, null, null, null
                ) ?: return@withContext null

                cursor.use { c ->
                    if (!c.moveToFirst()) return@withContext null

                    val verifiedCode = c.getInt(c.getColumnIndexOrThrow(BridgeProtocol.COL_VERIFIED))
                    val protocolVersion = runCatching {
                        c.getInt(c.getColumnIndexOrThrow(BridgeProtocol.COL_PROTOCOL_VERSION))
                    }.getOrDefault(0)

                    if (protocolVersion > BridgeProtocol.PROTOCOL_VERSION) {
                        Log.w(TAG, "Provider uses newer protocol ($protocolVersion) — update SDK")
                    }

                    when (verifiedCode) {
                        BridgeProtocol.RESULT_VERIFIED -> {
                            val jwt = c.getString(c.getColumnIndexOrThrow(BridgeProtocol.COL_JWT))
                            val jurisdiction = c.getString(
                                c.getColumnIndexOrThrow(BridgeProtocol.COL_JURISDICTION)
                            )
                            val minAge = c.getInt(
                                c.getColumnIndexOrThrow(BridgeProtocol.COL_MIN_AGE_VERIFIED)
                            )
                            val expiresAt = c.getLong(
                                c.getColumnIndexOrThrow(BridgeProtocol.COL_EXPIRES_AT)
                            )
                            BridgeResult.Verified(
                                source = TokenSource.STANDALONE_APP,
                                token = VerificationToken(
                                    jwt = jwt,
                                    expiresAt = Instant.ofEpochSecond(expiresAt),
                                    jurisdiction = jurisdiction,
                                    minimumAgeVerified = minAge
                                )
                            )
                        }
                        BridgeProtocol.RESULT_INSUFFICIENT_AGE -> {
                            val minAge = runCatching {
                                c.getInt(c.getColumnIndexOrThrow(BridgeProtocol.COL_MIN_AGE_VERIFIED))
                            }.getOrDefault(0)
                            BridgeResult.InsufficientAge(
                                source = TokenSource.STANDALONE_APP,
                                verifiedAge = minAge,
                                requiredAge = requiredAge
                            )
                        }
                        BridgeProtocol.RESULT_EXPIRED -> {
                            Log.d(TAG, "Standalone app token is expired")
                            null
                        }
                        BridgeProtocol.RESULT_NOT_VERIFIED -> {
                            Log.d(TAG, "Standalone app has no token")
                            null
                        }
                        else -> {
                            Log.w(TAG, "Unknown result code from provider: $verifiedCode")
                            null
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied querying ContentProvider: ${e.message}")
                null
            } catch (e: Exception) {
                Log.e(TAG, "ContentProvider query failed: ${e.message}")
                null
            }
        }

    private fun resolveFromLocal(requiredAge: Int): BridgeResult {
        return when (val result = engine.queryVerificationStatus(requiredAge)) {
            is QueryResult.Verified -> BridgeResult.Verified(
                source = TokenSource.IN_APP,
                token = result.token
            )
            is QueryResult.InsufficientAge -> BridgeResult.InsufficientAge(
                source = TokenSource.IN_APP,
                verifiedAge = result.verifiedAge,
                requiredAge = result.requiredAge
            )
            is QueryResult.NotVerified -> BridgeResult.NotVerified
        }
    }
}

sealed class BridgeResult {
    data class Verified(val source: TokenSource, val token: VerificationToken) : BridgeResult()
    object NotVerified : BridgeResult()
    data class InsufficientAge(val source: TokenSource, val verifiedAge: Int, val requiredAge: Int) : BridgeResult()
}

enum class TokenSource { STANDALONE_APP, IN_APP }
