package com.example.abhayhealth.feature.records

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.core.common.DefaultDispatcherProvider
import com.example.abhayhealth.core.common.DispatcherProvider
import com.example.abhayhealth.data.remote.FirestoreHealthRepository
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.model.ParsedMedicalResult
import com.example.abhayhealth.domain.repository.AuthRepository
import com.example.abhayhealth.feature.medicines.MedicationAlarmScheduler
import com.example.abhayhealth.feature.medicines.MedicationDao
import com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity
import com.example.abhayhealth.feature.vitals.VitalReadingRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExtractionReviewViewModel(
    private val medicalRecordRepository: MedicalRecordRepository,
    private val authRepository: AuthRepository,
    private val medicationDao: MedicationDao? = null,
    private val vitalReadingRepository: VitalReadingRepository? = null,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val firestoreHealthRepository: FirestoreHealthRepository? = null
) : ViewModel() {

    private val _selectedRecord = MutableStateFlow<MedicalRecord?>(null)
    val selectedRecord: StateFlow<MedicalRecord?> = _selectedRecord.asStateFlow()

    private val _extractedInfo = MutableStateFlow<ExtractedMedicalInfo?>(null)
    val extractedInfo: StateFlow<ExtractedMedicalInfo?> = _extractedInfo.asStateFlow()

    private val _documentTitle = MutableStateFlow("")
    val documentTitle: StateFlow<String> = _documentTitle.asStateFlow()

    private val _reportDate = MutableStateFlow("")
    val reportDate: StateFlow<String> = _reportDate.asStateFlow()

    private val _parsedResults = MutableStateFlow<List<ParsedMedicalResult>>(emptyList())
    val parsedResults: StateFlow<List<ParsedMedicalResult>> = _parsedResults.asStateFlow()

    private val _parsedMedicines = MutableStateFlow<List<PrescriptionMedicationEntity>>(emptyList())
    val parsedMedicines: StateFlow<List<PrescriptionMedicationEntity>> = _parsedMedicines.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _currentProcessingPage = MutableStateFlow(0)
    val currentProcessingPage: StateFlow<Int> = _currentProcessingPage.asStateFlow()

    private val _totalProcessingPages = MutableStateFlow(0)
    val totalProcessingPages: StateFlow<Int> = _totalProcessingPages.asStateFlow()

    private val _isMultiPagePdf = MutableStateFlow(false)
    val isMultiPagePdf: StateFlow<Boolean> = _isMultiPagePdf.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(1)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _extractionStatusMessage = MutableStateFlow("Initializing text recognition...")
    val extractionStatusMessage: StateFlow<String> = _extractionStatusMessage.asStateFlow()

    private val _hasExtractionFailed = MutableStateFlow(false)
    val hasExtractionFailed: StateFlow<Boolean> = _hasExtractionFailed.asStateFlow()

    private val _verifiedTestIds = MutableStateFlow<Set<String>>(emptySet())
    val verifiedTestIds: StateFlow<Set<String>> = _verifiedTestIds.asStateFlow()

    private val _verifiedMedicineIds = MutableStateFlow<Set<String>>(emptySet())
    val verifiedMedicineIds: StateFlow<Set<String>> = _verifiedMedicineIds.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _infoMessage = MutableStateFlow<String?>(null)
    val infoMessage: StateFlow<String?> = _infoMessage.asStateFlow()

    private val _showReExtractWarning = MutableStateFlow(false)
    val showReExtractWarning: StateFlow<Boolean> = _showReExtractWarning.asStateFlow()

    private var loadingJob: Job? = null
    private var extractionJob: Job? = null

    init {
        observeAuthState()
    }

    val currentAccountUid: String?
        get() = authRepository.currentUser?.uid?.ifBlank { null }

    private fun getCurrentUid(): String {
        return currentAccountUid ?: ""
    }

    private fun observeAuthState() {
        viewModelScope.launch(dispatcherProvider.main) {
            authRepository.authState.collectLatest { state ->
                when (state) {
                    is AuthState.Authenticated -> {
                        val currentRecord = _selectedRecord.value
                        if (currentRecord != null && currentRecord.accountUid != state.user.uid) {
                            clearAllState()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun clearAllState() {
        loadingJob?.cancel()
        extractionJob?.cancel()
        _selectedRecord.value = null
        _extractedInfo.value = null
        _documentTitle.value = ""
        _reportDate.value = ""
        _parsedResults.value = emptyList()
        _parsedMedicines.value = emptyList()
        _isExtracting.value = false
        _currentProcessingPage.value = 0
        _totalProcessingPages.value = 0
        _extractionStatusMessage.value = "Initializing text recognition..."
        _isSaving.value = false
        _errorMessage.value = null
        _infoMessage.value = null
        _showReExtractWarning.value = false
    }

    fun loadRecordAndInfo(recordId: Long) {
        val uid = getCurrentUid()
        if (uid.isBlank()) {
            _errorMessage.value = "Please sign in with Google to view this record."
            return
        }

        loadingJob?.cancel()
        loadingJob = viewModelScope.launch(dispatcherProvider.main) {
            val record = withContext(dispatcherProvider.io) {
                medicalRecordRepository.getRecordById(recordId, uid)
            }
            if (record == null) {
                clearAllState()
                _errorMessage.value = "This record is not available."
                return@launch
            }

            _selectedRecord.value = record
            val path = record.localFilePath
            if (!path.isNullOrBlank() && (path.endsWith(".pdf", ignoreCase = true) || record.mimeType?.contains("pdf", ignoreCase = true) == true)) {
                val file = java.io.File(path)
                if (file.exists()) {
                    val count = withContext(dispatcherProvider.io) {
                        DocumentExtractionEngine.getPdfPageCount(file)
                    }
                    _totalProcessingPages.value = count
                    if (count > 1) {
                        _isMultiPagePdf.value = true
                    }
                }
            }

            val info = withContext(dispatcherProvider.io) {
                medicalRecordRepository.getExtractedInfoSync(recordId, uid)
            }
            _extractedInfo.value = info

            if (info != null) {
                val deserialized = MedicalFieldParser.deserializeResults(info.structuredResultsJson)
                val meds = MedicalFieldParser.parsePrescriptionMedicines(info.rawText, uid)

                if (info.reviewStatus != ExtractedMedicalInfo.STATUS_UNREVIEWED || deserialized.isNotEmpty() || meds.isNotEmpty()) {
                    _documentTitle.value = info.documentTitle
                    _reportDate.value = info.reportDate ?: ""
                    _parsedResults.value = deserialized
                    _parsedMedicines.value = meds
                } else {
                    if (info.rawText.isNotBlank() && !info.rawText.startsWith("Failed") && !info.rawText.startsWith("No readable") && !info.rawText.startsWith("Could not")) {
                        val parsedAgain = MedicalFieldParser.parseTestResults(info.rawText)
                        val medsAgain = MedicalFieldParser.parsePrescriptionMedicines(info.rawText, uid)
                        if (parsedAgain.isNotEmpty() || medsAgain.isNotEmpty()) {
                            _documentTitle.value = info.documentTitle.ifBlank { record.title }
                            _reportDate.value = info.reportDate ?: (MedicalFieldParser.parseReportDate(info.rawText) ?: "")
                            _parsedResults.value = parsedAgain
                            _parsedMedicines.value = medsAgain
                            return@launch
                        }
                    }
                    _documentTitle.value = info.documentTitle.ifBlank { record.title }
                    if (_isMultiPagePdf.value) {
                        extractPage(1)
                    } else {
                        runExtraction(record, force = true)
                    }
                }
            } else {
                _documentTitle.value = record.title
                if (_isMultiPagePdf.value) {
                    extractPage(1)
                } else {
                    runExtraction(record, force = false)
                }
            }
        }
    }

    fun extractPage(pageNumber: Int) {
        val record = _selectedRecord.value ?: return
        val path = record.localFilePath ?: return
        val file = java.io.File(path)
        if (!file.exists()) return

        val uid = getCurrentUid()
        extractionJob?.cancel()
        extractionJob = viewModelScope.launch(dispatcherProvider.main) {
            _isExtracting.value = true
            _hasExtractionFailed.value = false
            _errorMessage.value = null

            _currentProcessingPage.value = pageNumber
            _currentPageIndex.value = pageNumber
            val total = _totalProcessingPages.value.coerceAtLeast(1)
            _extractionStatusMessage.value = "Extracting Page $pageNumber of $total..."

            try {
                val extraction = withContext(dispatcherProvider.io) {
                    DocumentExtractionEngine.extractPdfPage(file, pageNumber - 1)
                }

                when (extraction) {
                    is ExtractionResult.Success -> {
                        val raw = extraction.rawText
                        val title = MedicalFieldParser.parseDocumentTitle(raw, record.title)
                        val date = MedicalFieldParser.parseReportDate(raw) ?: ""
                        val results = MedicalFieldParser.parseTestResults(raw)
                        val medicines = MedicalFieldParser.parsePrescriptionMedicines(raw, uid)

                        _documentTitle.value = if (_documentTitle.value.isNotBlank()) _documentTitle.value else title
                        if (date.isNotBlank() && _reportDate.value.isBlank()) _reportDate.value = date

                        _parsedResults.value = results
                        _parsedMedicines.value = medicines

                        if (results.isEmpty() && medicines.isEmpty()) {
                            _infoMessage.value = "Page $pageNumber of $total analyzed. No specific test fields found on this page. Review and tap 'Save & Next Page'."
                        } else {
                            _infoMessage.value = "Extracted ${results.size} field(s) & ${medicines.size} medicine(s) from Page $pageNumber of $total. Review and tap 'Save & Next Page'."
                        }
                    }
                    is ExtractionResult.Error -> {
                        _errorMessage.value = extraction.message
                    }
                }
            } catch (t: Throwable) {
                _errorMessage.value = "Failed Page $pageNumber extraction: ${t.localizedMessage}"
            } finally {
                _isExtracting.value = false
            }
        }
    }

    fun saveCurrentPageAndNext(context: Context? = null, onSuccess: () -> Unit) {
        if (_isSaving.value) return

        val uid = getCurrentUid()
        val record = _selectedRecord.value ?: return
        val isMulti = _isMultiPagePdf.value
        val curPage = _currentPageIndex.value
        val totalPages = _totalProcessingPages.value

        viewModelScope.launch(dispatcherProvider.main) {
            _isSaving.value = true
            _errorMessage.value = null
            _infoMessage.value = null

            try {
                val existing = _extractedInfo.value
                val currentTitle = _documentTitle.value.trim().ifBlank { record.title }
                val currentDate = _reportDate.value.trim()

                val isFinalPage = !isMulti || curPage >= totalPages
                val status = if (isFinalPage) ExtractedMedicalInfo.STATUS_REVIEWED_AND_SAVED else ExtractedMedicalInfo.STATUS_EDITED

                val info = ExtractedMedicalInfo(
                    recordId = record.id,
                    accountUid = uid,
                    rawText = existing?.rawText ?: "",
                    documentTitle = currentTitle,
                    reportDate = currentDate.ifBlank { null },
                    structuredResultsJson = MedicalFieldParser.serializeResults(_parsedResults.value),
                    reviewStatus = status,
                    processedPages = curPage,
                    updatedAtTimestamp = System.currentTimeMillis()
                )

                withContext(dispatcherProvider.io) {
                    medicalRecordRepository.saveExtractedInfoWithTitleUpdate(info, info.documentTitle)
                }
                val updatedRecord = record.copy(title = info.documentTitle)
                firestoreHealthRepository?.enqueueMedicalRecord(updatedRecord, info)

                val dao = medicationDao
                val medicines = _parsedMedicines.value
                if (dao != null && medicines.isNotEmpty()) {
                    val syncedMeds = mutableListOf<PrescriptionMedicationEntity>()
                    withContext(dispatcherProvider.io) {
                        val existingMeds = dao.getMedicationsByAccount(uid).first()
                        medicines.forEach { med ->
                            val existingMatch = existingMeds.firstOrNull {
                                it.name.equals(med.name, ignoreCase = true) &&
                                    it.timeSlot.equals(med.timeSlot, ignoreCase = true)
                            }
                            val updatedMed = if (existingMatch != null) {
                                med.copy(id = existingMatch.id, accountUid = uid, isTaken = existingMatch.isTaken)
                            } else {
                                med.copy(accountUid = uid)
                            }
                            dao.insertOrUpdate(updatedMed)
                            syncedMeds.add(updatedMed)
                            context?.let { ctx ->
                                MedicationAlarmScheduler.scheduleMedicationAlarm(
                                    context = ctx,
                                    medicationId = updatedMed.id,
                                    name = updatedMed.name,
                                    dosage = updatedMed.dosage,
                                    timeSlot = updatedMed.timeSlot
                                )
                            }
                        }
                    }
                    syncedMeds.forEach { synced ->
                        firestoreHealthRepository?.enqueueMedication(synced)
                    }
                }

                val reportTimestamp = MedicalFieldParser.parseDateToTimestamp(currentDate) ?: record.recordDate
                val recordVitalNote = "Extracted from record #${record.id}"
                val vitals = MedicalFieldParser.extractVitalsFromResults(_parsedResults.value, uid)
                val vitalRepo = vitalReadingRepository
                if (vitalRepo != null && vitals.isNotEmpty()) {
                    val syncedVitals = mutableListOf<com.example.abhayhealth.feature.vitals.VitalReadingEntity>()
                    withContext(dispatcherProvider.io) {
                        val existingVitals = vitalRepo.getAllReadings(uid).first()
                        vitals.forEach { vital ->
                            val taggedVital = vital.copy(timestamp = reportTimestamp, notes = recordVitalNote)
                            val alreadyExists = existingVitals.any {
                                it.type == taggedVital.type &&
                                    it.notes == recordVitalNote &&
                                    it.systolic == taggedVital.systolic &&
                                    it.diastolic == taggedVital.diastolic &&
                                    it.weightKg == taggedVital.weightKg
                            }
                            if (!alreadyExists) {
                                val vitalId = vitalRepo.insertReading(taggedVital)
                                syncedVitals.add(taggedVital.copy(id = vitalId))
                            }
                        }
                    }
                    syncedVitals.forEach { syncedVital ->
                        firestoreHealthRepository?.enqueueVitalReading(syncedVital)
                    }
                }

                _selectedRecord.value = updatedRecord
                _extractedInfo.value = info
                _isSaving.value = false

                if (isMulti && curPage < totalPages) {
                    val nextPage = curPage + 1
                    _infoMessage.value = "Page $curPage saved! Loading Page $nextPage of $totalPages..."
                    extractPage(nextPage)
                } else {
                    _infoMessage.value = "All pages reviewed and saved successfully!"
                    onSuccess()
                }

            } catch (e: Exception) {
                _isSaving.value = false
                _errorMessage.value = "Failed to save page: ${e.localizedMessage ?: "Unknown error"}"
            }
        }
    }

    fun requestReExtraction() {
        val record = _selectedRecord.value
        if (record == null) {
            _errorMessage.value = "Record not available."
            return
        }

        val info = _extractedInfo.value
        if (info != null && info.reviewStatus != ExtractedMedicalInfo.STATUS_UNREVIEWED) {
            _showReExtractWarning.value = true
        } else {
            runExtraction(record, force = true)
        }
    }

    fun confirmReExtraction() {
        _showReExtractWarning.value = false
        val record = _selectedRecord.value ?: return
        runExtraction(record, force = true)
    }

    fun cancelReExtraction() {
        _showReExtractWarning.value = false
    }

    fun retryExtraction() {
        val record = _selectedRecord.value ?: return
        runExtraction(record, force = true)
    }

    fun cancelExtraction() {
        extractionJob?.cancel()
        _isExtracting.value = false
        _hasExtractionFailed.value = true
        _errorMessage.value = "Extraction cancelled. You can retry anytime or add fields manually."
    }

    fun toggleTestVerified(id: String) {
        val current = _verifiedTestIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _verifiedTestIds.value = current
    }

    fun toggleMedicineVerified(id: String) {
        val current = _verifiedMedicineIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _verifiedMedicineIds.value = current
    }

    fun verifyAll() {
        val allTestIds = _parsedResults.value.map { it.id }.toSet()
        val allMedIds = _parsedMedicines.value.map { it.id.toString() }.toSet()
        _verifiedTestIds.value = allTestIds
        _verifiedMedicineIds.value = allMedIds
    }

    fun runExtraction(record: MedicalRecord, force: Boolean = false) {
        val uid = getCurrentUid()

        extractionJob?.cancel()
        extractionJob = viewModelScope.launch(dispatcherProvider.main) {
            _isExtracting.value = true
            _hasExtractionFailed.value = false
            _errorMessage.value = null
            _infoMessage.value = null

            _currentProcessingPage.value = 0
            _totalProcessingPages.value = 0
            _extractionStatusMessage.value = "Analyzing multi-page document with text recognition..."

            try {
                // Generous 240 second watchdog for large multi-page documents
                val extraction = kotlinx.coroutines.withTimeoutOrNull(240_000L) {
                    DocumentExtractionEngine.extractText(record) { curPage, totalPages ->
                        _currentProcessingPage.value = curPage
                        _totalProcessingPages.value = totalPages
                        _extractionStatusMessage.value = "Reading page $curPage of $totalPages with text recognition..."
                    }
                }

                if (extraction == null) {
                    _hasExtractionFailed.value = true
                    _errorMessage.value = "Document extraction timed out. Tap 'Retry Extraction' or add details manually."
                    val timeoutInfo = ExtractedMedicalInfo(
                        recordId = record.id,
                        accountUid = uid,
                        rawText = "Document extraction timed out.",
                        documentTitle = record.title,
                        reportDate = null,
                        structuredResultsJson = "[]",
                        reviewStatus = ExtractedMedicalInfo.STATUS_UNREVIEWED,
                        processedPages = 1
                    )
                    withContext(dispatcherProvider.io) {
                        medicalRecordRepository.saveExtractedInfo(timeoutInfo)
                    }
                    _extractedInfo.value = timeoutInfo
                    return@launch
                }

                when (extraction) {
                    is ExtractionResult.Success -> {
                        val raw = extraction.rawText
                        val title = MedicalFieldParser.parseDocumentTitle(raw, record.title)
                        val date = MedicalFieldParser.parseReportDate(raw) ?: ""
                        val results = MedicalFieldParser.parseTestResults(raw)
                        val medicines = MedicalFieldParser.parsePrescriptionMedicines(raw, uid)

                        val info = ExtractedMedicalInfo(
                            recordId = record.id,
                            accountUid = uid,
                            rawText = raw,
                            documentTitle = title,
                            reportDate = date.ifBlank { null },
                            structuredResultsJson = MedicalFieldParser.serializeResults(results),
                            reviewStatus = ExtractedMedicalInfo.STATUS_UNREVIEWED,
                            processedPages = extraction.pagesProcessed
                        )

                        // Save locally on IO dispatcher
                        withContext(dispatcherProvider.io) {
                            medicalRecordRepository.saveExtractedInfo(info)
                        }

                        _documentTitle.value = title
                        _reportDate.value = date
                        _parsedResults.value = results
                        _parsedMedicines.value = medicines
                        _extractedInfo.value = info

                        if (results.isEmpty() && medicines.isEmpty()) {
                            _hasExtractionFailed.value = true
                            _errorMessage.value = "Scanned document text (${raw.length} characters across ${extraction.pagesProcessed} page(s)), but no specific test parameters or medicines could be matched. Tap 'Retry Extraction' or add fields manually."
                        } else {
                            _hasExtractionFailed.value = false
                            val partialNote = if (extraction.isPartial) " (First ${extraction.pagesProcessed} pages analyzed)" else " (${extraction.pagesProcessed} page(s))"
                            _infoMessage.value = "Extracted ${results.size} test parameter(s) and ${medicines.size} medicine(s)$partialNote. Please review and verify all fields before saving."
                        }
                    }

                    is ExtractionResult.Error -> {
                        _hasExtractionFailed.value = true
                        _errorMessage.value = extraction.message
                        val fallbackInfo = ExtractedMedicalInfo(
                            recordId = record.id,
                            accountUid = uid,
                            rawText = extraction.message,
                            documentTitle = record.title,
                            reportDate = null,
                            structuredResultsJson = "[]",
                            reviewStatus = ExtractedMedicalInfo.STATUS_UNREVIEWED,
                            processedPages = 1
                        )
                        withContext(dispatcherProvider.io) {
                            medicalRecordRepository.saveExtractedInfo(fallbackInfo)
                        }
                        _extractedInfo.value = fallbackInfo
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _hasExtractionFailed.value = true
                _errorMessage.value = "Extraction process failed: ${t.localizedMessage ?: "Unknown error"}. Tap 'Retry Extraction' or enter data manually."
            } finally {
                _isExtracting.value = false
            }
        }
    }

    fun onTitleChanged(value: String) { _documentTitle.value = value }
    fun onDateChanged(value: String) { _reportDate.value = value }

    fun addTestResult(testName: String, value: String, unit: String, referenceRange: String) {
        if (testName.isBlank() || value.isBlank()) return
        val current = _parsedResults.value.toMutableList()
        current.add(
            ParsedMedicalResult(
                id = java.util.UUID.randomUUID().toString(),
                testName = testName.trim(),
                value = value.trim(),
                unit = unit.trim(),
                referenceRange = referenceRange.trim()
            )
        )
        _parsedResults.value = current
    }

    fun updateTestResult(id: String, testName: String, value: String, unit: String, referenceRange: String) {
        val current = _parsedResults.value.map { item ->
            if (item.id == id) {
                item.copy(
                    testName = testName.trim(),
                    value = value.trim(),
                    unit = unit.trim(),
                    referenceRange = referenceRange.trim()
                )
            } else item
        }
        _parsedResults.value = current
    }

    fun deleteTestResult(id: String) {
        _parsedResults.value = _parsedResults.value.filter { it.id != id }
    }

    fun addMedicine(med: PrescriptionMedicationEntity) {
        if (med.name.isBlank()) return
        val current = _parsedMedicines.value.toMutableList()
        current.add(med)
        _parsedMedicines.value = current
    }

    fun addMedicine(name: String, dosage: String, timeSlot: String) {
        if (name.isBlank()) return
        val uid = getCurrentUid()
        addMedicine(
            PrescriptionMedicationEntity(
                accountUid = uid,
                name = name.trim(),
                dosage = dosage.trim().ifBlank { "As Directed" },
                timeSlot = timeSlot.trim().ifBlank { "MORNING (08:30)" }
            )
        )
    }

    fun updateMedicine(med: PrescriptionMedicationEntity) {
        _parsedMedicines.value = _parsedMedicines.value.map {
            if (it.id == med.id) med else it
        }
    }

    fun updateMedicine(index: Int, name: String, dosage: String, timeSlot: String) {
        val current = _parsedMedicines.value.toMutableList()
        if (index in current.indices) {
            val existing = current[index]
            current[index] = existing.copy(
                name = name.trim(),
                dosage = dosage.trim(),
                timeSlot = timeSlot.trim()
            )
            _parsedMedicines.value = current
        }
    }

    fun deleteMedicine(id: String) {
        _parsedMedicines.value = _parsedMedicines.value.filter { it.id != id }
    }

    fun deleteMedicine(index: Int) {
        val current = _parsedMedicines.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _parsedMedicines.value = current
        }
    }

    fun saveReviewedInfo(context: Context? = null, onSuccess: () -> Unit) {
        if (_isSaving.value) return

        val uid = getCurrentUid()
        val record = _selectedRecord.value ?: return

        viewModelScope.launch(dispatcherProvider.main) {
            _isSaving.value = true
            _errorMessage.value = null
            _infoMessage.value = null

            try {
                val existing = _extractedInfo.value
                val currentTitle = _documentTitle.value.trim().ifBlank { record.title }
                val currentDate = _reportDate.value.trim()

                val info = ExtractedMedicalInfo(
                    recordId = record.id,
                    accountUid = uid,
                    rawText = existing?.rawText ?: "",
                    documentTitle = currentTitle,
                    reportDate = currentDate.ifBlank { null },
                    structuredResultsJson = MedicalFieldParser.serializeResults(_parsedResults.value),
                    reviewStatus = ExtractedMedicalInfo.STATUS_REVIEWED_AND_SAVED,
                    processedPages = existing?.processedPages ?: 1,
                    updatedAtTimestamp = System.currentTimeMillis()
                )

                // 1. Update record title & extracted info
                withContext(dispatcherProvider.io) {
                    medicalRecordRepository.saveExtractedInfoWithTitleUpdate(info, info.documentTitle)
                }
                val updatedRecord = record.copy(title = info.documentTitle)
                firestoreHealthRepository?.enqueueMedicalRecord(updatedRecord, info)

                // 2. Add medications to MedicationDao (deduplicated by name + timeSlot) and schedule local notification alarms
                val dao = medicationDao
                val medicines = _parsedMedicines.value
                if (dao != null && medicines.isNotEmpty()) {
                    val syncedMeds = mutableListOf<PrescriptionMedicationEntity>()
                    withContext(dispatcherProvider.io) {
                        val existingMeds = dao.getMedicationsByAccount(uid).first()
                        medicines.forEach { med ->
                            val existingMatch = existingMeds.firstOrNull {
                                it.name.equals(med.name, ignoreCase = true) &&
                                    it.timeSlot.equals(med.timeSlot, ignoreCase = true)
                            }
                            val updatedMed = if (existingMatch != null) {
                                med.copy(id = existingMatch.id, accountUid = uid, isTaken = existingMatch.isTaken)
                            } else {
                                med.copy(accountUid = uid)
                            }
                            dao.insertOrUpdate(updatedMed)
                            syncedMeds.add(updatedMed)
                            context?.let { ctx ->
                                MedicationAlarmScheduler.scheduleMedicationAlarm(
                                    context = ctx,
                                    medicationId = updatedMed.id,
                                    name = updatedMed.name,
                                    dosage = updatedMed.dosage,
                                    timeSlot = updatedMed.timeSlot
                                )
                            }
                        }
                    }
                    syncedMeds.forEach { synced ->
                        firestoreHealthRepository?.enqueueMedication(synced)
                    }
                }

                // 3. Extract vitals and add to VitalReadingRepository (deduplicated per record) so they populate Trends & Home
                val reportTimestamp = MedicalFieldParser.parseDateToTimestamp(currentDate) ?: record.recordDate
                val recordVitalNote = "Extracted from record #${record.id}"
                val vitals = MedicalFieldParser.extractVitalsFromResults(_parsedResults.value, uid)
                val vitalRepo = vitalReadingRepository
                if (vitalRepo != null && vitals.isNotEmpty()) {
                    val syncedVitals = mutableListOf<com.example.abhayhealth.feature.vitals.VitalReadingEntity>()
                    withContext(dispatcherProvider.io) {
                        val existingVitals = vitalRepo.getAllReadings(uid).first()
                        vitals.forEach { vital ->
                            val taggedVital = vital.copy(timestamp = reportTimestamp, notes = recordVitalNote)
                            val alreadyExists = existingVitals.any {
                                it.type == taggedVital.type &&
                                    it.notes == recordVitalNote &&
                                    it.systolic == taggedVital.systolic &&
                                    it.diastolic == taggedVital.diastolic &&
                                    it.weightKg == taggedVital.weightKg
                            }
                            if (!alreadyExists) {
                                val vitalId = vitalRepo.insertReading(taggedVital)
                                syncedVitals.add(taggedVital.copy(id = vitalId))
                            }
                        }
                    }
                    syncedVitals.forEach { syncedVital ->
                        firestoreHealthRepository?.enqueueVitalReading(syncedVital)
                    }
                }

                _selectedRecord.value = updatedRecord
                _extractedInfo.value = info
                _infoMessage.value = "Data successfully saved! Medicines and vitals updated in your health vault."
                onSuccess()

            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _errorMessage.value = "Could not save your changes: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun clearMessages() {
        _errorMessage.value = null
        _infoMessage.value = null
    }
}
