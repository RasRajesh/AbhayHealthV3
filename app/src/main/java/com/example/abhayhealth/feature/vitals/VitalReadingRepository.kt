package com.example.abhayhealth.feature.vitals

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class VitalReadingRepository(
    private val vitalReadingDao: VitalReadingDao
) {

    fun getAllReadings(accountUid: String): Flow<List<VitalReadingEntity>> {
        return vitalReadingDao.getAllReadingsByAccount(accountUid)
    }

    fun getReadingsByType(accountUid: String, type: VitalType): Flow<List<VitalReadingEntity>> {
        return vitalReadingDao.getReadingsByType(accountUid, type.name)
    }

    suspend fun insertReading(reading: VitalReadingEntity): Long = withContext(Dispatchers.IO) {
        vitalReadingDao.insertReading(reading)
    }

    suspend fun deleteReading(reading: VitalReadingEntity): Int = withContext(Dispatchers.IO) {
        vitalReadingDao.deleteReading(reading)
    }

    suspend fun ensureBaselineReadings(accountUid: String) = withContext(Dispatchers.IO) {
        val count = vitalReadingDao.getReadingCount(accountUid)
        if (count == 0) {
            val now = System.currentTimeMillis()
            val dayMs = TimeUnit.DAYS.toMillis(1)

            val baselineReadings = listOf(
                // 7-day Blood Pressure trend (systolic / diastolic in mmHg)
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 126.0,
                    diastolic = 84.0,
                    timestamp = now - 6 * dayMs,
                    notes = "Evening reading post dinner"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 124.0,
                    diastolic = 82.0,
                    timestamp = now - 5 * dayMs,
                    notes = "Morning resting reading"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 121.0,
                    diastolic = 80.0,
                    timestamp = now - 4 * dayMs,
                    notes = "Normal morning check"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 125.0,
                    diastolic = 83.0,
                    timestamp = now - 3 * dayMs,
                    notes = "Post brisk walk"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 119.0,
                    diastolic = 79.0,
                    timestamp = now - 2 * dayMs,
                    notes = "Optimal resting BP"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 120.0,
                    diastolic = 78.0,
                    timestamp = now - 1 * dayMs,
                    notes = "Well rested morning"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.BLOOD_PRESSURE.name,
                    systolic = 118.0,
                    diastolic = 77.0,
                    timestamp = now,
                    notes = "Latest baseline reading"
                ),

                // 7-day Weight trend (in kg)
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.WEIGHT.name,
                    weightKg = 71.2,
                    timestamp = now - 6 * dayMs,
                    notes = "Morning fasting weight"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.WEIGHT.name,
                    weightKg = 70.9,
                    timestamp = now - 5 * dayMs,
                    notes = "Fasting weight"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.WEIGHT.name,
                    weightKg = 70.6,
                    timestamp = now - 4 * dayMs,
                    notes = "Consistent hydration"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.WEIGHT.name,
                    weightKg = 70.8,
                    timestamp = now - 3 * dayMs,
                    notes = "Post weekend measurement"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.WEIGHT.name,
                    weightKg = 70.3,
                    timestamp = now - 2 * dayMs,
                    notes = "Fasting check"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.WEIGHT.name,
                    weightKg = 70.0,
                    timestamp = now - 1 * dayMs,
                    notes = "Approaching target weight"
                ),
                VitalReadingEntity(
                    accountUid = accountUid,
                    type = VitalType.WEIGHT.name,
                    weightKg = 69.8,
                    timestamp = now,
                    notes = "Latest weekly milestone"
                )
            )

            vitalReadingDao.insertReadings(baselineReadings)
        }
    }
}
