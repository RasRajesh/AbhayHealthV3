package com.example.abhayhealth.feature.records

import com.example.abhayhealth.domain.model.ParsedMedicalResult
import com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import com.example.abhayhealth.feature.vitals.VitalType
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
        "HOSPITAL",
        "HEALTH REPORT",
        "TEST REPORT"
    )

    private val DATE_PATTERNS = listOf(
        // Day/month/year: 27-Apr-2020, 15/08/2025, 15-08-2025, 15.08.2025
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

    // Lab test indicator names
    private val TEST_PATTERN = Regex(
        """(?im)\b(Blood Pressure|BP|Systolic|Diastolic|Pulse|Heart Rate|Temp|Temperature|Weight|SpO2|Oxygen|Fast(?:ing)? Blood Glucose|Fasting Glucose|FBS|Random Blood Sugar|RBS|PPBS|Postprandial Glucose|Blood Sugar|HbA1c|Hemoglobin|Hb|CRP|Platelet Count|Platelets|Total Cholesterol|Cholesterol|Triglycerides|Creatinine|Serum Creatinine|Bilirubin|Potassium|Vitamin D|Vitamin B12|Uric Acid|Calcium|Sodium|TSH|SGOT|AST|SGPT|ALT|WBC|RBC|HDL|LDL|Urea|Blood Urea|Hb|T3|T4)\b\s*[:\-=(deg)]*\s*([<>≤≥]?\s*\d+(?:[\/\s.,]\d+)?)\s*([a-zA-Zµμ%/0-9^._-]+)?\s*(\([^)\r\n]+\))?"""
    )

    // Standard medical forms prefix
    private val MEDICINE_FORM_PREFIX = Regex(
        """(?im)^(?:[0-9]+[\.\)\-]\s*)?(TAB|TABLET|CAP|CAPSULE|SYP|SYRUP|INJ|INJECTION|DROPS|OINT|OINTMENT|GEL|CREAM)\.?\s+([A-Za-z0-9\s_+/-]+)""",
        RegexOption.IGNORE_CASE
    )

    // Common medicine names to detect even without form prefix
    private val COMMON_DRUGS = listOf(
        "Paracetamol", "Dolo", "Calpol", "Crocin", "Metformin", "Glimepiride",
        "Telmisartan", "Amlodipine", "Atorvastatin", "Rosuvastatin", "Pantoprazole",
        "Pan 40", "Pan-D", "Omeprazole", "Rabeprazole", "Amoxicillin", "Augmentin",
        "Azithromycin", "Cetirizine", "Levocetirizine", "Montelukast", "Montair",
        "Losartan", "Aspirin", "Ecosprin", "Clopidogrel", "Levothyroxine", "Thyronorm",
        "Ciprofloxacin", "Doxycycline", "Ibuprofen", "Diclofenac", "Voveran",
        "Combiflam", "Vildagliptin", "Teneligliptin", "Dapagliflozin", "Empagliflozin",
        "Neurobion", "Becosules", "Shelcal", "Limcee", "Supradyn", "Zincovit"
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

            when {
                testName.equals("BP", ignoreCase = true) -> {
                    testName = "Blood Pressure"
                    if (unit.isBlank()) unit = "mmHg"
                }
                testName.equals("Temp", ignoreCase = true) -> {
                    testName = "Temperature"
                    if (unit.isBlank()) unit = "°C"
                }
                testName.equals("FBS", ignoreCase = true) -> {
                    testName = "Fasting Blood Glucose"
                    if (unit.isBlank()) unit = "mg/dL"
                }
                testName.equals("RBS", ignoreCase = true) || testName.equals("PPBS", ignoreCase = true) -> {
                    testName = "Blood Glucose"
                    if (unit.isBlank()) unit = "mg/dL"
                }
                testName.equals("Hb", ignoreCase = true) -> {
                    testName = "Hemoglobin"
                    if (unit.isBlank()) unit = "g/dL"
                }
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
            var medName: String? = null
            var dosage = "As Directed"
            var primarySlot = "MORNING (08:30)"

            // 1. Check for form prefix (e.g. TAB Paracetamol 500mg)
            val formMatch = MEDICINE_FORM_PREFIX.find(line)
            if (formMatch != null) {
                val form = formMatch.groupValues[1].uppercase()
                val rawName = formMatch.groupValues[2].trim()
                // Take clean medicine name before instruction words
                val cleanName = rawName.split(Regex("""(?i)\s+(?:1-0-1|1-0-0|0-0-1|1-1-1|Morning|Night|Before|After|Days|Tab|Cap)\b"""))
                    .firstOrNull()?.trim() ?: rawName
                if (cleanName.length >= 2) {
                    medName = "$form $cleanName"
                }
            } else {
                // 2. Check for known medicine names in line
                for (drug in COMMON_DRUGS) {
                    if (line.contains(drug, ignoreCase = true)) {
                        // Extract drug and potential strength
                        val strengthMatch = Regex("""(?i)\b${Regex.escape(drug)}(?:\s+\d+\s*(?:mg|ml|mcg|g))?""").find(line)
                        medName = strengthMatch?.value ?: drug
                        break
                    }
                }
            }

            if (medName != null) {
                val key = medName.lowercase()
                if (seenNames.add(key)) {
                    val contextLine = if (i + 1 < lines.size) "$line ${lines[i + 1]}" else line
                    val isBeforeFood = contextLine.contains("Before Food", ignoreCase = true) || contextLine.contains("Empty Stomach", ignoreCase = true)
                    val isAfterFood = contextLine.contains("After Food", ignoreCase = true)

                    dosage = when {
                        isBeforeFood -> "Before Food"
                        isAfterFood -> "After Food"
                        else -> "As Directed"
                    }

                    val isMorning = contextLine.contains("Morning", ignoreCase = true) || contextLine.contains("1-0-0", ignoreCase = true) || contextLine.contains("1-0-1", ignoreCase = true) || contextLine.contains("1-1-1", ignoreCase = true)
                    val isAfternoon = contextLine.contains("Aft", ignoreCase = true) || contextLine.contains("Afternoon", ignoreCase = true) || contextLine.contains("1-1-1", ignoreCase = true)
                    val isEvening = contextLine.contains("Eve", ignoreCase = true) || contextLine.contains("Evening", ignoreCase = true)
                    val isNight = contextLine.contains("Night", ignoreCase = true) || contextLine.contains("0-0-1", ignoreCase = true) || contextLine.contains("1-0-1", ignoreCase = true) || contextLine.contains("1-1-1", ignoreCase = true)

                    primarySlot = when {
                        isMorning -> "MORNING (08:30)"
                        isAfternoon -> "AFTERNOON (13:30)"
                        isEvening -> "EVENING (19:00)"
                        isNight -> "NIGHT (21:30)"
                        else -> "MORNING (08:30)"
                    }

                    medicines.add(
                        PrescriptionMedicationEntity(
                            accountUid = accountUid,
                            name = medName,
                            dosage = dosage,
                            timeSlot = primarySlot
                        )
                    )

                    // Add second slot if prescribed twice daily
                    if (isNight && isMorning) {
                        medicines.add(
                            PrescriptionMedicationEntity(
                                accountUid = accountUid,
                                name = medName,
                                dosage = dosage,
                                timeSlot = "NIGHT (21:30)"
                            )
                        )
                    }
                }
            }
        }

        return medicines
    }

    /**
     * Extracts vitals from test results and converts them to VitalReadingEntity records
     */
    fun extractVitalsFromResults(results: List<ParsedMedicalResult>, accountUid: String): List<VitalReadingEntity> {
        val vitals = mutableListOf<VitalReadingEntity>()
        val now = System.currentTimeMillis()

        for (item in results) {
            val name = item.testName.lowercase()
            val valStr = item.value

            if (name.contains("blood pressure") || name == "bp") {
                // e.g. "120/80" or "120 / 80"
                val bpParts = valStr.split(Regex("""[/,\s]+""")).mapNotNull { it.toDoubleOrNull() }
                if (bpParts.size >= 2) {
                    vitals.add(
                        VitalReadingEntity(
                            accountUid = accountUid,
                            type = VitalType.BLOOD_PRESSURE.name,
                            systolic = bpParts[0],
                            diastolic = bpParts[1],
                            timestamp = now,
                            notes = "Extracted from medical report"
                        )
                    )
                }
            } else if (name.contains("weight")) {
                val weight = valStr.toDoubleOrNull()
                if (weight != null && weight in 20.0..300.0) {
                    vitals.add(
                        VitalReadingEntity(
                            accountUid = accountUid,
                            type = VitalType.WEIGHT.name,
                            weightKg = weight,
                            timestamp = now,
                            notes = "Extracted from medical report"
                        )
                    )
                }
            }
        }
        return vitals
    }

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
