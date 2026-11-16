package com.example.abhayhealth.domain.repository

import android.content.Context
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.model.AuthUser
import kotlinx.coroutines.flow.StateFlow

/**
 * Authentication Repository interface for Google Sign-In backed by Firebase Auth.
 */
interface AuthRepository {
    val authState: StateFlow<AuthState>
    val currentUser: AuthUser?
    val isAuthenticated: Boolean

    suspend fun attemptAutoSignIn(activityContext: Context)
    suspend fun signInWithGoogle(activityContext: Context)
    fun clearError()
    fun signOut()
    fun deleteAccount(onComplete: (Boolean, String?) -> Unit)
}
