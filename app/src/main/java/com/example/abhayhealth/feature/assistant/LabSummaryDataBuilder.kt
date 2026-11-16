package com.example.abhayhealth.feature.assistant

import com.example.abhayhealth.domain.model.PatientProfile
import com.example.abhayhealth.feature.records.ExtractedMedicalInfo
import com.example.abhayhealth.feature.records.MedicalFieldParser
import com.example.abhayhealth.feature.records.MedicalRecord
import com.example.abhayhealth.feature.records.export.MedicalReportPdfGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LabSummaryDataBuilder {

    fun buildLabReportEntries(
        selectedReportId: Long?,
        records: List<MedicalRecord>,
        extractedInfos: List<ExtractedMedicalInfo>
    ): List<MedicalReportPdfGenerator.LabReportEntry> {
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

        val targetRecords = if (selectedReportId != null) {
            records.filter { it.id == selectedReportId }
        } else {
            val labOnly = records.filter {
                !it.category.contains("PRESCRIPTION", ignoreCase = true)
            }
            labOnly.ifEmpty { records }
        }

        return targetRecords
            .sortedByDescending { it.recordDate }
            .map { record ->
                val extracted = extractedInfos.firstOrNull { it.recordId == record.id }
                val biomarkers = extracted?.let {
                    MedicalFieldParser.deserializeResults(it.structuredResultsJson)
                } ?: emptyList()
                val dateStr = extracted?.reportDate?.takeIf { it.isNotBlank() }
                    ?: sdf.format(Date(record.recordDate))
                MedicalReportPdfGenerator.LabReportEntry(
                    title = record.title.ifBlank { "Lab Report" },
                    category = record.category.ifBlank { "LAB_REPORT" },
                    dateText = dateStr,
                    results = biomarkers,
                    rawTextExcerpt = extracted?.rawText
                )
            }
    }

    fun buildStats(
        scopeTitle: String,
        entries: List<MedicalReportPdfGenerator.LabReportEntry>
    ): LabExportStats {
        return LabExportStats(
            scopeTitle = scopeTitle,
            reportCount = entries.size,
            biomarkerCount = entries.sumOf { it.results.size },
            abnormalCount = entries.sumOf { entry -> entry.results.count { it.isOutOfRange } }
        )
    }

    fun buildLabSummaryReportData(
        selectedReport: ReportOption,
        executiveSummary: AiExecutiveSummary,
        profile: PatientProfile?,
        records: List<MedicalRecord>,
        extractedInfos: List<ExtractedMedicalInfo>,
        generatedAt: Long = System.currentTimeMillis()
    ): MedicalReportPdfGenerator.LabSummaryReportData {
        val narrative = executiveSummary.englishText.ifBlank {
            "Processed clinical laboratory report summary generated from AbhayHealth local records."
        }
        val entries = buildLabReportEntries(
            selectedReportId = selectedReport.id,
            records = records,
            extractedInfos = extractedInfos
        )
        return MedicalReportPdfGenerator.LabSummaryReportData(
            patientProfile = profile,
            scopeTitle = selectedReport.title,
            aiSummaryNarrative = narrative,
            keyMetrics = executiveSummary.keyMetrics,
            labReports = entries,
            generatedAt = generatedAt
        )
    }
}
