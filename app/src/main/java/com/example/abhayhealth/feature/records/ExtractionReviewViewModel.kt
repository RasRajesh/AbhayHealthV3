package com.example.abhayhealth.feature.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.core.common.DefaultDispatcherProvider
import com.example.abhayhealth.core.common.DispatcherProvider
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.model.ParsedMedicalResult
import com.example.abhayhealth.domain.repository.AuthRepository
import com.example.abhayhealth.feature.medicines.MedicationDao
import com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity
import com.example.abhayhealth.feature.vitals.VitalReadingRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExtractionReviewViewModel(
    private val medicalRecordRepository: MedicalRecordRepository,
    private val authRepository: AuthRepository,
    private val medicationDao: MedicationDao? = null,
    private val vitalReadingRepository: VitalReadingRepository? = null,
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

    private val _parsedMedicines = MutableStateFlow<List<PrescriptionMedicationEntity>>(emptyList())
    val parsedMedicines: StateFlow<List<PrescriptionMedicationEntity>> = _parsedMedicines.asStateFlow()

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

    private fun getCurrentUid(): String {
        return authRepository.currentUser?.uid?.ifBlank { null } ?: "local_user"
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
        activeJob?.cancel()
        _selectedRecord.value = null
        _extractedInfo.value = null
        _documentTitle.value = ""
        _reportDate.value = ""
        _parsedResults.value = emptyList()
        _parsedMedicines.value = emptyList()
        _isExtracting.value = false
        _isSaving.value = false
        _errorMessage.value = null
        _infoMessage.value = null
        _showReExtractWarning.value = false
    }

    fun loadRecordAndInfo(recordId: Long) {
        val uid = getCurrentUid()

        activeJob?.cancel()
        activeJob = viewModelScope.launch(dispatcherProvider.main) {
            val record = medicalRecordRepository.getRecordById(recordId, uid)
            if (record == null) {
                clearAllState()
                _errorMessage.value = "This record is not available."
                return@launch
            }

            _selectedRecord.value = record
            val info = medicalRecordRepository.getExtractedInfoSync(recordId, uid)
            _extractedInfo.value = info

            if (info != null) {
                _documentTitle.value = info.documentTitle
                _reportDate.value = info.reportDate ?: ""
                _parsedResults.value = MedicalFieldParser.deserializeResults(info.structuredResultsJson)
                _parsedMedicines.value = MedicalFieldParser.parsePrescriptionMedicines(info.rawText, uid)
            } else {
                _documentTitle.value = record.title
                runExtraction(record, force = false)
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

    fun runExtraction(record: MedicalRecord, force: Boolean = false) {
        val uid = getCurrentUid()

        viewModelScope.launch(dispatcherProvider.main) {
            _isExtracting.value = true
            _errorMessage.value = null
            _infoMessage.value = null

            try {
                val extraction = DocumentExtractionEngine.extractText(record)

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

                        // Save locally
                        medicalRecordRepository.saveExtractedInfo(info)

                        _documentTitle.value = title
                        _reportDate.value = date
                        _parsedResults.value = results
                        _parsedMedicines.value = medicines
                        _extractedInfo.value = info
                        _infoMessage.value = "Text extracted. Review medications and vitals before saving."
                    }

                    is ExtractionResult.Error -> {
                        _errorMessage.value = extraction.message
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                _errorMessage.value = "Could not extract data from document. You can add fields manually."
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

    fun saveReviewedInfo(onSuccess: () -> Unit) {
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

                // 2. Add medications to MedicationDao so they populate Medicine Screen
                val dao = medicationDao
                val medicines = _parsedMedicines.value
                if (dao != null && medicines.isNotEmpty()) {
                    withContext(dispatcherProvider.io) {
                        medicines.forEach { med ->
                            dao.insertOrUpdate(med.copy(accountUid = uid))
                        }
                    }
                }

                // 3. Extract vitals and add to VitalReadingRepository so they populate Trends & Home
                val vitals = MedicalFieldParser.extractVitalsFromResults(_parsedResults.value, uid)
                val vitalRepo = vitalReadingRepository
                if (vitalRepo != null && vitals.isNotEmpty()) {
                    withContext(dispatcherProvider.io) {
                        vitals.forEach { vital ->
                            vitalRepo.insertReading(vital)
                        }
                    }
                }

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
