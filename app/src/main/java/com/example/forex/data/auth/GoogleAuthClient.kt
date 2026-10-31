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

class GoogleAuthClient(private val context: Context) {
    
    private val auth: FirebaseAuth? by lazy {
        try {
            FirebaseAuth.getInstance()
        } catch (e: IllegalStateException) {
            try {
                FirebaseApp.initializeApp(context)
                FirebaseAuth.getInstance()
            } catch (e2: Exception) {
                try {
                    val options = FirebaseOptions.Builder()
                        .setApplicationId("1:1234567890:android:abcdef1234567890")
                        .setApiKey("dummy_api_key_for_firebase")
                        .setProjectId("dummy-project")
                        .build()
                    FirebaseApp.initializeApp(context, options)
                    FirebaseAuth.getInstance()
                } catch (e3: Exception) {
                    null
                }
            }
        }
    }
    
    private val credentialManager = CredentialManager.create(context)

    fun getSignedInUser(): FirebaseUser? {
        return try {
            auth?.currentUser
        } catch (e: Exception) {
            null
        }
    }

    suspend fun signIn(): SignInResult {
        return try {
            if (auth == null) {
                return SignInResult(data = null, errorMessage = "Firebase not initialized")
            }
            
            // Note: you should ideally use the Web Client ID from Firebase Console.
            // When google-services.json is present, it's generated as R.string.default_web_client_id.
            // If it's missing, this dummy string avoids compilation errors, but Google Sign-In will fail at runtime.
            val webClientId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
                .let { if (it != 0) context.getString(it) else "YOUR_WEB_CLIENT_ID" }

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context, request)
            val credential = result.credential

            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                
                val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth!!.signInWithCredential(firebaseCredential).await()
                
                SignInResult(
                    data = authResult.user,
                    errorMessage = null
                )
            } else {
                SignInResult(data = null, errorMessage = "Received an invalid credential type")
            }
        } catch (e: GetCredentialException) {
            Log.e("GoogleAuthClient", "Credential Exception: ${e.message}")
            SignInResult(data = null, errorMessage = e.message)
        } catch (e: Exception) {
            Log.e("GoogleAuthClient", "Sign-in Exception: ${e.message}")
            SignInResult(data = null, errorMessage = e.message)
        }
    }

    fun signOut() {
        try {
            auth?.signOut()
        } catch (e: Exception) {
            Log.e("GoogleAuthClient", "Sign-out Exception: ${e.message}")
        }
    }
}

data class SignInResult(
    val data: FirebaseUser?,
    val errorMessage: String?
)
