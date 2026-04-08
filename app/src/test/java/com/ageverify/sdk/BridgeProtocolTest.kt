package com.ageverify.sdk

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for BridgeProtocol constants.
 *
 * These look trivial but are genuinely important: a typo in a constant
 * name silently breaks the Content Provider bridge across apps.
 * Having tests that pin these values means a refactor that renames them
 * will fail loudly rather than silently.
 */
class BridgeProtocolTest {

    @Test
    fun `authority is correct`() {
        assertThat(BridgeProtocol.AUTHORITY).isEqualTo("com.ageverify.provider")
    }

    @Test
    fun `token URI is correctly constructed from authority and path`() {
        assertThat(BridgeProtocol.TOKEN_URI)
            .isEqualTo("content://com.ageverify.provider/token")
    }

    @Test
    fun `base URI starts with content scheme`() {
        assertThat(BridgeProtocol.BASE_URI).startsWith("content://")
    }

    @Test
    fun `column names are non-empty and unique`() {
        val cols = listOf(
            BridgeProtocol.COL_VERIFIED,
            BridgeProtocol.COL_JWT,
            BridgeProtocol.COL_JURISDICTION,
            BridgeProtocol.COL_MIN_AGE_VERIFIED,
            BridgeProtocol.COL_EXPIRES_AT,
            BridgeProtocol.COL_PROTOCOL_VERSION
        )
        // All non-empty
        cols.forEach { assertThat(it).isNotEmpty() }
        // All unique — duplicates would silently corrupt cursor reads
        assertThat(cols.toSet().size).isEqualTo(cols.size)
    }

    @Test
    fun `result codes are distinct`() {
        val codes = setOf(
            BridgeProtocol.RESULT_VERIFIED,
            BridgeProtocol.RESULT_NOT_VERIFIED,
            BridgeProtocol.RESULT_INSUFFICIENT_AGE,
            BridgeProtocol.RESULT_EXPIRED
        )
        assertThat(codes.size).isEqualTo(4)
    }

    @Test
    fun `RESULT_VERIFIED is positive`() {
        assertThat(BridgeProtocol.RESULT_VERIFIED).isGreaterThan(0)
    }

    @Test
    fun `error result codes are non-positive`() {
        assertThat(BridgeProtocol.RESULT_NOT_VERIFIED).isAtMost(0)
        assertThat(BridgeProtocol.RESULT_INSUFFICIENT_AGE).isAtMost(0)
        assertThat(BridgeProtocol.RESULT_EXPIRED).isAtMost(0)
    }

    @Test
    fun `standalone package name is valid format`() {
        assertThat(BridgeProtocol.STANDALONE_PACKAGE).contains(".")
        assertThat(BridgeProtocol.STANDALONE_PACKAGE).isEqualTo("com.ageverify")
    }

    @Test
    fun `read permission string follows Android permission format`() {
        assertThat(BridgeProtocol.READ_PERMISSION)
            .startsWith("com.ageverify")
        assertThat(BridgeProtocol.READ_PERMISSION)
            .contains("permission")
    }

    @Test
    fun `protocol version is positive integer`() {
        assertThat(BridgeProtocol.PROTOCOL_VERSION).isAtLeast(1)
    }

    @Test
    fun `param names are non-empty and distinct`() {
        val params = listOf(
            BridgeProtocol.PARAM_REQUIRED_AGE,
            BridgeProtocol.PARAM_CALLER_PACKAGE
        )
        params.forEach { assertThat(it).isNotEmpty() }
        assertThat(params.toSet().size).isEqualTo(params.size)
    }
}
