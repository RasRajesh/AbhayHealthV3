package com.example.abhayhealth.feature.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.core.common.DefaultDispatcherProvider
import com.example.abhayhealth.core.common.DispatcherProvider
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.repository.AuthRepository
import com.example.abhayhealth.feature.records.ExtractedMedicalInfo
import com.example.abhayhealth.feature.records.MedicalFieldParser
import com.example.abhayhealth.feature.records.MedicalRecordRepository
import com.example.abhayhealth.feature.symptoms.SymptomLogEntity
import com.example.abhayhealth.feature.symptoms.SymptomLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class BiomarkerPoint(
    val recordDate: String,
    val timestamp: Long,
    val value: String,
    val numericValue: Double?,
    val unit: String
)

data class BiomarkerTrend(
    val biomarkerName: String,
    val latestValue: String,
    val numericValue: Double?,
    val unit: String,
    val referenceRange: String,
    val category: String, // "LABS" or "VITALS"
    val clinicalGuidelineTag: String? = null,
    val points: List<BiomarkerPoint>
)

class TrendsViewModel(
    private val medicalRecordRepository: MedicalRecordRepository? = null,
    private val authRepository: AuthRepository? = null,
    private val symptomLogRepository: SymptomLogRepository? = null,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider()
) : ViewModel() {

    private val _labTrends = MutableStateFlow<List<BiomarkerTrend>>(emptyList())
    val labTrends: StateFlow<List<BiomarkerTrend>> = _labTrends.asStateFlow()

    private val _vitalsTrends = MutableStateFlow<List<BiomarkerTrend>>(emptyList())
    val vitalsTrends: StateFlow<List<BiomarkerTrend>> = _vitalsTrends.asStateFlow()

    private val _biomarkerTrends = MutableStateFlow<List<BiomarkerTrend>>(emptyList())
    val biomarkerTrends: StateFlow<List<BiomarkerTrend>> = _biomarkerTrends.asStateFlow()

    private val _symptoms = MutableStateFlow<List<SymptomLogEntity>>(emptyList())
    val symptoms: StateFlow<List<SymptomLogEntity>> = _symptoms.asStateFlow()

    private val _showAddSymptomDialog = MutableStateFlow(false)
    val showAddSymptomDialog: StateFlow<Boolean> = _showAddSymptomDialog.asStateFlow()

    init {
        observeBiomarkers()
        observeSymptoms()
    }

    private fun observeBiomarkers() {
        val authRepo = authRepository ?: return
        val recordRepo = medicalRecordRepository ?: return

        viewModelScope.launch(dispatcherProvider.main) {
            authRepo.authState.collectLatest { state ->
                when (state) {
                    is AuthState.Authenticated -> {
                        val uid = state.user.uid
                        recordRepo.getAllExtractedInfo(uid).collectLatest { infoList ->
                            val trendsMap = mutableMapOf<String, MutableList<BiomarkerPoint>>()
                            val rangeMap = mutableMapOf<String, String>()

                            infoList.forEach { info ->
                                if (info.structuredResultsJson.isNotBlank() && info.structuredResultsJson != "[]") {
                                    val results = MedicalFieldParser.deserializeResults(info.structuredResultsJson)
                                    val dateStr = info.reportDate ?: "Recent"
                                    val timestamp = info.updatedAtTimestamp

                                    results.forEach { result ->
                                        val testName = result.testName.trim()
                                        val valStr = result.value.trim()

                                        if (testName.isNotBlank() && valStr.isNotBlank()) {
                                            val numValue = extractNumericValue(valStr)
                                            val list = trendsMap.getOrPut(testName) { mutableListOf() }
                                            list.add(
                                                BiomarkerPoint(
                                                    recordDate = dateStr,
                                                    timestamp = timestamp,
                                                    value = valStr,
                                                    numericValue = numValue,
                                                    unit = result.unit.trim()
                                                )
                                            )
                                            if (result.referenceRange.isNotBlank()) {
                                                rangeMap[testName] = result.referenceRange
                                            }
                                        }
                                    }
                                }
                            }

                            val allTrends = trendsMap.map { (name, points) ->
                                val sortedPoints = points.sortedByDescending { it.timestamp }
                                val latest = sortedPoints.firstOrNull()
                                val category = categorizeBiomarker(name)
                                val latestNum = latest?.numericValue
                                val guidelineTag = evaluateClinicalGuideline(name, latestNum)

                                BiomarkerTrend(
                                    biomarkerName = name,
                                    latestValue = latest?.value ?: "--",
                                    numericValue = latestNum,
                                    unit = latest?.unit ?: "",
                                    referenceRange = rangeMap[name] ?: "",
                                    category = category,
                                    clinicalGuidelineTag = guidelineTag,
                                    points = sortedPoints
                                )
                            }.sortedByDescending { it.points.size }

                            _biomarkerTrends.value = allTrends
                            _labTrends.value = allTrends.filter { it.category == "LABS" }
                            _vitalsTrends.value = allTrends.filter { it.category == "VITALS" }
                        }
                    }
                    else -> {
                        _biomarkerTrends.value = emptyList()
                        _labTrends.value = emptyList()
                        _vitalsTrends.value = emptyList()
                    }
                }
            }
        }
    }

    private fun observeSymptoms() {
        val authRepo = authRepository ?: return
        val symptomRepo = symptomLogRepository ?: return

        viewModelScope.launch(dispatcherProvider.main) {
            authRepo.authState.collectLatest { state ->
                when (state) {
                    is AuthState.Authenticated -> {
                        val uid = state.user.uid
                        symptomRepo.getAllSymptoms(uid).collectLatest { list ->
                            _symptoms.value = list
                        }
                    }
                    else -> {
                        _symptoms.value = emptyList()
                    }
                }
            }
        }
    }

    fun openAddSymptomDialog() {
        _showAddSymptomDialog.value = true
    }

    fun dismissAddSymptomDialog() {
        _showAddSymptomDialog.value = false
    }

    fun logSymptom(
        symptomName: String,
        severity: String,
        severityScore: Int,
        notes: String,
        triggers: String,
        durationMinutes: Int
    ) {
        val uid = authRepository?.currentUser?.uid ?: return
        val repo = symptomLogRepository ?: return

        viewModelScope.launch(dispatcherProvider.main) {
            val entity = SymptomLogEntity(
                accountUid = uid,
                symptomName = symptomName,
                severity = severity,
                severityScore = severityScore,
                timestamp = System.currentTimeMillis(),
                notes = notes,
                triggers = triggers,
                durationMinutes = durationMinutes
            )
            repo.logSymptom(entity)
            _showAddSymptomDialog.value = false
        }
    }

    fun deleteSymptom(symptom: SymptomLogEntity) {
        val repo = symptomLogRepository ?: return
        viewModelScope.launch(dispatcherProvider.main) {
            repo.deleteSymptom(symptom)
        }
    }

    private fun extractNumericValue(valStr: String): Double? {
        val cleaned = valStr.replace("<", "").replace(">", "").replace("≤", "").replace("≥", "").trim()
        val match = Regex("""^(\d+(?:\.\d+)?)""").find(cleaned)
        return match?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun categorizeBiomarker(name: String): String {
        val lower = name.lowercase()
        return if (lower.contains("bp") || lower.contains("blood pressure") ||
            lower.contains("temp") || lower.contains("systolic") ||
            lower.contains("diastolic") || lower.contains("pulse") ||
            lower.contains("heart rate")
        ) {
            "VITALS"
        } else {
            "LABS"
        }
    }

    private fun evaluateClinicalGuideline(name: String, value: Double?): String? {
        if (value == null) return null
        val lower = name.lowercase()

        return when {
            lower.contains("glucose") || lower.contains("fbs") -> when {
                value < 100.0 -> "Normal FBS"
                value in 100.0..125.0 -> "Impaired Fasting"
                value >= 126.0 -> "Elevated FBS"
                else -> null
            }
            lower.contains("hba1c") -> when {
                value < 5.7 -> "Normal (<5.7%)"
                value in 5.7..6.4 -> "Prediabetes"
                value >= 6.5 -> "Elevated (>=6.5%)"
                else -> null
            }
            lower.contains("hemoglobin") || lower.contains("hb") -> when {
                value in 12.0..16.5 -> "Optimal"
                value < 12.0 -> "Low Hb"
                else -> "Elevated"
            }
            lower.contains("systolic") -> when {
                value < 120.0 -> "Optimal (<120)"
                value in 120.0..129.0 -> "Elevated (120-129)"
                value >= 130.0 -> "Stage 1 HTN"
                else -> null
            }
            else -> null
        }
    }
}
