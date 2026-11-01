package com.example.abhayhealth.feature.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.abhayhealth.domain.model.ParsedMedicalResult

class MedicalFieldParserTest {

    @Test
    fun testParseAarthiScansSampleReport() {
        val ocrSample = """
            AARTHI SCANS & LABS
            Name : R MAMATHA
            PID : 0102315363 SID No. : 124014712
            Age / Sex : 28 Y / Female Reg Date & Time : 29/10/2026 10:06:31
            COMPLETE BLOOD COUNT(CBC) :EDTA WHOLE BLOOD
            RBC (Red Blood Cell Count)
            ( Method : WB/Automated)
            ( Specimen: EDTA WHOLE BLOOD)
            4.23 Million/cmm 3.8-4.8
            Haemoglobin (HB)
            ( Method : WB/Automated)
            ( Specimen: EDTA WHOLE BLOOD)
            9.1 g/dl 12.0-15.0
            PCV -( Haematocrit-Packed Cell Volume)
            ( Method : WB/Automated)
            29.6 % 36-46
            MCV (Mean Corpuscular Volume)
            ( Method : WB/Automated)
            69.9 fl 83-101
            MCH (Mean Corpuscular Hemoglobin )
            ( Method : Calculated)
            21.4 pg 27-31
            MCHC (Mean Corpuscular Hemoglobin Concentration)
            ( Method : WB/Automated)
            30.7 % 31.5-34.5
            Total WBC Count
            ( Method : WB/Automated)
            ( Specimen: EDTA WHOLE BLOOD)
            7180 cells/cumm 4000-10000
            Neutrophils
            57.1 % 40-70
            Lymphocytes
            31.0 % 20-40
            Monocytes
            5.2 % 2-8
            Eosinophils
            5.2 % 1-6
            Basophils
            0.7 % 0-1
            Immature Granulocyte Percentage(IG%)
            0.8 % 0-0.5
            Platelet Count
            ( Method : WB/Automated)
            3.36 Lakhs/cumm 1.5-4.5
            Erythrocyte Sedimentation Rate(ESR)
            17 mm/hr <20
            RDW-CV(Red Cell Distribution Width-CV)
            14.2 % 11.6-14.0
            RDW-SD(Red Cell Distribution Width-SD)
            43.0 fl 39-46
            MPV (Mean Platelet Volume)
            9.0 fl 6.5-12.0
            PDW(Platelet Distribution Width)
            16.0 % 9-14
            PCT(Plateletcrit)
            0.31 % 0.19-0.39
            P-LCR(Platelet Large Cell Ratio)
            22 % 15.0-43.0
            Absolute Neutrophil Count
            4000 cells/cumm 2000-7000
            Absolute Lymphocyte Count
            2100 cells/cumm 1000-3000
            Absolute Eosinophil Count
            370 cells/cumm 20-500
            Absolute Monocyte Count
            370 cells/cumm 200-1000
            Absolute Basophil Count
            37 cells/cumm 20-100
            GLUCOSE FASTING (FBS)
            ( Method : Glucose Oxidase - Peroxidase)
            104.0 mg/dL Normal:74-100
            UREA
            ( Method : Urease)
            20.2 mg/dL 13-45
            CREATININE
            ( Method : Creatinine amidohydrolase)
            0.5 mg/dL 0.5-1.1
            URIC ACID
            4.2 mg/dL 2.5-6.7
            CHOLESTEROL TOTAL
            128.0 mg/dL <200
            HDL CHOLESTEROL
            33.5 mg/dL 50-60
            LDL CHOLESTEROL
            72.5 mg/dL <100
            TRIGLYCERIDES
            110.0 mg/dL <150
            VLDL CHOLESTEROL
            22.0 mg/dL <30
            NON-HDL CHOLESTEROL
            94.5 mg/dL <130
            CHOL / HDL RATIO
            3.8 3.3-4.4
            LDL / HDL RATIO
            2.1 1.5-3.5
            BILIRUBIN TOTAL
            0.34 mg/dL 0.2-1.2
            BILIRUBIN DIRECT
            0.15 mg/dL 0.0-0.3
            BILIRUBIN INDIRECT
            0.19 mg/dL 0.1-0.9
            Aspartate aminotransferase(AST/SGOT)
            14.6 U/L 5-34
            Alanine aminotransferase(ALT/SGPT)
            7.9 U/L 5-35
            ALKALINE PHOSPHATASE
            57.2 U/L 42-98
            TOTAL PROTEIN
            7.01 g/dL 6.0-8.0
            ALBUMIN
            4.22 g/dL 3.5-5.2
            GLOBULIN
            2.79 g/dL 2.8-3.5
            A/G RATIO
            1.51 1.1-2.5
            GAMMA GT
            12.0 U/L 5-38
            Total-T3 (Triiodothyronine-T3)
            108.0 ng/dl 70-204
            Total-T4 (Thyroxine T4 )
            5.9 ug/dl 4.5-11.7
            TSH (Thyroid-stimulating hormone)-Ultra
            4.520 uIU/ml 0.4-4.5
            PUS CELLS
            2-4 /hpf 0-5
            EPITHELIAL CELLS
            4-6 /hpf 0-5
            RBCES
            4-6 /hpf 0-2
            CRYSTALS
            Nil
            HYALINE CASTS
            Nil
            COLOUR
            Pale Yellow
            CLARITY
            Clear
            CHEMICAL EXAMINATION PH
            6.0 4.5-8.0
            SPECIFIC GRAVITY
            1.015 1.005-1.030
            GLUCOSE - URINE
            Nil
            PROTEIN
            Trace
            NITRITE
            Negative
            KETONE
            Negative
            UROBILINOGEN
            Normal
            BLOOD
            Trace
        """.trimIndent()

        val results = MedicalFieldParser.parseTestResults(ocrSample)
        val names = results.map { it.testName }.toSet()

        println("Extracted ${results.size} tests: $names")
        assertTrue("Extracted count should be >= 45, was ${results.size}", results.size >= 45)

        // Essential Clinical Biomarkers
        assertTrue("Should extract Hemoglobin", names.contains("Hemoglobin"))
        assertTrue("Should extract Platelet Count", names.contains("Platelet Count"))
        assertTrue("Should extract Fasting Blood Glucose", names.contains("Fasting Blood Glucose"))
        assertTrue("Should extract Serum Creatinine", names.contains("Serum Creatinine"))
        assertTrue("Should extract Total Cholesterol", names.contains("Total Cholesterol"))
        assertTrue("Should extract TSH", names.contains("TSH"))
        assertTrue("Should extract Total T3", names.contains("Total T3"))
        assertTrue("Should extract Total T4", names.contains("Total T4"))
        assertTrue("Should extract Total Bilirubin", names.contains("Total Bilirubin"))
        assertTrue("Should extract SGOT / AST", names.contains("SGOT / AST"))
        assertTrue("Should extract SGPT / ALT", names.contains("SGPT / ALT"))
        assertTrue("Should extract Alkaline Phosphatase", names.contains("Alkaline Phosphatase"))
        assertTrue("Should extract Total Protein", names.contains("Total Protein"))
        assertTrue("Should extract Albumin", names.contains("Albumin"))
        assertTrue("Should extract Globulin", names.contains("Globulin"))
        assertTrue("Should extract A/G Ratio", names.contains("A/G Ratio"))
        assertTrue("Should extract Triglycerides", names.contains("Triglycerides"))
        assertTrue("Should extract HDL Cholesterol", names.contains("HDL Cholesterol"))
        assertTrue("Should extract LDL Cholesterol", names.contains("LDL Cholesterol"))
        assertTrue("Should extract Non-HDL Cholesterol", names.contains("Non-HDL Cholesterol"))
        assertTrue("Should extract Uric Acid", names.contains("Uric Acid"))
        assertTrue("Should extract Blood Urea", names.contains("Blood Urea"))
        assertTrue("Should extract ESR", names.contains("ESR"))
        assertTrue("Should extract Neutrophils", names.contains("Neutrophils"))
        assertTrue("Should extract Lymphocytes", names.contains("Lymphocytes"))
        assertTrue("Should extract Monocytes", names.contains("Monocytes"))
        assertTrue("Should extract Eosinophils", names.contains("Eosinophils"))
        assertTrue("Should extract Basophils", names.contains("Basophils"))
        assertTrue("Should extract Immature Granulocytes", names.contains("Immature Granulocytes"))
        assertTrue("Should extract RDW-CV", names.contains("RDW-CV"))
        assertTrue("Should extract RDW-SD", names.contains("RDW-SD"))
        assertTrue("Should extract MPV", names.contains("MPV"))
        assertTrue("Should extract PDW", names.contains("PDW"))
        assertTrue("Should extract PCT", names.contains("PCT (Plateletcrit)"))
        assertTrue("Should extract P-LCR", names.contains("P-LCR"))
        assertTrue("Should extract Urine Pus Cells", names.contains("Urine Pus Cells"))
        assertTrue("Should extract Urine Epithelial Cells", names.contains("Urine Epithelial Cells"))
        assertTrue("Should extract Urine RBCs", names.contains("Urine RBCs"))
        assertTrue("Should extract Urine Specific Gravity", names.contains("Urine Specific Gravity"))
        assertTrue("Should extract Urine pH", names.contains("Urine pH"))
        assertTrue("Should extract Urine Protein", names.contains("Urine Protein"))
        assertTrue("Should extract Urine Glucose", names.contains("Urine Glucose"))
        assertTrue("Should extract Urine Nitrite", names.contains("Urine Nitrite"))
        assertTrue("Should extract Urine Ketone", names.contains("Urine Ketone"))
    }

    @Test
    fun testParsePediatricPrescriptionSample() {
        val ocrPrescription = """
            SRINIVASA CHILDREN'S CLINIC
            Dr. D. Sreenivasulu Reddy M.B.B.S., D.C.H., NALS, PALS
            Name: HASHITHA Age: 4y Wt: 16.3 Date: 15.9.26
            CRP 8.2 mg/dl
            1 Syp CALPOL 250 5ml SOS every 6 hrs
            2 Syp ZIFI 100 6ml BD After food
            3 Electral Sachet 200ml water
        """.trimIndent()

        val category = MedicalFieldParser.parseDocumentCategory(ocrPrescription)
        assertEquals("PRESCRIPTION", category)

        val medicines = MedicalFieldParser.parsePrescriptionMedicines(ocrPrescription, "user_1")
        val medNames = medicines.map { it.name }
        println("Extracted medicines: $medNames")

        assertTrue("Should extract Calpol", medNames.any { it.contains("Calpol", ignoreCase = true) })
        assertTrue("Should extract Zifi", medNames.any { it.contains("Zifi", ignoreCase = true) })
        assertTrue("Should extract Electral", medNames.any { it.contains("Electral", ignoreCase = true) })

        val tests = MedicalFieldParser.parseTestResults(ocrPrescription)
        val vitals = MedicalFieldParser.extractVitalsFromResults(tests, "user_1")
        val weightVital = vitals.firstOrNull { it.type == "WEIGHT" }

        assertNotNull("Should extract Weight vital from prescription", weightVital)
        assertEquals(16.3, weightVital?.weightKg ?: 0.0, 0.01)
    }

    @Test
    fun testBiomarkerEvaluationAndOrganSystem() {
        val normalHb = ParsedMedicalResult(
            testName = "Haemoglobin (HB)",
            value = "13.5",
            unit = "g/dL",
            referenceRange = "12.0 - 15.0"
        )
        assertEquals(com.example.abhayhealth.domain.model.BiomarkerStatus.NORMAL, normalHb.status)
        assertEquals(com.example.abhayhealth.domain.model.OrganSystem.HEMIC, normalHb.organSystem)
        assertFalse(normalHb.isOutOfRange)

        val lowHb = ParsedMedicalResult(
            testName = "Haemoglobin (HB)",
            value = "9.2",
            unit = "g/dL",
            referenceRange = "12.0 - 15.0"
        )
        assertEquals(com.example.abhayhealth.domain.model.BiomarkerStatus.LOW, lowHb.status)
        assertTrue(lowHb.isOutOfRange)

        val highGlucose = ParsedMedicalResult(
            testName = "GLUCOSE FASTING (FBS)",
            value = "142",
            unit = "mg/dL",
            referenceRange = "70 - 100"
        )
        assertEquals(com.example.abhayhealth.domain.model.BiomarkerStatus.HIGH, highGlucose.status)
        assertEquals(com.example.abhayhealth.domain.model.OrganSystem.METABOLIC, highGlucose.organSystem)
        assertTrue(highGlucose.isOutOfRange)

        val highChol = ParsedMedicalResult(
            testName = "TOTAL CHOLESTEROL",
            value = "240",
            unit = "mg/dL",
            referenceRange = "< 200"
        )
        assertEquals(com.example.abhayhealth.domain.model.BiomarkerStatus.HIGH, highChol.status)
        assertEquals(com.example.abhayhealth.domain.model.OrganSystem.CARDIO, highChol.organSystem)

        val renalTest = ParsedMedicalResult(
            testName = "Serum Creatinine",
            value = "0.9",
            unit = "mg/dL",
            referenceRange = "0.6 - 1.2"
        )
        assertEquals(com.example.abhayhealth.domain.model.BiomarkerStatus.NORMAL, renalTest.status)
        assertEquals(com.example.abhayhealth.domain.model.OrganSystem.RENAL, renalTest.organSystem)

        val qualitativePus = ParsedMedicalResult(
            testName = "Urine Pus Cells",
            value = "4-6 /hpf",
            referenceRange = "0 - 5 /hpf"
        )
        assertEquals(com.example.abhayhealth.domain.model.OrganSystem.RENAL, qualitativePus.organSystem)

        val urineProteinAbnormal = ParsedMedicalResult(
            testName = "Urine Protein",
            value = "Trace (+)",
            referenceRange = "Nil"
        )
        assertEquals(com.example.abhayhealth.domain.model.BiomarkerStatus.QUALITATIVE_ABNORMAL, urineProteinAbnormal.status)
        assertTrue(urineProteinAbnormal.isOutOfRange)
    }

    @Test
    fun testMedicationAdvisoryHelper() {
        val calpolGuidance = com.example.abhayhealth.feature.medicines.MedicationAdvisoryHelper.getGuidance("Syp Calpol 250")
        assertNotNull(calpolGuidance)
        assertTrue(calpolGuidance!!.foodInstruction.contains("food", ignoreCase = true))

        val zifiGuidance = com.example.abhayhealth.feature.medicines.MedicationAdvisoryHelper.getGuidance("Syp Zifi 100")
        assertNotNull(zifiGuidance)
        assertTrue(zifiGuidance!!.tip.contains("Antibiotic", ignoreCase = true))

        val electralGuidance = com.example.abhayhealth.feature.medicines.MedicationAdvisoryHelper.getGuidance("Electral Sachet")
        assertNotNull(electralGuidance)
        assertTrue(electralGuidance!!.tip.contains("Rehydration", ignoreCase = true))
    }
}
