package com.example.forex.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.BuildConfig

/**
 * Secure API Key Manager using Android Keystore and encrypted SharedPreferences.
 * Prevents hardcoded API keys and provides secure storage for sensitive credentials.
 *
 * Threat model note: an API key compiled into BuildConfig is trivially extractable
 * from the APK (especially with minification disabled), so BuildConfig is treated as
 * a *developer convenience* for local debug builds only. Release builds should ship
 * with placeholder values in `.env` and let users supply their own keys through
 * [saveFcsApiKey] / [saveOpenRouterApiKey], which are stored encrypted on-device.
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

    private fun isPlaceholderOrUnset(key: String): Boolean =
        key.isBlank() || key == PLACEHOLDER_KEY ||
            key.startsWith("YOUR_") || key.endsWith("_API_KEY")

    /**
     * Returns the BuildConfig fallback key, but ONLY for debug builds. Release builds
     * must never embed live keys in the APK; they rely on user-supplied keys stored
     * in encrypted preferences.
     */
    private fun buildConfigFallback(buildConfigKey: String): String? =
        if (BuildConfig.DEBUG && !isPlaceholderOrUnset(buildConfigKey)) buildConfigKey else null

    /**
     * Retrieves FCS API Key from secure storage with fallback to BuildConfig.
     * Validates that key is not a placeholder value.
     */
    fun getFcsApiKey(): String? {
        // First try to get from encrypted storage
        val stored = encryptedPreferences.getString(KEY_FCS_API, null)
        if (!stored.isNullOrBlank() && !isPlaceholderOrUnset(stored)) {
            return stored
        }

        // Debug-only fallback to BuildConfig (never in release APKs).
        val debugKey = buildConfigFallback(BuildConfig.FCS_API_KEY) ?: return null
        saveFcsApiKey(debugKey)
        return debugKey
    }

    /**
     * Retrieves OpenRouter API Key from secure storage with fallback to BuildConfig.
     * Validates that key is not a placeholder value.
     */
    fun getOpenRouterApiKey(): String? {
        // First try to get from encrypted storage
        val stored = encryptedPreferences.getString(KEY_OPENROUTER_API, null)
        if (!stored.isNullOrBlank() && !isPlaceholderOrUnset(stored)) {
            return stored
        }

        // Debug-only fallback to BuildConfig (never in release APKs).
        val debugKey = buildConfigFallback(BuildConfig.OPENROUTER_API_KEY) ?: return null
        saveOpenRouterApiKey(debugKey)
        return debugKey
    }

    /**
     * Retrieves Alpha Vantage API Key from secure storage with debug-only BuildConfig fallback.
     */
    fun getAlphaVantageApiKey(): String? {
        val stored = encryptedPreferences.getString(KEY_ALPHAVANTAGE_API, null)
        if (!stored.isNullOrBlank() && !isPlaceholderOrUnset(stored)) {
            return stored
        }
        return buildConfigFallback(BuildConfig.ALPHAVANTAGE_API_KEY)
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
        if (!apiKey.isBlank() && !isPlaceholderOrUnset(apiKey)) {
            encryptedPreferences.edit().putString(KEY_FCS_API, apiKey).apply()
        }
    }

    /**
     * Securely saves OpenRouter API Key to encrypted SharedPreferences.
     */
    fun saveOpenRouterApiKey(apiKey: String) {
        if (!apiKey.isBlank() && !isPlaceholderOrUnset(apiKey)) {
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
        return !key.isNullOrBlank() && !isPlaceholderOrUnset(key)
    }

    /**
     * Checks if OpenRouter API is properly configured (not null, not placeholder).
     */
    fun isOpenRouterApiConfigured(): Boolean {
        val key = getOpenRouterApiKey()
        return !key.isNullOrBlank() && !isPlaceholderOrUnset(key)
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
        private const val KEY_ALPHAVANTAGE_API = "alphavantage_api_key"
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
