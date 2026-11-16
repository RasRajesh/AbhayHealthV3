package com.example.abhayhealth.data.remote

import android.content.Context
import android.util.Log
import com.example.abhayhealth.core.firebase.FirestoreClientProvider
import com.example.abhayhealth.core.firebase.OperationType
import com.example.abhayhealth.core.firebase.handleFirestoreError
import com.example.abhayhealth.domain.model.PatientProfile
import com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity
import com.example.abhayhealth.feature.records.ExtractedMedicalInfo
import com.example.abhayhealth.feature.records.MedicalRecord
import com.example.abhayhealth.feature.symptoms.SymptomLogEntity
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "FirestoreHealthRepo"
private const val SERVER_LOOKUP_TIMEOUT_MS = 1500L

data class FirestoreSyncState(
    val isFromCache: Boolean = false,
    val hasPendingWrites: Boolean = false,
    val lastSyncedAtMillis: Long? = null
)

/**
 * Cloud Firestore repository for AbhayHealth real-time + offline-first health vault sync.
 * Uses the named Enterprise Firestore database configured with explicit persistent SQLite cache
 * and offline write queuing.
 */
class FirestoreHealthRepository(
    private val db: FirebaseFirestore
) {
    constructor(context: Context) : this(
        FirestoreClientProvider.getInstance(context)
    )

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncState = MutableStateFlow(FirestoreSyncState())
    val syncState: StateFlow<FirestoreSyncState> = _syncState.asStateFlow()

    private fun recordSnapshotMetadata(metadata: SnapshotMetadata) {
        val prev = _syncState.value
        val now = if (!metadata.isFromCache && !metadata.hasPendingWrites()) {
            System.currentTimeMillis()
        } else {
            prev.lastSyncedAtMillis
        }
        _syncState.value = FirestoreSyncState(
            isFromCache = metadata.isFromCache,
            hasPendingWrites = metadata.hasPendingWrites(),
            lastSyncedAtMillis = now
        )
    }

    private fun userDocRef(userId: String) =
        db.collection("users").document(userId)

    private fun profileDocRef(userId: String) =
        userDocRef(userId).collection("private").document("profile")

    private fun recordsCollection(userId: String) =
        userDocRef(userId).collection("records")

    private fun medicationsCollection(userId: String) =
        userDocRef(userId).collection("medications")

    private fun vitalsCollection(userId: String) =
        userDocRef(userId).collection("vitals")

    private fun symptomsCollection(userId: String) =
        userDocRef(userId).collection("symptoms")

    // --- Real-time & Offline-Cached Streams (MetadataChanges.INCLUDE) ---

    fun observeUserAccount(userId: String): Flow<UserAccountDoc?> {
        val docRef = userDocRef(userId)
        return docRef.snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                recordSnapshotMetadata(snapshot.metadata)
                if (snapshot.exists()) {
                    snapshot.toObject(UserAccountDoc::class.java, ServerTimestampBehavior.ESTIMATE)
                } else null
            }
            .catch { error ->
                val firestoreError = error.unwrapFirestoreException()
                if (firestoreError != null) {
                    if (isActiveUser(userId)) {
                        handleFirestoreError(firestoreError, OperationType.GET, docRef.path)
                    }
                    throw firestoreError
                }
                throw error
            }
    }

    fun observePatientProfile(userId: String): Flow<PatientProfileDoc?> {
        val docRef = profileDocRef(userId)
        return docRef.snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                recordSnapshotMetadata(snapshot.metadata)
                if (snapshot.exists()) {
                    snapshot.toObject(PatientProfileDoc::class.java, ServerTimestampBehavior.ESTIMATE)
                } else null
            }
            .catch { error ->
                val firestoreError = error.unwrapFirestoreException()
                if (firestoreError != null) {
                    if (isActiveUser(userId)) {
                        handleFirestoreError(firestoreError, OperationType.GET, docRef.path)
                    }
                    throw firestoreError
                }
                throw error
            }
    }

    fun observeMedicalRecords(userId: String): Flow<List<MedicalRecordDoc>> {
        val col = recordsCollection(userId)
        return col.orderBy("recordDate", Query.Direction.DESCENDING)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                recordSnapshotMetadata(snapshot.metadata)
                snapshot.toObjects(MedicalRecordDoc::class.java, ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = error.unwrapFirestoreException()
                if (firestoreError != null) {
                    if (isActiveUser(userId)) {
                        handleFirestoreError(firestoreError, OperationType.LIST, col.path)
                    }
                    throw firestoreError
                }
                throw error
            }
    }

    fun observeMedications(userId: String): Flow<List<MedicationDoc>> {
        val col = medicationsCollection(userId)
        return col.orderBy("createdAtTimestamp", Query.Direction.ASCENDING)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                recordSnapshotMetadata(snapshot.metadata)
                snapshot.toObjects(MedicationDoc::class.java, ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = error.unwrapFirestoreException()
                if (firestoreError != null) {
                    if (isActiveUser(userId)) {
                        handleFirestoreError(firestoreError, OperationType.LIST, col.path)
                    }
                    throw firestoreError
                }
                throw error
            }
    }

    fun observeVitals(userId: String): Flow<List<VitalReadingDoc>> {
        val col = vitalsCollection(userId)
        return col.orderBy("timestamp", Query.Direction.ASCENDING)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                recordSnapshotMetadata(snapshot.metadata)
                snapshot.toObjects(VitalReadingDoc::class.java, ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = error.unwrapFirestoreException()
                if (firestoreError != null) {
                    if (isActiveUser(userId)) {
                        handleFirestoreError(firestoreError, OperationType.LIST, col.path)
                    }
                    throw firestoreError
                }
                throw error
            }
    }

    fun observeSymptoms(userId: String): Flow<List<SymptomLogDoc>> {
        val col = symptomsCollection(userId)
        return col.orderBy("timestamp", Query.Direction.DESCENDING)
            .snapshots(MetadataChanges.INCLUDE)
            .map { snapshot ->
                recordSnapshotMetadata(snapshot.metadata)
                snapshot.toObjects(SymptomLogDoc::class.java, ServerTimestampBehavior.ESTIMATE)
            }
            .catch { error ->
                val firestoreError = error.unwrapFirestoreException()
                if (firestoreError != null) {
                    if (isActiveUser(userId)) {
                        handleFirestoreError(firestoreError, OperationType.LIST, col.path)
                    }
                    throw firestoreError
                }
                throw error
            }
    }

    // --- Network & Offline Persistence Controls ---

    suspend fun waitForPendingWrites() {
        try {
            db.waitForPendingWrites().await()
            val prev = _syncState.value
            _syncState.value = prev.copy(
                hasPendingWrites = false,
                lastSyncedAtMillis = System.currentTimeMillis()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "waitForPendingWrites failed", e)
        }
    }

    suspend fun setNetworkEnabled(enabled: Boolean) {
        try {
            if (enabled) {
                db.enableNetwork().await()
            } else {
                db.disableNetwork().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "setNetworkEnabled($enabled) failed", e)
        }
    }

    // --- Non-Blocking Persistent Offline Write Queue Enqueue Methods ---

    fun enqueueUserAccount(userId: String, email: String?, displayName: String?) {
        repoScope.launch {
            runCatching { ensureUserAccount(userId, email, displayName) }
        }
    }

    fun enqueuePatientProfile(profile: PatientProfile) {
        repoScope.launch {
            runCatching { upsertPatientProfile(profile) }
        }
    }

    fun enqueueMedicalRecord(record: MedicalRecord, extractedInfo: ExtractedMedicalInfo? = null) {
        repoScope.launch {
            runCatching { upsertMedicalRecord(record, extractedInfo) }
        }
    }

    fun enqueueDeleteMedicalRecord(userId: String, localRecordId: Long) {
        repoScope.launch {
            runCatching { deleteMedicalRecord(userId, localRecordId) }
        }
    }

    fun enqueueMedication(medication: PrescriptionMedicationEntity) {
        repoScope.launch {
            runCatching { upsertMedication(medication) }
        }
    }

    fun enqueueDeleteMedication(userId: String, medicationId: String) {
        repoScope.launch {
            runCatching { deleteMedication(userId, medicationId) }
        }
    }

    fun enqueueVitalReading(vital: VitalReadingEntity) {
        repoScope.launch {
            runCatching { upsertVitalReading(vital) }
        }
    }

    fun enqueueDeleteVitalReading(userId: String, localVitalId: Long) {
        repoScope.launch {
            runCatching { deleteVitalReading(userId, localVitalId) }
        }
    }

    fun enqueueSymptomLog(symptom: SymptomLogEntity) {
        repoScope.launch {
            runCatching { upsertSymptomLog(symptom) }
        }
    }

    fun enqueueDeleteSymptomLog(userId: String, localSymptomId: Long) {
        repoScope.launch {
            runCatching { deleteSymptomLog(userId, localSymptomId) }
        }
    }

    // --- Account & Profile ---

    suspend fun ensureUserAccount(userId: String, email: String?, displayName: String?) {
        val docRef = userDocRef(userId)
        try {
            val snap = docRef.getOfflineResilient()
            val cleanEmail = email?.trim()?.takeIf { it.isNotEmpty() }?.take(320)
            val cleanName = displayName?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
            if (snap == null || !snap.exists()) {
                val createMap = buildMap<String, Any> {
                    put("userId", userId)
                    cleanEmail?.let { put("email", it) }
                    cleanName?.let { put("displayName", it) }
                    put("createdAt", FieldValue.serverTimestamp())
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                val task = docRef.set(createMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            } else {
                val updateMap = mapOf<String, Any>(
                    "email" to (cleanEmail ?: FieldValue.delete()),
                    "displayName" to (cleanName ?: FieldValue.delete()),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val task = docRef.update(updateMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun upsertPatientProfile(profile: PatientProfile) {
        val docRef = profileDocRef(profile.accountUid)
        val cleanName = profile.fullName.trim().ifBlank { "Patient" }.take(120)
        val cleanLang = profile.preferredLanguage.trim().ifBlank { "English" }.take(40)
        val safeTimestamp = profile.updatedAtTimestamp.coerceAtLeast(0L)

        try {
            val snap = docRef.getOfflineResilient()
            if (snap == null || !snap.exists()) {
                val createMap = buildMap<String, Any> {
                    put("userId", profile.accountUid)
                    put("fullName", cleanName)
                    put("preferredLanguage", cleanLang)
                    put("updatedAtTimestamp", safeTimestamp)
                    profile.dateOfBirth?.trim()?.takeIf { it.isNotEmpty() }?.take(40)?.let { put("dateOfBirth", it) }
                    profile.gender?.trim()?.takeIf { it.isNotEmpty() }?.take(40)?.let { put("gender", it) }
                    profile.city?.trim()?.takeIf { it.isNotEmpty() }?.take(80)?.let { put("city", it) }
                    profile.state?.trim()?.takeIf { it.isNotEmpty() }?.take(80)?.let { put("state", it) }
                    profile.photoUri?.trim()?.takeIf { it.isNotEmpty() }?.take(500)?.let { put("photoUri", it) }
                    profile.bloodGroup?.trim()?.takeIf { it.isNotEmpty() }?.take(16)?.let { put("bloodGroup", it) }
                    profile.allergies?.trim()?.takeIf { it.isNotEmpty() }?.take(1000)?.let { put("allergies", it) }
                    profile.healthConditions?.trim()?.takeIf { it.isNotEmpty() }?.take(1000)?.let { put("healthConditions", it) }
                    profile.emergencyContactName?.trim()?.takeIf { it.isNotEmpty() }?.take(120)?.let { put("emergencyContactName", it) }
                    profile.emergencyContactPhone?.trim()?.takeIf { it.isNotEmpty() }?.take(32)?.let { put("emergencyContactPhone", it) }
                    profile.additionalNotes?.trim()?.takeIf { it.isNotEmpty() }?.take(2000)?.let { put("additionalNotes", it) }
                    put("createdAt", FieldValue.serverTimestamp())
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                val task = docRef.set(createMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            } else {
                val updateMap = mapOf<String, Any>(
                    "fullName" to cleanName,
                    "preferredLanguage" to cleanLang,
                    "updatedAtTimestamp" to safeTimestamp,
                    "dateOfBirth" to (profile.dateOfBirth?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: FieldValue.delete()),
                    "gender" to (profile.gender?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: FieldValue.delete()),
                    "city" to (profile.city?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: FieldValue.delete()),
                    "state" to (profile.state?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: FieldValue.delete()),
                    "photoUri" to (profile.photoUri?.trim()?.takeIf { it.isNotEmpty() }?.take(500) ?: FieldValue.delete()),
                    "bloodGroup" to (profile.bloodGroup?.trim()?.takeIf { it.isNotEmpty() }?.take(16) ?: FieldValue.delete()),
                    "allergies" to (profile.allergies?.trim()?.takeIf { it.isNotEmpty() }?.take(1000) ?: FieldValue.delete()),
                    "healthConditions" to (profile.healthConditions?.trim()?.takeIf { it.isNotEmpty() }?.take(1000) ?: FieldValue.delete()),
                    "emergencyContactName" to (profile.emergencyContactName?.trim()?.takeIf { it.isNotEmpty() }?.take(120) ?: FieldValue.delete()),
                    "emergencyContactPhone" to (profile.emergencyContactPhone?.trim()?.takeIf { it.isNotEmpty() }?.take(32) ?: FieldValue.delete()),
                    "additionalNotes" to (profile.additionalNotes?.trim()?.takeIf { it.isNotEmpty() }?.take(2000) ?: FieldValue.delete()),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val task = docRef.update(updateMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    // --- Medical Records & Extracted Info ---

    suspend fun upsertMedicalRecord(
        record: MedicalRecord,
        extractedInfo: ExtractedMedicalInfo? = null
    ) {
        val docId = "rec_${record.id}"
        val docRef = recordsCollection(record.accountUid).document(docId)
        val cleanTitle = record.title.trim().ifBlank { "Medical Document" }.take(200)
        val cleanCategory = record.category.trim().ifBlank { "OTHER" }.take(64)
        val validStatus = when (extractedInfo?.reviewStatus) {
            ExtractedMedicalInfo.STATUS_REVIEWED_AND_SAVED,
            ExtractedMedicalInfo.STATUS_EDITED,
            ExtractedMedicalInfo.STATUS_UNREVIEWED -> extractedInfo.reviewStatus
            else -> ExtractedMedicalInfo.STATUS_UNREVIEWED
        }
        val safePages = (extractedInfo?.processedPages ?: 1).coerceIn(1, 500)
        val safeUpdated = (extractedInfo?.updatedAtTimestamp ?: record.createdAtTimestamp).coerceAtLeast(0L)

        try {
            val snap = docRef.getOfflineResilient()
            if (snap == null || !snap.exists()) {
                val createMap = buildMap<String, Any> {
                    put("userId", record.accountUid)
                    put("localRecordId", record.id.coerceAtLeast(0L))
                    put("title", cleanTitle)
                    put("category", cleanCategory)
                    put("recordDate", record.recordDate.coerceAtLeast(0L))
                    put("createdAtTimestamp", record.createdAtTimestamp.coerceAtLeast(0L))
                    put("reviewStatus", validStatus)
                    put("processedPages", safePages)
                    put("updatedAtTimestamp", safeUpdated)
                    record.mimeType?.trim()?.takeIf { it.isNotEmpty() }?.take(120)?.let { put("mimeType", it) }
                    extractedInfo?.documentTitle?.trim()?.takeIf { it.isNotEmpty() }?.take(200)?.let { put("documentTitle", it) }
                    extractedInfo?.reportDate?.trim()?.takeIf { it.isNotEmpty() }?.take(64)?.let { put("reportDate", it) }
                    extractedInfo?.rawText?.takeIf { it.isNotEmpty() }?.take(100000)?.let { put("rawText", it) }
                    extractedInfo?.structuredResultsJson?.takeIf { it.isNotEmpty() }?.take(100000)?.let { put("structuredResultsJson", it) }
                    put("createdAt", FieldValue.serverTimestamp())
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                val task = docRef.set(createMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            } else {
                val updateMap = mutableMapOf<String, Any>(
                    "title" to cleanTitle,
                    "category" to cleanCategory,
                    "recordDate" to record.recordDate.coerceAtLeast(0L),
                    "mimeType" to (record.mimeType?.trim()?.takeIf { it.isNotEmpty() }?.take(120) ?: FieldValue.delete()),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (extractedInfo != null) {
                    updateMap["reviewStatus"] = validStatus
                    updateMap["processedPages"] = safePages
                    updateMap["updatedAtTimestamp"] = safeUpdated
                    updateMap["documentTitle"] = extractedInfo.documentTitle.trim().takeIf { it.isNotEmpty() }?.take(200) ?: FieldValue.delete()
                    updateMap["reportDate"] = extractedInfo.reportDate?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: FieldValue.delete()
                    updateMap["rawText"] = extractedInfo.rawText.takeIf { it.isNotEmpty() }?.take(100000) ?: FieldValue.delete()
                    updateMap["structuredResultsJson"] = extractedInfo.structuredResultsJson.takeIf { it.isNotEmpty() }?.take(100000) ?: FieldValue.delete()
                }
                val task = docRef.update(updateMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun deleteMedicalRecord(userId: String, localRecordId: Long) {
        val docRef = recordsCollection(userId).document("rec_$localRecordId")
        try {
            val task = docRef.delete()
                .addOnFailureListener { e -> handleFirestoreError(e, OperationType.DELETE, docRef.path) }
            _syncState.value = _syncState.value.copy(hasPendingWrites = true)
            task.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // --- Medications ---

    suspend fun upsertMedication(medication: PrescriptionMedicationEntity) {
        val docId = sanitizeId(medication.id)
        val docRef = medicationsCollection(medication.accountUid).document(docId)
        val cleanName = medication.name.trim().ifBlank { "Medication" }.take(160)
        val cleanDosage = medication.dosage.trim().ifBlank { "1 Tablet" }.take(160)
        val cleanSlot = medication.timeSlot.trim().ifBlank { "MORNING (08:30)" }.take(64)

        try {
            val snap = docRef.getOfflineResilient()
            if (snap == null || !snap.exists()) {
                val createMap = mapOf<String, Any>(
                    "userId" to medication.accountUid,
                    "name" to cleanName,
                    "dosage" to cleanDosage,
                    "timeSlot" to cleanSlot,
                    "isTaken" to medication.isTaken,
                    "createdAtTimestamp" to medication.createdAtTimestamp.coerceAtLeast(0L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val task = docRef.set(createMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            } else {
                val updateMap = mapOf<String, Any>(
                    "name" to cleanName,
                    "dosage" to cleanDosage,
                    "timeSlot" to cleanSlot,
                    "isTaken" to medication.isTaken,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val task = docRef.update(updateMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun deleteMedication(userId: String, medicationId: String) {
        val docRef = medicationsCollection(userId).document(sanitizeId(medicationId))
        try {
            val task = docRef.delete()
                .addOnFailureListener { e -> handleFirestoreError(e, OperationType.DELETE, docRef.path) }
            _syncState.value = _syncState.value.copy(hasPendingWrites = true)
            task.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // --- Vitals ---

    suspend fun upsertVitalReading(vital: VitalReadingEntity) {
        val docId = "vital_${vital.id}"
        val docRef = vitalsCollection(vital.accountUid).document(docId)
        val cleanType = if (vital.type == "WEIGHT") "WEIGHT" else "BLOOD_PRESSURE"
        val cleanNotes = vital.notes.take(1000)

        try {
            val snap = docRef.getOfflineResilient()
            if (snap == null || !snap.exists()) {
                val createMap = buildMap<String, Any> {
                    put("userId", vital.accountUid)
                    put("localVitalId", vital.id.coerceAtLeast(0L))
                    put("type", cleanType)
                    put("timestamp", vital.timestamp.coerceAtLeast(0L))
                    put("notes", cleanNotes)
                    vital.systolic?.let { put("systolic", it) }
                    vital.diastolic?.let { put("diastolic", it) }
                    vital.weightKg?.let { put("weightKg", it) }
                    put("createdAt", FieldValue.serverTimestamp())
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                val task = docRef.set(createMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            } else {
                val updateMap = mapOf<String, Any>(
                    "type" to cleanType,
                    "timestamp" to vital.timestamp.coerceAtLeast(0L),
                    "notes" to cleanNotes,
                    "systolic" to (vital.systolic ?: FieldValue.delete()),
                    "diastolic" to (vital.diastolic ?: FieldValue.delete()),
                    "weightKg" to (vital.weightKg ?: FieldValue.delete()),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val task = docRef.update(updateMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun deleteVitalReading(userId: String, localVitalId: Long) {
        val docRef = vitalsCollection(userId).document("vital_$localVitalId")
        try {
            val task = docRef.delete()
                .addOnFailureListener { e -> handleFirestoreError(e, OperationType.DELETE, docRef.path) }
            _syncState.value = _syncState.value.copy(hasPendingWrites = true)
            task.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // --- Symptoms ---

    suspend fun upsertSymptomLog(symptom: SymptomLogEntity) {
        val docId = "sym_${symptom.id}"
        val docRef = symptomsCollection(symptom.accountUid).document(docId)
        val cleanName = symptom.symptomName.trim().ifBlank { "Symptom" }.take(160)
        val cleanSeverity = when (symptom.severity.uppercase()) {
            SymptomLogEntity.SEVERITY_MILD,
            SymptomLogEntity.SEVERITY_MODERATE,
            SymptomLogEntity.SEVERITY_SEVERE,
            SymptomLogEntity.SEVERITY_CRITICAL -> symptom.severity.uppercase()
            else -> SymptomLogEntity.SEVERITY_MILD
        }
        val safeScore = symptom.severityScore.coerceIn(1, 4)
        val safeDuration = symptom.durationMinutes.coerceIn(0, 100000)

        try {
            val snap = docRef.getOfflineResilient()
            if (snap == null || !snap.exists()) {
                val createMap = mapOf<String, Any>(
                    "userId" to symptom.accountUid,
                    "localSymptomId" to symptom.id.coerceAtLeast(0L),
                    "symptomName" to cleanName,
                    "severity" to cleanSeverity,
                    "severityScore" to safeScore,
                    "timestamp" to symptom.timestamp.coerceAtLeast(0L),
                    "notes" to symptom.notes.take(2000),
                    "triggers" to symptom.triggers.take(500),
                    "durationMinutes" to safeDuration,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val task = docRef.set(createMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            } else {
                val updateMap = mapOf<String, Any>(
                    "symptomName" to cleanName,
                    "severity" to cleanSeverity,
                    "severityScore" to safeScore,
                    "timestamp" to symptom.timestamp.coerceAtLeast(0L),
                    "notes" to symptom.notes.take(2000),
                    "triggers" to symptom.triggers.take(500),
                    "durationMinutes" to safeDuration,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val task = docRef.update(updateMap)
                    .addOnFailureListener { e -> handleFirestoreError(e, OperationType.WRITE, docRef.path) }
                _syncState.value = _syncState.value.copy(hasPendingWrites = true)
                task.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            throw e
        }
    }

    suspend fun deleteSymptomLog(userId: String, localSymptomId: Long) {
        val docRef = symptomsCollection(userId).document("sym_$localSymptomId")
        try {
            val task = docRef.delete()
                .addOnFailureListener { e -> handleFirestoreError(e, OperationType.DELETE, docRef.path) }
            _syncState.value = _syncState.value.copy(hasPendingWrites = true)
            task.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // --- Complete Account Deletion ---

    suspend fun deleteAllUserData(userId: String) {
        val collections = listOf(
            recordsCollection(userId),
            medicationsCollection(userId),
            vitalsCollection(userId),
            symptomsCollection(userId)
        )
        for (col in collections) {
            try {
                val snap = col.get().await()
                if (!snap.isEmpty) {
                    val batch = db.batch()
                    snap.documents.forEach { batch.delete(it.reference) }
                    batch.commit().await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.DELETE, col.path)
                throw e
            }
        }

        val profileRef = profileDocRef(userId)
        try {
            if (profileRef.get().await().exists()) {
                profileRef.delete().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, profileRef.path)
            throw e
        }

        val userRef = userDocRef(userId)
        try {
            if (userRef.get().await().exists()) {
                userRef.delete().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, userRef.path)
            throw e
        }
    }

    /**
     * Resolves a document snapshot while online, or seamlessly falls back to Firestore's local
     * persistent SQLite cache when offline or unavailable. Returns null on cache miss offline so
     * newly created offline documents queue cleanly via .set(...).
     * Non-availability exceptions (e.g., PERMISSION_DENIED) propagate immediately.
     */
    private suspend fun DocumentReference.getOfflineResilient(): DocumentSnapshot? {
        return try {
            val cachedOrServer = withTimeoutOrNull(SERVER_LOOKUP_TIMEOUT_MS) {
                get(Source.DEFAULT).await()
            }
            if (cachedOrServer != null) {
                cachedOrServer
            } else {
                getFromCacheOrNull()
            }
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                getFromCacheOrNull()
            } else {
                throw e
            }
        }
    }

    private suspend fun DocumentReference.getFromCacheOrNull(): DocumentSnapshot? {
        return try {
            get(Source.CACHE).await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                null
            } else {
                throw e
            }
        }
    }

    private fun isActiveUser(userId: String): Boolean {
        return try {
            FirebaseAuth.getInstance().currentUser?.uid == userId
        } catch (_: Exception) {
            false
        }
    }

    private fun Throwable.unwrapFirestoreException(): FirebaseFirestoreException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is FirebaseFirestoreException) return current
            current = current.cause
        }
        return null
    }

    private fun sanitizeId(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        return cleaned.ifEmpty { "doc_${System.currentTimeMillis()}" }
    }
}
