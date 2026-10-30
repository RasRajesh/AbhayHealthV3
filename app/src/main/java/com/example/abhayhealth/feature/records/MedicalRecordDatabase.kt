package `in`.abhayhealth.app.feature.records

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import `in`.abhayhealth.app.feature.medicines.MedicationDao
import `in`.abhayhealth.app.feature.medicines.PrescriptionMedicationEntity

/**
 * Room Database for local medical record metadata, extracted info, and prescription medications.
 */
@Database(
    entities = [MedicalRecord::class, ExtractedMedicalInfo::class, PrescriptionMedicationEntity::class],
    version = 3,
    exportSchema = false
)
abstract class MedicalRecordDatabase : RoomDatabase() {

    abstract fun medicalRecordDao(): MedicalRecordDao
    abstract fun extractedInfoDao(): ExtractedInfoDao
    abstract fun medicationDao(): MedicationDao

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

        @Volatile
        private var INSTANCE: MedicalRecordDatabase? = null

        fun getInstance(context: Context): MedicalRecordDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MedicalRecordDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
