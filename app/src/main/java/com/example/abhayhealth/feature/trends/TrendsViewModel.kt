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
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import com.example.abhayhealth.feature.vitals.VitalReadingRepository
import com.example.abhayhealth.feature.vitals.VitalType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val vitalReadingRepository: VitalReadingRepository? = null,
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
        val recordRepo = medicalRecordRepository ?: return
        viewModelScope.launch(dispatcherProvider.main) {
            val authRepo = authRepository
            if (authRepo != null) {
                authRepo.authState.collectLatest { state ->
                    val uid = when (state) {
                        is AuthState.Authenticated -> state.user.uid
                        else -> authRepo.currentUser?.uid?.ifBlank { null } ?: "local_user"
                    }
                    observeForUid(uid, recordRepo)
                }
            } else {
                observeForUid("local_user", recordRepo)
            }
        }
    }

    private suspend fun observeForUid(uid: String, recordRepo: MedicalRecordRepository) {
        val vitalRepo = vitalReadingRepository
        val extractedFlow = recordRepo.getAllExtractedInfo(uid)
        val vitalsFlow = vitalRepo?.getAllReadings(uid)

        val combinedFlow = if (vitalsFlow != null) {
            combine(extractedFlow, vitalsFlow) { infoList, vitalsList ->
                Pair(infoList, vitalsList)
            }
        } else {
            extractedFlow.combine(flowOf(emptyList<VitalReadingEntity>())) { infoList, vitalsList ->
                Pair(infoList, vitalsList)
            }
        }

        combinedFlow.collectLatest { (infoList, vitalsList) ->
            val trendsMap = mutableMapOf<String, MutableList<BiomarkerPoint>>()
            val rangeMap = mutableMapOf<String, String>()
            val sdf = SimpleDateFormat("dd MMM", Locale.getDefault())

            // 1. Process Extracted Medical Document Info
            infoList.forEach { info ->
                if (info.structuredResultsJson.isNotBlank() && info.structuredResultsJson != "[]") {
                    val results = MedicalFieldParser.deserializeResults(info.structuredResultsJson)
                    val dateStr = info.reportDate ?: sdf.format(Date(info.updatedAtTimestamp))
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

            // 2. Process Vitals
            vitalsList.forEach { vital ->
                val dateStr = sdf.format(Date(vital.timestamp))
                if (vital.type == VitalType.BLOOD_PRESSURE.name) {
                    if (vital.systolic != null) {
                        val sList = trendsMap.getOrPut("Blood Pressure (Systolic)") { mutableListOf() }
                        sList.add(
                            BiomarkerPoint(
                                recordDate = dateStr,
                                timestamp = vital.timestamp,
                                value = "${vital.systolic.toInt()}",
                                numericValue = vital.systolic,
                                unit = "mmHg"
                            )
                        )
                        rangeMap["Blood Pressure (Systolic)"] = "< 120 mmHg"
                    }
                    if (vital.diastolic != null) {
                        val dList = trendsMap.getOrPut("Blood Pressure (Diastolic)") { mutableListOf() }
                        dList.add(
                            BiomarkerPoint(
                                recordDate = dateStr,
                                timestamp = vital.timestamp,
                                value = "${vital.diastolic.toInt()}",
                                numericValue = vital.diastolic,
                                unit = "mmHg"
                            )
                        )
                        rangeMap["Blood Pressure (Diastolic)"] = "< 80 mmHg"
                    }
                } else if (vital.type == VitalType.WEIGHT.name && vital.weightKg != null) {
                    val wList = trendsMap.getOrPut("Weight") { mutableListOf() }
                    wList.add(
                        BiomarkerPoint(
                            recordDate = dateStr,
                            timestamp = vital.timestamp,
                            value = "${vital.weightKg}",
                            numericValue = vital.weightKg,
                            unit = "kg"
                        )
                    )
                    rangeMap["Weight"] = "Healthy BMI Range"
                }
            }

            val allTrends = trendsMap.map { (name, points) ->
                // Sort chronologically for time-series visualization
                val sortedPoints = points.sortedBy { it.timestamp }
                val latest = sortedPoints.lastOrNull()
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

    private fun observeSymptoms() {
        val symptomRepo = symptomLogRepository ?: return
        viewModelScope.launch(dispatcherProvider.main) {
            val authRepo = authRepository
            if (authRepo != null) {
                authRepo.authState.collectLatest { state ->
                    val uid = when (state) {
                        is AuthState.Authenticated -> state.user.uid
                        else -> authRepo.currentUser?.uid?.ifBlank { null } ?: "local_user"
                    }
                    symptomRepo.getAllSymptoms(uid).collectLatest { list ->
                        _symptoms.value = list
                    }
                }
            } else {
                symptomRepo.getAllSymptoms("local_user").collectLatest { list ->
                    _symptoms.value = list
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
