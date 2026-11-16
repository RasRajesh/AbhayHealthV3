package com.example.abhayhealth.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.abhayhealth.base.FirestoreEmulatorTestBase
import com.example.abhayhealth.core.firebase.FirestoreClientProvider
import com.example.abhayhealth.data.remote.FirestoreHealthRepository
import com.example.abhayhealth.domain.model.PatientProfile
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import com.example.abhayhealth.feature.vitals.VitalType
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.UUID

class FirestoreHealthRepositoryOfflineTest : FirestoreEmulatorTestBase() {

    @Test
    fun configureOfflinePersistence_sets100MbPersistentCacheAndIndexManager() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secondaryApp = FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId("in.abhayhealth.app")
                .setProjectId("demo-no-project")
                .setApiKey("fake-api-key-for-emulator")
                .build(),
            "offline-config-test-${UUID.randomUUID()}"
        )
        val secondaryFirestore = FirebaseFirestore.getInstance(secondaryApp, databaseId)

        FirestoreClientProvider.configureOfflinePersistence(secondaryFirestore, databaseId)

        val localCache = secondaryFirestore.firestoreSettings.cacheSettings
        assertThat(localCache).isInstanceOf(PersistentCacheSettings::class.java)
        val persistentCache = localCache as PersistentCacheSettings
        assertThat(persistentCache.sizeBytes).isEqualTo(100L * 1024L * 1024L)
        assertThat(secondaryFirestore.persistentCacheIndexManager).isNotNull()
    }

    @Test
    fun offlineWritesAndUpdates_emitFromCacheImmediately_andSyncWhenNetworkResumes() = runBlocking {
        val aliceUid = signInTestUser(OFFLINE_ALICE_EMAIL)
        val repository = FirestoreHealthRepository(firestore)
        val vitalId = 777L

        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repository.setNetworkEnabled(false)
            }

            // Enqueue profile create + vital create while offline
            repository.enqueuePatientProfile(
                PatientProfile(
                    accountUid = aliceUid,
                    fullName = "Offline Cached Patient",
                    bloodGroup = "B+",
                    preferredLanguage = "English",
                    updatedAtTimestamp = 1700000100000L
                )
            )
            repository.enqueueVitalReading(
                VitalReadingEntity(
                    id = vitalId,
                    accountUid = aliceUid,
                    type = VitalType.WEIGHT.name,
                    weightKg = 68.5,
                    timestamp = 1700000100000L,
                    notes = "Logged offline"
                )
            )

            // Snapshot listeners with MetadataChanges.INCLUDE emit queued writes immediately from cache
            val cachedProfile = withTimeout(FLOW_TIMEOUT_MS) {
                repository.observePatientProfile(aliceUid)
                    .first { it?.fullName == "Offline Cached Patient" }
            }
            val cachedVitals = withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeVitals(aliceUid)
                    .first { list -> list.any { it.localVitalId == vitalId } }
            }

            assertThat(cachedProfile?.bloodGroup).isEqualTo("B+")
            assertThat(cachedVitals.first { it.localVitalId == vitalId }.weightKg).isEqualTo(68.5)
            assertThat(repository.syncState.value.hasPendingWrites).isTrue()

            // Verify subsequent update of a cached doc while still offline uses cache fallback cleanly
            repository.enqueuePatientProfile(
                PatientProfile(
                    accountUid = aliceUid,
                    fullName = "Offline Updated Patient",
                    bloodGroup = "B+",
                    preferredLanguage = "Telugu",
                    updatedAtTimestamp = 1700000200000L
                )
            )
            val updatedCachedProfile = withTimeout(FLOW_TIMEOUT_MS) {
                repository.observePatientProfile(aliceUid)
                    .first { it?.fullName == "Offline Updated Patient" }
            }
            assertThat(updatedCachedProfile?.preferredLanguage).isEqualTo("Telugu")

            // Reconnect network and wait for queued offline writes to flush to emulator
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repository.setNetworkEnabled(true)
                repository.waitForPendingWrites()
            }

            assertThat(repository.syncState.value.hasPendingWrites).isFalse()

            val syncedProfile = withTimeout(FLOW_TIMEOUT_MS) {
                repository.observePatientProfile(aliceUid)
                    .first { it?.fullName == "Offline Updated Patient" }
            }
            assertThat(syncedProfile?.preferredLanguage).isEqualTo("Telugu")
        } finally {
            runCatching {
                withTimeout(DEFAULT_TIMEOUT_MS) {
                    repository.setNetworkEnabled(true)
                }
            }
        }
    }

    private companion object {
        const val OFFLINE_ALICE_EMAIL = "offline_alice@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 5000L
    }
}
