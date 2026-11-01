package com.example.abhayhealth.feature.records

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
            Platelet Count
            ( Method : WB/Automated)
            3.36 Lakhs/cumm 1.5-4.5
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
            TRIGLYCERIDES
            110.0 mg/dL <150
            Total-T3 (Triiodothyronine-T3)
            108.0 ng/dl 70-204
            Total-T4 (Thyroxine T4 )
            5.9 ug/dl 4.5-11.7
            TSH (Thyroid-stimulating hormone)-Ultra
            4.520 uIU/ml 0.4-4.5
        """.trimIndent()

        val results = MedicalFieldParser.parseTestResults(ocrSample)
        val names = results.map { it.testName }

        println("Extracted ${results.size} tests: $names")
        assertTrue("Should extract Hemoglobin", names.contains("Hemoglobin"))
        assertTrue("Should extract Platelet Count", names.contains("Platelet Count"))
        assertTrue("Should extract Fasting Blood Glucose", names.contains("Fasting Blood Glucose"))
        assertTrue("Should extract Serum Creatinine", names.contains("Serum Creatinine"))
        assertTrue("Should extract Total Cholesterol", names.contains("Total Cholesterol"))
        assertTrue("Should extract TSH", names.contains("TSH"))
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
    }
}
