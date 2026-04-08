package com.ageverify.sdk

/**
 * BridgeProtocol
 *
 * Single source of truth for all constants shared between:
 * - AgeVerifyContentProvider (standalone app, the token issuer)
 * - TokenBridge (SDK embedded in partner apps, the token consumer)
 *
 * Both sides must use exactly the same values. Any drift here breaks the bridge.
 *
 * Architecture:
 *
 *   STANDALONE APP                        PARTNER APP
 *   ┌──────────────────────────────┐      ┌─────────────────────────────┐
 *   │ AgeVerifyContentProvider     │◄─────│ TokenBridge.queryToken()    │
 *   │  - Holds master JWT token    │      │  - Calls query() on URI     │
 *   │  - Verifies caller signature │      │  - Reads columns from cursor│
 *   │  - Returns token or null     │      │  - Falls back if not found  │
 *   └──────────────────────────────┘      └─────────────────────────────┘
 */
object BridgeProtocol {

    // ── Authority & URI ───────────────────────────────────────────────────────

    /** Content Provider authority — must match AndroidManifest provider declaration */
    const val AUTHORITY = "com.ageverify.provider"

    /** Base content URI */
    const val BASE_URI = "content://$AUTHORITY"

    /** Path for token queries */
    const val PATH_TOKEN = "token"

    /** Full URI for token queries */
    const val TOKEN_URI = "$BASE_URI/$PATH_TOKEN"

    // ── Query Parameters ──────────────────────────────────────────────────────

    /** Partner passes required age as a query parameter */
    const val PARAM_REQUIRED_AGE = "required_age"

    /** Partner passes their package name for audit logging */
    const val PARAM_CALLER_PACKAGE = "caller_package"

    // ── Cursor Columns ────────────────────────────────────────────────────────

    /** Boolean (0/1) — whether verification exists and passes required age */
    const val COL_VERIFIED = "verified"

    /** The signed JWT — only returned if verified = 1 */
    const val COL_JWT = "jwt"

    /** ISO country code of the jurisdiction verified under */
    const val COL_JURISDICTION = "jurisdiction"

    /** Minimum age that was verified */
    const val COL_MIN_AGE_VERIFIED = "min_age_verified"

    /** Unix timestamp (seconds) when token expires */
    const val COL_EXPIRES_AT = "expires_at"

    /** Protocol version — for forward compatibility */
    const val COL_PROTOCOL_VERSION = "protocol_version"

    // ── Security ──────────────────────────────────────────────────────────────

    /**
     * Custom permission that partner apps must declare to query the provider.
     * This prevents any random app from querying age tokens.
     *
     * Partner apps must add to their AndroidManifest:
     * <uses-permission android:name="com.ageverify.permission.READ_TOKEN" />
     */
    const val READ_PERMISSION = "com.ageverify.permission.READ_TOKEN"

    /**
     * The standalone app's package name.
     * Used by SDK to check if the standalone app is installed.
     */
    const val STANDALONE_PACKAGE = "com.ageverify"

    // ── Protocol Version ──────────────────────────────────────────────────────

    /** Current bridge protocol version — increment if columns change */
    const val PROTOCOL_VERSION = 1

    // ── Error Codes returned in COL_VERIFIED ─────────────────────────────────

    const val RESULT_VERIFIED = 1
    const val RESULT_NOT_VERIFIED = 0
    const val RESULT_INSUFFICIENT_AGE = -1
    const val RESULT_EXPIRED = -2
}
