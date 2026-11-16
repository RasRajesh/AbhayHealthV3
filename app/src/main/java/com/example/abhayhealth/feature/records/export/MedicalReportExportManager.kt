package com.example.abhayhealth.feature.records.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.example.abhayhealth.domain.repository.PatientProfileRepository
import com.example.abhayhealth.feature.medicines.MedicationDao
import com.example.abhayhealth.feature.records.MedicalRecordRepository
import com.example.abhayhealth.feature.vitals.VitalReadingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manager that coordinates pulling medical records, medications, vitals, and profile
 * from the Room database and compiling them into an exported PDF report.
 */
class MedicalReportExportManager(
    private val context: Context,
    private val medicalRecordRepository: MedicalRecordRepository,
    private val medicationDao: MedicationDao,
    private val vitalReadingRepository: VitalReadingRepository,
    private val patientProfileRepository: PatientProfileRepository
) {

    private val generator = MedicalReportPdfGenerator(context)

    suspend fun generateReport(accountUid: String): File = withContext(Dispatchers.IO) {
        val profile = try {
            patientProfileRepository.getProfile(accountUid).first()
        } catch (_: Exception) {
            null
        }

        val medications = try {
            medicationDao.getMedicationsByAccount(accountUid).first()
        } catch (_: Exception) {
            emptyList()
        }

        val records = try {
            medicalRecordRepository.getRecords(accountUid).first()
        } catch (_: Exception) {
            emptyList()
        }

        val extractedList = try {
            medicalRecordRepository.getAllExtractedInfo(accountUid).first()
        } catch (_: Exception) {
            emptyList()
        }
        val extractedMap = extractedList.associateBy { it.recordId }

        val vitals = try {
            vitalReadingRepository.getAllReadings(accountUid).first()
        } catch (_: Exception) {
            emptyList()
        }

        val symptoms = try {
            com.example.abhayhealth.feature.records.MedicalRecordDatabase.getInstance(context).symptomLogDao().getAllSymptoms(accountUid).first()
        } catch (_: Exception) {
            emptyList()
        }

        val reportData = MedicalReportPdfGenerator.ReportData(
            patientProfile = profile,
            medications = medications,
            records = records,
            extractedInfoMap = extractedMap,
            vitals = vitals,
            symptoms = symptoms,
            generatedAt = System.currentTimeMillis()
        )

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val targetFile = File(reportsDir, "AbhayHealth_Medical_Summary_$timeStamp.pdf")

        generator.generatePdfReport(reportData, targetFile)
    }

    fun getReportUri(file: File): Uri = getReportUri(context, file)

    fun shareReport(file: File, patientName: String) {
        sharePdfFile(
            context = context,
            file = file,
            subject = "Medical Summary & Medication Schedule - $patientName",
            message = "Sharing verified medical history, active medications, and vital logs from AbhayHealth for $patientName.",
            chooserTitle = "Share Medical Report with Doctor / Provider"
        )
    }

    fun viewReport(file: File) {
        viewPdfFile(context, file)
    }

    fun printReport(file: File) {
        printPdfFile(context, file)
    }

    companion object {
        fun getReportUri(context: Context, file: File): Uri {
            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        fun sharePdfFile(
            context: Context,
            file: File,
            subject: String,
            message: String,
            chooserTitle: String = "Share PDF Report"
        ) {
            val uri = getReportUri(context, file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, message)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }

        fun viewPdfFile(context: Context, file: File) {
            val uri = getReportUri(context, file)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(viewIntent, "Open PDF Report").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }

        fun printPdfFile(context: Context, file: File) {
            try {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                if (printManager != null) {
                    val printAdapter = PdfPrintDocumentAdapter(file)
                    printManager.print("AbhayHealth_Medical_Report", printAdapter, PrintAttributes.Builder().build())
                } else {
                    viewPdfFile(context, file)
                }
            } catch (_: Exception) {
                viewPdfFile(context, file)
            }
        }

        fun copyFileToUri(context: Context, sourceFile: File, destinationUri: Uri) {
            context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: throw IllegalStateException("Unable to open destination file for writing.")
        }
    }
}
