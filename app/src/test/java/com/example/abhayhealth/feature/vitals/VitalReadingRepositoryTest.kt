package com.example.abhayhealth.feature.vitals

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeVitalReadingDao : VitalReadingDao {
    private val readings = mutableListOf<VitalReadingEntity>()
    private val readingsFlow = MutableStateFlow<List<VitalReadingEntity>>(emptyList())

    private fun updateFlow() {
        readingsFlow.value = readings.toList()
    }

    override fun getAllReadingsByAccount(accountUid: String): Flow<List<VitalReadingEntity>> {
        return readingsFlow.map { list -> list.filter { it.accountUid == accountUid } }
    }

    override fun getReadingsByType(accountUid: String, type: String): Flow<List<VitalReadingEntity>> {
        return readingsFlow.map { list -> list.filter { it.accountUid == accountUid && it.type == type } }
    }

    override fun insertReading(reading: VitalReadingEntity): Long {
        readings.add(reading)
        updateFlow()
        return reading.id
    }

    override fun insertReadings(readings: List<VitalReadingEntity>): List<Long> {
        this.readings.addAll(readings)
        updateFlow()
        return readings.map { it.id }
    }

    override fun deleteReading(reading: VitalReadingEntity): Int {
        val removed = readings.remove(reading)
        updateFlow()
        return if (removed) 1 else 0
    }

    override fun getReadingCount(accountUid: String): Int {
        return readings.count { it.accountUid == accountUid }
    }
}

class VitalReadingRepositoryTest {

    private lateinit var fakeDao: FakeVitalReadingDao
    private lateinit var repository: VitalReadingRepository

    @Before
    fun setUp() {
        fakeDao = FakeVitalReadingDao()
        repository = VitalReadingRepository(fakeDao)
    }

    @Test
    fun testEnsureBaselineReadings_seedsReadingsWhenEmpty() = runBlocking {
        assertEquals(0, fakeDao.getReadingCount("user_123"))

        repository.ensureBaselineReadings("user_123")

        val count = fakeDao.getReadingCount("user_123")
        assertTrue("Expected baseline readings to be seeded", count > 0)

        val bpReadings = repository.getReadingsByType("user_123", VitalType.BLOOD_PRESSURE).first()
        val weightReadings = repository.getReadingsByType("user_123", VitalType.WEIGHT).first()

        assertEquals(7, bpReadings.size)
        assertEquals(7, weightReadings.size)
    }

    @Test
    fun testInsertReading_addsNewReadingToAccount() = runBlocking {
        val newReading = VitalReadingEntity(
            id = 101L,
            accountUid = "user_456",
            type = VitalType.BLOOD_PRESSURE.name,
            systolic = 120.0,
            diastolic = 80.0,
            timestamp = System.currentTimeMillis(),
            notes = "After morning walk"
        )

        repository.insertReading(newReading)

        val readings = repository.getReadingsByType("user_456", VitalType.BLOOD_PRESSURE).first()
        assertEquals(1, readings.size)
        assertEquals(120.0, readings[0].systolic ?: 0.0, 0.01)
        assertEquals(80.0, readings[0].diastolic ?: 0.0, 0.01)
        assertEquals("After morning walk", readings[0].notes)
    }
}
