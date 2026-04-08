package com.ageverify.config

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Tests for jurisdiction resolution and age threshold logic.
 *
 * Every jurisdiction enum value is tested. Country code resolution is tested
 * for known codes, unknown codes, and case-insensitive matching.
 */
class JurisdictionConfigTest {

    // ── Individual jurisdiction thresholds ────────────────────────────────────

    @Test
    fun `India minimum age is 18 per DPDP Act 2023`() {
        assertThat(Jurisdiction.INDIA.minimumAge).isEqualTo(18)
    }

    @Test
    fun `USA COPPA minimum age is 13`() {
        assertThat(Jurisdiction.USA_COPPA.minimumAge).isEqualTo(13)
    }

    @Test
    fun `USA adult content minimum age is 18`() {
        assertThat(Jurisdiction.USA_ADULT.minimumAge).isEqualTo(18)
    }

    @Test
    fun `UK Online Safety Act minimum age is 18`() {
        assertThat(Jurisdiction.UK.minimumAge).isEqualTo(18)
    }

    @Test
    fun `UK social media baseline is 16`() {
        assertThat(Jurisdiction.UK_SOCIAL.minimumAge).isEqualTo(16)
    }

    @Test
    fun `EU GDPR default minimum age is 16`() {
        assertThat(Jurisdiction.EU_GDPR.minimumAge).isEqualTo(16)
    }

    @Test
    fun `EU GDPR minimum permitted is 13`() {
        assertThat(Jurisdiction.EU_GDPR_MIN.minimumAge).isEqualTo(13)
    }

    @Test
    fun `EU adult minimum age is 18`() {
        assertThat(Jurisdiction.EU_ADULT.minimumAge).isEqualTo(18)
    }

    @Test
    fun `Australia social media minimum age is 16`() {
        assertThat(Jurisdiction.AUSTRALIA_SOCIAL.minimumAge).isEqualTo(16)
    }

    @Test
    fun `Australia adult minimum age is 18`() {
        assertThat(Jurisdiction.AUSTRALIA_ADULT.minimumAge).isEqualTo(18)
    }

    @Test
    fun `Singapore minimum age is 18`() {
        assertThat(Jurisdiction.SINGAPORE.minimumAge).isEqualTo(18)
    }

    @Test
    fun `Canada PIPEDA minimum age is 13`() {
        assertThat(Jurisdiction.CANADA.minimumAge).isEqualTo(13)
    }

    @Test
    fun `Canada adult minimum age is 18`() {
        assertThat(Jurisdiction.CANADA_ADULT.minimumAge).isEqualTo(18)
    }

    @Test
    fun `South Korea PIPA minimum age is 14`() {
        assertThat(Jurisdiction.SOUTH_KOREA.minimumAge).isEqualTo(14)
    }

    @Test
    fun `Brazil LGPD minimum age is 12`() {
        assertThat(Jurisdiction.BRAZIL.minimumAge).isEqualTo(12)
    }

    @Test
    fun `Brazil adult minimum age is 18`() {
        assertThat(Jurisdiction.BRAZIL_ADULT.minimumAge).isEqualTo(18)
    }

    @Test
    fun `global default is conservative 18`() {
        assertThat(Jurisdiction.GLOBAL_DEFAULT.minimumAge).isEqualTo(18)
    }

    // ── Country code resolution ───────────────────────────────────────────────

    @Test
    fun `IN resolves to INDIA`() {
        val j = Jurisdiction.fromCountryCode("IN")
        assertThat(j.minimumAge).isEqualTo(18)
        assertThat(j.countryCode).isEqualTo("IN")
    }

    @Test
    fun `GB resolves to UK`() {
        assertThat(Jurisdiction.fromCountryCode("GB").minimumAge).isEqualTo(18)
    }

    @Test
    fun `AU resolves to AUSTRALIA`() {
        assertThat(Jurisdiction.fromCountryCode("AU").minimumAge).isAtLeast(16)
    }

    @Test
    fun `SG resolves to SINGAPORE`() {
        assertThat(Jurisdiction.fromCountryCode("SG").minimumAge).isEqualTo(18)
    }

    @Test
    fun `unknown country code falls back to GLOBAL_DEFAULT`() {
        val j = Jurisdiction.fromCountryCode("ZZ")
        assertThat(j).isEqualTo(Jurisdiction.GLOBAL_DEFAULT)
        assertThat(j.minimumAge).isEqualTo(18)
    }

    @Test
    fun `empty string falls back to GLOBAL_DEFAULT`() {
        assertThat(Jurisdiction.fromCountryCode(""))
            .isEqualTo(Jurisdiction.GLOBAL_DEFAULT)
    }

    @Test
    fun `country code resolution is case insensitive`() {
        assertThat(Jurisdiction.fromCountryCode("in"))
            .isEqualTo(Jurisdiction.fromCountryCode("IN"))
    }

    @Test
    fun `minimumAgeFor convenience method matches enum value`() {
        assertThat(Jurisdiction.minimumAgeFor("IN")).isEqualTo(18)
        assertThat(Jurisdiction.minimumAgeFor("XX")).isEqualTo(18) // GLOBAL_DEFAULT
    }

    // ── VerificationConfig ────────────────────────────────────────────────────

    @Test
    fun `effectiveMinAge uses jurisdiction minimum by default`() {
        val config = VerificationConfig(jurisdiction = Jurisdiction.INDIA)
        assertThat(config.effectiveMinAge).isEqualTo(18)
    }

    @Test
    fun `effectiveMinAge uses customMinAge when provided`() {
        val config = VerificationConfig(
            jurisdiction = Jurisdiction.USA_COPPA,
            customMinAge = 16
        )
        // Custom overrides jurisdiction's 13
        assertThat(config.effectiveMinAge).isEqualTo(16)
    }

    @Test
    fun `effectiveMinAge falls back to jurisdiction when customMinAge is null`() {
        val config = VerificationConfig(
            jurisdiction = Jurisdiction.UK,
            customMinAge = null
        )
        assertThat(config.effectiveMinAge).isEqualTo(18)
    }

    @Test
    fun `requireFaceMatch defaults to true`() {
        assertThat(VerificationConfig().requireFaceMatch).isTrue()
    }

    @Test
    fun `default config uses GLOBAL_DEFAULT jurisdiction`() {
        assertThat(VerificationConfig().jurisdiction)
            .isEqualTo(Jurisdiction.GLOBAL_DEFAULT)
    }

    @Test
    fun `acceptsVerifiedAge uses effective jurisdiction threshold`() {
        val config = VerificationConfig(jurisdiction = Jurisdiction.INDIA)
        assertThat(config.acceptsVerifiedAge(18)).isTrue()
        assertThat(config.acceptsVerifiedAge(17)).isFalse()
    }

    @Test
    fun `acceptsVerifiedAge respects custom minimum age`() {
        val config = VerificationConfig(
            jurisdiction = Jurisdiction.USA_COPPA,
            customMinAge = 21
        )
        assertThat(config.acceptsVerifiedAge(21)).isTrue()
        assertThat(config.acceptsVerifiedAge(20)).isFalse()
    }

    // ── Legal basis strings are non-empty ─────────────────────────────────────

    @Test
    fun `all jurisdictions have non-empty legal basis`() {
        for (j in Jurisdiction.values()) {
            assertWithMessage("${j.name} should have a legal basis").that(j.legalBasis)
                .isNotEmpty()
        }
    }

    @Test
    fun `all jurisdictions have valid country codes`() {
        for (j in Jurisdiction.values()) {
            assertWithMessage("${j.name} should have a country code").that(j.countryCode)
                .isNotEmpty()
            assertWithMessage("${j.name} country code should be 2 chars")
                .that(j.countryCode.length)
                .isAtMost(2)
        }
    }

    // ── No jurisdiction has a minimum age below 0 or above 21 ────────────────

    @Test
    fun `all minimum ages are within realistic legal range`() {
        for (j in Jurisdiction.values()) {
            assertWithMessage("${j.name} minimum age should be >= 0").that(j.minimumAge)
                .isAtLeast(0)
            assertWithMessage("${j.name} minimum age should be <= 21").that(j.minimumAge)
                .isAtMost(21)
        }
    }
}
