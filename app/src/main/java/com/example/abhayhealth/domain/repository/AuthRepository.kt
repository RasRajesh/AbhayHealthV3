package `in`.abhayhealth.app.domain.repository

import android.app.Activity
import `in`.abhayhealth.app.domain.model.AuthState
import `in`.abhayhealth.app.domain.model.AuthUser
import kotlinx.coroutines.flow.StateFlow

/**
 * Authentication Repository interface for Firebase Phone Authentication.
 */
interface AuthRepository {
    val authState: StateFlow<AuthState>
    val currentUser: AuthUser?
    val isAuthenticated: Boolean

    fun sendOtp(phoneNumber: String, activity: Activity)
    fun verifyOtp(verificationId: String, code: String)
    fun resendOtp(phoneNumber: String, activity: Activity, resendToken: Any?)
    fun signOut()
    fun deleteAccount(onComplete: (Boolean, String?) -> Unit)
}
