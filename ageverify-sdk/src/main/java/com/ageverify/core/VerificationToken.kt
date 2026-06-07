package com.ageverify.core

import java.time.Instant

data class VerificationToken(
    val jwt: String,
    val expiresAt: Instant,
    val jurisdiction: String,
    val minimumAgeVerified: Int
)
