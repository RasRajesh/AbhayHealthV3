package com.example.abhayhealth.feature.records

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.abhayhealth.feature.medicines.MedicationDao
import com.example.abhayhealth.feature.medicines.PrescriptionMedicationEntity
import com.example.abhayhealth.feature.vitals.VitalReadingDao
import com.example.abhayhealth.feature.vitals.VitalReadingEntity

/**
 * Room Database for local medical record metadata, extracted info, prescription medications, and vital readings.
 */
@Database(
    entities = [
        MedicalRecord::class,
        ExtractedMedicalInfo::class,
        PrescriptionMedicationEntity::class,
        VitalReadingEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class MedicalRecordDatabase : RoomDatabase() {

    abstract fun medicalRecordDao(): MedicalRecordDao
    abstract fun extractedInfoDao(): ExtractedInfoDao
    abstract fun medicationDao(): MedicationDao
    abstract fun vitalReadingDao(): VitalReadingDao

    companion object {
        const val DATABASE_NAME = "abhayhealth_medical_records.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `extracted_medical_info` (
                        `recordId` INTEGER NOT NULL,
                        `accountUid` TEXT NOT NULL,
                        `rawText` TEXT NOT NULL,
                        `documentTitle` TEXT NOT NULL,
                        `reportDate` TEXT,
                        `structuredResultsJson` TEXT NOT NULL,
                        `reviewStatus` TEXT NOT NULL,
                        `processedPages` INTEGER NOT NULL,
                        `updatedAtTimestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`recordId`),
                        FOREIGN KEY(`recordId`) REFERENCES `medical_records`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `prescription_medications` (
                        `id` TEXT NOT NULL,
                        `accountUid` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `dosage` TEXT NOT NULL,
                        `timeSlot` TEXT NOT NULL,
                        `isTaken` INTEGER NOT NULL,
                        `createdAtTimestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vital_readings` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `accountUid` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `systolic` REAL,
                        `diastolic` REAL,
                        `weightKg` REAL,
                        `timestamp` INTEGER NOT NULL,
                        `notes` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: MedicalRecordDatabase? = null

        fun getInstance(context: Context): MedicalRecordDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MedicalRecordDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
