package com.example.abhayhealth.feature.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PdfTextExtractorTest {

    @Test
    fun testExtractTextFromPdfBytesSimpleTj() {
        val pdfContent = """
            %PDF-1.4
            1 0 obj
            << /Length 65 >>
            stream
            BT
            /F1 12 Tf
            (COMPLETE BLOOD COUNT) Tj
            T*
            (Haemoglobin 9.1 g/dl) Tj
            ET
            endstream
            endobj
        """.trimIndent()

        val text = PdfTextExtractor.extractTextFromPdfBytes(pdfContent.toByteArray(Charsets.US_ASCII))
        assertTrue(text.contains("COMPLETE BLOOD COUNT"))
        assertTrue(text.contains("Haemoglobin 9.1 g/dl"))
    }

    @Test
    fun testExtractTextFromPdfBytesWithTjArray() {
        val pdfContent = """
            %PDF-1.4
            1 0 obj
            << /Length 120 >>
            stream
            BT
            /F1 10 Tf
            [(AARTHI ) -200 (SCANS ) -200 (& LABS)] TJ
            T*
            [(RBC ) -200 (4.23 ) -200 (Million/cmm ) -200 (3.8-4.8)] TJ
            ET
            endstream
            endobj
        """.trimIndent()

        val text = PdfTextExtractor.extractTextFromPdfBytes(pdfContent.toByteArray(Charsets.US_ASCII))
        assertTrue(text.contains("AARTHI SCANS & LABS"))
        assertTrue(text.contains("RBC 4.23 Million/cmm 3.8-4.8"))
    }

    @Test
    fun testExtractTextFromCompressedFlateStream() {
        val rawStream = """
            BT
            /F1 12 Tf
            (GLUCOSE FASTING 104.0 mg/dL 74-100) Tj
            ET
        """.trimIndent().toByteArray(Charsets.ISO_8859_1)

        val deflater = Deflater()
        deflater.setInput(rawStream)
        deflater.finish()
        val compressedStream = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            compressedStream.write(buf, 0, count)
        }
        deflater.end()

        val header = "%PDF-1.4\n1 0 obj\n<< /Length ${compressedStream.size()} /Filter /FlateDecode >>\nstream\n".toByteArray(Charsets.US_ASCII)
        val footer = "\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII)

        val pdfBytes = header + compressedStream.toByteArray() + footer

        val text = PdfTextExtractor.extractTextFromPdfBytes(pdfBytes)
        assertTrue(text.contains("GLUCOSE FASTING 104.0 mg/dL 74-100"))
    }

    @Test
    fun testSkipBinaryImageStream() {
        val pdfContent = """
            %PDF-1.4
            1 0 obj
            << /Type /XObject /Subtype /Image /Width 100 /Height 100 /Filter /DCTDecode >>
            stream
            (This is raw image binary that should be skipped by the text extractor)
            endstream
            endobj
            2 0 obj
            << /Length 50 >>
            stream
            BT
            (Real Report Text) Tj
            ET
            endstream
            endobj
        """.trimIndent()

        val text = PdfTextExtractor.extractTextFromPdfBytes(pdfContent.toByteArray(Charsets.US_ASCII))
        assertFalse(text.contains("This is raw image binary"))
        assertTrue(text.contains("Real Report Text"))
    }

    @Test
    fun testMalformedStreamResilience() {
        // Stream with unclosed parenthesis and random binary characters
        val malformedStream = """
            %PDF-1.4
            1 0 obj
            << /Length 200 >>
            stream
            BT
            (Unclosed string without closing paren
            some binary \x00 \xFF (another paren
            (Valid Text After) Tj
            ET
            endstream
            endobj
        """.trimIndent()

        val text = PdfTextExtractor.extractTextFromPdfBytes(malformedStream.toByteArray(Charsets.US_ASCII))
        // Should complete without infinite loop, out of memory, or throwing
        assertFalse(text.isBlank())
    }
}
