package com.example.forex.data.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/**
 * Production-ready Google Authentication Client.
 * 
 * Features:
 * - Proper Firebase initialization with fallback configuration
 * - Comprehensive null safety throughout
 * - Structured error handling with sealed result types
 * - Thread-safe operations
 * - Resource cleanup via Closeable pattern
 * - Enhanced logging for debugging
 */
class GoogleAuthClient(private val context: Context) : Closeable {
    
    companion object {
        private const val TAG = "GoogleAuthClient"
        
        // Firebase configuration for forexanalyst-99ff5 project
        private const val FIREBASE_PROJECT_ID = "forexanalyst-99ff5"
        private const val FIREBASE_APP_ID = "1:154992800406:android:847f286c693933652548b7"
        private const val FIREBASE_API_KEY = "AIzaSyAkNRgBVGNAGAckNICFzliaomDt8oUiQPg"
        private const val FIREBASE_WEB_CLIENT_ID = "154992800406-6thfpklaqo0ittf4fnrf70eaje13bc34.apps.googleusercontent.com"
    }
    
    // ========================================================================
    // FIREBASE AUTHENTICATION
    // ========================================================================

    private val auth: FirebaseAuth by lazy { initializeFirebaseAuth() }
    private val credentialManager: CredentialManager by lazy { CredentialManager.create(context) }
    
    private var isClosed = false

    /**
     * Initialize Firebase Auth with proper error handling and fallback configuration.
     * Uses the real Firebase project: forexanalyst-99ff5
     */
    private fun initializeFirebaseAuth(): FirebaseAuth {
        return try {
            // Try to get existing FirebaseAuth instance
            FirebaseAuth.getInstance()
        } catch (e: IllegalStateException) {
            initializeFirebaseWithFallback(e)
        }
    }

    /**
     * Initialize Firebase with fallback configuration for the forexanalyst-99ff5 project.
     */
    private fun initializeFirebaseWithFallback(initialError: Exception): FirebaseAuth {
        return try {
            // Try to initialize Firebase app
            FirebaseApp.initializeApp(context)
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "Firebase auto-initialization failed, using manual configuration", e)
            initializeFirebaseManually()
        }
    }

    /**
     * Manual Firebase initialization with the forexanalyst-99ff5 project configuration.
     */
    private fun initializeFirebaseManually(): FirebaseAuth {
        return try {
            val options = FirebaseOptions.Builder()
                .setApplicationId(FIREBASE_APP_ID)
                .setApiKey(FIREBASE_API_KEY)
                .setProjectId(FIREBASE_PROJECT_ID)
                .build()
            
            // Check if app is already initialized to avoid duplicate initialization
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context, options)
            }
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase manually", e)
            throw IllegalStateException("Firebase initialization failed: ${e.message}", e)
        }
    }

    // ========================================================================
    // LIFECYCLE MANAGEMENT
    // ========================================================================

    override fun close() {
        if (isClosed) return
        isClosed = true
        try {
            // Sign out current user
            signOutSafe()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    // ========================================================================
    // AUTHENTICATION OPERATIONS
    // ========================================================================

    /**
     * Get the currently signed-in user with proper null safety.
     */
    fun getSignedInUser(): FirebaseUser? {
        return runCatching {
            auth.currentUser
        }.getOrElse { e ->
            Log.e(TAG, "Failed to get current user", e)
            null
        }
    }

    /**
     * Sign in with Google using Play Services credential manager.
     * Returns a structured result with comprehensive error information.
     */
    suspend fun signIn(): SignInResult {
        return try {
            // Ensure Firebase is properly initialized
            if (!isFirebaseAvailable()) {
                return SignInResult.Error(FirebaseNotInitialized)
            }
            
            // Get web client ID from resources or use fallback
            val webClientId = getWebClientId()
            if (webClientId.isBlank()) {
                return SignInResult.Error(InvalidConfiguration("Web client ID not available"))
            }

            // Build Google Sign-In request
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            // Execute credential request
            val result = runCatching {
                credentialManager.getCredential(context, request)
            }.getOrElse { e ->
                Log.e(TAG, "Credential request failed", e)
                return SignInResult.Error(CredentialException(e.message ?: "Unknown credential error"))
            }

            val credential = result.credential
            
            // Process Google credential
            if (credential is CustomCredential && 
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                
                val googleIdTokenCredential = runCatching {
                    GoogleIdTokenCredential.createFrom(credential.data)
                }.getOrElse { e ->
                    Log.e(TAG, "Failed to create Google ID token credential", e)
                    return SignInResult.Error(InvalidCredential("Invalid credential format"))
                }
                
                val idToken = googleIdTokenCredential.idToken
                if (idToken.isBlank()) {
                    return SignInResult.Error(InvalidCredential("Empty ID token"))
                }
                
                // Sign in with Firebase
                val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = runCatching {
                    auth.signInWithCredential(firebaseCredential).await()
                }.getOrElse { e ->
                    Log.e(TAG, "Firebase sign-in failed", e)
                    return SignInResult.Error(FirebaseAuthException(e.message ?: "Authentication failed"))
                }
                
                SignInResult.Success(authResult.user)
            } else {
                SignInResult.Error(InvalidCredential("Received an invalid credential type: ${credential?.type}"))
            }
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Exception: ${e.message}", e)
            SignInResult.Error(CredentialException(e.message ?: "Credential error"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected sign-in error", e)
            SignInResult.Error(UnexpectedError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Check if Firebase is available and properly initialized.
     */
    private fun isFirebaseAvailable(): Boolean {
        return runCatching { 
            auth != null && FirebaseApp.getApps(context).isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * Get the web client ID from resources or use the configured fallback.
     */
    private fun getWebClientId(): String {
        return try {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) {
                context.getString(resId)
            } else {
                // Fallback to the web client ID from Firebase configuration
                FIREBASE_WEB_CLIENT_ID
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get web client ID from resources", e)
            FIREBASE_WEB_CLIENT_ID
        }
    }

    /**
     * Sign out the current user with proper error handling.
     */
    fun signOut() {
        signOutSafe()
    }

    /**
     * Safe sign out that handles null auth gracefully.
     */
    private fun signOutSafe() {
        runCatching {
            auth.signOut()
            Log.i(TAG, "User signed out successfully")
        }.onFailure { e ->
            Log.e(TAG, "Sign-out failed", e)
        }
    }

    // ========================================================================
    // ERROR TYPES
    // ========================================================================

    /**
     * Sealed hierarchy for authentication errors.
     */
    sealed class AuthError(val message: String) {
        object FirebaseNotInitialized : AuthError("Firebase not initialized")
        class InvalidConfiguration(detail: String) : AuthError("Invalid configuration: $detail")
        class CredentialException(detail: String) : AuthError("Credential error: $detail")
        class InvalidCredential(detail: String) : AuthError("Invalid credential: $detail")
        class FirebaseAuthException(detail: String) : AuthError("Firebase auth error: $detail")
        class UnexpectedError(detail: String) : AuthError("Unexpected error: $detail")
    }

    /**
     * Result of a sign-in operation.
     */
    sealed class SignInResult {
        data class Success(val user: FirebaseUser) : SignInResult()
        data class Error(val error: AuthError) : SignInResult()
        
        // Legacy compatibility
        val data: FirebaseUser?
            get() = when (this) {
                is Success -> user
                is Error -> null
            }
        
        val errorMessage: String?
            get() = when (this) {
                is Success -> null
                is Error -> error.message
            }
    }
}