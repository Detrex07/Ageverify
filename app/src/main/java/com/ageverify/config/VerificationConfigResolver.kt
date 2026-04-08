package com.ageverify.config

object VerificationConfigResolver {

    fun fromInputs(
        jurisdictionCode: String?,
        minAge: Int?,
        requireFaceMatch: Boolean = true
    ): VerificationConfig {
        val jurisdiction = jurisdictionCode
            ?.takeIf { it.isNotBlank() }
            ?.let { Jurisdiction.fromCountryCode(it) }
            ?: Jurisdiction.GLOBAL_DEFAULT

        return VerificationConfig(
            jurisdiction = jurisdiction,
            customMinAge = minAge?.takeIf { it > 0 },
            requireFaceMatch = requireFaceMatch
        )
    }
}
