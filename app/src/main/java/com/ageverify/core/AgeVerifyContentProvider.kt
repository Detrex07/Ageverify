package com.ageverify.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.util.Log
import com.ageverify.sdk.BridgeProtocol
import com.ageverify.sdk.ProviderSecurity
import java.time.Instant

/**
 * AgeVerifyContentProvider
 *
 * Lives in the STANDALONE app. Exposes the stored verification token
 * to partner apps that have embedded the AgeVerify SDK.
 *
 * Security model:
 * 1. readPermission declared in manifest — Android OS blocks any app
 *    without com.ageverify.permission.READ_TOKEN before our code runs
 * 2. We additionally validate the caller via ProviderSecurity
 * 3. We return ONLY a pass/fail signal + JWT — no raw biometric data ever
 * 4. The JWT itself is signed — a tampered token fails signature verification
 *
 * Supports only READ (query). INSERT/UPDATE/DELETE return nothing.
 * This is a read-only credential store.
 *
 * AndroidManifest declaration (in standalone app):
 *
 * <provider
 *     android:name=".core.AgeVerifyContentProvider"
 *     android:authorities="com.ageverify.provider"
 *     android:exported="true"
 *     android:readPermission="com.ageverify.permission.READ_TOKEN" />
 */
class AgeVerifyContentProvider : ContentProvider() {

    private lateinit var tokenManager: TokenManager

    companion object {
        private const val TAG = "AgeVerifyProvider"
        private const val TOKEN_QUERY = 1

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(BridgeProtocol.AUTHORITY, BridgeProtocol.PATH_TOKEN, TOKEN_QUERY)
        }
    }

    override fun onCreate(): Boolean {
        tokenManager = TokenManager(context!!)
        Log.d(TAG, "AgeVerifyContentProvider initialised")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        if (uriMatcher.match(uri) != TOKEN_QUERY) {
            Log.w(TAG, "Unknown URI: $uri")
            return null
        }

        // Log the caller for audit (package name, not stored permanently)
        val callingUid = Binder.getCallingUid()
        val callerPackage = uri.getQueryParameter(BridgeProtocol.PARAM_CALLER_PACKAGE)
            ?: "unknown"
        Log.d(TAG, "Token query from uid=$callingUid package=$callerPackage")

        // Validate caller (allowlist check — pass empty set to allow any permitted app)
        if (!ProviderSecurity.isCallerTrusted(context!!, callingUid)) {
            Log.w(TAG, "Caller not trusted: uid=$callingUid")
            return buildErrorCursor(BridgeProtocol.RESULT_NOT_VERIFIED)
        }

        // Parse required age from query parameter
        val requiredAge = uri.getQueryParameter(BridgeProtocol.PARAM_REQUIRED_AGE)
            ?.toIntOrNull() ?: 18

        // Retrieve and evaluate token
        return buildTokenCursor(requiredAge)
    }

    private fun buildTokenCursor(requiredAge: Int): Cursor {
        val columns = arrayOf(
            BridgeProtocol.COL_VERIFIED,
            BridgeProtocol.COL_JWT,
            BridgeProtocol.COL_JURISDICTION,
            BridgeProtocol.COL_MIN_AGE_VERIFIED,
            BridgeProtocol.COL_EXPIRES_AT,
            BridgeProtocol.COL_PROTOCOL_VERSION
        )
        val cursor = MatrixCursor(columns)

        val token = tokenManager.getValidToken()

        when {
            token == null -> {
                // No valid token exists
                cursor.addRow(arrayOf<Any?>(
                    BridgeProtocol.RESULT_NOT_VERIFIED,
                    null, null, null, null,
                    BridgeProtocol.PROTOCOL_VERSION
                ))
            }
            token.expiresAt.isBefore(Instant.now()) -> {
                // Token expired — clear it
                tokenManager.clearToken()
                cursor.addRow(arrayOf<Any?>(
                    BridgeProtocol.RESULT_EXPIRED,
                    null, null, null, null,
                    BridgeProtocol.PROTOCOL_VERSION
                ))
            }
            token.minimumAgeVerified < requiredAge -> {
                // Token exists but age threshold insufficient for this partner
                cursor.addRow(arrayOf<Any?>(
                    BridgeProtocol.RESULT_INSUFFICIENT_AGE,
                    null,
                    token.jurisdiction,
                    token.minimumAgeVerified,
                    token.expiresAt.epochSecond,
                    BridgeProtocol.PROTOCOL_VERSION
                ))
            }
            else -> {
                // Valid token that meets the age requirement
                cursor.addRow(arrayOf<Any?>(
                    BridgeProtocol.RESULT_VERIFIED,
                    token.jwt,
                    token.jurisdiction,
                    token.minimumAgeVerified,
                    token.expiresAt.epochSecond,
                    BridgeProtocol.PROTOCOL_VERSION
                ))
            }
        }

        return cursor
    }

    private fun buildErrorCursor(errorCode: Int): Cursor {
        val columns = arrayOf(BridgeProtocol.COL_VERIFIED, BridgeProtocol.COL_PROTOCOL_VERSION)
        val cursor = MatrixCursor(columns)
        cursor.addRow(arrayOf<Any?>(errorCode, BridgeProtocol.PROTOCOL_VERSION))
        return cursor
    }

    // ── Write operations — not supported ─────────────────────────────────────

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun getType(uri: Uri): String = "vnd.android.cursor.item/ageverify.token"
}
