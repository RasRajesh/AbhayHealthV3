package com.example.abhayhealth.feature.records

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.abhayhealth.core.security.SecurityAudit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
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

    const val MAX_PDF_PAGES = 10

    suspend fun extractText(record: MedicalRecord): ExtractionResult = withContext(Dispatchers.IO) {
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
                extractFromPdf(file)
            } else {
                extractFromImage(file)
            }
        } catch (t: Throwable) {
            SecurityAudit.logError("DocumentExtractionEngine", "Failed during document extraction", t)
            ExtractionResult.Error(t.localizedMessage ?: "Failed to extract text from document.")
        }
    }


    private suspend fun extractFromPdf(file: File): ExtractionResult {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        return try {
            pfd = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            renderer = PdfRenderer(pfd)

            val pageCount = renderer.pageCount
            if (pageCount == 0) {
                return ExtractionResult.Error("PDF document contains no readable pages.")
            }

            val pagesToProcess = minOf(pageCount, MAX_PDF_PAGES)
            val fullTextBuilder = StringBuilder()

            var lastError: Throwable? = null

            for (i in 0 until pagesToProcess) {
                var page: PdfRenderer.Page? = null
                var bitmap: Bitmap? = null

                try {
                    page = renderer.openPage(i)
                    val maxDimension = 1600
                    val maxDim = maxOf(page.width, page.height)
                    val scale = if (maxDim > 0) (maxDimension.toFloat() / maxDim).coerceIn(1.5f, 2.2f) else 1.8f
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)

                    // ML Kit InputImage.fromBitmap requires ARGB_8888. RGB_565 throws IllegalArgumentException on device!
                    val renderedBitmap = Bitmap.createBitmap(
                        width,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap = renderedBitmap
                    // CRITICAL: Fill with pure white before rendering. PdfRenderer does not clear background,
                    // so transparent PDF pixels remain black without eraseColor!
                    renderedBitmap.eraseColor(android.graphics.Color.WHITE)

                    page.render(
                        renderedBitmap,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )

                    val inputImage = InputImage.fromBitmap(renderedBitmap, 0)
                    val visionText = withTimeoutOrNull(10000L) {
                        recognizer.process(inputImage).await()
                    }

                    if (visionText != null && visionText.text.isNotBlank()) {
                        if (fullTextBuilder.isNotEmpty()) {
                            fullTextBuilder.append("\n--- Page ${i + 1} ---\n")
                        }
                        fullTextBuilder.append(visionText.text)
                    }
                } catch (t: Throwable) {
                    lastError = t
                    SecurityAudit.logError("DocumentExtractionEngine", "Skipped rendering PDF page $i due to error", t)
                } finally {
                    try {
                        bitmap?.let {
                            if (!it.isRecycled) it.recycle()
                        }
                    } catch (_: Exception) {
                    }

                    try {
                        page?.close()
                    } catch (_: Exception) {
                    }
                }
            }

            val extractedText = fullTextBuilder.toString().trim()

            if (extractedText.isBlank()) {
                val errorMsg = lastError?.localizedMessage
                    ?: "No readable medical text could be detected from this document ($pagesToProcess page(s) analyzed)."
                ExtractionResult.Error(errorMsg)
            } else {
                ExtractionResult.Success(
                    rawText = extractedText,
                    pagesProcessed = pagesToProcess,
                    isPartial = pageCount > MAX_PDF_PAGES
                )
            }
        } finally {
            // Close each resource independently so one failure doesn't block the others.
            try {
                renderer?.close()
            } catch (_: Exception) {
            }

            try {
                pfd?.close()
            } catch (_: Exception) {
            }

            try {
                recognizer.close()
            } catch (_: Exception) {
            }
        }
    }


    private suspend fun extractFromImage(file: File): ExtractionResult {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
            val maxDimension = 1280

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
                android.media.ExifInterface(file.absolutePath)
            } catch (_: Exception) {
                null
            }
            val rotationDegrees = when (exif?.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            var preprocessedBitmap: Bitmap? = null
            try {
                // Apply grayscale desaturation and contrast enhancement to improve OCR recognition on low-quality/faint photos
                preprocessedBitmap = DocumentImagePreprocessor.preprocessForOcr(rawBitmap)

                val inputImage = InputImage.fromBitmap(preprocessedBitmap, rotationDegrees)
                val visionText = withTimeoutOrNull(7000L) {
                    recognizer.process(inputImage).await()
                }
                var text = visionText?.text?.trim() ?: ""

                // Resilient fallback: If preprocessing produced little or no text (< 15 chars),
                // test the original raw bitmap in case the document had unusual color inversion
                if (text.length < 15 && !rawBitmap.isRecycled) {
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
            } finally {
                try {
                    preprocessedBitmap?.let {
                        if (!it.isRecycled) it.recycle()
                    }
                } catch (_: Exception) {
                }
                try {
                    if (!rawBitmap.isRecycled) rawBitmap.recycle()
                } catch (_: Exception) {
                }
            }
        } finally {
            try {
                recognizer.close()
            } catch (_: Exception) {
            }
        }
    }
}
