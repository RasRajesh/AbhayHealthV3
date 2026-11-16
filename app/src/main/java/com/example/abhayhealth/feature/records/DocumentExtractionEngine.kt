package com.example.abhayhealth.feature.records

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.example.abhayhealth.core.security.SecurityAudit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface ExtractionResult {
    data class Success(
        val rawText: String,
        val pagesProcessed: Int,
        val isPartial: Boolean = false
    ) : ExtractionResult

    data class Error(val message: String) : ExtractionResult
}

object DocumentExtractionEngine {

    suspend fun extractText(
        record: MedicalRecord,
        onProgress: ((currentPage: Int, totalPages: Int) -> Unit)? = null
    ): ExtractionResult = withContext(Dispatchers.IO) {
        val path = record.localFilePath
        if (path.isNullOrBlank()) {
            return@withContext ExtractionResult.Error("Local document file path is missing.")
        }

        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            return@withContext ExtractionResult.Error("Document file not found or unreadable on device.")
        }

        val isPdf = record.mimeType?.contains("pdf", ignoreCase = true) == true || path.endsWith(".pdf", ignoreCase = true)

        try {
            if (isPdf) {
                extractFromPdf(file, onProgress)
            } else {
                extractFromImage(file)
            }
        } catch (t: Throwable) {
            SecurityAudit.logError("DocumentExtractionEngine", "Failed during document extraction", t)
            ExtractionResult.Error(t.localizedMessage ?: "Failed to extract text from document.")
        }
    }


    private suspend fun extractFromPdf(
        file: File,
        onProgress: ((currentPage: Int, totalPages: Int) -> Unit)? = null
    ): ExtractionResult {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var recognizer: TextRecognizer? = null

        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            if (pfd == null) {
                return ExtractionResult.Error("Unable to open document file descriptor.")
            }

            renderer = try {
                PdfRenderer(pfd)
            } catch (t: Throwable) {
                SecurityAudit.logError("DocumentExtractionEngine", "PdfRenderer initialization failed", t)
                val fallbackDirect = PdfTextExtractor.extractText(file)
                if (!fallbackDirect.isNullOrBlank()) {
                    return ExtractionResult.Success(
                        rawText = fallbackDirect,
                        pagesProcessed = 1,
                        isPartial = false
                    )
                }
                return ExtractionResult.Error("Cannot render PDF document: ${t.localizedMessage ?: "Invalid or encrypted PDF"}")
            }

            val pageCount = renderer.pageCount
            if (pageCount == 0) {
                return ExtractionResult.Error("PDF document contains no readable pages.")
            }

            // Existing text recognition service
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val fullTextBuilder = StringBuilder()
            var lastError: Throwable? = null

            // Iterate through ALL pages of the uploaded PDF using text recognition
            for (pageIndex in 0 until pageCount) {
                onProgress?.invoke(pageIndex + 1, pageCount)

                var page: PdfRenderer.Page? = null
                var renderedBitmap: Bitmap? = null

                try {
                    page = renderer.openPage(pageIndex)
                    val targetMaxDim = 1200
                    val currentMaxDim = maxOf(page.width, page.height)
                    val scale = if (currentMaxDim > 0) {
                        (targetMaxDim.toFloat() / currentMaxDim).coerceIn(0.2f, 1.5f)
                    } else 1.0f
                    val width = (page.width * scale).toInt().coerceIn(100, targetMaxDim)
                    val height = (page.height * scale).toInt().coerceIn(100, targetMaxDim)

                    // Safe allocation capped at 1200px (~5 MB), recycled immediately after recognition
                    renderedBitmap = Bitmap.createBitmap(
                        width,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    renderedBitmap.eraseColor(android.graphics.Color.WHITE)

                    page.render(
                        renderedBitmap,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )

                    // Close native page immediately after rendering to prevent native memory exhaustion
                    try {
                        page.close()
                    } catch (_: Exception) {}
                    page = null

                    val inputImage = InputImage.fromBitmap(renderedBitmap, 0)
                    val visionText = try {
                        withTimeoutOrNull(20_000L) {
                            recognizer.process(inputImage).await()
                        }
                    } catch (t: Throwable) {
                        SecurityAudit.logError("DocumentExtractionEngine", "Text recognition failed for page $pageIndex", t)
                        null
                    }

                    val pageText = visionText?.text?.trim()
                    if (!pageText.isNullOrBlank()) {
                        if (fullTextBuilder.isNotEmpty()) {
                            fullTextBuilder.append("\n\n--- Page ${pageIndex + 1} of $pageCount ---\n")
                        } else if (pageCount > 1) {
                            fullTextBuilder.append("--- Page 1 of $pageCount ---\n")
                        }
                        fullTextBuilder.append(pageText)
                    }
                } catch (t: Throwable) {
                    lastError = t
                    SecurityAudit.logError("DocumentExtractionEngine", "Skipped rendering PDF page $pageIndex due to error", t)
                } finally {
                    try {
                        page?.close()
                    } catch (_: Exception) {}
                    try {
                        renderedBitmap?.recycle()
                    } catch (_: Exception) {}
                }
            }

            var extractedText = fullTextBuilder.toString().trim()

            // Complement with direct stream text if OCR was blank
            if (extractedText.isBlank()) {
                val directText = PdfTextExtractor.extractText(file)
                if (!directText.isNullOrBlank()) {
                    extractedText = directText
                }
            }

            if (extractedText.isBlank()) {
                val errorMsg = lastError?.localizedMessage
                    ?: "No readable medical text could be detected from this document ($pageCount page(s) analyzed)."
                ExtractionResult.Error(errorMsg)
            } else {
                ExtractionResult.Success(
                    rawText = extractedText,
                    pagesProcessed = pageCount,
                    isPartial = false
                )
            }
        } finally {
            try {
                renderer?.close()
            } catch (_: Exception) {}

            try {
                pfd?.close()
            } catch (_: Exception) {}

            try {
                recognizer?.close()
            } catch (_: Exception) {}
        }
    }

    fun getPdfPageCount(file: File): Int {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) ?: return 1
            renderer = PdfRenderer(pfd)
            renderer.pageCount
        } catch (_: Throwable) {
            1
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    suspend fun extractPdfPage(
        file: File,
        pageIndex: Int
    ): ExtractionResult = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var recognizer: TextRecognizer? = null

        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                ?: return@withContext ExtractionResult.Error("Unable to open PDF document.")

            renderer = try {
                PdfRenderer(pfd)
            } catch (t: Throwable) {
                val fallbackDirect = PdfTextExtractor.extractText(file)
                if (!fallbackDirect.isNullOrBlank()) {
                    return@withContext ExtractionResult.Success(rawText = fallbackDirect, pagesProcessed = 1)
                }
                return@withContext ExtractionResult.Error("Cannot render PDF page.")
            }

            val totalPages = renderer.pageCount
            if (pageIndex < 0 || pageIndex >= totalPages) {
                return@withContext ExtractionResult.Error("Page index $pageIndex out of range (Total: $totalPages).")
            }

            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            var pageText: String? = null

            var page: PdfRenderer.Page? = null
            var renderedBitmap: Bitmap? = null

            try {
                page = renderer.openPage(pageIndex)
                val targetMaxDim = 1200
                val currentMaxDim = maxOf(page.width, page.height)
                val scale = if (currentMaxDim > 0) {
                    (targetMaxDim.toFloat() / currentMaxDim).coerceIn(0.2f, 1.5f)
                } else 1.0f
                val width = (page.width * scale).toInt().coerceIn(100, targetMaxDim)
                val height = (page.height * scale).toInt().coerceIn(100, targetMaxDim)

                renderedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                renderedBitmap.eraseColor(android.graphics.Color.WHITE)

                page.render(renderedBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                try { page.close() } catch (_: Exception) {}
                page = null

                val inputImage = InputImage.fromBitmap(renderedBitmap, 0)
                val visionText = withTimeoutOrNull(20_000L) {
                    recognizer.process(inputImage).await()
                }
                pageText = visionText?.text?.trim()
            } catch (t: Throwable) {
                SecurityAudit.logError("DocumentExtractionEngine", "Failed page $pageIndex extraction", t)
            } finally {
                try { page?.close() } catch (_: Exception) {}
                try { renderedBitmap?.recycle() } catch (_: Exception) {}
            }

            var finalPageText = if (!pageText.isNullOrBlank()) {
                "--- Page ${pageIndex + 1} of $totalPages ---\n$pageText"
            } else ""

            if (finalPageText.isBlank()) {
                val direct = PdfTextExtractor.extractText(file)
                if (!direct.isNullOrBlank()) {
                    finalPageText = direct
                }
            }

            if (finalPageText.isBlank()) {
                ExtractionResult.Error("No readable text found on Page ${pageIndex + 1} of $totalPages.")
            } else {
                ExtractionResult.Success(
                    rawText = finalPageText,
                    pagesProcessed = 1,
                    isPartial = pageIndex + 1 < totalPages
                )
            }
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            try { recognizer?.close() } catch (_: Exception) {}
        }
    }


    private suspend fun extractFromImage(file: File): ExtractionResult {
        var recognizer: TextRecognizer? = null

        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

            val width = boundsOptions.outWidth
            val height = boundsOptions.outHeight

            if (width <= 0 || height <= 0) {
                return ExtractionResult.Error("Selected image could not be read.")
            }

            var sampleSize = 1
            val maxDimension = 1200

            while (
                width / sampleSize > maxDimension ||
                height / sampleSize > maxDimension
            ) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val rawBitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                decodeOptions
            ) ?: return ExtractionResult.Error("Failed to decode image file.")

            // Read EXIF orientation to ensure camera photos captured in portrait or landscape are not inverted
            val exif = try {
                ExifInterface(file.absolutePath)
            } catch (_: Exception) {
                null
            }
            val rotationDegrees = when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            // Apply grayscale desaturation and contrast enhancement to improve OCR recognition on low-quality/faint photos
            val preprocessedBitmap = try {
                DocumentImagePreprocessor.preprocessForOcr(rawBitmap)
            } catch (_: Throwable) {
                rawBitmap
            }

            val inputImage = InputImage.fromBitmap(preprocessedBitmap, rotationDegrees)
            val visionText = try {
                withTimeoutOrNull(7000L) {
                    recognizer.process(inputImage).await()
                }
            } catch (t: Throwable) {
                SecurityAudit.logError("DocumentExtractionEngine", "Image OCR failed", t)
                null
            }
            var text = visionText?.text?.trim() ?: ""

            // Resilient fallback: If preprocessing produced little or no text (< 15 chars),
            // test the original raw bitmap in case the document had unusual color inversion
            if (text.length < 15 && preprocessedBitmap !== rawBitmap) {
                try {
                    val rawInput = InputImage.fromBitmap(rawBitmap, rotationDegrees)
                    val rawVision = withTimeoutOrNull(4000L) {
                        recognizer.process(rawInput).await()
                    }
                    val fallbackText = rawVision?.text?.trim() ?: ""
                    if (fallbackText.length > text.length) {
                        text = fallbackText
                    }
                } catch (t: Throwable) {
                    SecurityAudit.logError("DocumentExtractionEngine", "Raw fallback OCR attempt skipped", t)
                }
            }

            if (text.isBlank()) {
                ExtractionResult.Error("No readable medical text recognized in this document image.")
            } else {
                ExtractionResult.Success(
                    rawText = text,
                    pagesProcessed = 1,
                    isPartial = false
                )
            }
        } catch (t: Throwable) {
            SecurityAudit.logError("DocumentExtractionEngine", "Image extraction crashed", t)
            ExtractionResult.Error("Image processing failed: ${t.localizedMessage ?: "Unknown error"}")
        } finally {
            try {
                recognizer?.close()
            } catch (_: Exception) {}
        }
    }
}
