package com.ageverify.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VerificationConfigResolverTest {

    @Test
    fun `uses provided jurisdiction age and face match flag`() {
        val config = VerificationConfigResolver.fromInputs(
            jurisdictionCode = "GB",
            minAge = 21,
            requireFaceMatch = false
        )

        assertThat(config.jurisdiction).isEqualTo(Jurisdiction.UK)
        assertThat(config.effectiveMinAge).isEqualTo(21)
        assertThat(config.requireFaceMatch).isFalse()
    }

    @Test
    fun `defaults to global config when no inputs are provided`() {
        val config = VerificationConfigResolver.fromInputs(
            jurisdictionCode = null,
            minAge = null
        )

        assertThat(config.jurisdiction).isEqualTo(Jurisdiction.GLOBAL_DEFAULT)
        assertThat(config.effectiveMinAge).isEqualTo(Jurisdiction.GLOBAL_DEFAULT.minimumAge)
        assertThat(config.requireFaceMatch).isTrue()
    }

    @Test
    fun `ignores non positive custom age values`() {
        val config = VerificationConfigResolver.fromInputs(
            jurisdictionCode = "IN",
            minAge = 0,
            requireFaceMatch = true
        )

        assertThat(config.jurisdiction).isEqualTo(Jurisdiction.INDIA)
        assertThat(config.effectiveMinAge).isEqualTo(Jurisdiction.INDIA.minimumAge)
        assertThat(config.requireFaceMatch).isTrue()
    }

    @Test
    fun `treats blank jurisdiction code as global default`() {
        val config = VerificationConfigResolver.fromInputs(
            jurisdictionCode = "  ",
            minAge = 19,
            requireFaceMatch = true
        )

        assertThat(config.jurisdiction).isEqualTo(Jurisdiction.GLOBAL_DEFAULT)
        assertThat(config.effectiveMinAge).isEqualTo(19)
    }
}
