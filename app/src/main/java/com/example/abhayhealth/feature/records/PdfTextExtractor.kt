package com.example.abhayhealth.feature.records

import com.example.abhayhealth.core.security.SecurityAudit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Ultra-fast, zero-bitmap digital PDF text extractor.
 *
 * Extracts text directly from digital PDF content streams (FlateDecode / raw)
 * without rendering bitmaps or invoking ML Kit. This eliminates native memory pressure,
 * provides sub-100ms extraction for digital lab reports & hospital packages, and prevents
 * OutOfMemory / SIGABRT crashes on memory-constrained devices.
 */
object PdfTextExtractor {

    private const val MAX_PDF_BYTES_FOR_EXTRACTION = 25 * 1024 * 1024 // 25 MiB

    fun extractText(file: File): String? {
        if (!file.exists() || !file.canRead() || file.length() == 0L) return null
        if (file.length() > MAX_PDF_BYTES_FOR_EXTRACTION) return null

        return try {
            val bytes = file.readBytes()
            val text = extractTextFromPdfBytes(bytes)
            if (text.isNotBlank() && text.length >= 30) {
                text
            } else {
                null
            }
        } catch (t: Throwable) {
            SecurityAudit.logError("PdfTextExtractor", "Direct PDF stream extraction failed", t)
            null
        }
    }

    fun extractTextFromPdfBytes(bytes: ByteArray): String {
        val streams = extractContentStreams(bytes)
        if (streams.isEmpty()) return ""

        val resultBuilder = StringBuilder()

        for (stream in streams) {
            val streamText = parsePdfContentStream(stream)
            if (streamText.isNotBlank()) {
                if (resultBuilder.isNotEmpty()) {
                    resultBuilder.append("\n")
                }
                resultBuilder.append(streamText)
            }
        }

        return resultBuilder.toString().trim()
    }

    private fun extractContentStreams(bytes: ByteArray): List<ByteArray> {
        val streams = mutableListOf<ByteArray>()
        var index = 0
        val streamKeyword = "stream".toByteArray(Charsets.US_ASCII)
        val endstreamKeyword = "endstream".toByteArray(Charsets.US_ASCII)

        while (index < bytes.size) {
            val streamStart = indexOfBytes(bytes, streamKeyword, index)
            if (streamStart == -1) break

            // Skip 'stream' and following newline (\r\n or \n)
            var contentStart = streamStart + streamKeyword.size
            if (contentStart < bytes.size && bytes[contentStart] == '\r'.code.toByte()) contentStart++
            if (contentStart < bytes.size && bytes[contentStart] == '\n'.code.toByte()) contentStart++

            val endStream = indexOfBytes(bytes, endstreamKeyword, contentStart)
            if (endStream == -1) break

            var contentEnd = endStream
            // Backtrack whitespace before endstream
            while (contentEnd > contentStart && (bytes[contentEnd - 1] == '\n'.code.toByte() || bytes[contentEnd - 1] == '\r'.code.toByte())) {
                contentEnd--
            }

            if (contentEnd > contentStart) {
                val streamLength = contentEnd - contentStart
                val rawStream = ByteArray(streamLength)
                System.arraycopy(bytes, contentStart, rawStream, 0, streamLength)

                // Try inflating if FlateDecode
                val inflated = tryInflate(rawStream)
                if (inflated != null) {
                    streams.add(inflated)
                } else {
                    // Raw uncompressed stream
                    streams.add(rawStream)
                }
            }

            index = endStream + endstreamKeyword.size
        }

        return streams
    }

    private fun tryInflate(data: ByteArray): ByteArray? {
        // Try standard zlib inflater
        try {
            val inflater = Inflater(false)
            inflater.setInput(data)
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count <= 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, count)
            }
            inflater.end()
            val result = out.toByteArray()
            if (result.isNotEmpty()) return result
        } catch (_: Throwable) {
        }

        // Try raw deflate (no zlib header)
        try {
            val inflater = Inflater(true)
            inflater.setInput(data)
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count <= 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, count)
            }
            inflater.end()
            val result = out.toByteArray()
            if (result.isNotEmpty()) return result
        } catch (_: Throwable) {
        }

        // Try InflaterInputStream
        try {
            InflaterInputStream(ByteArrayInputStream(data)).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                }
                val result = out.toByteArray()
                if (result.isNotEmpty()) return result
            }
        } catch (_: Throwable) {
        }

        return null
    }

    private fun parsePdfContentStream(streamBytes: ByteArray): String {
        val streamText = String(streamBytes, Charsets.ISO_8859_1)
        val sb = StringBuilder()
        var pos = 0
        val len = streamText.length

        while (pos < len) {
            val c = streamText[pos]

            // Look for string literal '('
            if (c == '(') {
                val start = pos + 1
                var depth = 1
                var i = start
                var escaped = false

                while (i < len && depth > 0) {
                    val curr = streamText[i]
                    if (escaped) {
                        escaped = false
                    } else if (curr == '\\') {
                        escaped = true
                    } else if (curr == '(') {
                        depth++
                    } else if (curr == ')') {
                        depth--
                    }
                    i++
                }

                if (depth == 0) {
                    val rawStr = streamText.substring(start, i - 1)
                    val unescaped = unescapePdfString(rawStr)
                    if (unescaped.isNotBlank()) {
                        if (sb.isNotEmpty() && !sb.endsWith(" ") && !sb.endsWith("\n") &&
                            !unescaped.startsWith(" ") && !unescaped.startsWith(",") &&
                            !unescaped.startsWith(":") && !unescaped.startsWith(")") &&
                            !unescaped.startsWith("-") && !unescaped.startsWith("/")
                        ) {
                            sb.append(" ")
                        }
                        sb.append(unescaped)
                    }
                    pos = i
                    continue
                }
            }

            // Look for hex string '<...>' (not '<<' dictionary)
            if (c == '<' && pos + 1 < len && streamText[pos + 1] != '<') {
                val end = streamText.indexOf('>', pos + 1)
                if (end != -1 && end - pos < 500) {
                    val hex = streamText.substring(pos + 1, end).trim()
                    val decoded = decodeHexPdfString(hex)
                    if (decoded.isNotBlank()) {
                        sb.append(decoded)
                    }
                    pos = end + 1
                    continue
                }
            }

            // Detect newline indicators in PDF operators (T*, TD, Td, ET)
            if (c == 'T' && pos + 1 < len) {
                val next = streamText[pos + 1]
                if (next == '*' || next == 'D' || next == 'd') {
                    if (sb.isNotEmpty() && !sb.endsWith('\n')) {
                        sb.append("\n")
                    }
                }
            } else if (c == 'E' && pos + 1 < len && streamText[pos + 1] == 'T') {
                if (sb.isNotEmpty() && !sb.endsWith('\n')) {
                    sb.append("\n")
                }
            }

            pos++
        }

        return sb.toString().trim()
    }

    private fun unescapePdfString(input: String): String {
        val out = StringBuilder()
        var i = 0
        val len = input.length

        while (i < len) {
            val c = input[i]
            if (c == '\\' && i + 1 < len) {
                val next = input[i + 1]
                when (next) {
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'b' -> { out.append('\b'); i += 2 }
                    'f' -> { out.append('\u000C'); i += 2 }
                    '(', ')', '\\' -> { out.append(next); i += 2 }
                    in '0'..'7' -> {
                        // Octal sequence \ddd (up to 3 octal digits)
                        var octal = ""
                        var j = i + 1
                        while (j < len && j - (i + 1) < 3 && input[j] in '0'..'7') {
                            octal += input[j]
                            j++
                        }
                        val code = octal.toIntOrNull(8) ?: 32
                        out.append(code.toChar())
                        i = j
                    }
                    else -> {
                        out.append(next)
                        i += 2
                    }
                }
            } else {
                out.append(c)
                i++
            }
        }

        return out.toString()
    }

    private fun decodeHexPdfString(hex: String): String {
        val clean = hex.replace(Regex("""\s+"""), "")
        if (clean.length % 2 != 0 && clean.length < 2) return ""
        val bytes = ByteArray(clean.length / 2)
        var i = 0
        while (i + 1 < clean.length) {
            val byteVal = clean.substring(i, i + 2).toIntOrNull(16) ?: break
            bytes[i / 2] = byteVal.toByte()
            i += 2
        }
        return String(bytes, Charsets.ISO_8859_1).filter { it.isLetterOrDigit() || it.isWhitespace() || it in ".,-/:%()><" }
    }

    private fun indexOfBytes(source: ByteArray, target: ByteArray, fromIndex: Int = 0): Int {
        if (target.isEmpty() || fromIndex >= source.size) return -1
        val max = source.size - target.size
        for (i in fromIndex..max) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
