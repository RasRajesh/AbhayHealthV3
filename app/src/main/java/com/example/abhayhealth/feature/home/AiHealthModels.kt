package com.example.abhayhealth.feature.home

import com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity

enum class HealthStatusTier {
    OPTIMAL,
    STABLE,
    ATTENTION_NEEDED
}

enum class AiInsightCategory {
    MEDICATION,
    LAB_VITAL,
    ALLERGY_SAFETY,
    CARE_ADVICE
}

enum class AiSeverity {
    INFO,
    POSITIVE,
    WARNING
}

data class AiInsightItem(
    val id: String,
    val title: String,
    val description: String,
    val category: AiInsightCategory,
    val severity: AiSeverity
)

data class AiHealthSummaryUiState(
    val isEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val healthScore: Int = 85,
    val statusTier: HealthStatusTier = HealthStatusTier.STABLE,
    val totalMedications: Int = 0,
    val takenMedications: Int = 0,
    val nextMedication: PrescriptionMedicationEntity? = null,
    val totalRecords: Int = 0,
    val latestRecordTitle: String? = null,
    val latestRecordDate: String? = null,
    val extractedVitalsSummary: String? = null,
    val insights: List<AiInsightItem> = emptyList(),
    val actionRecommendations: List<String> = emptyList(),
    val activeConditions: List<String> = emptyList(),
    val activeAllergies: List<String> = emptyList(),
    val lastAnalyzedTime: String = "Just now"
)

enum class AiDialogType {
    NONE,
    MEDICATION_SAFETY,
    DOCTOR_BRIEF,
    LAB_EXPLAINER
}
