package com.example.abhayhealth.feature.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.core.common.DefaultDispatcherProvider
import com.example.abhayhealth.core.common.DispatcherProvider
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.model.ParsedMedicalResult
import com.example.abhayhealth.domain.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExtractionReviewViewModel(
    private val medicalRecordRepository: MedicalRecordRepository,
    private val authRepository: AuthRepository,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider()
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

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _infoMessage = MutableStateFlow<String?>(null)
    val infoMessage: StateFlow<String?> = _infoMessage.asStateFlow()

    private val _showReExtractWarning = MutableStateFlow(false)
    val showReExtractWarning: StateFlow<Boolean> = _showReExtractWarning.asStateFlow()

    private var activeJob: Job? = null

    init {
        observeAuthState()
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
                    else -> {
                        clearAllState()
                    }
                }
            }
        }
    }

    fun clearAllState() {
        activeJob?.cancel()
        _selectedRecord.value = null
        _extractedInfo.value = null
        _documentTitle.value = ""
        _reportDate.value = ""
        _parsedResults.value = emptyList()
        _isExtracting.value = false
        _isSaving.value = false
        _errorMessage.value = null
        _infoMessage.value = null
        _showReExtractWarning.value = false
    }

    fun loadRecordAndInfo(recordId: Long) {
        val uid = authRepository.currentUser?.uid
        if (uid.isNullOrEmpty()) {
            clearAllState()
            _errorMessage.value = "Please sign in to view records."
            return
        }

        activeJob?.cancel()
        activeJob = viewModelScope.launch(dispatcherProvider.main) {
            val record = medicalRecordRepository.getRecordById(recordId, uid)
            if (record == null || record.accountUid != uid) {
                clearAllState()
                _errorMessage.value = "This record is not available in the current account."
                return@launch
            }

            _selectedRecord.value = record
            val info = medicalRecordRepository.getExtractedInfoSync(recordId, uid)
            _extractedInfo.value = info

            if (info != null) {
                _documentTitle.value = info.documentTitle
                _reportDate.value = info.reportDate ?: ""
                _parsedResults.value = MedicalFieldParser.deserializeResults(info.structuredResultsJson)
            } else {
                _documentTitle.value = record.title
                runExtraction(record, force = false)
            }
        }
    }

    fun requestReExtraction() {
        val uid = authRepository.currentUser?.uid
        val record = _selectedRecord.value
        if (uid.isNullOrEmpty() || record == null || record.accountUid != uid) {
            _errorMessage.value = "This record is not available in the current account."
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
        val uid = authRepository.currentUser?.uid
        val record = _selectedRecord.value
        if (uid.isNullOrEmpty() || record == null || record.accountUid != uid) {
            _errorMessage.value = "This record is not available in the current account."
            return
        }
        runExtraction(record, force = true)
    }

    fun cancelReExtraction() {
        _showReExtractWarning.value = false
    }


    fun runExtraction(record: MedicalRecord, force: Boolean = false) {
        val uid = authRepository.currentUser?.uid

        if (uid.isNullOrEmpty() || record.accountUid != uid) {
            _errorMessage.value = "Unauthorized extraction request."
            return
        }

        viewModelScope.launch(dispatcherProvider.main) {
            _isExtracting.value = true
            _errorMessage.value = null
            _infoMessage.value = null

            try {
                val extraction = DocumentExtractionEngine.extractText(record)

                when (extraction) {
                    is ExtractionResult.Success -> {
                        val raw = extraction.rawText
                        val title = MedicalFieldParser.parseDocumentTitle(
                            raw,
                            record.title
                        )
                        val date = MedicalFieldParser.parseReportDate(raw) ?: ""
                        val results = MedicalFieldParser.parseTestResults(raw)

                        val info = ExtractedMedicalInfo(
                            recordId = record.id,
                            accountUid = uid,
                            rawText = raw,
                            documentTitle = title,
                            reportDate = date.ifBlank { null },
                            structuredResultsJson =
                                MedicalFieldParser.serializeResults(results),
                            reviewStatus = ExtractedMedicalInfo.STATUS_UNREVIEWED,
                            processedPages = extraction.pagesProcessed
                        )

                        // Save locally before marking extraction as successful.
                        medicalRecordRepository.saveExtractedInfo(info)

                        _documentTitle.value = title
                        _reportDate.value = date
                        _parsedResults.value = results
                        _extractedInfo.value = info
                        _infoMessage.value = "Text extracted successfully. Please review the results."
                    }

                    is ExtractionResult.Error -> {
                        _errorMessage.value = extraction.message
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                _errorMessage.value =
                    "Could not process or save this report. Please try again."
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


    fun saveReviewedInfo(onSuccess: () -> Unit) {
        if (_isSaving.value) return

        val uid = authRepository.currentUser?.uid
        val record = _selectedRecord.value

        if (uid.isNullOrEmpty() || record == null || record.accountUid != uid) {
            _errorMessage.value = "This record is not available in the current account."
            return
        }

        viewModelScope.launch(dispatcherProvider.main) {
            _isSaving.value = true
            _errorMessage.value = null
            _infoMessage.value = null

            try {
                // Recheck account ownership before saving.
                val currentUid = authRepository.currentUser?.uid
                if (currentUid.isNullOrEmpty() ||
                    currentUid != uid ||
                    record.accountUid != currentUid
                ) {
                    _errorMessage.value =
                        "This record is not available in the current account."
                    return@launch
                }

                val existing = _extractedInfo.value

                val currentTitle = _documentTitle.value.trim()
                    .ifBlank { record.title }

                val currentDate = _reportDate.value.trim()

                val existingResults = MedicalFieldParser.deserializeResults(
                    existing?.structuredResultsJson ?: "[]"
                )

                val isEdited =
                    existing?.reviewStatus == ExtractedMedicalInfo.STATUS_EDITED ||
                            _parsedResults.value != existingResults ||
                            currentTitle != (existing?.documentTitle ?: record.title) ||
                            currentDate != (existing?.reportDate ?: "")

                val newStatus = if (isEdited) {
                    ExtractedMedicalInfo.STATUS_EDITED
                } else {
                    ExtractedMedicalInfo.STATUS_REVIEWED_AND_SAVED
                }

                val info = ExtractedMedicalInfo(
                    recordId = record.id,
                    accountUid = uid,
                    rawText = existing?.rawText ?: "",
                    documentTitle = currentTitle,
                    reportDate = currentDate.ifBlank { null },
                    structuredResultsJson =
                        MedicalFieldParser.serializeResults(_parsedResults.value),
                    reviewStatus = newStatus,
                    processedPages = existing?.processedPages ?: 1,
                    updatedAtTimestamp = System.currentTimeMillis()
                )

                medicalRecordRepository.saveExtractedInfoWithTitleUpdate(
                    info,
                    info.documentTitle
                )

                _extractedInfo.value = info
                _infoMessage.value = "Changes saved."
                onSuccess()

            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _errorMessage.value =
                    "Could not save your changes. Your edits are still available to retry."
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
