package com.example.forex.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.BuildConfig

/**
 * Secure API Key Manager using Android Keystore and encrypted SharedPreferences.
 * Prevents hardcoded API keys and provides secure storage for sensitive credentials.
 */
class SecureApiKeyManager(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPreferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Retrieves FCS API Key from secure storage with fallback to BuildConfig.
     * Validates that key is not a placeholder value.
     */
    fun getFcsApiKey(): String? {
        // First try to get from encrypted storage
        val stored = encryptedPreferences.getString(KEY_FCS_API, null)
        if (!stored.isNullOrBlank() && stored != PLACEHOLDER_KEY) {
            return stored
        }

        // Fallback to BuildConfig if set
        val buildConfigKey = BuildConfig.FCS_API_KEY
        if (buildConfigKey.isNotBlank() && buildConfigKey != PLACEHOLDER_KEY) {
            // Save it to encrypted storage for future use
            saveFcsApiKey(buildConfigKey)
            return buildConfigKey
        }

        return null
    }

    /**
     * Retrieves OpenRouter API Key from secure storage with fallback to BuildConfig.
     * Validates that key is not a placeholder value.
     */
    fun getOpenRouterApiKey(): String? {
        // First try to get from encrypted storage
        val stored = encryptedPreferences.getString(KEY_OPENROUTER_API, null)
        if (!stored.isNullOrBlank() && stored != PLACEHOLDER_KEY) {
            return stored
        }

        // Fallback to BuildConfig if set
        val buildConfigKey = BuildConfig.OPENROUTER_API_KEY
        if (buildConfigKey.isNotBlank() && buildConfigKey != PLACEHOLDER_KEY) {
            // Save it to encrypted storage for future use
            saveOpenRouterApiKey(buildConfigKey)
            return buildConfigKey
        }

        return null
    }

    /**
     * Retrieves the configured AI model name, with sensible default.
     */
    fun getAiModel(): String {
        val stored = encryptedPreferences.getString(KEY_AI_MODEL, null)
        return if (!stored.isNullOrBlank()) {
            stored
        } else {
            DEFAULT_AI_MODEL
        }
    }

    /**
     * Securely saves FCS API Key to encrypted SharedPreferences.
     */
    fun saveFcsApiKey(apiKey: String) {
        if (apiKey.isNotBlank() && apiKey != PLACEHOLDER_KEY) {
            encryptedPreferences.edit().putString(KEY_FCS_API, apiKey).apply()
        }
    }

    /**
     * Securely saves OpenRouter API Key to encrypted SharedPreferences.
     */
    fun saveOpenRouterApiKey(apiKey: String) {
        if (apiKey.isNotBlank() && apiKey != PLACEHOLDER_KEY) {
            encryptedPreferences.edit().putString(KEY_OPENROUTER_API, apiKey).apply()
        }
    }

    /**
     * Securely saves AI Model preference.
     */
    fun saveAiModel(model: String) {
        if (model.isNotBlank()) {
            encryptedPreferences.edit().putString(KEY_AI_MODEL, model).apply()
        }
    }

    /**
     * Checks if FCS API is properly configured (not null, not placeholder).
     */
    fun isFcsApiConfigured(): Boolean {
        val key = getFcsApiKey()
        return !key.isNullOrBlank() && key != PLACEHOLDER_KEY
    }

    /**
     * Checks if OpenRouter API is properly configured (not null, not placeholder).
     */
    fun isOpenRouterApiConfigured(): Boolean {
        val key = getOpenRouterApiKey()
        return !key.isNullOrBlank() && key != PLACEHOLDER_KEY
    }

    /**
     * Clears all stored API keys (for logout or account switching).
     */
    fun clearAllKeys() {
        encryptedPreferences.edit().clear().apply()
    }

    /**
     * Clears FCS API key only.
     */
    fun clearFcsApiKey() {
        encryptedPreferences.edit().remove(KEY_FCS_API).apply()
    }

    /**
     * Clears OpenRouter API key only.
     */
    fun clearOpenRouterApiKey() {
        encryptedPreferences.edit().remove(KEY_OPENROUTER_API).apply()
    }

    companion object {
        private const val SECURE_PREFS_NAME = "forex_secure_prefs"
        private const val KEY_FCS_API = "fcs_api_key"
        private const val KEY_OPENROUTER_API = "openrouter_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val PLACEHOLDER_KEY = "YOUR_API_KEY_HERE"
        private const val DEFAULT_AI_MODEL = "google/gemini-2.5-flash"

        @Volatile
        private var instance: SecureApiKeyManager? = null

        fun getInstance(context: Context): SecureApiKeyManager {
            return instance ?: synchronized(this) {
                SecureApiKeyManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
