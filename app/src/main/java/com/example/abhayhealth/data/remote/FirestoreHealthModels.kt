package com.example.abhayhealth.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserAccountDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String? = null,
    val displayName: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PatientProfileDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val fullName: String = "",
    val dateOfBirth: String? = null,
    val gender: String? = null,
    val city: String? = null,
    val state: String? = null,
    val preferredLanguage: String = "English",
    val photoUri: String? = null,
    val bloodGroup: String? = null,
    val allergies: String? = null,
    val healthConditions: String? = null,
    val emergencyContactName: String? = null,
    val emergencyContactPhone: String? = null,
    val additionalNotes: String? = null,
    val updatedAtTimestamp: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class MedicalRecordDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val localRecordId: Long = 0L,
    val title: String = "",
    val category: String = "",
    val recordDate: Long = 0L,
    val mimeType: String? = null,
    val createdAtTimestamp: Long = 0L,
    val documentTitle: String? = null,
    val reportDate: String? = null,
    val rawText: String? = null,
    val structuredResultsJson: String? = null,
    val reviewStatus: String = "UNREVIEWED",
    val processedPages: Int = 1,
    val updatedAtTimestamp: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class MedicationDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val dosage: String = "",
    val timeSlot: String = "",
    @get:PropertyName("isTaken")
    @set:PropertyName("isTaken")
    var isTaken: Boolean = false,
    val createdAtTimestamp: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class VitalReadingDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val localVitalId: Long = 0L,
    val type: String = "",
    val systolic: Double? = null,
    val diastolic: Double? = null,
    val weightKg: Double? = null,
    val timestamp: Long = 0L,
    val notes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SymptomLogDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val localSymptomId: Long = 0L,
    val symptomName: String = "",
    val severity: String = "MILD",
    val severityScore: Int = 1,
    val timestamp: Long = 0L,
    val notes: String = "",
    val triggers: String = "",
    val durationMinutes: Int = 0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
