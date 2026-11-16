package com.example.abhayhealth.data.repository

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.abhayhealth.R
import com.example.abhayhealth.core.security.SecurityAudit
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.model.AuthUser
import com.example.abhayhealth.domain.repository.AuthRepository
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "FirebaseAuthRepo"

/**
 * Firebase Auth repository using Jetpack Credential Manager + Google Sign-In.
 * No anonymous or email/password fallbacks are permitted in application code.
 */
class FirebaseAuthRepository(
    context: Context
) : AuthRepository {

    private val appContext = context.applicationContext
    private val credentialManager = CredentialManager.create(appContext)
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val auth: FirebaseAuth by lazy {
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(appContext)
        }
        Firebase.auth
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _authState.value = if (user != null) {
            AuthState.Authenticated(user.toAuthUser())
        } else if (_authState.value !is AuthState.Error) {
            AuthState.Unauthenticated
        } else {
            _authState.value
        }
    }

    init {
        val existingUser = runCatching { auth.currentUser }.getOrNull()
        _authState.value = if (existingUser != null) {
            AuthState.Authenticated(existingUser.toAuthUser())
        } else {
            AuthState.Unauthenticated
        }
        runCatching { auth.addAuthStateListener(authStateListener) }
            .onFailure { e ->
                SecurityAudit.logError(TAG, "Failed to register FirebaseAuth listener", e)
            }
    }

    override val currentUser: AuthUser?
        get() = runCatching { auth.currentUser?.toAuthUser() }.getOrNull()

    override val isAuthenticated: Boolean
        get() = currentUser != null

    override suspend fun attemptAutoSignIn(activityContext: Context) {
        if (auth.currentUser != null) return
        val clientId = resolveWebClientId() ?: return

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        try {
            val result = credentialManager.getCredential(activityContext, request)
            handleGoogleCredential(result.credential)
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            // Silent check found no authorized account yet; wait for interactive tap.
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Auto Google Sign-In cancelled: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Silent Google Sign-In skipped: ${e.message}")
        }
    }

    override suspend fun signInWithGoogle(activityContext: Context) {
        val clientId = resolveWebClientId()
        if (clientId == null) {
            _authState.value = AuthState.Error(
                message = "Google Sign-In configuration missing (default_web_client_id).",
                isConfigurationError = true
            )
            return
        }

        if (auth.currentUser == null) {
            _authState.value = AuthState.Loading
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        try {
            val result = credentialManager.getCredential(activityContext, request)
            handleGoogleCredential(result.credential)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            _authState.value = if (auth.currentUser != null) {
                AuthState.Authenticated(auth.currentUser!!.toAuthUser())
            } else {
                AuthState.Unauthenticated
            }
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google credentials found on device: ${e.message}", e)
            _authState.value = AuthState.Error(
                "No Google account is configured on this device. Please add a Google account in device Settings and try again."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            _authState.value = AuthState.Error(
                e.localizedMessage ?: "Google Sign-In failed. Please try again."
            )
        }
    }

    private suspend fun handleGoogleCredential(credential: androidx.credentials.Credential) {
        if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
            val authResult = auth.signInWithCredential(firebaseCredential).await()
            val signedInUser = authResult.user ?: auth.currentUser
            if (signedInUser != null) {
                _authState.value = AuthState.Authenticated(signedInUser.toAuthUser())
            } else {
                _authState.value = AuthState.Error("Unable to retrieve Firebase user after Google Sign-In.")
            }
        } else {
            _authState.value = AuthState.Error("Unexpected credential type returned from Google Sign-In.")
        }
    }

    override fun clearError() {
        if (_authState.value is AuthState.Error) {
            _authState.value = if (auth.currentUser != null) {
                AuthState.Authenticated(auth.currentUser!!.toAuthUser())
            } else {
                AuthState.Unauthenticated
            }
        }
    }

    override fun signOut() {
        repoScope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    try {
                        auth.signOut()
                    } catch (e: Exception) {
                        SecurityAudit.logError(TAG, "Firebase sign-out error", e)
                    }
                    _authState.value = AuthState.Unauthenticated
                }
            }
        }
    }

    override fun deleteAccount(onComplete: (Boolean, String?) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            _authState.value = AuthState.Unauthenticated
            onComplete(true, null)
            return
        }

        user.delete().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                repoScope.launch {
                    withContext(NonCancellable) {
                        runCatching {
                            credentialManager.clearCredentialState(ClearCredentialStateRequest())
                        }
                    }
                }
                _authState.value = AuthState.Unauthenticated
                onComplete(true, null)
            } else {
                val exception = task.exception
                val errorMsg = if (exception is FirebaseAuthRecentLoginRequiredException) {
                    "Security requirement: Please sign out and sign in again with Google before deleting your account."
                } else {
                    exception?.localizedMessage ?: "Failed to delete Firebase account."
                }
                onComplete(false, errorMsg)
            }
        }
    }

    private fun resolveWebClientId(): String? {
        return try {
            val id = appContext.resources.getIdentifier(
                "default_web_client_id",
                "string",
                appContext.packageName
            )
            if (id != 0) appContext.getString(id).takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve default_web_client_id", e)
            null
        }
    }

    private fun FirebaseUser.toAuthUser(): AuthUser = AuthUser(
        uid = uid,
        phoneNumber = phoneNumber,
        email = email,
        displayName = displayName
    )
}
