package com.example.abhayhealth.feature.medicines

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for Prescription Medications.
 */
@Dao
interface MedicationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(medication: PrescriptionMedicationEntity): Long

    @Query("SELECT * FROM prescription_medications WHERE accountUid = :accountUid ORDER BY createdAtTimestamp ASC")
    fun getMedicationsByAccount(accountUid: String): Flow<List<PrescriptionMedicationEntity>>

    @Query("SELECT * FROM prescription_medications WHERE id = :id AND accountUid = :accountUid")
    fun getMedicationByIdSync(id: String, accountUid: String): PrescriptionMedicationEntity?

    @Query("UPDATE prescription_medications SET isTaken = NOT isTaken WHERE id = :id AND accountUid = :accountUid")
    fun toggleDoseTaken(id: String, accountUid: String): Int

    @Query("DELETE FROM prescription_medications WHERE id = :id AND accountUid = :accountUid")
    fun deleteMedication(id: String, accountUid: String): Int

    @Query("SELECT * FROM prescription_medications")
    fun getAllMedicationsSync(): List<PrescriptionMedicationEntity>

    @Query("SELECT * FROM prescription_medications WHERE id = :id")
    fun getMedicationByIdSync(id: String): PrescriptionMedicationEntity?

    @Query("UPDATE prescription_medications SET isTaken = 1 WHERE id = :id")
    fun markDoseTaken(id: String): Int
}
