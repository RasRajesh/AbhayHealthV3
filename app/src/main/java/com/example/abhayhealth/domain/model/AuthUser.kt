package com.example.abhayhealth.domain.model

/**
 * Domain model representing an authenticated Firebase user account.
 */
data class AuthUser(
    val uid: String,
    val phoneNumber: String? = null,
    val email: String? = null,
    val displayName: String? = null
)
