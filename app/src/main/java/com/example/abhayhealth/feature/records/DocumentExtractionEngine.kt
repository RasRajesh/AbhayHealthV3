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

    const val MAX_PDF_PAGES = 5

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
        } catch (e: Exception) {
            SecurityAudit.logError("DocumentExtractionEngine", "Failed during document extraction", e)
            ExtractionResult.Error(e.localizedMessage ?: "Failed to extract text from document.")
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

            for (i in 0 until pagesToProcess) {
                val page = renderer.openPage(i)
                var bitmap: Bitmap? = null

                try {
                    val width = page.width * 2
                    val height = page.height * 2

                    val renderedBitmap = Bitmap.createBitmap(
                        width,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap = renderedBitmap

                    page.render(
                        renderedBitmap,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )

                    val inputImage = InputImage.fromBitmap(renderedBitmap, 0)
                    val visionText = recognizer.process(inputImage).await()

                    if (visionText.text.isNotBlank()) {
                        if (fullTextBuilder.isNotEmpty()) {
                            fullTextBuilder.append("\n--- Page ${i + 1} ---\n")
                        }
                        fullTextBuilder.append(visionText.text)
                    }
                } finally {
                    try {
                        bitmap?.let {
                            if (!it.isRecycled) it.recycle()
                        }
                    } catch (_: Exception) {
                        // Continue closing the PDF page even if recycling fails.
                    }

                    try {
                        page.close()
                    } catch (_: Exception) {
                        // Continue releasing remaining resources.
                    }
                }
            }

            val extractedText = fullTextBuilder.toString().trim()

            if (extractedText.isBlank()) {
                ExtractionResult.Error("No readable text could be recognized from the PDF.")
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
            val maxDimension = 2048

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

            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                decodeOptions
            ) ?: return ExtractionResult.Error("Failed to decode image file.")

            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val visionText = recognizer.process(inputImage).await()
                val text = visionText.text.trim()

                if (text.isBlank()) {
                    ExtractionResult.Error("No readable text found in image.")
                } else {
                    ExtractionResult.Success(
                        rawText = text,
                        pagesProcessed = 1,
                        isPartial = false
                    )
                }
            } finally {
                try {
                    if (!bitmap.isRecycled) bitmap.recycle()
                } catch (_: Exception) {
                    // Bitmap cleanup must not prevent recognizer cleanup.
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
