package com.example.abhayhealth.data.sync

import android.content.Context
import android.util.Log
import com.example.abhayhealth.core.firebase.FirestoreClientProvider
import com.example.abhayhealth.data.remote.FirestoreHealthRepository
import com.example.abhayhealth.data.remote.MedicalRecordDoc
import com.example.abhayhealth.data.remote.MedicationDoc
import com.example.abhayhealth.data.remote.PatientProfileDoc
import com.example.abhayhealth.data.remote.SymptomLogDoc
import com.example.abhayhealth.data.remote.VitalReadingDoc
import com.example.abhayhealth.domain.model.PatientProfile
import com.example.abhayhealth.domain.repository.PatientProfileRepository
import com.example.abhayhealth.domain.repository.UserPreferencesRepository
import com.example.abhayhealth.feature.medicines.MedicationDao
import com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity
import com.example.abhayhealth.feature.records.ExtractedInfoDao
import com.example.abhayhealth.feature.records.ExtractedMedicalInfo
import com.example.abhayhealth.feature.records.MedicalRecord
import com.example.abhayhealth.feature.records.MedicalRecordDao
import com.example.abhayhealth.feature.symptoms.SymptomLogDao
import com.example.abhayhealth.feature.symptoms.SymptomLogEntity
import com.example.abhayhealth.feature.vitals.VitalReadingDao
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "FirestoreHealthSync"

enum class CloudSyncStatus {
    ONLINE_SYNCED,
    SYNCING_PENDING,
    OFFLINE_CACHED
}

/**
 * Bidirectional real-time and offline-first sync coordinator between Cloud Firestore
 * (backed by persistent SQLite disk cache) and local Room / DataStore.
 * Preserves local document files and AlarmManager alarms while mirroring all structured health
 * telemetry in real time and queuing mutations offline until connectivity resumes.
 */
class FirestoreHealthSyncManager(
    private val firestoreRepo: FirestoreHealthRepository,
    private val profileRepository: PatientProfileRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val medicalRecordDao: MedicalRecordDao,
    private val extractedInfoDao: ExtractedInfoDao,
    private val medicationDao: MedicationDao,
    private val vitalReadingDao: VitalReadingDao,
    private val symptomLogDao: SymptomLogDao,
    private val appContext: Context? = null
) {
    private var syncJob: Job? = null
    private var activeUid: String? = null

    private val _syncStatus = MutableStateFlow(CloudSyncStatus.ONLINE_SYNCED)
    val syncStatus: StateFlow<CloudSyncStatus> = _syncStatus.asStateFlow()

    fun start(
        scope: CoroutineScope,
        userId: String,
        email: String?,
        displayName: String?
    ) {
        if (activeUid == userId && syncJob?.isActive == true) return
        stop()
        activeUid = userId

        val knownRecordIds = mutableSetOf<Long>()
        val knownMedicationIds = mutableSetOf<String>()
        val knownVitalIds = mutableSetOf<Long>()
        val knownSymptomIds = mutableSetOf<Long>()
        var didBackfill = false

        syncJob = scope.launch(Dispatchers.IO) {
            // Observe combined connectivity + Firestore persistent cache metadata
            appContext?.let { ctx ->
                launch {
                    var wasOnline = true
                    combine(
                        FirestoreClientProvider.observeNetworkConnectivity(ctx),
                        firestoreRepo.syncState
                    ) { isOnline, firestoreState ->
                        Triple(isOnline, firestoreState.hasPendingWrites, firestoreState.isFromCache)
                    }.collect { (isOnline, hasPendingWrites, isFromCache) ->
                        _syncStatus.value = when {
                            !isOnline -> CloudSyncStatus.OFFLINE_CACHED
                            hasPendingWrites -> CloudSyncStatus.SYNCING_PENDING
                            isFromCache -> CloudSyncStatus.SYNCING_PENDING
                            else -> CloudSyncStatus.ONLINE_SYNCED
                        }
                        if (isOnline && !wasOnline && hasPendingWrites) {
                            firestoreRepo.waitForPendingWrites()
                        }
                        wasOnline = isOnline
                    }
                }
            }

            // Enqueue user account doc without blocking offline startup
            firestoreRepo.enqueueUserAccount(userId, email, displayName)

            // 1. Observe cloud profile (cache + server) -> apply to local DataStore or enqueue local
            launch {
                firestoreRepo.observePatientProfile(userId)
                    .catch { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Profile sync stream error", e)
                    }
                    .collect { cloudProfile ->
                        val localProfile = profileRepository.getProfile(userId).first()
                        if (cloudProfile == null) {
                            if (localProfile != null) {
                                firestoreRepo.enqueuePatientProfile(localProfile)
                            }
                        } else if (localProfile == null || cloudProfile.updatedAtTimestamp >= localProfile.updatedAtTimestamp) {
                            val mapped = cloudProfile.toDomain(userId)
                            if (mapped != localProfile) {
                                profileRepository.saveProfile(mapped)
                                if (mapped.fullName.isNotBlank()) {
                                    userPreferencesRepository.setPatientName(mapped.fullName)
                                }
                            }
                        }
                    }
            }

            // 2. Observe cloud records -> apply to Room (MedicalRecord before ExtractedMedicalInfo)
            launch {
                firestoreRepo.observeMedicalRecords(userId)
                    .catch { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Records sync stream error", e)
                    }
                    .collect { cloudDocs ->
                        val currentCloudIds = mutableSetOf<Long>()
                        cloudDocs.forEach { doc ->
                            val localId = resolveNumericId(doc.localRecordId, doc.id)
                            currentCloudIds.add(localId)
                            val existingRecord = medicalRecordDao.getRecordById(localId, userId)
                            if (existingRecord == null ||
                                existingRecord.title != doc.title ||
                                existingRecord.category != doc.category ||
                                existingRecord.recordDate != doc.recordDate ||
                                existingRecord.mimeType != doc.mimeType
                            ) {
                                medicalRecordDao.insertRecord(
                                    MedicalRecord(
                                        id = localId,
                                        accountUid = userId,
                                        title = doc.title,
                                        category = doc.category,
                                        recordDate = doc.recordDate,
                                        localFilePath = existingRecord?.localFilePath,
                                        mimeType = doc.mimeType,
                                        createdAtTimestamp = doc.createdAtTimestamp
                                    )
                                )
                            }

                            if (!doc.rawText.isNullOrBlank() ||
                                !doc.structuredResultsJson.isNullOrBlank() ||
                                !doc.documentTitle.isNullOrBlank() ||
                                doc.reviewStatus != ExtractedMedicalInfo.STATUS_UNREVIEWED
                            ) {
                                val existingInfo = extractedInfoDao.getExtractedInfoSync(localId, userId)
                                if (existingInfo == null || doc.updatedAtTimestamp >= existingInfo.updatedAtTimestamp) {
                                    extractedInfoDao.insertOrUpdate(
                                        ExtractedMedicalInfo(
                                            recordId = localId,
                                            accountUid = userId,
                                            rawText = doc.rawText.orEmpty(),
                                            documentTitle = doc.documentTitle ?: doc.title,
                                            reportDate = doc.reportDate,
                                            structuredResultsJson = doc.structuredResultsJson ?: "[]",
                                            reviewStatus = doc.reviewStatus,
                                            processedPages = doc.processedPages.coerceAtLeast(1),
                                            updatedAtTimestamp = doc.updatedAtTimestamp
                                        )
                                    )
                                }
                            }
                        }

                        // Remove locally cached records deleted remotely on another device
                        val removedIds = knownRecordIds - currentCloudIds
                        removedIds.forEach { removedId ->
                            medicalRecordDao.deleteRecordById(removedId, userId)
                        }
                        knownRecordIds.clear()
                        knownRecordIds.addAll(currentCloudIds)

                        if (!didBackfill) {
                            didBackfill = true
                            backfillLocalDataIfMissing(userId, currentCloudIds)
                        }
                    }
            }

            // 3. Observe cloud medications -> apply to Room
            launch {
                firestoreRepo.observeMedications(userId)
                    .catch { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Medications sync stream error", e)
                    }
                    .collect { cloudMeds ->
                        val currentIds = mutableSetOf<String>()
                        cloudMeds.forEach { doc ->
                            currentIds.add(doc.id)
                            val existing = medicationDao.getMedicationByIdSync(doc.id, userId)
                            val mapped = doc.toEntity(userId)
                            if (existing != mapped) {
                                medicationDao.insertOrUpdate(mapped)
                            }
                        }
                        val removedIds = knownMedicationIds - currentIds
                        removedIds.forEach { removedId ->
                            medicationDao.deleteMedication(removedId, userId)
                        }
                        knownMedicationIds.clear()
                        knownMedicationIds.addAll(currentIds)

                        // Queue any purely local medications into Firestore offline persistent cache
                        val localMeds = medicationDao.getMedicationsByAccount(userId).first()
                        localMeds.filter { it.id !in currentIds }.forEach { missing ->
                            firestoreRepo.enqueueMedication(missing)
                        }
                    }
            }

            // 4. Observe cloud vitals -> apply to Room
            launch {
                firestoreRepo.observeVitals(userId)
                    .catch { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Vitals sync stream error", e)
                    }
                    .collect { cloudVitals ->
                        val localExisting = vitalReadingDao.getAllReadingsByAccount(userId).first()
                        val localById = localExisting.associateBy { it.id }
                        val currentIds = mutableSetOf<Long>()

                        cloudVitals.forEach { doc ->
                            val localId = resolveNumericId(doc.localVitalId, doc.id)
                            currentIds.add(localId)
                            val mapped = doc.toEntity(userId, localId)
                            if (localById[localId] != mapped) {
                                vitalReadingDao.insertReading(mapped)
                            }
                        }

                        val removedIds = knownVitalIds - currentIds
                        removedIds.forEach { removedId ->
                            localById[removedId]?.let { vitalReadingDao.deleteReading(it) }
                        }
                        knownVitalIds.clear()
                        knownVitalIds.addAll(currentIds)

                        localExisting.filter { it.id > 0L && it.id !in currentIds }.forEach { missing ->
                            firestoreRepo.enqueueVitalReading(missing)
                        }
                    }
            }

            // 5. Observe cloud symptoms -> apply to Room
            launch {
                firestoreRepo.observeSymptoms(userId)
                    .catch { e ->
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Symptoms sync stream error", e)
                    }
                    .collect { cloudSymptoms ->
                        val localExisting = symptomLogDao.getAllSymptoms(userId).first()
                        val localById = localExisting.associateBy { it.id }
                        val currentIds = mutableSetOf<Long>()

                        cloudSymptoms.forEach { doc ->
                            val localId = resolveNumericId(doc.localSymptomId, doc.id)
                            currentIds.add(localId)
                            val mapped = doc.toEntity(userId, localId)
                            if (localById[localId] != mapped) {
                                symptomLogDao.insertSymptom(mapped)
                            }
                        }

                        val removedIds = knownSymptomIds - currentIds
                        removedIds.forEach { removedId ->
                            symptomLogDao.deleteSymptomById(removedId, userId)
                        }
                        knownSymptomIds.clear()
                        knownSymptomIds.addAll(currentIds)

                        localExisting.filter { it.id > 0L && it.id !in currentIds }.forEach { missing ->
                            firestoreRepo.enqueueSymptomLog(missing)
                        }
                    }
            }
        }
    }

    private suspend fun backfillLocalDataIfMissing(userId: String, cloudRecordIds: Set<Long>) {
        val localRecords = medicalRecordDao.getRecordsByAccount(userId).first()
        localRecords.filter { it.id > 0L && it.id !in cloudRecordIds }.forEach { record ->
            val info = extractedInfoDao.getExtractedInfoSync(record.id, userId)
            firestoreRepo.enqueueMedicalRecord(record, info)
        }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        activeUid = null
    }

    private fun resolveNumericId(storedId: Long, docId: String): Long {
        if (storedId > 0L) return storedId
        val suffix = docId.substringAfterLast('_').toLongOrNull()
        if (suffix != null && suffix > 0L) return suffix
        return (docId.hashCode().toLong() and 0x7FFFFFFFL).coerceAtLeast(1L)
    }

    private fun PatientProfileDoc.toDomain(uid: String) = PatientProfile(
        accountUid = uid,
        fullName = fullName,
        dateOfBirth = dateOfBirth,
        gender = gender,
        city = city,
        state = state,
        preferredLanguage = preferredLanguage.ifBlank { "English" },
        photoUri = photoUri,
        bloodGroup = bloodGroup,
        allergies = allergies,
        healthConditions = healthConditions,
        emergencyContactName = emergencyContactName,
        emergencyContactPhone = emergencyContactPhone,
        additionalNotes = additionalNotes,
        updatedAtTimestamp = updatedAtTimestamp
    )

    private fun MedicationDoc.toEntity(uid: String) = PrescriptionMedicationEntity(
        id = id,
        accountUid = uid,
        name = name,
        dosage = dosage,
        timeSlot = timeSlot,
        isTaken = isTaken,
        createdAtTimestamp = createdAtTimestamp
    )

    private fun VitalReadingDoc.toEntity(uid: String, localId: Long) = VitalReadingEntity(
        id = localId,
        accountUid = uid,
        type = type,
        systolic = systolic,
        diastolic = diastolic,
        weightKg = weightKg,
        timestamp = timestamp,
        notes = notes
    )

    private fun SymptomLogDoc.toEntity(uid: String, localId: Long) = SymptomLogEntity(
        id = localId,
        accountUid = uid,
        symptomName = symptomName,
        severity = severity,
        severityScore = severityScore,
        timestamp = timestamp,
        notes = notes,
        triggers = triggers,
        durationMinutes = durationMinutes
    )
}
