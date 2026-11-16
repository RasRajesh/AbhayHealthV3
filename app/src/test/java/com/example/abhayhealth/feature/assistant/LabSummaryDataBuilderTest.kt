package com.example.abhayhealth.feature.assistant

import com.example.abhayhealth.domain.model.ParsedMedicalResult
import com.example.abhayhealth.domain.model.PatientProfile
import com.example.abhayhealth.feature.records.ExtractedMedicalInfo
import com.example.abhayhealth.feature.records.MedicalFieldParser
import com.example.abhayhealth.feature.records.MedicalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabSummaryDataBuilderTest {

    private fun sampleProfile() = PatientProfile(
        accountUid = "u1",
        fullName = "Ananya Sharma",
        dateOfBirth = "12/05/1992",
        gender = "Female",
        bloodGroup = "O+"
    )

    private fun sampleRecords(): List<MedicalRecord> = listOf(
        MedicalRecord(
            id = 1L,
            accountUid = "u1",
            title = "Complete Blood Count",
            category = "LAB_REPORT",
            recordDate = 1000L,
            localFilePath = "/tmp/cbc.pdf",
            mimeType = "application/pdf"
        ),
        MedicalRecord(
            id = 2L,
            accountUid = "u1",
            title = "Lipid & Thyroid Panel",
            category = "LAB_REPORT",
            recordDate = 2000L,
            localFilePath = "/tmp/lipid.pdf",
            mimeType = "application/pdf"
        ),
        MedicalRecord(
            id = 3L,
            accountUid = "u1",
            title = "Cardiology Prescription",
            category = "PRESCRIPTION",
            recordDate = 3000L,
            localFilePath = "/tmp/rx.jpg",
            mimeType = "image/jpeg"
        )
    )

    private fun sampleExtractedInfos(): List<ExtractedMedicalInfo> {
        val cbcResults = listOf(
            ParsedMedicalResult(
                testName = "Hemoglobin",
                value = "9.8",
                unit = "g/dL",
                referenceRange = "12.0 - 16.0 g/dL"
            ),
            ParsedMedicalResult(
                testName = "Platelet Count",
                value = "2.8",
                unit = "Lakhs/cumm",
                referenceRange = "1.5 - 4.5"
            )
        )
        val lipidResults = listOf(
            ParsedMedicalResult(
                testName = "TSH",
                value = "6.2",
                unit = "uIU/ml",
                referenceRange = "0.4 - 4.5"
            )
        )
        return listOf(
            ExtractedMedicalInfo(
                recordId = 1L,
                accountUid = "u1",
                rawText = "Hemoglobin 9.8 g/dL",
                documentTitle = "Complete Blood Count",
                structuredResultsJson = MedicalFieldParser.serializeResults(cbcResults),
                reportDate = "28 Oct 2026"
            ),
            ExtractedMedicalInfo(
                recordId = 2L,
                accountUid = "u1",
                rawText = "TSH 6.2 uIU/ml",
                documentTitle = "Lipid & Thyroid Panel",
                structuredResultsJson = MedicalFieldParser.serializeResults(lipidResults),
                reportDate = "05 Nov 2026"
            )
        )
    }

    @Test
    fun buildLabSummaryReportData_allScope_includesOnlyLabReportsNewestFirst() {
        val data = LabSummaryDataBuilder.buildLabSummaryReportData(
            selectedReport = ReportOption.TOTAL_HEALTH,
            executiveSummary = AiExecutiveSummary(
                englishText = "Two lab panels analyzed with 2 out-of-range markers.",
                keyMetrics = listOf("BP: 120/80 mmHg", "2 Meds")
            ),
            profile = sampleProfile(),
            records = sampleRecords(),
            extractedInfos = sampleExtractedInfos(),
            generatedAt = 9999L
        )

        assertEquals("Total Health Summary", data.scopeTitle)
        assertEquals("Ananya Sharma", data.patientProfile?.fullName)
        assertEquals(2, data.labReports.size)
        // Sorted newest-first: record 2 (2000L) before record 1 (1000L), prescription 3 excluded
        assertEquals("Lipid & Thyroid Panel", data.labReports[0].title)
        assertEquals("05 Nov 2026", data.labReports[0].dateText)
        assertEquals("Complete Blood Count", data.labReports[1].title)

        val stats = LabSummaryDataBuilder.buildStats(data.scopeTitle, data.labReports)
        assertEquals(2, stats.reportCount)
        assertEquals(3, stats.biomarkerCount)
        assertEquals(2, stats.abnormalCount)
    }

    @Test
    fun buildLabSummaryReportData_singleReportScope_filtersToSelectedReport() {
        val specificScope = ReportOption(
            id = 1L,
            title = "Complete Blood Count",
            titleTe = "సిబిసి",
            subtitle = "2 biomarkers",
            date = "28 Oct 2026"
        )

        val data = LabSummaryDataBuilder.buildLabSummaryReportData(
            selectedReport = specificScope,
            executiveSummary = AiExecutiveSummary(englishText = ""),
            profile = sampleProfile(),
            records = sampleRecords(),
            extractedInfos = sampleExtractedInfos()
        )

        assertEquals(1, data.labReports.size)
        assertEquals("Complete Blood Count", data.labReports.first().title)
        assertEquals(2, data.labReports.first().results.size)
        assertTrue(data.aiSummaryNarrative.isNotBlank())

        val stats = LabSummaryDataBuilder.buildStats(data.scopeTitle, data.labReports)
        assertEquals(1, stats.reportCount)
        assertEquals(2, stats.biomarkerCount)
        assertEquals(1, stats.abnormalCount)
    }
}
