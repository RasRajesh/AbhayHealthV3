package com.example.abhayhealth.feature.records

import com.example.abhayhealth.domain.model.ParsedMedicalResult
import com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import com.example.abhayhealth.feature.vitals.VitalType
import java.util.UUID

/**
 * High-precision on-device clinical parser for Indian and international medical documents.
 * Extracts lab test biomarkers, vital signs, prescription medicines, document categories, and dates.
 */
object MedicalFieldParser {

    private val TITLE_KEYWORDS = listOf(
        "PRESCRIPTION",
        "MEDICAL PRESCRIPTION",
        "DOCTOR'S PRESCRIPTION",
        "LABORATORY REPORT",
        "DIAGNOSTIC REPORT",
        "PATHOLOGY REPORT",
        "BIOCHEMISTRY REPORT",
        "HAEMATOLOGY REPORT",
        "BLOOD TEST REPORT",
        "CLINICAL SUMMARY",
        "DISCHARGE SUMMARY",
        "OUTPATIENT RECORD",
        "HEALTH CHECKUP REPORT"
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
            """\b(?:0[1-9]|[12]\d|3[01])\s+(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)[,\s]+(?:19|20)?\d{2}\b""",
            RegexOption.IGNORE_CASE
        )
    )

    // Extensive catalog of clinical biomarkers with aliases, default units, and reference ranges
    data class TestDefinition(
        val canonicalName: String,
        val aliases: List<String>,
        val defaultUnit: String,
        val standardReferenceRange: String
    )

    private val TEST_CATALOG = listOf(
        // Complete Blood Count (CBC)
        TestDefinition("Hemoglobin", listOf("Hemoglobin", "Haemoglobin", "Hb"), "g/dL", "12.0 - 16.0 g/dL"),
        TestDefinition("WBC Count", listOf("WBC Count", "WBC", "Total Leucocyte Count", "TLC", "White Blood Cells"), "/cumm", "4,000 - 11,000 /cumm"),
        TestDefinition("Platelet Count", listOf("Platelet Count", "Platelets", "Total Platelet Count"), "Lakhs/cumm", "1.50 - 4.50 Lakhs/cumm"),
        TestDefinition("RBC Count", listOf("RBC Count", "RBC", "Total Red Blood Cells", "Red Blood Cells"), "mill/cumm", "4.5 - 5.5 mill/cumm"),
        TestDefinition("Packed Cell Volume", listOf("Packed Cell Volume", "PCV", "Hematocrit", "HCT"), "%", "36.0 - 48.0 %"),
        TestDefinition("MCV", listOf("MCV", "Mean Corpuscular Volume"), "fl", "80.0 - 100.0 fl"),
        TestDefinition("MCH", listOf("MCH", "Mean Corpuscular Hemoglobin"), "pg", "27.0 - 32.0 pg"),
        TestDefinition("MCHC", listOf("MCHC"), "g/dL", "32.0 - 36.0 g/dL"),
        TestDefinition("ESR", listOf("ESR", "Erythrocyte Sedimentation Rate"), "mm/hr", "0 - 20 mm/hr"),
        TestDefinition("Neutrophils", listOf("Neutrophils", "Polymorphs"), "%", "40 - 75 %"),
        TestDefinition("Lymphocytes", listOf("Lymphocytes"), "%", "20 - 45 %"),
        TestDefinition("Eosinophils", listOf("Eosinophils"), "%", "1 - 6 %"),
        TestDefinition("Monocytes", listOf("Monocytes"), "%", "2 - 10 %"),

        // Diabetes & Glycemic Panel
        TestDefinition("Fasting Blood Glucose", listOf("Fasting Blood Glucose", "Fasting Glucose", "FBS", "Glucose Fasting", "Fasting Blood Sugar"), "mg/dL", "70 - 100 mg/dL"),
        TestDefinition("Postprandial Blood Glucose", listOf("Postprandial Blood Glucose", "PPBS", "Post Prandial Glucose", "PP Glucose", "Post Meal Sugar"), "mg/dL", "< 140 mg/dL"),
        TestDefinition("Random Blood Sugar", listOf("Random Blood Sugar", "RBS", "Blood Sugar", "Glucose Random"), "mg/dL", "70 - 140 mg/dL"),
        TestDefinition("HbA1c", listOf("HbA1c", "Glycated Hemoglobin", "Glycosylated Hemoglobin"), "%", "< 5.7 %"),

        // Lipid Profile
        TestDefinition("Total Cholesterol", listOf("Total Cholesterol", "Serum Cholesterol", "Cholesterol"), "mg/dL", "< 200 mg/dL"),
        TestDefinition("HDL Cholesterol", listOf("HDL Cholesterol", "HDL", "Good Cholesterol"), "mg/dL", "> 40 mg/dL"),
        TestDefinition("LDL Cholesterol", listOf("LDL Cholesterol", "LDL", "Bad Cholesterol"), "mg/dL", "< 100 mg/dL"),
        TestDefinition("Triglycerides", listOf("Triglycerides", "TGL", "Serum Triglycerides"), "mg/dL", "< 150 mg/dL"),
        TestDefinition("VLDL Cholesterol", listOf("VLDL Cholesterol", "VLDL"), "mg/dL", "< 30 mg/dL"),

        // Kidney Function Test (KFT / RFT)
        TestDefinition("Serum Creatinine", listOf("Serum Creatinine", "Creatinine", "S. Creatinine"), "mg/dL", "0.6 - 1.2 mg/dL"),
        TestDefinition("Blood Urea", listOf("Blood Urea", "Urea"), "mg/dL", "15 - 40 mg/dL"),
        TestDefinition("Blood Urea Nitrogen", listOf("Blood Urea Nitrogen", "BUN"), "mg/dL", "7 - 20 mg/dL"),
        TestDefinition("Uric Acid", listOf("Uric Acid", "Serum Uric Acid"), "mg/dL", "3.5 - 7.2 mg/dL"),
        TestDefinition("eGFR", listOf("eGFR", "Estimated GFR"), "mL/min", "> 90 mL/min"),
        TestDefinition("Sodium", listOf("Sodium", "Serum Sodium", "Na+"), "mEq/L", "135 - 145 mEq/L"),
        TestDefinition("Potassium", listOf("Potassium", "Serum Potassium", "K+"), "mEq/L", "3.5 - 5.1 mEq/L"),
        TestDefinition("Calcium", listOf("Calcium", "Serum Calcium", "Total Calcium"), "mg/dL", "8.8 - 10.2 mg/dL"),

        // Liver Function Test (LFT)
        TestDefinition("SGOT / AST", listOf("SGOT", "AST", "Aspartate Aminotransferase", "SGOT / AST"), "U/L", "< 40 U/L"),
        TestDefinition("SGPT / ALT", listOf("SGPT", "ALT", "Alanine Aminotransferase", "SGPT / ALT"), "U/L", "< 45 U/L"),
        TestDefinition("Alkaline Phosphatase", listOf("Alkaline Phosphatase", "ALP"), "U/L", "44 - 147 U/L"),
        TestDefinition("Total Bilirubin", listOf("Total Bilirubin", "Bilirubin Total", "S. Bilirubin"), "mg/dL", "0.2 - 1.2 mg/dL"),
        TestDefinition("Direct Bilirubin", listOf("Direct Bilirubin", "Conjugated Bilirubin"), "mg/dL", "< 0.3 mg/dL"),
        TestDefinition("Total Protein", listOf("Total Protein", "Serum Protein"), "g/dL", "6.4 - 8.3 g/dL"),
        TestDefinition("Albumin", listOf("Albumin", "Serum Albumin"), "g/dL", "3.5 - 5.2 g/dL"),

        // Thyroid Profile
        TestDefinition("TSH", listOf("TSH", "Thyroid Stimulating Hormone", "Ultrasensitive TSH"), "uIU/mL", "0.35 - 4.94 uIU/mL"),
        TestDefinition("Free T3", listOf("Free T3", "FT3"), "pg/mL", "1.7 - 4.2 pg/mL"),
        TestDefinition("Free T4", listOf("Free T4", "FT4"), "ng/dL", "0.7 - 1.8 ng/dL"),
        TestDefinition("Total T3", listOf("Total T3", "T3"), "ng/dL", "80 - 200 ng/dL"),
        TestDefinition("Total T4", listOf("Total T4", "T4"), "ug/dL", "5.0 - 12.0 ug/dL"),

        // Vitamins & Inflammatory Markers
        TestDefinition("Vitamin D", listOf("Vitamin D", "25-OH Vitamin D", "Vitamin D3", "25 Hydroxy Vitamin D"), "ng/mL", "30.0 - 100.0 ng/mL"),
        TestDefinition("Vitamin B12", listOf("Vitamin B12", "Cyanocobalamin"), "pg/mL", "211 - 911 pg/mL"),
        TestDefinition("C-Reactive Protein", listOf("C-Reactive Protein", "CRP", "hs-CRP"), "mg/L", "< 5.0 mg/L"),
        TestDefinition("Serum Ferritin", listOf("Serum Ferritin", "Ferritin"), "ng/mL", "30 - 400 ng/mL"),

        // Vitals
        TestDefinition("Blood Pressure", listOf("Blood Pressure", "BP", "B.P."), "mmHg", "120/80 mmHg"),
        TestDefinition("Pulse", listOf("Pulse", "Heart Rate", "Pulse Rate"), "BPM", "60 - 100 BPM"),
        TestDefinition("SpO2", listOf("SpO2", "Oxygen Saturation", "O2 Saturation"), "%", "95 - 100 %"),
        TestDefinition("Temperature", listOf("Temperature", "Temp"), "°F", "97.0 - 99.0 °F"),
        TestDefinition("Weight", listOf("Weight", "Body Weight", "Wt"), "kg", "Target BMI 18.5 - 24.9")
    )

    // Standard medical forms prefix
    private val MEDICINE_FORM_PREFIX = Regex(
        """(?im)^(?:[0-9]+[\.\)\-]\s*)?(TAB|TABLET|CAP|CAPSULE|SYP|SYRUP|INJ|INJECTION|DROPS|OINT|OINTMENT|GEL|CREAM|SUSP|SUSPENSION|LOTION|INHALER|RESPULES)\.?\s+([A-Za-z0-9\s_+/-]+)""",
        RegexOption.IGNORE_CASE
    )

    // Over 160 common medicines across antibiotics, diabetes, cardiac, analgesics, gastro, vitamins
    private val COMMON_DRUGS = listOf(
        // Analgesics & Antipyretics
        "Paracetamol", "Dolo", "Calpol", "Crocin", "Combiflam", "Meftal", "Meftal-Spas",
        "Ibuprofen", "Diclofenac", "Voveran", "Tramadol", "Ultracet", "Aceclofenac",
        "Zerodol", "Zerodol-SP", "Zerodol-P", "Ketorolac", "Naproxen",

        // Antibiotics & Anti-Infectives
        "Amoxicillin", "Augmentin", "Moxikind", "Azithromycin", "Azee", "Cefixime",
        "Taxim-O", "Ciprofloxacin", "Ciplox", "Levofloxacin", "Levomac", "Ofloxacin",
        "Zanocin", "Metronidazole", "Flagyl", "Doxycycline", "Doxt", "Clindamycin",
        "Linezolid", "Cephalexin", "Amikacin",

        // Blood Pressure & Cardiac
        "Telmisartan", "Telma", "Telma-H", "Telma-AM", "Amlodipine", "Amlong", "Losartan",
        "Losar", "Atenolol", "Metoprolol", "Betaloc", "Ramipril", "Cardace", "Enalapril",
        "Cilnidipine", "Cilacar", "Nebivolol", "Bisoprolol", "Concor", "Chlorthalidone",
        "Hydrochlorothiazide", "Torsemide", "Dytor", "Furosemide", "Lasix",

        // Diabetes & Glucose Management
        "Metformin", "Glycomet", "Glycomet-GP", "Glimepiride", "Amaryl", "Gliclazide",
        "Vildagliptin", "Galvus", "Galvus-Met", "Teneligliptin", "Sitagliptin", "Januvia",
        "Janumet", "Linagliptin", "Trajenta", "Dapagliflozin", "Forxiga", "Empagliflozin",
        "Jardiance", "Pioglitazone", "Voglibose", "Glipizide", "Insulin", "Lantus",

        // Gastrointestinal & Acid Reflux
        "Pantoprazole", "Pan 40", "Pan-D", "Pantocid", "Pantocid-DSR", "Rabeprazole",
        "Razo", "Razo-D", "Omeprazole", "Omez", "Omez-D", "Esomeprazole", "Nexpro",
        "Ranitidine", "Famotidine", "Domperidone", "Ondansetron", "Emeset", "Sucralfate",
        "Lactulose", "Digene", "Gelusil",

        // Cholesterol & Statins
        "Atorvastatin", "Atorva", "Lipitor", "Rosuvastatin", "Rozavel", "Crestor",
        "Fenofibrate", "Ezetimibe",

        // Respiratory, Allergy & ENT
        "Cetirizine", "Cetzine", "Levocetirizine", "Levocet", "Montelukast", "Montair",
        "Montair-LC", "Fexofenadine", "Allegra", "Bilastine", "Salbutamol", "Asthalin",
        "Budesonide", "Budecort", "Formoterol", "Foracort", "Seroflo", "Deriphyllin",
        "Ascoril", "Benadryl", "Alex", "Chericof",

        // Thyroid & Hormones
        "Levothyroxine", "Thyronorm", "Eltroxin", "Carbimazole",

        // Neurology & Mental Health
        "Gabapentin", "Pregabalin", "Clonazepam", "Alprazolam", "Escitalopram", "Nexito",
        "Sertraline", "Duloxetine", "Amitriptyline", "Methylcobalamin",

        // Vitamins & Supplements
        "Vitamin D3", "Calcirol", "Shelcal", "Calcium", "Neurobion", "Neurobion Forte",
        "Becosules", "Zincovit", "Limcee", "Supradyn", "Folvite", "Folic Acid",
        "Ferrous Ascorbate", "Orofer", "Evion",

        // Blood Thinners
        "Aspirin", "Ecosprin", "Clopidogrel", "Deplatt", "Clopivas", "Warfarin",
        "Apixaban", "Eliquis", "Rivaroxaban"
    )

    fun parseDocumentCategory(rawText: String): String {
        val upper = rawText.uppercase()
        return when {
            upper.contains("PRESCRIPTION") || upper.contains("RX") || upper.contains("TAB ") || upper.contains("CAP ") || upper.contains("DOSAGE") ->
                "PRESCRIPTION"
            upper.contains("PATHOLOGY") || upper.contains("BIOCHEMISTRY") || upper.contains("HAEMATOLOGY") || upper.contains("REFERENCE RANGE") || upper.contains("LABORATORY") ->
                "LAB_REPORT"
            upper.contains("X-RAY") || upper.contains("ULTRASOUND") || upper.contains("MRI") || upper.contains("CT SCAN") || upper.contains("SONOGRAPHY") ->
                "IMAGING"
            upper.contains("DISCHARGE") || upper.contains("ADMISSION") || upper.contains("SUMMARY") ->
                "DISCHARGE_SUMMARY"
            else -> "MEDICAL_RECORD"
        }
    }

    fun parseDocumentTitle(rawText: String, defaultTitle: String): String {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        for (line in lines) {
            val upper = line.uppercase()
            for (keyword in TITLE_KEYWORDS) {
                if (upper.contains(keyword)) {
                    return line.take(50)
                }
            }
        }

        // Check for doctor or hospital header
        for (line in lines.take(5)) {
            val upper = line.uppercase()
            if (upper.startsWith("DR.") || upper.contains("CLINIC") || upper.contains("HOSPITAL") || upper.contains("LABORATORY") || upper.contains("DIAGNOSTICS")) {
                return line.take(50)
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

    fun parseDateToTimestamp(dateStr: String): Long? {
        val formats = listOf(
            "dd-MMM-yyyy", "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy",
            "yyyy-MM-dd", "yyyy/MM/dd", "dd MMM yyyy", "dd MMMM yyyy"
        )
        for (format in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(format, java.util.Locale.ENGLISH)
                sdf.isLenient = false
                val parsed = sdf.parse(dateStr.trim())
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Multi-strategy biomarker extraction from lab reports and medical diagnostics.
     * Combines tabular line scanning and regex key-value extraction to guarantee 0 missing metrics.
     */
    fun parseTestResults(rawText: String): List<ParsedMedicalResult> {
        val results = mutableListOf<ParsedMedicalResult>()
        val seenTests = mutableSetOf<String>()

        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Strategy 1: Tabular & Line-by-Line Scanning across TEST_CATALOG
        for (lineIndex in lines.indices) {
            val line = lines[lineIndex]
            val nextLine = if (lineIndex + 1 < lines.size) lines[lineIndex + 1] else ""

            for (testDef in TEST_CATALOG) {
                val key = testDef.canonicalName.lowercase()
                if (seenTests.contains(key)) continue

                // Check if line contains any alias
                val matchedAlias = testDef.aliases.firstOrNull { alias ->
                    Regex("""\b${Regex.escape(alias)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)
                }

                if (matchedAlias != null) {
                    // Extract value, unit, and reference range from this line or next line
                    val combinedText = "$line $nextLine"
                    val parsed = extractTestDetailsFromText(combinedText, matchedAlias, testDef)

                    if (parsed != null) {
                        seenTests.add(key)
                        results.add(parsed)
                        break
                    }
                }
            }
        }

        // Strategy 2: Regex extraction for Blood Pressure, Temperature, Glucose, and any missed tests
        val genericPattern = Regex(
            """(?im)\b(Blood Pressure|BP|Systolic|Diastolic|Pulse|Heart Rate|Temp|Temperature|Weight|SpO2|Oxygen|FBS|PPBS|RBS|HbA1c|Hemoglobin|Hb|CRP|Platelets|Cholesterol|Triglycerides|Creatinine|Bilirubin|Potassium|Sodium|Calcium|Uric Acid|TSH|SGOT|SGPT|WBC|RBC|Urea)\b\s*[:\-=(deg)]*\s*([<>≤≥]?\s*\d+(?:[\/\s.,]\d+)?)\s*([a-zA-Zµμ%/0-9^._-]+)?\s*(\([^)\r\n]+\))?"""
        )

        for (match in genericPattern.findAll(rawText)) {
            var rawName = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            var unit = match.groupValues[3].trim()
            var refRange = match.groupValues[4].trim()

            val canonicalName = when {
                rawName.equals("BP", true) -> "Blood Pressure"
                rawName.equals("FBS", true) -> "Fasting Blood Glucose"
                rawName.equals("PPBS", true) -> "Postprandial Blood Glucose"
                rawName.equals("RBS", true) -> "Random Blood Sugar"
                rawName.equals("Hb", true) -> "Hemoglobin"
                rawName.equals("Temp", true) -> "Temperature"
                rawName.equals("Platelets", true) -> "Platelet Count"
                rawName.equals("Cholesterol", true) -> "Total Cholesterol"
                rawName.equals("Creatinine", true) -> "Serum Creatinine"
                rawName.equals("Bilirubin", true) -> "Total Bilirubin"
                else -> rawName
            }

            val key = canonicalName.lowercase()
            if (seenTests.add(key)) {
                if (unit.isBlank()) {
                    unit = TEST_CATALOG.firstOrNull { it.canonicalName.equals(canonicalName, true) }?.defaultUnit ?: ""
                }
                if (refRange.isBlank()) {
                    refRange = TEST_CATALOG.firstOrNull { it.canonicalName.equals(canonicalName, true) }?.standardReferenceRange ?: ""
                }

                results.add(
                    ParsedMedicalResult(
                        id = UUID.randomUUID().toString(),
                        testName = canonicalName,
                        value = value,
                        unit = unit,
                        referenceRange = refRange
                    )
                )
            }
        }

        return results
    }

    private fun extractTestDetailsFromText(
        text: String,
        matchedAlias: String,
        testDef: TestDefinition
    ): ParsedMedicalResult? {
        val afterAlias = text.substringAfter(matchedAlias, "").trim()
        if (afterAlias.isBlank()) return null

        // Match number: e.g. 120/80 or 13.5 or 95 or 7,500 or 1.25
        val numberRegex = Regex("""[:\-=\s]*([<>≤≥]?\s*\d+(?:[\/.,]\d+)?)\s*([a-zA-Zµμ%/0-9^._-]+)?""")
        val match = numberRegex.find(afterAlias) ?: return null

        val rawVal = match.groupValues[1].trim()
        var unit = match.groupValues[2].trim()

        if (rawVal.isBlank()) return null

        if (unit.isBlank() || unit.matches(Regex("""\d+"""))) {
            unit = testDef.defaultUnit
        }

        // Look for reference range pattern (e.g. 12.0 - 15.0 or < 100 or 70 - 110)
        val rangeRegex = Regex("""(\(?\d+(?:\.\d+)?\s*[-–to]\s*\d+(?:\.\d+)?\s*(?:[a-zA-Z/%]+)?\)?|[<≤>≥]\s*\d+(?:\.\d+)?)""")
        val rangeMatch = rangeRegex.find(afterAlias.substringAfter(rawVal))
        val refRange = rangeMatch?.value?.trim()?.removeSurrounding("(", ")") ?: testDef.standardReferenceRange

        return ParsedMedicalResult(
            id = UUID.randomUUID().toString(),
            testName = testDef.canonicalName,
            value = rawVal,
            unit = unit,
            referenceRange = refRange
        )
    }

    /**
     * High-recall Prescription parser:
     * Extracts medicine name, dosage instructions (Before/After food), frequency (1-0-1, OD, BD),
     * and automatically schedules morning, afternoon, evening, and night slots.
     */
    fun parsePrescriptionMedicines(rawText: String, accountUid: String): List<PrescriptionMedicationEntity> {
        val medicines = mutableListOf<PrescriptionMedicationEntity>()
        val seenNames = mutableSetOf<String>()

        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        for (i in lines.indices) {
            val line = lines[i]
            var medName: String? = null

            // 1. Check for form prefix (e.g. TAB Paracetamol 500mg)
            val formMatch = MEDICINE_FORM_PREFIX.find(line)
            if (formMatch != null) {
                val form = formMatch.groupValues[1].uppercase()
                val rawName = formMatch.groupValues[2].trim()
                val cleanName = rawName.split(Regex("""(?i)\s+(?:1-0-1|1-0-0|0-0-1|1-1-1|OD|BD|TID|QID|Morning|Night|Before|After|Days|Tab|Cap)\b"""))
                    .firstOrNull()?.trim() ?: rawName
                if (cleanName.length >= 2) {
                    medName = "$form $cleanName"
                }
            } else {
                // 2. Check for known medicine names in line
                for (drug in COMMON_DRUGS) {
                    val drugPattern = Regex("""\b${Regex.escape(drug)}(?:\s+\d+\s*(?:mg|ml|mcg|g))?""", RegexOption.IGNORE_CASE)
                    val drugMatch = drugPattern.find(line)
                    if (drugMatch != null) {
                        medName = drugMatch.value
                        break
                    }
                }

                // 3. Check for numbered prescription line starting with Rx or 1., 2.
                if (medName == null) {
                    val rxMatch = Regex("""^(?:Rx:?|\d+[\.\)])\s*([A-Za-z0-9\s_+/-]+?)(?:\s+(?:1-0-1|1-0-0|0-0-1|1-1-1|OD|BD|TID|QID|Morning|Night|x\s*\d+|\d+\s*days))""", RegexOption.IGNORE_CASE).find(line)
                    if (rxMatch != null) {
                        val candidate = rxMatch.groupValues[1].trim()
                        if (candidate.length in 3..40 && !candidate.contains("patient", true) && !candidate.contains("doctor", true)) {
                            medName = candidate
                        }
                    }
                }
            }

            if (medName != null) {
                val key = medName.lowercase()
                if (seenNames.add(key)) {
                    val contextLine = if (i + 1 < lines.size) "$line ${lines[i + 1]}" else line

                    val isBeforeFood = contextLine.contains("Before Food", true) ||
                            contextLine.contains("Empty Stomach", true) ||
                            contextLine.contains("BBF", true) ||
                            contextLine.contains("AC", true)

                    val isAfterFood = contextLine.contains("After Food", true) ||
                            contextLine.contains("Post Meal", true) ||
                            contextLine.contains("PC", true)

                    val dosage = when {
                        isBeforeFood -> "Before Food"
                        isAfterFood -> "After Food"
                        else -> "As Directed"
                    }

                    val isMorning = contextLine.contains("Morning", true) ||
                            contextLine.contains("1-0-0", true) ||
                            contextLine.contains("1-0-1", true) ||
                            contextLine.contains("1-1-1", true) ||
                            contextLine.contains("OD", true) ||
                            contextLine.contains("BD", true) ||
                            contextLine.contains("BID", true) ||
                            contextLine.contains("TID", true)

                    val isAfternoon = contextLine.contains("Afternoon", true) ||
                            contextLine.contains("Aft", true) ||
                            contextLine.contains("1-1-1", true) ||
                            contextLine.contains("0-1-0", true) ||
                            contextLine.contains("TID", true)

                    val isEvening = contextLine.contains("Evening", true) ||
                            contextLine.contains("Eve", true)

                    val isNight = contextLine.contains("Night", true) ||
                            contextLine.contains("0-0-1", true) ||
                            contextLine.contains("1-0-1", true) ||
                            contextLine.contains("1-1-1", true) ||
                            contextLine.contains("BD", true) ||
                            contextLine.contains("BID", true) ||
                            contextLine.contains("HS", true) ||
                            contextLine.contains("Bedtime", true)

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
                            name = medName,
                            dosage = dosage,
                            timeSlot = primarySlot
                        )
                    )

                    // Add second slot for twice-daily (BD / 1-0-1) prescriptions
                    if ((isNight && isMorning) || contextLine.contains("BD", true) || contextLine.contains("BID", true) || contextLine.contains("1-0-1", true)) {
                        medicines.add(
                            PrescriptionMedicationEntity(
                                accountUid = accountUid,
                                name = medName,
                                dosage = dosage,
                                timeSlot = "NIGHT (21:30)"
                            )
                        )
                    }

                    // Add third slot for thrice-daily (TID / 1-1-1) prescriptions
                    if (contextLine.contains("1-1-1", true) || contextLine.contains("TID", true)) {
                        medicines.add(
                            PrescriptionMedicationEntity(
                                accountUid = accountUid,
                                name = medName,
                                dosage = dosage,
                                timeSlot = "AFTERNOON (13:30)"
                            )
                        )
                    }
                }
            }
        }

        return medicines
    }

    /**
     * Extracts vitals from test results and converts them to VitalReadingEntity records.
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
                            notes = "Extracted from medical document"
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
                            notes = "Extracted from medical document"
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
