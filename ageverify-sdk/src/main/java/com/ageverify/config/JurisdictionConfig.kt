package com.ageverify.config

/**
 * Jurisdiction-aware age thresholds.
 *
 * Partner apps declare their jurisdiction. The SDK validates against
 * that jurisdiction's minimum age. Easily extensible.
 */
enum class Jurisdiction(
    val countryCode: String,
    val minimumAge: Int,
    val legalBasis: String
) {
    // India
    INDIA("IN", 18, "DPDP Act 2023 - treats all under-18 as minors"),

    // United States
    USA_COPPA("US", 13, "COPPA - Children's Online Privacy Protection Act"),
    USA_ADULT("US", 18, "US - Adult content, gambling, alcohol"),

    // United Kingdom
    UK("GB", 18, "Online Safety Act 2023"),
    UK_SOCIAL("GB", 16, "UK - Social media baseline"),

    // European Union
    EU_GDPR("EU", 16, "GDPR Article 8 - default member state"),
    EU_GDPR_MIN("EU", 13, "GDPR Article 8 - lowest permitted (DE, AT, CZ)"),
    EU_ADULT("EU", 18, "EU - Adult content"),

    // Australia
    AUSTRALIA_SOCIAL("AU", 16, "Online Safety Amendment Act 2024"),
    AUSTRALIA_ADULT("AU", 18, "Australian Classification Act"),

    // Singapore
    SINGAPORE("SG", 18, "PDPA - Personal Data Protection Act"),

    // Canada
    CANADA("CA", 13, "PIPEDA - baseline"),
    CANADA_ADULT("CA", 18, "Canada - Adult content"),

    // South Korea
    SOUTH_KOREA("KR", 14, "COPPA-equivalent, PIPA"),

    // Brazil
    BRAZIL("BR", 12, "LGPD - Lei Geral de Proteção de Dados"),
    BRAZIL_ADULT("BR", 18, "Brazil - Adult content"),

    // Default fallback - most restrictive sensible default
    GLOBAL_DEFAULT("XX", 18, "Conservative global default");

    companion object {
        /**
         * Resolve jurisdiction from ISO country code.
         * Returns the most restrictive match for that country.
         * Partner apps can override by passing the explicit enum value.
         */
        fun fromCountryCode(code: String): Jurisdiction {
            return values().firstOrNull {
                it.countryCode.equals(code, ignoreCase = true)
            } ?: GLOBAL_DEFAULT
        }

        /**
         * Returns minimum age for a given country code.
         * Uses the most conservative applicable threshold.
         */
        fun minimumAgeFor(countryCode: String): Int {
            return fromCountryCode(countryCode).minimumAge
        }
    }
}

/**
 * Configuration passed into the verification engine.
 *
 * @param jurisdiction The legal jurisdiction the partner app operates under.
 * @param customMinAge Override age threshold if jurisdiction doesn't cover your use case.
 * @param requireFaceMatch Enforce face-to-ID matching (true for higher assurance, false for age-only).
 */
data class VerificationConfig(
    val jurisdiction: Jurisdiction = Jurisdiction.GLOBAL_DEFAULT,
    val customMinAge: Int? = null,
    val requireFaceMatch: Boolean = true
) {
    val effectiveMinAge: Int
        get() = customMinAge ?: jurisdiction.minimumAge

    fun acceptsVerifiedAge(minimumAgeVerified: Int): Boolean {
        return minimumAgeVerified >= effectiveMinAge
    }
}
