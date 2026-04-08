package com.ageverify.core

import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

internal object DOBTextParser {

    private val monthNameFormatters = listOf(
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d MMMM yyyy")
            .toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d MMM yyyy")
            .toFormatter(Locale.ENGLISH)
    )

    // Ordered from most specific to least specific to reduce false positives.
    private val dobPatterns = listOf(

        // Labelled patterns — most reliable, catches "DOB:", "Date of Birth:", "D.O.B" etc.
        Regex(
            """(?:DOB|Date\s+of\s+Birth|D\.O\.B\.?|Born|Geb\.?datum|Fecha\s+Nac\.?|Né\s+le)[:\s]+(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """(?:DOB|Date\s+of\s+Birth|D\.O\.B\.?|Born)[:\s]+(\d{1,2}\s+\w{3,9}\s+\d{4})""",
            RegexOption.IGNORE_CASE
        ),

        // ISO format: YYYY-MM-DD (common on machine-readable zones).
        Regex("""(\d{4})[/\-](\d{2})[/\-](\d{2})"""),

        // DD/MM/YYYY or DD-MM-YYYY or DD.MM.YYYY.
        Regex("""(\d{2})[/\-\.](\d{2})[/\-\.](\d{4})"""),

        // DD MMM YYYY (e.g. 15 JAN 1995, 03 March 1988).
        Regex(
            """(\d{1,2})\s+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\s+(\d{4})""",
            RegexOption.IGNORE_CASE
        ),

        // YYYY/MM/DD.
        Regex("""(\d{4})[/](\d{2})[/](\d{2})""")
    )

    fun parse(text: String): DOBResult {
        for (pattern in dobPatterns) {
            val match = pattern.find(text) ?: continue
            val parsed = tryParseDate(match.groupValues)
            if (parsed != null && isRealisticDOB(parsed)) {
                return DOBResult.Success(parsed, text)
            }
        }
        return DOBResult.NotFound(text)
    }

    fun computeAge(dob: LocalDate): Int = Period.between(dob, LocalDate.now()).years

    private fun tryParseDate(groups: List<String>): LocalDate? {
        val cleanGroups = groups.drop(1).filter { it.isNotEmpty() }

        // DD MMM YYYY
        if (cleanGroups.size == 3 && cleanGroups[1].any { it.isLetter() }) {
            val fullString = "${cleanGroups[0]} ${cleanGroups[1]} ${cleanGroups[2]}"
            for (fmt in monthNameFormatters) {
                runCatching { LocalDate.parse(fullString, fmt) }.getOrNull()?.let { return it }
            }
        }

        // ISO: YYYY-MM-DD
        if (cleanGroups.size == 3 && cleanGroups[0].length == 4) {
            return runCatching {
                LocalDate.of(
                    cleanGroups[0].toInt(),
                    cleanGroups[1].toInt(),
                    cleanGroups[2].toInt()
                )
            }.getOrNull()
        }

        // DD/MM/YYYY
        if (cleanGroups.size == 3 && cleanGroups[2].length == 4) {
            return runCatching {
                LocalDate.of(
                    cleanGroups[2].toInt(),
                    cleanGroups[1].toInt(),
                    cleanGroups[0].toInt()
                )
            }.getOrNull()
        }

        return null
    }

    /**
     * Sanity check: DOB must be between 1 and 120 years ago.
     * Rejects garbage OCR output.
     */
    private fun isRealisticDOB(date: LocalDate): Boolean {
        val age = computeAge(date)
        return age in 1..120
    }
}
