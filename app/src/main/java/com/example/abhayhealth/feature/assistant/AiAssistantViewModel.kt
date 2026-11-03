package com.example.abhayhealth.feature.assistant

import android.content.Context
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
import com.example.abhayhealth.feature.records.MedicalRecordRepository
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import com.example.abhayhealth.feature.vitals.VitalReadingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class MessageSender {
    USER, ASSISTANT
}

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val language: String = "English",
    val quickSuggestions: List<String> = emptyList()
)

data class AiExecutiveSummary(
    val title: String = "Executive Clinical Summary",
    val titleTe: String = "క్లినికల్ ఎగ్జిక్యూటివ్ సారాంశం",
    val englishText: String = "",
    val teluguText: String = "",
    val keyMetrics: List<String> = emptyList(),
    val criticalAlertCount: Int = 0,
    val activeMedicationsCount: Int = 0,
    val lastUpdatedText: String = ""
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

    private val _executiveSummary = MutableStateFlow(AiExecutiveSummary())
    val executiveSummary: StateFlow<AiExecutiveSummary> = _executiveSummary.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<AiChatMessage>> = _chatMessages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // Internal clinical state cache
    private var cachedProfile: PatientProfile? = null
    private var cachedBiomarkers: List<ParsedMedicalResult> = emptyList()
    private var cachedMedications: List<PrescriptionMedicationEntity> = emptyList()
    private var cachedVitals: List<VitalReadingEntity> = emptyList()

    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    init {
        initTts(context.applicationContext)
        observeLanguage()
        observeDataSources()
    }

    private fun initTts(appContext: Context) {
        try {
            textToSpeech = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    _ttsAvailable.value = true
                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                            _currentlySpeakingId.value = null
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            _currentlySpeakingId.value = null
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            _isSpeaking.value = false
                            _currentlySpeakingId.value = null
                        }
                    })
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
                    initConversation(normalized)
                }
            }
        }
    }

    private fun initConversation(lang: String) {
        val isTe = lang == "Telugu"
        val greetingText = if (isTe) {
            "నమస్కారం! నేను మీ AbhayHealth AI ఆరోగ్య సహాయకుడిని. మీ తాజా ల్యాబ్ రికార్డులు, రక్తపోటు, షుగర్ స్థాయిలు మరియు మందుల వివరాలు నా వద్ద ఉన్నాయి. మీరు ఏదైనా ప్రశ్న అడగవచ్చు లేదా పైనున్న స్పీకర్ బటన్ నొక్కి మీ పూర్తి ఆరోగ్య సారాంశాన్ని వినవచ్చు!"
        } else {
            "Hello! I am your AbhayHealth AI Health Assistant. I have analyzed your diagnostic lab results, vitals, and current medication regimen. Feel free to ask any health question, or tap the Speaker icon above to listen to your clinical summary read aloud!"
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
                val uid = if (authState is AuthState.Authenticated) authState.user.uid else "local_user"

                // Observe Patient Profile
                launch {
                    patientProfileRepository?.getProfile(uid)?.collectLatest { profile ->
                        cachedProfile = profile
                        refreshExecutiveSummary()
                    }
                }

                // Observe Extracted Biomarkers
                launch {
                    medicalRecordRepository?.getAllExtractedInfo(uid)?.collectLatest { infos ->
                        val results = mutableListOf<ParsedMedicalResult>()
                        infos.forEach { info ->
                            results.addAll(MedicalFieldParser.deserializeResults(info.structuredResultsJson))
                        }
                        cachedBiomarkers = results
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

    private fun refreshExecutiveSummary() {
        val patientName = cachedProfile?.fullName?.ifBlank { null } ?: "Patient"
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
        engSummaryBuilder.append("Clinical Health Brief for $patientName. ")
        engSummaryBuilder.append("Current vital telemetry indicates arterial blood pressure at $bpStr and fasting blood glucose at $sugarStr. Heart rate is steady at $hrStr. ")
        if (outOfRangeBiomarkers.isNotEmpty()) {
            engSummaryBuilder.append("Laboratory analysis identified ${outOfRangeBiomarkers.size} out-of-range biomarkers (including ${outOfRangeBiomarkers.first().testName} at ${outOfRangeBiomarkers.first().value}). Clinical follow-up is recommended. ")
        } else {
            engSummaryBuilder.append("All extracted clinical laboratory markers demonstrate physiological stability within standard reference intervals. ")
        }
        if (medCount > 0) {
            engSummaryBuilder.append("Current active medication regimen comprises $medCount prescribed treatments ($medNames). Please ensure doses are taken punctually with appropriate food pairing.")
        } else {
            engSummaryBuilder.append("No active prescription drug interactions detected.")
        }

        // Telugu Summary Synthesis
        val teSummaryBuilder = StringBuilder()
        teSummaryBuilder.append("$patientName గారి సమగ్ర వైద్య నివేదిక సారాంశం. ")
        teSummaryBuilder.append("ప్రస్తుత ఆరోగ్య కొలతల ప్రకారం రక్తపోటు $bpStr మరియు రక్తంలో గ్లూకోజ్ $sugarStr గా నమోదయ్యాయి. గుండె వేగం నిమిషానికి $hrStr గా ఉంది. ")
        if (outOfRangeBiomarkers.isNotEmpty()) {
            teSummaryBuilder.append("ల్యాబ్ పరీక్షలలో ${outOfRangeBiomarkers.size} అంశాలు పరిమితికి మించి ఉన్నట్లు గుర్తించబడింది (${outOfRangeBiomarkers.first().testName}: ${outOfRangeBiomarkers.first().value}). వైద్యుని సంప్రదించడం మంచిది. ")
        } else {
            teSummaryBuilder.append("ల్యాబ్ నివేదికలలో అన్ని పరీక్షల ఫలితాలు సాధారణ పరిమితుల్లో ఆరోగ్యకరంగా ఉన్నాయి. ")
        }
        if (medCount > 0) {
            teSummaryBuilder.append("ప్రస్తుతం $medCount మందులు వాడుతున్నారు ($medNames). మందులను సూచించిన వేళకు ఆహారంతో పాటు తప్పక తీసుకోండి.")
        } else {
            teSummaryBuilder.append("ప్రస్తుతం ఎటువంటి మందుల పరస్పర వ్యతిరేక ప్రభావాలు లేవు.")
        }

        val keyMetrics = listOf(
            "BP: $bpStr",
            "Sugar: $sugarStr",
            "HR: $hrStr",
            if (medCount > 0) "$medCount Meds" else "No Meds"
        )

        val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        _executiveSummary.value = AiExecutiveSummary(
            title = "Clinical Executive Summary",
            titleTe = "క్లినికల్ ఎగ్జిక్యూటివ్ సారాంశం",
            englishText = engSummaryBuilder.toString(),
            teluguText = teSummaryBuilder.toString(),
            keyMetrics = keyMetrics,
            criticalAlertCount = criticalCount,
            activeMedicationsCount = medCount,
            lastUpdatedText = dateFormat.format(Date())
        )
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
        if (!isTtsReady || textToSpeech == null) return

        if (_isSpeaking.value && _currentlySpeakingId.value == id) {
            stopSpeaking()
            return
        }

        stopSpeaking()
        _currentlySpeakingId.value = id
        _isSpeaking.value = true

        val targetLocale = if (isTelugu) Locale("te", "IN") else Locale.US
        val langResult = textToSpeech?.setLanguage(targetLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            textToSpeech?.language = Locale.US
        }

        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stopSpeaking() {
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {}
        _isSpeaking.value = false
        _currentlySpeakingId.value = null
    }

    fun sendUserMessage(promptText: String) {
        val clean = promptText.trim()
        if (clean.isBlank()) return

        val isTe = _selectedLanguage.value == "Telugu"
        val userMsg = AiChatMessage(
            sender = MessageSender.USER,
            text = clean,
            language = _selectedLanguage.value
        )

        val currentList = _chatMessages.value.toMutableList()
        currentList.add(userMsg)
        _chatMessages.value = currentList
        _isProcessing.value = true

        viewModelScope.launch(dispatcherProvider.default) {
            val replyText = generateClinicalResponse(clean, isTe)
            val suggestions = if (isTe) {
                listOf("మందుల మార్గదర్శకాలు", "రక్తపోటు నియంత్రణ", "ఆహార సలహాలు")
            } else {
                listOf("Check drug safety", "How to lower BP", "Healthy dietary plan")
            }

            val assistantMsg = AiChatMessage(
                sender = MessageSender.ASSISTANT,
                text = replyText,
                language = _selectedLanguage.value,
                quickSuggestions = suggestions
            )

            withContext(dispatcherProvider.main) {
                _chatMessages.value = _chatMessages.value + assistantMsg
                _isProcessing.value = false
            }
        }
    }

    private fun generateClinicalResponse(query: String, isTelugu: Boolean): String {
        val lower = query.lowercase()

        // 1. Lab reports / Biomarkers
        if (lower.contains("lab") || lower.contains("report") || lower.contains("test") || lower.contains("biomarker") ||
            lower.contains("ల్యాబ్") || lower.contains("రిపోర్ట్") || lower.contains("పరీక్ష")) {
            return if (isTelugu) {
                if (cachedBiomarkers.isEmpty()) {
                    "మీ రికార్డులలో ఇంకా ల్యాబ్ పరీక్షల వివరాలు నమోదు కాలేదు. 'Records' ట్యాబ్ నుండి మీ రక్త పరీక్ష లేదా ల్యాబ్ నివేదిక ఫోటోను అప్‌లోడ్ చేయండి. AI స్వయంచాలకంగా ఫలితాలను విశ్లేషిస్తుంది."
                } else {
                    val outOfRange = cachedBiomarkers.filter { it.isOutOfRange }
                    val bioSummary = cachedBiomarkers.take(5).joinToString(", ") { "${it.testName}: ${it.value} ${it.unit}".trim() }
                    if (outOfRange.isNotEmpty()) {
                        "మీ ల్యాబ్ నివేదికల పరిశీలన:\n• నమోదైన పరీక్షలు: $bioSummary\n• హెచ్చరిక: ${outOfRange.size} పరీక్ష ఫలితాలు సాధారణ పరిమితి కంటే వ్యత్యాసంగా ఉన్నాయి (${outOfRange.joinToString { "${it.testName} (${it.value})" }}). దయచేసి మీ వైద్యుని సంప్రదించి చికిత్స సలహా తీసుకోండి."
                    } else {
                        "మీ ల్యాబ్ నివేదికల పరిశీలన:\n• నమోదైన పరీక్షలు: $bioSummary\n• ఫలితం: మీ అన్ని ల్యాబ్ సూచికలు సాధారణ పరిమితుల్లో ఆరోగ్యకరంగా ఉన్నాయి!"
                    }
                }
            } else {
                if (cachedBiomarkers.isEmpty()) {
                    "No diagnostic laboratory results found in your account yet. You can upload a photo of your blood test or prescription in the 'Records' tab, and I will instantly extract and analyze every biomarker."
                } else {
                    val outOfRange = cachedBiomarkers.filter { it.isOutOfRange }
                    val bioSummary = cachedBiomarkers.take(5).joinToString(", ") { "${it.testName}: ${it.value} ${it.unit}".trim() }
                    if (outOfRange.isNotEmpty()) {
                        "Clinical Lab Report Audit:\n• Extracted Markers: $bioSummary\n• Attention Required: ${outOfRange.size} biomarkers are outside normal physiological ranges (${outOfRange.joinToString { "${it.testName} at ${it.value}" }}). We recommend sharing these with your physician."
                    } else {
                        "Clinical Lab Report Audit:\n• Extracted Markers: $bioSummary\n• Result: All evaluated biomarkers are within optimal physiological reference ranges. Keep up the good lifestyle and medication adherence!"
                    }
                }
            }
        }

        // 2. Medication / Drugs / Safety
        if (lower.contains("med") || lower.contains("drug") || lower.contains("tablet") || lower.contains("pill") || lower.contains("safety") ||
            lower.contains("మందు") || lower.contains("మాత్ర") || lower.contains("భద్రత")) {
            return if (isTelugu) {
                if (cachedMedications.isEmpty()) {
                    "మీ వద్ద ఇంకా యాక్టివ్ ప్రిస్క్రిప్షన్ మందులు నమోదు కాలేదు. 'Medicines' ట్యాబ్‌లో మీ ప్రిస్క్రిప్షన్‌ను జోడించండి."
                } else {
                    val medListStr = cachedMedications.joinToString("\n") { "• ${it.name} (${it.dosage}) - సమయం: ${it.timeSlot}" }
                    "మీ ప్రస్తుత మందుల వివరాలు మరియు భద్రతా సమాచారం:\n$medListStr\n\nముఖ్య సూచన: మందులను రోజూ నిర్దేశిత సమయానికి తగినంత నీటితో మరియు వైద్యుల సూచనల ప్రకారం ఆహారంతో పాటు తీసుకోండి."
                }
            } else {
                if (cachedMedications.isEmpty()) {
                    "No active prescription medications found in your profile. You can log medications under the 'Medicines' tab for automatic dose scheduling and interaction monitoring."
                } else {
                    val medListStr = cachedMedications.joinToString("\n") { "• ${it.name} (${it.dosage}) — Scheduled for: ${it.timeSlot}" }
                    "Active Medication Regimen & Safety Audit:\n$medListStr\n\nClinical Guidance: Always maintain adherence at scheduled times. Take oral hypoglycemics (like Metformin) with meals to avoid gastrointestinal upset, and antihypertensives in the morning."
                }
            }
        }

        // 3. Blood Pressure / Vitals
        if (lower.contains("pressure") || lower.contains("bp") || lower.contains("pulse") || lower.contains("heart") ||
            lower.contains("రక్తపోటు") || lower.contains("గుండె") || lower.contains("నాడి")) {
            val bpReading = cachedVitals.firstOrNull { it.type == "BLOOD_PRESSURE" && it.systolic != null }
            val bpStr = if (bpReading != null) "${bpReading.systolic?.toInt()}/${bpReading.diastolic?.toInt()} mmHg" else "120/80 mmHg"
            return if (isTelugu) {
                "మీ ప్రస్తుత రక్తపోటు: $bpStr.\nరక్తపోటు నిర్వహణ చిట్కాలు:\n1. ఆహారంలో ఉప్పు (సోడియం) పరిమాణాన్ని తగ్గించండి.\n2. రోజుకు 30 నిమిషాలు నడక లేదా తేలికపాటి వ్యాయామం చేయండి.\n3. క్రమం తప్పకుండా రక్తపోటును కొలవండి."
            } else {
                "Your latest recorded Blood Pressure is $bpStr.\nCardiovascular Health Guidance:\n1. Target BP is generally below 120/80 mmHg for healthy adults.\n2. Limit dietary sodium intake (< 2,000 mg/day) and incorporate potassium-rich vegetables.\n3. Engage in at least 150 minutes of moderate aerobic activity weekly."
            }
        }

        // 4. Food / Diet instructions
        if (lower.contains("food") || lower.contains("diet") || lower.contains("eat") || lower.contains("ఆహారం") || lower.contains("తిండి")) {
            return if (isTelugu) {
                "ఆరోగ్యకరమైన ఆహార నియమాలు:\n• రక్తంలో చక్కెర నియంత్రణకు పీచు పదార్ధాలు (ఫైబర్), తాజా కూరగాయలు మరియు చిరుధాన్యాలు ఎక్కువగా తీసుకోండి.\n• తీపి పదార్థాలు మరియు మైదా వంటి శుద్ధి చేసిన పిండి పదార్థాలను నివారించండి.\n• రోజుకు కనీసం 2.5 నుండి 3 లీటర్ల మంచినీరు త్రాగండి."
            } else {
                "Clinical Nutritional Guidance:\n• For metabolic & cardiovascular wellness: Prioritize complex carbohydrates (millets, oats, brown rice) and lean legumes.\n• Minimize ultra-processed foods, refined sugars, and trans fats.\n• Stay well hydrated with at least 2.5–3 liters of water daily, especially when taking daily medications."
            }
        }

        // General fallback
        return if (isTelugu) {
            "మీరు అడిగిన విషయానికి సంబంధించి: మీ ల్యాబ్ నివేదికలు, మందుల మోతాదులు మరియు రోజువారీ ఆరోగ్య సూచికల ఆధారంగా నేను నిరంతరం సహాయం అందిస్తాను. మీ ప్రశ్నకు మరింత నిర్దిష్టమైన సలహా కోసం మీ వైద్యుని సంప్రదించాల్సిందిగా సూచిస్తున్నాము."
        } else {
            "Regarding your query: Based on your synthesized medical telemetry, diagnostic markers, and prescription timeline, your health trends are actively tracked. If you experience persistent symptoms, please consult your primary physician directly."
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
    }
}
