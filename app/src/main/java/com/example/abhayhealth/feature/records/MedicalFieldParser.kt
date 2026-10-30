package `in`.abhayhealth.app

import `in`.abhayhealth.app.R.feature.records

import `in`.abhayhealth.app.domain.model.ParsedMedicalResult
import `in`.abhayhealth.app.feature.medicines.PrescriptionMedicationEntity
import java.util.UUID

object MedicalFieldParser {

    private val TITLE_KEYWORDS = listOf(
        "SAMPLE PRESCRIPTION",
        "PRESCRIPTION",
        "LABORATORY REPORT",
        "DIAGNOSTIC REPORT",
        "BLOOD TEST REPORT",
        "HAEMATOLOGY REPORT",
        "PATHOLOGY REPORT",
        "CLINICAL SUMMARY",
        "CLINIC",
        "TEST REPORT"
    )

    private val DATE_PATTERNS = listOf(
        // Day/month/year: 27-Apr-2020, 15/08/2025, 15-08-2025, 15.08.2025, 15.9.26
        Regex(
            """\b(?:0[1-9]|[12]\d|3[01])[/.-](?:0[1-9]|1[0-2]|Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[/.-](?:19|20)?\d{2}\b""",
            RegexOption.IGNORE_CASE
        ),
        // Year/month/day: 2025-08-15, 2025/08/15
        Regex(
            """\b(?:19|20)\d{2}[/.-](?:0[1-9]|1[0-2])[/.-](?:0[1-9]|[12]\d|3[01])\b""",
            RegexOption.IGNORE_CASE
        ),
        // Text month: 27 Apr 2020, 15 Aug 2025, 15 August 2025
        Regex(
            """\b(?:0[1-9]|[12]\d|3[01])\s+(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\s+(?:19|20)?\d{2}\b""",
            RegexOption.IGNORE_CASE
        )
    )

    // Expanded Vitals and Lab test extraction pattern
    // Word boundary \b matches test names anywhere in OCR text lines
    private val TEST_PATTERN = Regex(
        """(?im)\b(BP|Blood Pressure|Temp|Temperature|CRP|Hemoglobin|Fast Blood Glucose|Fasting Glucose|Random Glucose|Platelet Count|Cholesterol|Triglycerides|Creatinine|Bilirubin|Potassium|Vitamin D|Uric Acid|HbA1c|Glucose|Calcium|Sodium|TSH|SGOT|SGPT|WBC|RBC|HDL|LDL|Urea|Hb|T3|T4)\b\s*[:\-=(deg)]*\s*([<>≤≥]?\s*\d+(?:[\/\s.,]\d+)?)\s*([a-zA-Zµμ%/0-9^._-]+)?\s*(\([^)\r\n]+\))?"""
    )

    // Medicine line pattern: TAB, CAP, SYP, INJ, DROPS
    private val MEDICINE_LINE_PATTERN = Regex(
        """(?im)(?:\d+[\)\.-]\s*)?(TAB|CAP|SYP|INJ|DROPS)\.?\s+([A-Za-z0-9\s_-]+?)(?=\s+(?:\d+\s*(?:Morning|Night|Aft|Eve|ml|Tablet|Cap|Days|SOS|Before|After)|\r?\n|$))""",
        RegexOption.IGNORE_CASE
    )

    fun parseDocumentTitle(rawText: String, defaultTitle: String): String {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        for (line in lines) {
            val upper = line.uppercase()
            for (keyword in TITLE_KEYWORDS) {
                if (upper.contains(keyword)) {
                    return line
                }
            }
        }

        return lines.firstOrNull()?.take(40) ?: defaultTitle
    }

    fun parseReportDate(rawText: String): String? {
        for (pattern in DATE_PATTERNS) {
            val match = pattern.find(rawText)
            if (match != null) {
                return match.value
            }
        }
        return null
    }

    fun parseTestResults(rawText: String): List<ParsedMedicalResult> {
        val results = mutableListOf<ParsedMedicalResult>()
        val seenTests = mutableSetOf<String>()

        for (match in TEST_PATTERN.findAll(rawText)) {
            var testName = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            var unit = match.groupValues[3].trim()
            val referenceRange = match.groupValues[4].trim()

            if (testName.equals("BP", ignoreCase = true)) {
                testName = "Blood Pressure"
                if (unit.isBlank()) unit = "mmHg"
            } else if (testName.equals("Temp", ignoreCase = true)) {
                testName = "Temperature"
                if (unit.isBlank()) unit = "°C"
            }

            val key = testName.lowercase()
            if (seenTests.add(key)) {
                results.add(
                    ParsedMedicalResult(
                        id = UUID.randomUUID().toString(),
                        testName = testName,
                        value = value,
                        unit = unit,
                        referenceRange = referenceRange
                    )
                )
            }
        }

        return results
    }

    fun parsePrescriptionMedicines(rawText: String, accountUid: String): List<PrescriptionMedicationEntity> {
        val medicines = mutableListOf<PrescriptionMedicationEntity>()
        val seenNames = mutableSetOf<String>()

        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        for (i in lines.indices) {
            val line = lines[i]
            val match = MEDICINE_LINE_PATTERN.find(line)
            if (match != null) {
                val form = match.groupValues[1].uppercase()
                val name = match.groupValues[2].trim()
                if (name.length < 2) continue

                val fullMedName = "$form $name"
                val key = fullMedName.lowercase()

                if (seenNames.add(key)) {
                    // Extract dosage & schedule instructions from current line or next line
                    val contextLine = if (i + 1 < lines.size) "$line ${lines[i + 1]}" else line
                    val isBeforeFood = contextLine.contains("Before Food", ignoreCase = true)
                    val isAfterFood = contextLine.contains("After Food", ignoreCase = true)
                    val instruction = when {
                        isBeforeFood -> "Before Food"
                        isAfterFood -> "After Food"
                        else -> "As Directed"
                    }

                    // Schedule slot determination
                    val isMorning = contextLine.contains("Morning", ignoreCase = true) || contextLine.contains("1-0-0", ignoreCase = true) || contextLine.contains("1-0-1", ignoreCase = true)
                    val isAfternoon = contextLine.contains("Aft", ignoreCase = true) || contextLine.contains("Afternoon", ignoreCase = true) || contextLine.contains("1-1-1", ignoreCase = true)
                    val isEvening = contextLine.contains("Eve", ignoreCase = true) || contextLine.contains("Evening", ignoreCase = true)
                    val isNight = contextLine.contains("Night", ignoreCase = true) || contextLine.contains("0-0-1", ignoreCase = true) || contextLine.contains("1-0-1", ignoreCase = true)

                    val primarySlot = when {
                        isMorning -> "MORNING (08:30)"
                        isAfternoon -> "AFTERNOON (13:30)"
                        isEvening -> "EVENING (19:00)"
                        isNight -> "NIGHT (21:30)"
                        else -> "MORNING (08:30)"
                    }

                    medicines.add(
                        PrescriptionMedicationEntity(
                            accountUid = accountUid,
                            name = fullMedName,
                            dosage = instruction,
                            timeSlot = primarySlot
                        )
                    )

                    // If prescribed for multiple slots (e.g., Morning and Night)
                    if (isNight && isMorning) {
                        medicines.add(
                            PrescriptionMedicationEntity(
                                accountUid = accountUid,
                                name = fullMedName,
                                dosage = instruction,
                                timeSlot = "NIGHT (21:30)"
                            )
                        )
                    }
                }
            }
        }

        return medicines
    }

    // Keep the existing storage format so previously saved results remain readable.
    fun serializeResults(results: List<ParsedMedicalResult>): String {
        if (results.isEmpty()) return "[]"

        return results.joinToString(";;;") { result ->
            "${escape(result.id)}|||${escape(result.testName)}|||${escape(result.value)}|||${escape(result.unit)}|||${escape(result.referenceRange)}"
        }
    }

    fun deserializeResults(serialized: String): List<ParsedMedicalResult> {
        if (serialized.isBlank() || serialized == "[]") return emptyList()

        val results = mutableListOf<ParsedMedicalResult>()

        for (item in serialized.split(";;;")) {
            if (item.isBlank()) continue

            val parts = item.split("|||")
            if (parts.size >= 3) {
                results.add(
                    ParsedMedicalResult(
                        id = unescape(parts.getOrElse(0) { UUID.randomUUID().toString() }),
                        testName = unescape(parts.getOrElse(1) { "Medical Test" }),
                        value = unescape(parts.getOrElse(2) { "" }),
                        unit = unescape(parts.getOrElse(3) { "" }),
                        referenceRange = unescape(parts.getOrElse(4) { "" })
                    )
                )
            }
        }

        return results
    }

    private fun escape(value: String): String =
        value.replace("|||", " ").replace(";;;", " ")

    private fun unescape(value: String): String = value
}
