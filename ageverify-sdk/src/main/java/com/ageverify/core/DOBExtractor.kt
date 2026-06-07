package com.ageverify.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Extracts Date of Birth from any ID document using ML Kit OCR.
 *
 * Strategy:
 * 1. Run OCR on the ID image — works on any printed document
 * 2. Search extracted text for DOB patterns via regex
 * 3. Parse the date and compute age
 *
 * Supports: Indian IDs (Aadhaar, PAN, DL), UK/US driving licences,
 * EU national IDs, passports, and any ID with a visible DOB field.
 */
class DOBExtractor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Run OCR on the ID bitmap and return extracted DOB as LocalDate.
     * Returns null if no valid DOB found.
     */
    suspend fun extractDOB(idBitmap: Bitmap): DOBResult {
        val rawText = runOCR(idBitmap)
        return DOBTextParser.parse(rawText)
    }

    private suspend fun runOCR(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val fullText = result.textBlocks.joinToString("\n") { it.text }
                    cont.resume(fullText)
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                }
        }

    /**
     * Internal for testing. Production code calls parseDOBFromText().
     * Using `internal` visibility so tests in the same module can access it
     * without making it fully public.
     */
    internal fun parseDOBFromTextPublic(text: String): DOBResult = DOBTextParser.parse(text)

    fun computeAge(dob: LocalDate): Int = DOBTextParser.computeAge(dob)

    /** Release the ML Kit text recognizer. Call when the extractor is no longer needed. */
    fun close() {
        recognizer.close()
    }
}

sealed class DOBResult {
    data class Success(val dob: LocalDate, val rawText: String) : DOBResult()
    data class NotFound(val rawText: String) : DOBResult()
}
