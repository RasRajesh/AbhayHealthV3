package `in`.abhayhealth.app

import `in`.abhayhealth.app.R.data.repository

import android.content.Context
import android.app.Activity
import `in`.abhayhealth.app.core.security.SecurityAudit
import `in`.abhayhealth.app.domain.model.AuthState
import `in`.abhayhealth.app.domain.model.AuthUser
import `in`.abhayhealth.app.domain.repository.AuthRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * Firebase implementation of AuthRepository for Phone OTP Authentication.
 */
class FirebaseAuthRepository(
    private val appContext: Context
) : AuthRepository {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var firebaseAuth: FirebaseAuth? = null

    init {
        try {
            val app = FirebaseApp.getApps(appContext)
                .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?: FirebaseApp.initializeApp(appContext)
                ?: throw IllegalStateException(
                    "Default FirebaseApp is missing. Check google-services.json and generated Firebase resources."
                )

            firebaseAuth = FirebaseAuth.getInstance(app)

            firebaseAuth?.addAuthStateListener { firebase ->
                val user = firebase.currentUser

                if (user != null) {
                    _authState.value = AuthState.Authenticated(
                        AuthUser(
                            uid = user.uid,
                            phoneNumber = user.phoneNumber
                        )
                    )
                } else if (
                    _authState.value !is AuthState.CodeSent &&
                    _authState.value !is AuthState.Loading
                ) {
                    _authState.value = AuthState.Unauthenticated
                }
            }
        } catch (e: Exception) {
            SecurityAudit.logError(
                "FirebaseAuthRepository",
                "Firebase initialization exception",
                e
            )

            _authState.value = AuthState.Error(
                message = "Firebase initialization failed: ${
                    e.localizedMessage ?: "Unknown initialization error"
                }",
                isConfigurationError = true
            )
        }
    }

    override val currentUser: AuthUser?
        get() = firebaseAuth?.currentUser?.let { user ->
            AuthUser(uid = user.uid, phoneNumber = user.phoneNumber)
        }

    override val isAuthenticated: Boolean
        get() = firebaseAuth?.currentUser != null

    override fun sendOtp(phoneNumber: String, activity: Activity) {
        val auth = firebaseAuth
        if (auth == null) {
            _authState.value = AuthState.Error(
                message = "Firebase Authentication is not configured. google-services.json required.",
                isConfigurationError = true
            )
            return
        }

        _authState.value = AuthState.Loading

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                SecurityAudit.logEvent("Phone verification automatically completed by system")
                signInWithCredential(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                SecurityAudit.logError("FirebaseAuthRepository", "Phone verification failed", e)
                val errorMessage = when (e) {
                    is FirebaseTooManyRequestsException -> "Quota exceeded or too many requests. Please try again later."
                    is FirebaseAuthInvalidCredentialsException -> "Invalid phone number format provided."
                    else -> e.localizedMessage ?: "Verification failed. Please check your network and try again."
                }
                _authState.value = AuthState.Error(errorMessage)
            }

            override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                SecurityAudit.logEvent("OTP code sent successfully")
                _authState.value = AuthState.CodeSent(
                    verificationId = verificationId,
                    phoneNumber = phoneNumber,
                    resendToken = token
                )
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    override fun verifyOtp(verificationId: String, code: String) {
        if (code.isBlank() || code.length < 6) {
            _authState.value = AuthState.Error("Please enter the complete 6-digit OTP code.")
            return
        }

        _authState.value = AuthState.Loading
        try {
            val credential = PhoneAuthProvider.getCredential(verificationId, code)
            signInWithCredential(credential)
        } catch (e: Exception) {
            SecurityAudit.logError("FirebaseAuthRepository", "Invalid credential error", e)
            _authState.value = AuthState.Error("Invalid code entered. Please verify and try again.")
        }
    }

    override fun resendOtp(phoneNumber: String, activity: Activity, resendToken: Any?) {
        val auth = firebaseAuth
        if (auth == null) {
            _authState.value = AuthState.Error("Firebase Auth is not configured.", isConfigurationError = true)
            return
        }

        _authState.value = AuthState.Loading

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                signInWithCredential(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                SecurityAudit.logError("FirebaseAuthRepository", "Resend OTP failed", e)
                _authState.value = AuthState.Error(e.localizedMessage ?: "Failed to resend OTP.")
            }

            override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                SecurityAudit.logEvent("OTP code resent successfully")
                _authState.value = AuthState.CodeSent(
                    verificationId = verificationId,
                    phoneNumber = phoneNumber,
                    resendToken = token
                )
            }
        }

        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)

        if (resendToken is PhoneAuthProvider.ForceResendingToken) {
            builder.setForceResendingToken(resendToken)
        }

        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }

    private fun signInWithCredential(credential: PhoneAuthCredential) {
        val auth = firebaseAuth ?: return
        auth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = task.result?.user
                    if (user != null) {
                        _authState.value = AuthState.Authenticated(
                            AuthUser(uid = user.uid, phoneNumber = user.phoneNumber)
                        )
                    } else {
                        _authState.value = AuthState.Error("Sign-in succeeded but user object was null.")
                    }
                } else {
                    val exception = task.exception
                    SecurityAudit.logError("FirebaseAuthRepository", "Sign-in with credential failed", exception)
                    val errorMsg = when (exception) {
                        is FirebaseAuthInvalidCredentialsException -> "Incorrect or expired OTP entered."
                        else -> exception?.localizedMessage ?: "Authentication failed. Please try again."
                    }
                    _authState.value = AuthState.Error(errorMsg)
                }
            }
    }

    override fun signOut() {
        try {
            firebaseAuth?.signOut()
            _authState.value = AuthState.Unauthenticated
        } catch (e: Exception) {
            SecurityAudit.logError("FirebaseAuthRepository", "Sign-out error", e)
            _authState.value = AuthState.Unauthenticated
        }
    }

    override fun deleteAccount(onComplete: (Boolean, String?) -> Unit) {
        val user = firebaseAuth?.currentUser
        if (user != null) {
            user.delete().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    _authState.value = AuthState.Unauthenticated
                    onComplete(true, null)
                } else {
                    val exception = task.exception
                    val errorMsg = if (exception is FirebaseAuthRecentLoginRequiredException) {
                        "Security requirement: Recent login required before deleting account. Please sign out and sign in again."
                    } else {
                        exception?.localizedMessage ?: "Failed to delete account."
                    }
                    onComplete(false, errorMsg)
                }
            }
        } else {
            _authState.value = AuthState.Unauthenticated
            onComplete(true, null)
        }
    }
}
