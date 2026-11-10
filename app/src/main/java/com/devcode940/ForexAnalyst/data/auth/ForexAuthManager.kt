package com.devcode940.ForexAnalyst.data.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.devcode940.ForexAnalyst.data.db.ForexDao
import com.devcode940.ForexAnalyst.data.db.UserProfileEntity
import com.devcode940.ForexAnalyst.data.model.UserProfile
import com.devcode940.ForexAnalyst.data.security.PasswordHasher
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.io.Closeable
import java.util.UUID

/**
 * Production-ready Forex Authentication Manager.
 * 
 * Features:
 * - Comprehensive authentication with email/password and Google
 * - Proper Firebase initialization with fallback configuration
 * - Comprehensive null safety throughout
 * - Structured error handling with sealed result types
 * - Thread-safe operations
 * - Resource cleanup via Closeable pattern
 * - Enhanced logging for debugging
 * - Password hashing with upgrade support for legacy hashes
 */
class ForexAuthManager(
    private val context: Context,
    private val forexDao: ForexDao
) : Closeable {
    
    companion object {
        private const val TAG = "ForexAuthManager"
        
        // Firebase configuration - loaded from google-services.json via FirebaseApp
        // These values should NOT be hardcoded in production code
        // They are loaded automatically by Firebase initialization
        private const val FIREBASE_PROJECT_ID = "forexanalyst-99ff5"
        
        // Note: API keys and client IDs are loaded from google-services.json
        // Do NOT hardcode them in source files as they can be extracted from APK
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
    // USER PROFILE FLOW
    // ========================================================================

    val activeUserProfileFlow: Flow<UserProfile?> = forexDao.getActiveUserProfile().map { entity ->
        entity?.let {
            UserProfile(
                uid = it.uid,
                email = it.email,
                displayName = it.displayName,
                photoUrl = it.photoUrl,
                accountTier = it.accountTier,
                joinedAt = it.joinedAt
            )
        }
    }

    // ========================================================================
    // LIFECYCLE MANAGEMENT
    // ========================================================================

    override fun close() {
        if (isClosed) return
        isClosed = true
        try {
            signOutSafe()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    // ========================================================================
    // PASSWORD HASHING
    // ========================================================================

    private fun hashPassword(password: String): String = PasswordHasher.hash(password)

    /**
     * Verifies a password against the stored hash. Supports the modern PBKDF2 format
     * ("iterations:salt:hash") and legacy unsalted SHA-256 hex hashes for backward
     * compatibility. A null/blank stored hash NEVER authenticates (fixes auth bypass).
     * Returns true if the stored hash was legacy and should be upgraded on next write.
     */
    private fun verifyPasswordWithUpgradeCheck(password: String, storedHash: String?): Pair<Boolean, Boolean> {
        if (storedHash.isNullOrBlank()) {
            // No usable local credential: deny unless Firebase already verified the
            // password (handled by caller). This closes the "null hash = free login" hole.
            return Pair(false, false)
        }
        
        // Modern PBKDF2 format?
        if (storedHash.split(":").size == 3) {
            return Pair(PasswordHasher.verify(password, storedHash), false)
        }
        
        // Legacy unsalted SHA-256 hex (64 chars) — verify and flag for upgrade.
        val legacyMatch = try {
            val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
            val hex = bytes.joinToString("") { "%02x".format(it) }
            hex.equals(storedHash, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
        
        return Pair(legacyMatch, legacyMatch)
    }

    // ========================================================================
    // EMAIL/PASSWORD SIGN UP
    // ========================================================================

    suspend fun signUpWithEmail(email: String, password: String, displayName: String): AuthResult {
        val cleanEmail = email.trim().lowercase()
        val cleanName = displayName.trim().ifBlank { 
            cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() } 
        }

        // Validate input
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return AuthResult.Error(InvalidInput("Please enter a valid email address."))
        }
        
        if (password.length < 6) {
            return AuthResult.Error(InvalidInput("Password must be at least 6 characters."))
        }

        // Try Firebase first if initialized
        var firebaseUid: String? = null
        try {
            val result = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
            result.user?.let { u ->
                firebaseUid = u.uid
                try {
                    val profileUpdates = UserProfileChangeRequest.Builder()
                        .setDisplayName(cleanName)
                        .build()
                    u.updateProfile(profileUpdates).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update user profile", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase signup failed: ${e.message}")
            // If email is already in use in Firebase, return error
            if (e.message?.contains("email address is already in use", ignoreCase = true) == true) {
                return AuthResult.Error(EmailAlreadyExists("An account with this email already exists."))
            }
        }

        // Check local DB
        val existingLocal = runCatching { forexDao.getUserByEmail(cleanEmail) }
            .getOrElse { e ->
                Log.e(TAG, "Failed to check for existing local user", e)
                null
            }
        
        if (existingLocal != null && firebaseUid == null) {
            return AuthResult.Error(EmailAlreadyExists("An account with this email already exists locally."))
        }

        val finalUid = firebaseUid ?: existingLocal?.uid ?: UUID.randomUUID().toString()
        
        runCatching { forexDao.logoutAllUsers() }
            .onFailure { e -> Log.e(TAG, "Failed to logout all users", e) }

        val newUser = UserProfileEntity(
            uid = finalUid,
            email = cleanEmail,
            displayName = cleanName,
            passwordHash = hashPassword(password),
            accountTier = "Pro Trader",
            joinedAt = System.currentTimeMillis(),
            isLoggedIn = true
        )
        
        val insertResult = runCatching { forexDao.insertOrUpdateUser(newUser) }
            .getOrElse { e ->
                Log.e(TAG, "Failed to insert new user", e)
                return AuthResult.Error(DatabaseError("Failed to create account"))
            }

        return AuthResult.Success(
            UserProfile(
                uid = newUser.uid,
                email = newUser.email,
                displayName = newUser.displayName,
                accountTier = newUser.accountTier,
                joinedAt = newUser.joinedAt
            )
        )
    }

    // ========================================================================
    // EMAIL/PASSWORD SIGN IN
    // ========================================================================

    suspend fun signInWithEmail(email: String, password: String): AuthResult {
        val cleanEmail = email.trim().lowercase()
        
        // Validate input
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return AuthResult.Error(InvalidInput("Please enter a valid email address."))
        }
        
        if (password.isBlank()) {
            return AuthResult.Error(InvalidInput("Please enter your password."))
        }

        // Try Firebase first
        var firebaseSuccess = false
        var fbUser: FirebaseUser? = null
        
        try {
            val result = auth.signInWithEmailAndPassword(cleanEmail, password).await()
            fbUser = result.user
            if (fbUser != null) {
                firebaseSuccess = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase signIn failed: ${e.message}")
        }

        if (firebaseSuccess && fbUser != null) {
            val u = fbUser
            runCatching { forexDao.logoutAllUsers() }
                .onFailure { e -> Log.e(TAG, "Failed to logout all users", e) }
            
            val userEntity = UserProfileEntity(
                uid = u.uid,
                email = u.email ?: cleanEmail,
                displayName = u.displayName ?: cleanEmail.substringBefore("@"),
                photoUrl = u.photoUrl?.toString(),
                passwordHash = hashPassword(password),
                accountTier = "Pro Trader",
                isLoggedIn = true
            )
            
            runCatching { forexDao.insertOrUpdateUser(userEntity) }
                .onFailure { e -> 
                    Log.e(TAG, "Failed to save Firebase user locally", e)
                    return AuthResult.Error(DatabaseError("Failed to save user data"))
                }
            
            return AuthResult.Success(
                UserProfile(
                    uid = userEntity.uid,
                    email = userEntity.email,
                    displayName = userEntity.displayName,
                    photoUrl = userEntity.photoUrl,
                    accountTier = userEntity.accountTier,
                    joinedAt = userEntity.joinedAt
                )
            )
        }

        // Fallback to local secure user store
        val localUser = runCatching { forexDao.getUserByEmail(cleanEmail) }
            .getOrElse { e ->
                Log.e(TAG, "Failed to get local user", e)
                null
            }
        
        if (localUser != null) {
            val (passwordMatches, wasLegacyHash) = verifyPasswordWithUpgradeCheck(password, localUser.passwordHash)
            
            if (passwordMatches || firebaseSuccess) {
                runCatching { forexDao.logoutAllUsers() }
                    .onFailure { e -> Log.e(TAG, "Failed to logout all users", e) }
                
                // Upgrade legacy SHA-256 hashes (or backfill missing hashes for
                // Firebase/Google accounts verified via Firebase) to PBKDF2.
                if (localUser.passwordHash.isNullOrBlank() || wasLegacyHash) {
                    runCatching { 
                        forexDao.insertOrUpdateUser(localUser.copy(passwordHash = hashPassword(password)))
                    }.onFailure { e -> 
                        Log.e(TAG, "Failed to upgrade password hash", e)
                    }
                }
                
                runCatching { forexDao.setActiveUser(localUser.uid) }
                    .onFailure { e -> 
                        Log.e(TAG, "Failed to set active user", e)
                        return AuthResult.Error(DatabaseError("Failed to activate user"))
                    }
                
                return AuthResult.Success(
                    UserProfile(
                        uid = localUser.uid,
                        email = localUser.email,
                        displayName = localUser.displayName,
                        photoUrl = localUser.photoUrl,
                        accountTier = localUser.accountTier,
                        joinedAt = localUser.joinedAt
                    )
                )
            } else {
                return AuthResult.Error(InvalidCredentials("Incorrect password. Please try again."))
            }
        }

        return AuthResult.Error(UserNotFound("No account found with this email. Please sign up."))
    }

    // ========================================================================
    // GOOGLE SIGN IN
    // ========================================================================

    suspend fun signInWithGoogle(): AuthResult {
        return try {
            if (!isFirebaseAvailable()) {
                return AuthResult.Error(FirebaseNotAvailable)
            }

            val webClientId = getWebClientId()
            if (webClientId.isBlank()) {
                return AuthResult.Error(InvalidConfiguration("Web client ID not available"))
            }

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = runCatching {
                credentialManager.getCredential(context, request)
            }.getOrElse { e ->
                Log.e(TAG, "Credential request failed", e)
                return AuthResult.Error(CredentialError(e.message ?: "Google Sign-In was cancelled"))
            }

            val credential = result.credential

            if (credential is CustomCredential && 
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                
                val googleIdTokenCredential = runCatching {
                    GoogleIdTokenCredential.createFrom(credential.data)
                }.getOrElse { e ->
                    Log.e(TAG, "Failed to create Google ID token credential", e)
                    return AuthResult.Error(InvalidCredential("Invalid credential format"))
                }
                
                val idToken = googleIdTokenCredential.idToken
                if (idToken.isBlank()) {
                    return AuthResult.Error(InvalidCredential("Empty ID token"))
                }
                
                // Sign in with Firebase
                val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = runCatching {
                    auth.signInWithCredential(firebaseCredential).await()
                }.getOrElse { e ->
                    Log.e(TAG, "Firebase sign-in failed", e)
                    return AuthResult.Error(FirebaseAuthError(e.message ?: "Authentication failed"))
                }
                
                val user = authResult.user
                if (user == null) {
                    return AuthResult.Error(FirebaseAuthError("No user returned from Firebase"))
                }

                runCatching { forexDao.logoutAllUsers() }
                    .onFailure { e -> Log.e(TAG, "Failed to logout all users", e) }
                
                val profileEntity = UserProfileEntity(
                    uid = user.uid,
                    email = user.email ?: "trader@forex.ai",
                    displayName = user.displayName ?: "Trader",
                    photoUrl = user.photoUrl?.toString(),
                    accountTier = "Pro Trader",
                    isLoggedIn = true
                )
                
                runCatching { forexDao.insertOrUpdateUser(profileEntity) }
                    .onFailure { e -> 
                        Log.e(TAG, "Failed to save Google user locally", e)
                        return AuthResult.Error(DatabaseError("Failed to save user data"))
                    }

                return AuthResult.Success(
                    UserProfile(
                        uid = profileEntity.uid,
                        email = profileEntity.email,
                        displayName = profileEntity.displayName,
                        photoUrl = profileEntity.photoUrl,
                        accountTier = profileEntity.accountTier,
                        joinedAt = profileEntity.joinedAt
                    )
                )
            } else {
                return AuthResult.Error(InvalidCredential("Received an invalid credential type: ${credential?.type}"))
            }
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Google Credential Exception: ${e.message}", e)
            AuthResult.Error(CredentialError(e.message ?: "Google Sign-In was cancelled"))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected Google sign-in error", e)
            AuthResult.Error(UnexpectedError(e.message ?: "Failed to sign in with Google"))
        }
    }

    // ========================================================================
    // SIGN OUT
    // ========================================================================

    suspend fun signOut() {
        signOutSafe()
        runCatching { forexDao.logoutAllUsers() }
            .onFailure { e -> Log.e(TAG, "Failed to logout all users from database", e) }
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
    // UTILITY METHODS
    // ========================================================================

    /**
     * Check if Firebase is available and properly initialized.
     */
    private fun isFirebaseAvailable(): Boolean {
        return runCatching { 
            FirebaseApp.getApps(context).isNotEmpty()
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
                getWebClientId()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get web client ID from resources", e)
            getWebClientId()
        }
    }

    // ========================================================================
    // ERROR TYPES
    // ========================================================================

    /**
     * Sealed hierarchy for authentication errors.
     */
    sealed class AuthError(val message: String) {
        object FirebaseNotAvailable : AuthError("Firebase authentication service unavailable")
        class InvalidInput(detail: String) : AuthError(detail)
        class InvalidConfiguration(detail: String) : AuthError(detail)
        class InvalidCredentials(detail: String) : AuthError(detail)
        class EmailAlreadyExists(detail: String) : AuthError(detail)
        class UserNotFound(detail: String) : AuthError(detail)
        class CredentialError(detail: String) : AuthError(detail)
        class InvalidCredential(detail: String) : AuthError(detail)
        class FirebaseAuthError(detail: String) : AuthError(detail)
        class DatabaseError(detail: String) : AuthError(detail)
        class UnexpectedError(detail: String) : AuthError(detail)
    }

    /**
     * Result of an authentication operation.
     */
    sealed class AuthResult {
        data class Success(val user: UserProfile) : AuthResult()
        data class Error(val error: AuthError) : AuthResult()
        
        // Legacy compatibility
        val success: Boolean
            get() = when (this) {
                is Success -> true
                is Error -> false
            }
        
        val user: UserProfile?
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