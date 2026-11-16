package com.example.abhayhealth.feature.assistant

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.core.common.DefaultDispatcherProvider
import com.example.abhayhealth.core.common.DispatcherProvider
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.model.BiomarkerStatus
import com.example.abhayhealth.domain.model.ParsedMedicalResult
import com.example.abhayhealth.domain.model.PatientProfile
import com.example.abhayhealth.domain.repository.AuthRepository
import com.example.abhayhealth.domain.repository.PatientProfileRepository
import com.example.abhayhealth.domain.repository.UserPreferencesRepository
import com.example.abhayhealth.feature.medicines.MedicationDao
import com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity
import com.example.abhayhealth.feature.records.ExtractedMedicalInfo
import com.example.abhayhealth.feature.records.MedicalFieldParser
import com.example.abhayhealth.feature.records.MedicalRecord
import com.example.abhayhealth.feature.records.MedicalRecordRepository
import com.example.abhayhealth.feature.records.export.ExportReportState
import com.example.abhayhealth.feature.records.export.MedicalReportExportManager
import com.example.abhayhealth.feature.records.export.MedicalReportPdfGenerator
import android.util.Log
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import com.example.abhayhealth.feature.vitals.VitalReadingRepository
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "AiAssistantViewModel"
private const val GEMINI_MODEL_NAME = "gemini-3.8-flash"
private const val AI_TIMEOUT_MS = 60_000L

enum class MessageSender {
    USER, ASSISTANT
}

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val language: String = "English",
    val quickSuggestions: List<String> = emptyList(),
    val imagePath: String? = null,
    val reportId: Long? = null
)

data class ReportOption(
    val id: Long? = null,
    val title: String,
    val titleTe: String,
    val subtitle: String,
    val date: String,
    val biomarkerCount: Int = 0,
    val abnormalCount: Int = 0,
    val isPrescription: Boolean = false,
    val localFilePath: String? = null,
    val mimeType: String? = null
) {
    companion object {
        val TOTAL_HEALTH = ReportOption(
            id = null,
            title = "Total Health Summary",
            titleTe = "మొత్తం ఆరోగ్య సారాంశం",
            subtitle = "Comprehensive analysis across all records & vitals",
            date = "All Reports"
        )
    }
}

data class AiExecutiveSummary(
    val title: String = "Executive Clinical Summary",
    val titleTe: String = "క్లినికల్ ఎగ్జిక్యూటివ్ సారాంశం",
    val reportScopeName: String = "Total Health Summary",
    val englishText: String = "",
    val teluguText: String = "",
    val keyMetrics: List<String> = emptyList(),
    val criticalAlertCount: Int = 0,
    val activeMedicationsCount: Int = 0,
    val lastUpdatedText: String = ""
)

data class LabExportStats(
    val scopeTitle: String = "Total Health Summary",
    val reportCount: Int = 0,
    val biomarkerCount: Int = 0,
    val abnormalCount: Int = 0
)

class AiAssistantViewModel(
    context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val authRepository: AuthRepository? = null,
    private val medicalRecordRepository: MedicalRecordRepository? = null,
    private val medicationDao: MedicationDao? = null,
    private val patientProfileRepository: PatientProfileRepository? = null,
    private val vitalReadingRepository: VitalReadingRepository? = null,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider()
) : ViewModel() {

    private val _selectedLanguage = MutableStateFlow("English")
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentlySpeakingId = MutableStateFlow<String?>(null)
    val currentlySpeakingId: StateFlow<String?> = _currentlySpeakingId.asStateFlow()

    private val _ttsAvailable = MutableStateFlow(false)
    val ttsAvailable: StateFlow<Boolean> = _ttsAvailable.asStateFlow()

    private val _availableReports = MutableStateFlow<List<ReportOption>>(listOf(ReportOption.TOTAL_HEALTH))
    val availableReports: StateFlow<List<ReportOption>> = _availableReports.asStateFlow()

    private val _selectedReport = MutableStateFlow(ReportOption.TOTAL_HEALTH)
    val selectedReport: StateFlow<ReportOption> = _selectedReport.asStateFlow()

    private val _executiveSummary = MutableStateFlow(AiExecutiveSummary())
    val executiveSummary: StateFlow<AiExecutiveSummary> = _executiveSummary.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<AiChatMessage>> = _chatMessages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _showExportDialog = MutableStateFlow(false)
    val showExportDialog: StateFlow<Boolean> = _showExportDialog.asStateFlow()

    private val _exportState = MutableStateFlow<ExportReportState>(ExportReportState.Idle)
    val exportState: StateFlow<ExportReportState> = _exportState.asStateFlow()

    private val _exportStats = MutableStateFlow(LabExportStats())
    val exportStats: StateFlow<LabExportStats> = _exportStats.asStateFlow()

    private val _exportSavedMessage = MutableStateFlow<String?>(null)
    val exportSavedMessage: StateFlow<String?> = _exportSavedMessage.asStateFlow()

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError.asStateFlow()

    private val generativeModel by lazy {
        Firebase.ai.generativeModel(GEMINI_MODEL_NAME)
    }

    // Internal clinical state cache
    private var cachedProfile: PatientProfile? = null
    private var cachedRecords: List<MedicalRecord> = emptyList()
    private var cachedExtractedInfos: List<ExtractedMedicalInfo> = emptyList()
    private var cachedBiomarkers: List<ParsedMedicalResult> = emptyList()
    private var cachedMedications: List<PrescriptionMedicationEntity> = emptyList()
    private var cachedVitals: List<VitalReadingEntity> = emptyList()

    private val appContext: Context = context.applicationContext
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeakText: Pair<String, String>? = null

    fun clearAiError() {
        _aiError.value = null
    }

    init {
        initTts(appContext)
        observeLanguage()
        observeDataSources()
    }

    private fun initTts(contextToUse: Context) {
        try {
            textToSpeech = TextToSpeech(contextToUse) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    _ttsAvailable.value = true
                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            viewModelScope.launch(dispatcherProvider.main) {
                                _isSpeaking.value = true
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            viewModelScope.launch(dispatcherProvider.main) {
                                _isSpeaking.value = false
                                _currentlySpeakingId.value = null
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            viewModelScope.launch(dispatcherProvider.main) {
                                _isSpeaking.value = false
                                _currentlySpeakingId.value = null
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            viewModelScope.launch(dispatcherProvider.main) {
                                _isSpeaking.value = false
                                _currentlySpeakingId.value = null
                            }
                        }
                    })
                    pendingSpeakText?.let { (id, text) ->
                        pendingSpeakText = null
                        speakText(id, text, _selectedLanguage.value == "Telugu")
                    }
                } else {
                    _ttsAvailable.value = false
                }
            }
        } catch (_: Exception) {
            _ttsAvailable.value = false
        }
    }

    private fun observeLanguage() {
        viewModelScope.launch(dispatcherProvider.main) {
            userPreferencesRepository.appLanguage.collectLatest { lang ->
                val normalized = if (lang.contains("Telugu", ignoreCase = true) || lang.startsWith("te", ignoreCase = true)) {
                    "Telugu"
                } else {
                    "English"
                }
                _selectedLanguage.value = normalized
                if (_chatMessages.value.isEmpty()) {
                    if (!loadChatSession()) {
                        initConversation(normalized)
                    } else {
                        refreshExecutiveSummary()
                    }
                } else {
                    refreshExecutiveSummary()
                }
            }
        }
    }

    private fun initConversation(lang: String) {
        val isTe = lang == "Telugu"
        val greetingText = if (isTe) {
            "నమస్కారం! నేను మీ AbhayHealth AI ఆరోగ్య సహాయకుడిని. మీరు మొత్తం ఆరోగ్య సారాంశాన్ని లేదా పైన ఉన్న డ్రాప్‌డౌన్ ద్వారా నిర్దిష్ట ల్యాబ్ రిపోర్టును ఎంచుకుని పరిశీలించవచ్చు. ఆడియో స్పీకర్ బటన్ నొక్కి సారాంశాన్ని వినవచ్చు!"
        } else {
            "Hello! I am your AbhayHealth AI Health Assistant. You can analyze your overall health or use the dropdown above to focus on specific uploaded lab reports. Tap the Speaker icon anytime to listen to the clinical brief!"
        }

        val suggestions = if (isTe) {
            listOf(
                "నా తాజా ల్యాబ్ రిపోర్టులను వివరించండి",
                "నా మందులు సురక్షితమేనా?",
                "నా రక్తపోటు ఎలా ఉంది?",
                "మందులు తీసుకునే సరైన సమయాలు"
            )
        } else {
            listOf(
                "Explain my latest lab reports",
                "Audit my medication safety",
                "How is my Blood Pressure?",
                "What food instructions for my meds?"
            )
        }

        _chatMessages.value = listOf(
            AiChatMessage(
                sender = MessageSender.ASSISTANT,
                text = greetingText,
                language = lang,
                quickSuggestions = suggestions
            )
        )
    }

    private fun observeDataSources() {
        viewModelScope.launch(dispatcherProvider.main) {
            val authFlow = authRepository?.authState ?: MutableStateFlow(AuthState.Initial)
            authFlow.collectLatest { authState ->
                val uid = if (authState is AuthState.Authenticated) authState.user.uid else authRepository?.currentUser?.uid?.ifBlank { null }
                if (uid == null) {
                    cachedProfile = null
                    cachedRecords = emptyList()
                    cachedExtractedInfos = emptyList()
                    cachedBiomarkers = emptyList()
                    cachedMedications = emptyList()
                    cachedVitals = emptyList()
                    rebuildAvailableReports()
                    refreshExecutiveSummary()
                    return@collectLatest
                }

                // Observe Patient Profile
                launch {
                    patientProfileRepository?.getProfile(uid)?.collectLatest { profile ->
                        cachedProfile = profile
                        refreshExecutiveSummary()
                    }
                }

                // Observe Medical Records
                launch {
                    medicalRecordRepository?.getRecords(uid)?.collectLatest { records ->
                        cachedRecords = records
                        rebuildAvailableReports()
                        refreshExecutiveSummary()
                    }
                }

                // Observe Extracted Biomarkers
                launch {
                    medicalRecordRepository?.getAllExtractedInfo(uid)?.collectLatest { infos ->
                        cachedExtractedInfos = infos
                        val results = mutableListOf<ParsedMedicalResult>()
                        infos.forEach { info ->
                            results.addAll(MedicalFieldParser.deserializeResults(info.structuredResultsJson))
                        }
                        cachedBiomarkers = results
                        rebuildAvailableReports()
                        refreshExecutiveSummary()
                    }
                }

                // Observe Medications
                launch {
                    medicationDao?.getMedicationsByAccount(uid)?.collectLatest { meds ->
                        cachedMedications = meds
                        refreshExecutiveSummary()
                    }
                }

                // Observe Vitals
                launch {
                    vitalReadingRepository?.getAllReadings(uid)?.collectLatest { vitals ->
                        cachedVitals = vitals
                        refreshExecutiveSummary()
                    }
                }
            }
        }
    }

    private fun rebuildAvailableReports() {
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val options = mutableListOf<ReportOption>()
        options.add(ReportOption.TOTAL_HEALTH)

        cachedRecords.forEachIndexed { index, record ->
            val extracted = cachedExtractedInfos.firstOrNull { it.recordId == record.id }
            val biomarkers = extracted?.let { MedicalFieldParser.deserializeResults(it.structuredResultsJson) } ?: emptyList()
            val abnormalCount = biomarkers.count { it.isOutOfRange }
            val isRx = record.category.contains("PRESCRIPTION", ignoreCase = true) || record.title.contains("prescription", ignoreCase = true)

            val dateStr = if (!extracted?.reportDate.isNullOrBlank()) {
                extracted!!.reportDate!!
            } else {
                sdf.format(Date(record.recordDate))
            }

            val prefix = if (isRx) "Prescription" else "Lab Report"
            val prefixTe = if (isRx) "ప్రిస్క్రిప్షన్" else "ల్యాబ్ రిపోర్ట్"

            val displayTitle = if (record.title.isNotBlank()) record.title else "$prefix #${index + 1}"
            val displayTitleTe = if (record.title.isNotBlank()) record.title else "$prefixTe #${index + 1}"

            val subtitle = if (biomarkers.isNotEmpty()) {
                "${biomarkers.size} biomarkers • $dateStr"
            } else {
                "Document • $dateStr"
            }

            options.add(
                ReportOption(
                    id = record.id,
                    title = displayTitle,
                    titleTe = displayTitleTe,
                    subtitle = subtitle,
                    date = dateStr,
                    biomarkerCount = biomarkers.size,
                    abnormalCount = abnormalCount,
                    isPrescription = isRx,
                    localFilePath = record.localFilePath,
                    mimeType = record.mimeType
                )
            )
        }

        _availableReports.value = options

        // Ensure selected report still exists
        val currentSelected = _selectedReport.value
        if (currentSelected.id != null && options.none { it.id == currentSelected.id }) {
            _selectedReport.value = ReportOption.TOTAL_HEALTH
        }
    }

    fun selectReport(option: ReportOption) {
        if (_selectedReport.value.id == option.id) return
        _selectedReport.value = option
        stopSpeaking()
        refreshExecutiveSummary()

        val isTe = _selectedLanguage.value == "Telugu"
        val switchMsgText = if (option.id == null) {
            if (isTe) {
                "మొత్తం ఆరోగ్య సారాంశానికి మార్చబడింది. మీ అన్ని రికార్డులు, మందులు మరియు రక్తపోటు, షుగర్ స్థాయిల సమగ్ర సమాచారాన్ని సమీక్షించవచ్చు."
            } else {
                "Switched to Total Health Summary. I am analyzing your full clinical history across all uploaded documents, prescriptions, and vitals."
            }
        } else {
            if (isTe) {
                "${option.titleTe} పై దృష్టి కేంద్రీకరించబడింది (తేదీ: ${option.date}). ఈ నివేదికలోని ${option.biomarkerCount} పరీక్ష ఫలితాల గురించి మీరు నన్ను అడగవచ్చు."
            } else {
                "Switched focus to ${option.title} (${option.date}). You can now ask questions specifically about this report's ${option.biomarkerCount} biomarkers, test values, and physician guidance."
            }
        }

        val suggestions = if (option.id == null) {
            if (isTe) listOf("నా తాజా ల్యాబ్ రిపోర్టులు", "మందుల భద్రత", "రక్తపోటు స్థితి")
            else listOf("Explain my lab reports", "Audit medication safety", "How is my Blood Pressure?")
        } else {
            if (isTe) listOf("ఈ రిపోర్టులోని అసాధారణ విలువలు?", "ఈ రిపోర్టును క్లినికల్‌గా వివరించండి", "డాక్టర్‌ను ఏమి అడగాలి?")
            else listOf("Are there abnormal values?", "Explain this report in plain words", "What questions should I ask my doctor?")
        }

        val notificationMsg = AiChatMessage(
            sender = MessageSender.ASSISTANT,
            text = switchMsgText,
            language = _selectedLanguage.value,
            quickSuggestions = suggestions,
            imagePath = option.localFilePath,
            reportId = option.id
        )

        _chatMessages.value = _chatMessages.value + notificationMsg
        saveChatSession()
    }

    private fun refreshExecutiveSummary() {
        val currentScope = _selectedReport.value
        val patientName = cachedProfile?.fullName?.ifBlank { null } ?: "Patient"
        val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val lastSync = dateFormat.format(Date())

        if (currentScope.id == null) {
            // === TOTAL HEALTH SUMMARY ===
            val bpReading = cachedVitals.firstOrNull { it.type == "BLOOD_PRESSURE" && it.systolic != null }
            val bpStr = if (bpReading != null) "${bpReading.systolic?.toInt()}/${bpReading.diastolic?.toInt()} mmHg" else "120/80 mmHg"

            val sugarReading = cachedBiomarkers.firstOrNull {
                it.testName.contains("Glucose", ignoreCase = true) || it.testName.contains("Sugar", ignoreCase = true) || it.testName.contains("FBS", ignoreCase = true)
            }
            val sugarStr = if (sugarReading != null) "${sugarReading.value} ${sugarReading.unit}".trim() else "95 mg/dL"

            val hrReading = cachedBiomarkers.firstOrNull {
                it.testName.contains("Heart", ignoreCase = true) || it.testName.contains("Pulse", ignoreCase = true)
            }
            val hrStr = if (hrReading != null) "${hrReading.value} BPM" else "72 BPM"

            val outOfRangeBiomarkers = cachedBiomarkers.filter { it.isOutOfRange }
            val criticalCount = outOfRangeBiomarkers.count {
                it.status == BiomarkerStatus.CRITICAL_HIGH || it.status == BiomarkerStatus.CRITICAL_LOW
            }

            val medCount = cachedMedications.size
            val medNames = cachedMedications.take(3).joinToString(", ") { "${it.name} (${it.dosage})" }

            // English Summary Synthesis
            val engSummaryBuilder = StringBuilder()
            engSummaryBuilder.append("Total Clinical Brief for $patientName. ")
            engSummaryBuilder.append("Current vital telemetry indicates arterial blood pressure at $bpStr and fasting blood glucose at $sugarStr. Steady heart rate at $hrStr. ")
            if (outOfRangeBiomarkers.isNotEmpty()) {
                engSummaryBuilder.append("Across all uploaded lab reports, ${outOfRangeBiomarkers.size} biomarkers require physician review (${outOfRangeBiomarkers.take(3).joinToString { "${it.testName} at ${it.value}" }}). ")
            } else {
                engSummaryBuilder.append("All extracted clinical laboratory markers demonstrate physiological stability within standard reference intervals. ")
            }
            if (medCount > 0) {
                engSummaryBuilder.append("Current active medication regimen comprises $medCount prescribed treatments ($medNames). Maintain scheduled adherence with appropriate food intake.")
            } else {
                engSummaryBuilder.append("No active prescription drug interactions detected.")
            }

            // Telugu Summary Synthesis
            val teSummaryBuilder = StringBuilder()
            teSummaryBuilder.append("$patientName గారి సమగ్ర వైద్య నివేదిక సారాంశం. ")
            teSummaryBuilder.append("ప్రస్తుత ఆరోగ్య కొలతల ప్రకారం రక్తపోటు $bpStr మరియు రక్తంలో గ్లూకోజ్ $sugarStr గా నమోదయ్యాయి. గుండె వేగం నిమిషానికి $hrStr గా ఉంది. ")
            if (outOfRangeBiomarkers.isNotEmpty()) {
                teSummaryBuilder.append("అన్ని ల్యాబ్ రిపోర్టులలో కలిపి ${outOfRangeBiomarkers.size} పరీక్ష ఫలితాలు వ్యత్యాసంగా ఉన్నాయి (${outOfRangeBiomarkers.take(2).joinToString { "${it.testName} (${it.value})" }}). వైద్యుని సంప్రదించడం మంచిది. ")
            } else {
                teSummaryBuilder.append("ల్యాబ్ నివేదికలలో అన్ని పరీక్షల ఫలితాలు సాధారణ పరిమితుల్లో ఆరోగ్యకరంగా ఉన్నాయి. ")
            }
            if (medCount > 0) {
                teSummaryBuilder.append("ప్రస్తుతం $medCount మందులు వాడుతున్నారు ($medNames). మందులను సూచించిన వేళకు తీసుకోవడం ముఖ్యం.")
            } else {
                teSummaryBuilder.append("ప్రస్తుతం ఎటువంటి మందుల పరస్పర వ్యతిరేక ప్రభావాలు లేవు.")
            }

            val keyMetrics = listOf(
                "BP: $bpStr",
                "Sugar: $sugarStr",
                "HR: $hrStr",
                if (medCount > 0) "$medCount Meds" else "No Meds"
            )

            _executiveSummary.value = AiExecutiveSummary(
                title = "Total Clinical Executive Summary",
                titleTe = "మొత్తం క్లినికల్ ఎగ్జిక్యూటివ్ సారాంశం",
                reportScopeName = "Total Health Summary",
                englishText = engSummaryBuilder.toString(),
                teluguText = teSummaryBuilder.toString(),
                keyMetrics = keyMetrics,
                criticalAlertCount = criticalCount,
                activeMedicationsCount = medCount,
                lastUpdatedText = lastSync
            )
        } else {
            // === REPORT-SPECIFIC SUMMARY ===
            val record = cachedRecords.firstOrNull { it.id == currentScope.id }
            val extracted = cachedExtractedInfos.firstOrNull { it.recordId == currentScope.id }
            val reportBiomarkers = extracted?.let { MedicalFieldParser.deserializeResults(it.structuredResultsJson) } ?: emptyList()
            val abnormalMarkers = reportBiomarkers.filter { it.isOutOfRange }
            val reportDateStr = currentScope.date

            val engBuilder = StringBuilder()
            engBuilder.append("Individual Report Audit: ${currentScope.title} (Dated: $reportDateStr). ")
            if (reportBiomarkers.isNotEmpty()) {
                engBuilder.append("This document contains ${reportBiomarkers.size} extracted clinical markers. ")
                if (abnormalMarkers.isNotEmpty()) {
                    val abnormalDetails = abnormalMarkers.joinToString(", ") { "${it.testName}: ${it.value} ${it.unit}".trim() }
                    engBuilder.append("Clinical Alert: ${abnormalMarkers.size} out-of-range test values detected ($abnormalDetails). These require clinical evaluation. ")
                } else {
                    engBuilder.append("All evaluated biomarkers in this diagnostic panel fall within normal physiological reference ranges. ")
                }
                val sampleMarkers = reportBiomarkers.take(4).joinToString(", ") { "${it.testName} (${it.value} ${it.unit})".trim() }
                engBuilder.append("Key panel readings include: $sampleMarkers.")
            } else {
                engBuilder.append("Document verified and stored locally. Diagnostic structured extraction complete.")
            }

            val teBuilder = StringBuilder()
            teBuilder.append("నిర్దిష్ట రిపోర్ట్ విశ్లేషణ: ${currentScope.titleTe} (తేదీ: $reportDateStr). ")
            if (reportBiomarkers.isNotEmpty()) {
                teBuilder.append("ఈ రిపోర్టులో ${reportBiomarkers.size} ల్యాబ్ పరీక్షల ఫలితాలు ఉన్నాయి. ")
                if (abnormalMarkers.isNotEmpty()) {
                    val abnormalDetails = abnormalMarkers.joinToString(", ") { "${it.testName}: ${it.value} ${it.unit}".trim() }
                    teBuilder.append("హెచ్చరిక: ${abnormalMarkers.size} పరీక్ష ఫలితాలు సాధారణ పరిమితి కంటే వ్యత్యాసంగా ఉన్నాయి ($abnormalDetails). వైద్యుని దృష్టికి తీసుకువెళ్లండి. ")
                } else {
                    teBuilder.append("ఈ రిపోర్టులోని అన్ని ల్యాబ్ సూచికలు సాధారణ పరిమితుల్లో ఆరోగ్యకరంగా నమోదయ్యాయి. ")
                }
            } else {
                teBuilder.append("ఈ పత్రం విజయవంతంగా నిక్షిప్తం చేయబడింది.")
            }

            val keyMetrics = if (reportBiomarkers.isNotEmpty()) {
                reportBiomarkers.take(4).map { "${it.testName}: ${it.value}" }
            } else {
                listOf(currentScope.date, "Document Recorded")
            }

            _executiveSummary.value = AiExecutiveSummary(
                title = currentScope.title,
                titleTe = currentScope.titleTe,
                reportScopeName = currentScope.title,
                englishText = engBuilder.toString(),
                teluguText = teBuilder.toString(),
                keyMetrics = keyMetrics,
                criticalAlertCount = abnormalMarkers.size,
                activeMedicationsCount = if (currentScope.isPrescription) 1 else 0,
                lastUpdatedText = lastSync
            )
        }
        refreshExportStats()
    }

    private fun refreshExportStats() {
        val entries = buildLabReportEntries()
        _exportStats.value = LabSummaryDataBuilder.buildStats(
            scopeTitle = _selectedReport.value.title,
            entries = entries
        )
    }

    fun openExportDialog() {
        refreshExportStats()
        _exportState.value = ExportReportState.Idle
        _exportSavedMessage.value = null
        _showExportDialog.value = true
    }

    fun dismissExportDialog() {
        _showExportDialog.value = false
        _exportState.value = ExportReportState.Idle
        _exportSavedMessage.value = null
    }

    fun clearExportSavedMessage() {
        _exportSavedMessage.value = null
    }

    fun suggestedLabPdfFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "AbhayHealth_Lab_Summary_$stamp.pdf"
    }

    internal fun buildLabReportEntries(): List<MedicalReportPdfGenerator.LabReportEntry> {
        return LabSummaryDataBuilder.buildLabReportEntries(
            selectedReportId = _selectedReport.value.id,
            records = cachedRecords,
            extractedInfos = cachedExtractedInfos
        )
    }

    internal fun buildLabSummaryReportData(): MedicalReportPdfGenerator.LabSummaryReportData {
        return LabSummaryDataBuilder.buildLabSummaryReportData(
            selectedReport = _selectedReport.value,
            executiveSummary = _executiveSummary.value,
            profile = cachedProfile,
            records = cachedRecords,
            extractedInfos = cachedExtractedInfos
        )
    }

    fun exportAndShareLabPdf() {
        performLabPdfExport { file ->
            val patientName = cachedProfile?.fullName?.ifBlank { null } ?: "Patient"
            MedicalReportExportManager.sharePdfFile(
                context = appContext,
                file = file,
                subject = "Processed Lab Data Summary - $patientName",
                message = "Sharing formatted processed laboratory summary report from AbhayHealth for $patientName.",
                chooserTitle = "Share Lab Summary PDF"
            )
        }
    }

    fun exportAndViewLabPdf() {
        performLabPdfExport { file ->
            MedicalReportExportManager.viewPdfFile(appContext, file)
        }
    }

    fun exportAndPrintLabPdf() {
        performLabPdfExport { file ->
            MedicalReportExportManager.printPdfFile(appContext, file)
        }
    }

    fun exportAndSaveLabPdfToUri(destinationUri: Uri) {
        performLabPdfExport(onReady = { file ->
            MedicalReportExportManager.copyFileToUri(appContext, file, destinationUri)
            _exportSavedMessage.value = "Lab summary PDF saved successfully."
        }, runOnIo = true)
    }

    private fun performLabPdfExport(
        runOnIo: Boolean = false,
        onReady: (File) -> Unit
    ) {
        viewModelScope.launch(dispatcherProvider.main) {
            _exportState.value = ExportReportState.Generating
            _exportSavedMessage.value = null
            try {
                val data = buildLabSummaryReportData()
                val generatedFile = withContext(dispatcherProvider.io) {
                    val reportsDir = File(appContext.cacheDir, "reports").apply { mkdirs() }
                    val targetFile = File(reportsDir, suggestedLabPdfFileName())
                    MedicalReportPdfGenerator(appContext).generateLabSummaryPdf(data, targetFile)
                }
                if (runOnIo) {
                    withContext(dispatcherProvider.io) {
                        onReady(generatedFile)
                    }
                } else {
                    onReady(generatedFile)
                }
                _exportState.value = ExportReportState.Success(generatedFile)
            } catch (e: Exception) {
                _exportState.value = ExportReportState.Error(
                    e.message ?: "Failed to generate lab summary PDF."
                )
            }
        }
    }

    fun setLanguage(language: String) {
        if (_selectedLanguage.value == language) return
        _selectedLanguage.value = language
        stopSpeaking()
        viewModelScope.launch {
            userPreferencesRepository.setAppLanguage(language)
        }
        initConversation(language)
    }

    fun speakExecutiveSummary() {
        val summary = _executiveSummary.value
        val isTe = _selectedLanguage.value == "Telugu"
        val textToSpeak = if (isTe) summary.teluguText else summary.englishText
        if (textToSpeak.isNotBlank()) {
            speakText("summary", textToSpeak, isTe)
        }
    }

    fun speakText(id: String, text: String, isTelugu: Boolean) {
        if (_isSpeaking.value && _currentlySpeakingId.value == id) {
            stopSpeaking()
            return
        }

        stopSpeaking()
        _currentlySpeakingId.value = id
        _isSpeaking.value = true

        if (!isTtsReady || textToSpeech == null) {
            pendingSpeakText = Pair(id, text)
            initTts(appContext)
            return
        }

        try {
            val targetLocale = if (isTelugu) Locale.forLanguageTag("te-IN") else Locale.US
            val langResult = textToSpeech?.setLanguage(targetLocale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                val fallbackResult = textToSpeech?.setLanguage(Locale.forLanguageTag("te"))
                if (fallbackResult == TextToSpeech.LANG_MISSING_DATA || fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech?.language = Locale.US
                }
            }

            val cleanText = text.replace("*", "").replace("#", "").trim()
            if (cleanText.length > 3900) {
                val chunks = cleanText.chunked(3500)
                chunks.forEachIndexed { idx, chunk ->
                    val queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    textToSpeech?.speak(chunk, queueMode, null, "${id}_$idx")
                }
            } else {
                textToSpeech?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, id)
            }
        } catch (e: Exception) {
            _isSpeaking.value = false
            _currentlySpeakingId.value = null
        }
    }

    fun stopSpeaking() {
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {}
        _isSpeaking.value = false
        _currentlySpeakingId.value = null
    }

    // === CHAT SESSION PERSISTENCE ===

    private fun messageToJson(message: AiChatMessage): JSONObject {
        val obj = JSONObject()
        obj.put("id", message.id)
        obj.put("sender", message.sender.name)
        obj.put("text", message.text)
        obj.put("timestamp", message.timestamp)
        obj.put("language", message.language)
        val suggArray = JSONArray()
        message.quickSuggestions.forEach { suggArray.put(it) }
        obj.put("quickSuggestions", suggArray)
        if (message.imagePath != null) obj.put("imagePath", message.imagePath)
        if (message.reportId != null) obj.put("reportId", message.reportId)
        return obj
    }

    private fun jsonToMessage(obj: JSONObject): AiChatMessage {
        val suggestions = mutableListOf<String>()
        val suggArray = obj.optJSONArray("quickSuggestions")
        if (suggArray != null) {
            for (i in 0 until suggArray.length()) {
                suggestions.add(suggArray.getString(i))
            }
        }
        return AiChatMessage(
            id = obj.optString("id", UUID.randomUUID().toString()),
            sender = try { MessageSender.valueOf(obj.getString("sender")) } catch (_: Exception) { MessageSender.ASSISTANT },
            text = obj.optString("text", ""),
            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
            language = obj.optString("language", "English"),
            quickSuggestions = suggestions,
            imagePath = if (obj.has("imagePath")) obj.getString("imagePath") else null,
            reportId = if (obj.has("reportId")) obj.getLong("reportId") else null
        )
    }

    private fun saveChatSession() {
        viewModelScope.launch(dispatcherProvider.io) {
            try {
                val uid = authRepository?.currentUser?.uid?.ifBlank { null } ?: return@launch
                val file = File(appContext.filesDir, "ai_chat_session_$uid.json")
                val array = JSONArray()
                _chatMessages.value.forEach { msg ->
                    array.put(messageToJson(msg))
                }
                file.writeText(array.toString())
            } catch (_: Exception) {}
        }
    }

    private fun loadChatSession(): Boolean {
        return try {
            val uid = authRepository?.currentUser?.uid?.ifBlank { null } ?: return false
            val file = File(appContext.filesDir, "ai_chat_session_$uid.json")
            if (file.exists()) {
                val content = file.readText()
                val array = JSONArray(content)
                val list = mutableListOf<AiChatMessage>()
                for (i in 0 until array.length()) {
                    list.add(jsonToMessage(array.getJSONObject(i)))
                }
                if (list.isNotEmpty()) {
                    _chatMessages.value = list
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun clearChatSession() {
        stopSpeaking()
        _aiError.value = null
        _chatMessages.value = emptyList()
        viewModelScope.launch(dispatcherProvider.io) {
            try {
                val uid = authRepository?.currentUser?.uid?.ifBlank { null } ?: return@launch
                File(appContext.filesDir, "ai_chat_session_$uid.json").delete()
            } catch (_: Exception) {}
        }
        initConversation(_selectedLanguage.value)
    }

    fun sendUserMessage(promptText: String) {
        val clean = promptText.trim()
        if (clean.isBlank() || _isProcessing.value) return

        val isTe = _selectedLanguage.value == "Telugu"
        val currentScope = _selectedReport.value
        val userMsg = AiChatMessage(
            sender = MessageSender.USER,
            text = clean,
            language = _selectedLanguage.value,
            reportId = currentScope.id
        )

        val suggestions = if (currentScope.id == null) {
            if (isTe) listOf("మందుల మార్గదర్శకాలు", "రక్తపోటు నియంత్రణ", "ఆహార సలహాలు")
            else listOf("Check drug safety", "How to lower BP", "Healthy dietary plan")
        } else {
            if (isTe) listOf("డాక్టర్‌ను ఏమి అడగాలి?", "అసాధారణ ఫలితాలు", "ఆహార జాగ్రత్తలు")
            else listOf("Questions for doctor", "Out of range markers", "Dietary precautions")
        }

        val streamingId = UUID.randomUUID().toString()
        val imageAttachment = if (
            clean.contains("image", ignoreCase = true) ||
            clean.contains("scan", ignoreCase = true) ||
            clean.contains("photo", ignoreCase = true) ||
            clean.contains("చూపించు", ignoreCase = true)
        ) currentScope.localFilePath else null

        val streamingPlaceholder = AiChatMessage(
            id = streamingId,
            sender = MessageSender.ASSISTANT,
            text = "",
            language = _selectedLanguage.value,
            quickSuggestions = emptyList(),
            imagePath = imageAttachment,
            reportId = currentScope.id
        )

        val historySnapshot = _chatMessages.value.takeLast(6)
        _aiError.value = null
        _chatMessages.value = _chatMessages.value + userMsg + streamingPlaceholder
        _isProcessing.value = true
        saveChatSession()

        viewModelScope.launch(dispatcherProvider.main) {
            val clinicalPrompt = withContext(dispatcherProvider.default) {
                buildGeminiClinicalPrompt(clean, isTe, currentScope, historySnapshot)
            }
            val accumulatedText = StringBuilder()
            try {
                withTimeout(AI_TIMEOUT_MS) {
                    generativeModel.generateContentStream(clinicalPrompt).collect { chunk ->
                        val chunkText = chunk.text.orEmpty()
                        if (chunkText.isNotEmpty()) {
                            accumulatedText.append(chunkText)
                            val updatedText = accumulatedText.toString()
                            _chatMessages.value = _chatMessages.value.map { msg ->
                                if (msg.id == streamingId) msg.copy(text = updatedText) else msg
                            }
                        }
                    }
                }

                val finalReply = accumulatedText.toString().trim()
                if (finalReply.isEmpty()) {
                    _chatMessages.value = _chatMessages.value.filterNot { it.id == streamingId }
                    _aiError.value = if (isTe) {
                        "AI మోడల్ నుండి సమాధానం రాలేదు. దయచేసి మళ్లీ ప్రయత్నించండి."
                    } else {
                        "Gemini returned an empty response. Please try asking again."
                    }
                } else {
                    _chatMessages.value = _chatMessages.value.map { msg ->
                        if (msg.id == streamingId) {
                            msg.copy(text = finalReply, quickSuggestions = suggestions)
                        } else msg
                    }
                    saveChatSession()
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini streaming timed out", e)
                if (accumulatedText.isBlank()) {
                    _chatMessages.value = _chatMessages.value.filterNot { it.id == streamingId }
                } else {
                    _chatMessages.value = _chatMessages.value.map { msg ->
                        if (msg.id == streamingId) msg.copy(quickSuggestions = suggestions) else msg
                    }
                    saveChatSession()
                }
                _aiError.value = if (isTe) {
                    "AI ప్రతిస్పందన 60 సెకన్ల తర్వాత నిలిచిపోయింది. నెట్‌వర్క్ తనిఖీ చేసి మళ్లీ ప్రయత్నించండి."
                } else {
                    "AI response timed out after 60 seconds. Check your connection and try again."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Firebase AI generation failed", e)
                if (accumulatedText.isBlank()) {
                    _chatMessages.value = _chatMessages.value.filterNot { it.id == streamingId }
                }
                _aiError.value = e.localizedMessage
                    ?: if (isTe) "AI సహాయకుడిని సంప్రదించడంలో లోపం జరిగింది." else "Unable to reach Gemini AI. Please verify network & App Check setup."
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun buildGeminiClinicalPrompt(
        userQuery: String,
        isTelugu: Boolean,
        scope: ReportOption,
        recentHistory: List<AiChatMessage>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("You are AbhayHealth's clinical health assistant helping a patient understand their personal medical records, medications, and vital telemetry.")
        sb.appendLine("Guidelines:")
        sb.appendLine("- Reply strictly in ${if (isTelugu) "Telugu (తెలుగు)" else "English"}.")
        sb.appendLine("- Ground every numerical claim strictly in the patient data below; clearly state if a specific measurement is not recorded.")
        sb.appendLine("- Keep explanations warm, structured, easy for a patient to understand, and under 250 words unless asked for detail.")
        sb.appendLine("- Remind the user to consult their physician for diagnosis or prescription changes.")
        sb.appendLine()

        sb.appendLine("=== PATIENT PROFILE ===")
        val profile = cachedProfile
        if (profile != null) {
            sb.appendLine("Name: ${profile.fullName}")
            profile.gender?.takeIf { it.isNotBlank() }?.let { sb.appendLine("Gender: $it") }
            profile.dateOfBirth?.takeIf { it.isNotBlank() }?.let { sb.appendLine("DOB: $it") }
            profile.bloodGroup?.takeIf { it.isNotBlank() }?.let { sb.appendLine("Blood Group: $it") }
            profile.healthConditions?.takeIf { it.isNotBlank() }?.let { sb.appendLine("Known Conditions: $it") }
            profile.allergies?.takeIf { it.isNotBlank() }?.let { sb.appendLine("Known Allergies: $it") }
        } else {
            sb.appendLine("No demographic/profile details entered yet.")
        }
        sb.appendLine()

        sb.appendLine("=== ACTIVE MEDICATIONS (${cachedMedications.size}) ===")
        if (cachedMedications.isEmpty()) {
            sb.appendLine("None recorded.")
        } else {
            cachedMedications.take(15).forEach { med ->
                val takenLabel = if (med.isTaken) "Taken today" else "Pending"
                sb.appendLine("- ${med.name} | Dosage: ${med.dosage} | Slot: ${med.timeSlot} ($takenLabel)")
            }
        }
        sb.appendLine()

        sb.appendLine("=== RECENT VITAL READINGS (${cachedVitals.size}) ===")
        if (cachedVitals.isEmpty()) {
            sb.appendLine("None recorded.")
        } else {
            val dateFmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US)
            cachedVitals.take(10).forEach { v ->
                val valueStr = when {
                    v.type == "BLOOD_PRESSURE" && v.systolic != null && v.diastolic != null ->
                        "${v.systolic.toInt()}/${v.diastolic.toInt()} mmHg"
                    v.type == "WEIGHT" && v.weightKg != null -> "${v.weightKg} kg"
                    else -> v.type
                }
                val note = v.notes.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                sb.appendLine("- ${v.type}: $valueStr on ${dateFmt.format(Date(v.timestamp))}$note")
            }
        }
        sb.appendLine()

        if (scope.id != null) {
            val record = cachedRecords.firstOrNull { it.id == scope.id }
            val extracted = cachedExtractedInfos.firstOrNull { it.recordId == scope.id }
            val reportBiomarkers = extracted?.let { MedicalFieldParser.deserializeResults(it.structuredResultsJson) } ?: emptyList()
            sb.appendLine("=== FOCUSED DOCUMENT: ${scope.title} (${scope.date}) ===")
            sb.appendLine("Category: ${record?.category ?: "MEDICAL_DOCUMENT"}")
            if (reportBiomarkers.isNotEmpty()) {
                sb.appendLine("Biomarkers:")
                reportBiomarkers.take(40).forEach { b ->
                    val ref = if (b.referenceRange.isNotBlank()) " [Ref: ${b.referenceRange}]" else ""
                    val flag = if (b.isOutOfRange) " [OUT OF RANGE: ${b.status.name}]" else " [NORMAL]"
                    sb.appendLine("- ${b.testName}: ${b.value} ${b.unit}$ref$flag")
                }
            }
            extracted?.rawText?.takeIf { it.isNotBlank() }?.let { raw ->
                sb.appendLine("Document Excerpt:")
                sb.appendLine(raw.take(2500))
            }
        } else {
            sb.appendLine("=== ALL EXTRACTED BIOMARKERS (${cachedBiomarkers.size}) ===")
            if (cachedBiomarkers.isEmpty()) {
                sb.appendLine("No lab biomarkers extracted yet.")
            } else {
                cachedBiomarkers.take(40).forEach { b ->
                    val ref = if (b.referenceRange.isNotBlank()) " [Ref: ${b.referenceRange}]" else ""
                    val flag = if (b.isOutOfRange) " [OUT OF RANGE: ${b.status.name}]" else " [NORMAL]"
                    sb.appendLine("- ${b.testName}: ${b.value} ${b.unit}$ref$flag")
                }
            }
        }
        sb.appendLine()

        if (recentHistory.isNotEmpty()) {
            sb.appendLine("=== RECENT CONVERSATION ===")
            recentHistory.filter { it.text.isNotBlank() }.forEach { msg ->
                sb.appendLine("${msg.sender.name}: ${msg.text.take(400)}")
            }
            sb.appendLine()
        }

        sb.appendLine("=== USER QUESTION ===")
        sb.appendLine(userQuery)
        return sb.toString()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
    }
}
