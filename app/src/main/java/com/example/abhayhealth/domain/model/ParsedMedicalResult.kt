package com.example.abhayhealth.domain.model

import java.util.UUID

/**
 * Domain model representing a structured medical test result extracted from a report.
 */
data class ParsedMedicalResult(
    val id: String = UUID.randomUUID().toString(),
    val testName: String,
    val value: String,
    val unit: String = "",
    val referenceRange: String = ""
)
