package com.devcode940.ForexAnalyst.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.devcode940.ForexAnalyst.BuildConfig
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock

/**
 * Production-ready Secure API Key Manager using Android Keystore and encrypted SharedPreferences.
 * 
 * Features:
 * - Secure storage using Android's EncryptedSharedPreferences
 * - Thread-safe operations with ReentrantLock
 * - Resource cleanup via Closeable pattern
 * - Comprehensive key validation
 * - Debug-only BuildConfig fallback with security warnings
 * - Singleton pattern with proper initialization
 * 
 * Threat model note: an API key compiled into BuildConfig is trivially extractable
 * from the APK (especially with minification disabled), so BuildConfig is treated as
 * a *developer convenience* for local debug builds only. Release builds should ship
 * with placeholder values in `.env` and let users supply their own keys through
 * [saveFcsApiKey] / [saveOpenRouterApiKey], which are stored encrypted on-device.
 * 
 * Security Best Practices:
 * - Never log API keys
 * - Use encrypted storage for all sensitive data
 * - Validate keys are not placeholders
 * - Clear keys on logout/account switching
 * - Use Closeable pattern for resource cleanup
 */
class SecureApiKeyManager(private val context: Context) : Closeable {
    
    companion object {
        private const val TAG = "SecureApiKeyManager"
        private const val SECURE_PREFS_NAME = "forex_secure_prefs_v2"
        private const val KEY_FCS_API = "fcs_api_key"
        private const val KEY_OPENROUTER_API = "openrouter_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_ALPHAVANTAGE_API = "alphavantage_api_key"
        private const val PLACEHOLDER_KEY = "YOUR_API_KEY_HERE"
        private const val DEFAULT_AI_MODEL = "google/gemini-2.5-flash"
        
        // Security constants
        private const val MAX_KEY_LENGTH = 512
        private const val MIN_KEY_LENGTH = 16
        
        @Volatile
        private var instance: SecureApiKeyManager? = null
        private val lock = Any()

        /**
         * Get singleton instance with thread-safe initialization.
         */
        fun getInstance(context: Context): SecureApiKeyManager {
            return instance ?: synchronized(lock) {
                instance ?: SecureApiKeyManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        /**
         * Reset singleton instance (for testing or app restart).
         */
        fun resetInstance() {
            synchronized(lock) {
                instance?.close()
                instance = null
            }
        }
    }
    
    // ========================================================================
    // RESOURCE MANAGEMENT
    // ========================================================================
    
    private val masterKey: MasterKey by lazy {
        try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MasterKey", e)
            throw SecurityException("Failed to initialize secure key storage", e)
        }
    }

    private val encryptedPreferences: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
            throw SecurityException("Failed to initialize secure preferences", e)
        }
    }
    
    private var isClosed = false
    private val operationLock = ReentrantLock()
    
    override fun close() {
        if (isClosed) return
        isClosed = true
        try {
            // Clear sensitive data from memory
            operationLock.lock()
            try {
                encryptedPreferences.edit().clear().apply()
                Log.i(TAG, "SecureApiKeyManager: cleared all sensitive data")
            } finally {
                operationLock.unlock()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during SecureApiKeyManager cleanup", e)
        }
    }
    
    // ========================================================================
    // KEY VALIDATION
    // ========================================================================
    
    /**
     * Check if a key is a placeholder or unset.
     */
    private fun isPlaceholderOrUnset(key: String?): Boolean {
        if (key.isNullOrBlank()) return true
        if (key == PLACEHOLDER_KEY) return true
        if (key.startsWith("YOUR_") && key.endsWith("_API_KEY")) return true
        if (key.startsWith("sk-") && key.length < MIN_KEY_LENGTH) return true
        if (key.startsWith("AIza") && key.length < MIN_KEY_LENGTH) return true
        return false
    }
    
    /**
     * Validate key meets security requirements.
     */
    private fun validateKey(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        if (isPlaceholderOrUnset(key)) return false
        if (key.length > MAX_KEY_LENGTH) return false
        if (key.length < MIN_KEY_LENGTH) return false
        return true
    }
    
    /**
     * Securely mask key for logging (show only first 4 and last 4 characters).
     */
    private fun maskKey(key: String?): String {
        if (key.isNullOrBlank()) return "null"
        if (key.length <= 8) return "****"
        return "${key.take(4)}...${key.takeLast(4)}"
    }
    
    // ========================================================================
    // BUILDCONFIG FALLBACK (DEBUG ONLY)
    // ========================================================================
    
    /**
     * Returns the BuildConfig fallback key, but ONLY for debug builds.
     * Release builds must never embed live keys in the APK.
     * 
     * WARNING: This will log a security warning if used in release builds.
     */
    private fun buildConfigFallback(buildConfigKey: String, keyName: String): String? {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "WARNING: Attempted to use BuildConfig fallback for $keyName in RELEASE build!")
            return null
        }
        
        if (isPlaceholderOrUnset(buildConfigKey)) {
            Log.d(TAG, "BuildConfig $keyName is placeholder - skipping fallback")
            return null
        }
        
        Log.d(TAG, "Using BuildConfig fallback for $keyName (DEBUG only)")
        return buildConfigKey
    }
    
    // ========================================================================
    // API KEY MANAGEMENT
    // ========================================================================
    
    /**
     * Retrieves FCS API Key from secure storage with fallback to BuildConfig.
     * Validates that key is not a placeholder value.
     */
    fun getFcsApiKey(): String? {
        if (isClosed) {
            Log.e(TAG, "SecureApiKeyManager is closed")
            return null
        }
        
        operationLock.lock()
        try {
            // First try to get from encrypted storage
            val stored = encryptedPreferences.getString(KEY_FCS_API, null)
            if (validateKey(stored)) {
                Log.d(TAG, "Retrieved FCS API key from secure storage")
                return stored
            }

            // Debug-only fallback to BuildConfig (never in release APKs).
            val debugKey = buildConfigFallback(BuildConfig.FCS_API_KEY, "FCS_API_KEY")
            if (debugKey != null) {
                saveFcsApiKey(debugKey)
                Log.d(TAG, "Used BuildConfig FCS API key fallback: ${maskKey(debugKey)}")
                return debugKey
            }
            
            Log.w(TAG, "FCS API key not available")
            return null
        } finally {
            operationLock.unlock()
        }
    }

    /**
     * Retrieves OpenRouter API Key from secure storage with fallback to BuildConfig.
     * Validates that key is not a placeholder value.
     */
    fun getOpenRouterApiKey(): String? {
        if (isClosed) {
            Log.e(TAG, "SecureApiKeyManager is closed")
            return null
        }
        
        operationLock.lock()
        try {
            // First try to get from encrypted storage
            val stored = encryptedPreferences.getString(KEY_OPENROUTER_API, null)
            if (validateKey(stored)) {
                Log.d(TAG, "Retrieved OpenRouter API key from secure storage")
                return stored
            }

            // Debug-only fallback to BuildConfig (never in release APKs).
            val debugKey = buildConfigFallback(BuildConfig.OPENROUTER_API_KEY, "OPENROUTER_API_KEY")
            if (debugKey != null) {
                saveOpenRouterApiKey(debugKey)
                Log.d(TAG, "Used BuildConfig OpenRouter API key fallback: ${maskKey(debugKey)}")
                return debugKey
            }
            
            Log.w(TAG, "OpenRouter API key not available")
            return null
        } finally {
            operationLock.unlock()
        }
    }

    /**
     * Retrieves Alpha Vantage API Key from secure storage with debug-only BuildConfig fallback.
     */
    fun getAlphaVantageApiKey(): String? {
        if (isClosed) {
            Log.e(TAG, "SecureApiKeyManager is closed")
            return null
        }
        
        operationLock.lock()
        try {
            val stored = encryptedPreferences.getString(KEY_ALPHAVANTAGE_API, null)
            if (validateKey(stored)) {
                Log.d(TAG, "Retrieved AlphaVantage API key from secure storage")
                return stored
            }
            
            val debugKey = buildConfigFallback(BuildConfig.ALPHAVANTAGE_API_KEY, "ALPHAVANTAGE_API_KEY")
            if (debugKey != null) {
                Log.d(TAG, "Used BuildConfig AlphaVantage API key fallback: ${maskKey(debugKey)}")
                return debugKey
            }
            
            Log.w(TAG, "AlphaVantage API key not available")
            return null
        } finally {
            operationLock.unlock()
        }
    }

    /**
     * Retrieves the configured AI model name, with sensible default.
     */
    fun getAiModel(): String {
        if (isClosed) {
            Log.e(TAG, "SecureApiKeyManager is closed")
            return DEFAULT_AI_MODEL
        }
        
        operationLock.lock()
        try {
            val stored = encryptedPreferences.getString(KEY_AI_MODEL, null)
            return if (!stored.isNullOrBlank()) {
                stored
            } else {
                DEFAULT_AI_MODEL
            }
        } finally {
            operationLock.unlock()
        }
    }

    // ========================================================================
    // KEY STORAGE
    // ========================================================================
    
    /**
     * Securely saves FCS API Key to encrypted SharedPreferences.
     * Validates key before saving.
     */
    fun saveFcsApiKey(apiKey: String): Boolean {
        if (isClosed) {
            Log.e(TAG, "SecureApiKeyManager is closed")
            return false
        }
        
        operationLock.lock()
        try {
            if (!validateKey(apiKey)) {
                Log.w(TAG, "Attempted to save invalid FCS API key")
                return false
            }
            
            encryptedPreferences.edit().putString(KEY_FCS_API, apiKey).apply()
            Log.d(TAG, "Saved FCS API key: ${maskKey(apiKey)}")
            return true
        } finally {
            operationLock.unlock()
        }
    }

    /**
     * Securely saves OpenRouter API Key to encrypted SharedPreferences.
     * Validates key before saving.
     */
    fun saveOpenRouterApiKey(apiKey: String): Boolean {
        if (isClosed) {
            Log.e(TAG, "SecureApiKeyManager is closed")
            return false
        }
        
        operationLock.lock()
        try {
            if (!validateKey(apiKey)) {
                Log.w(TAG, "Attempted to save invalid OpenRouter API key")
                return false
            }
            
            encryptedPreferences.edit().putString(KEY_OPENROUTER_API, apiKey).apply()
            Log.d(TAG, "Saved OpenRouter API key: ${maskKey(apiKey)}")
            return true
        } finally {
            operationLock.unlock()
        }
    }

    /**
     * Securely saves AI Model preference.
     */
    fun saveAiModel(model: String): Boolean {
        if (isClosed) {
            Log.e(TAG, "SecureApiKeyManager is closed")
            return false
        }
        
        operationLock.lock()
        try {
            if (model.isBlank()) {
                Log.w(TAG, "Attempted to save blank AI model")
                return false
            }
            
            encryptedPreferences.edit().putString(KEY_AI_MODEL, model).apply()
            Log.d(TAG, "Saved AI model: $model")
            return true
        } finally {
            operationLock.unlock()
        }
    }

    // ========================================================================
    // KEY VALIDATION & STATUS
    // ========================================================================
    
    /**
     * Checks if FCS API is properly configured (not null, not placeholder).
     */
    fun isFcsApiConfigured(): Boolean {
        val key = getFcsApiKey()
        return validateKey(key)
    }

    /**
     * Checks if OpenRouter API is properly configured (not null, not placeholder).
     */
    fun isOpenRouterApiConfigured(): Boolean {
        val key = getOpenRouterApiKey()
        return validateKey(key)
    }

    /**
     * Checks if AlphaVantage API is properly configured.
     */
    fun isAlphaVantageApiConfigured(): Boolean {
        val key = getAlphaVantageApiKey()
        return validateKey(key)
    }

    /**
     * Get configuration status for all APIs.
     */
    fun getConfigurationStatus(): ApiConfigurationStatus {
        return ApiConfigurationStatus(
            fcsConfigured = isFcsApiConfigured(),
            openRouterConfigured = isOpenRouterApiConfigured(),
            alphaVantageConfigured = isAlphaVantageApiConfigured(),
            aiModel = getAiModel()
        )
    }
    
    /**
     * Status of API configuration.
     */
    data class ApiConfigurationStatus(
        val fcsConfigured: Boolean,
        val openRouterConfigured: Boolean,
        val alphaVantageConfigured: Boolean,
        val aiModel: String
    ) {
        fun isFullyConfigured(): Boolean = 
            fcsConfigured && openRouterConfigured && alphaVantageConfigured
        
        fun getUnconfiguredApis(): List<String> {
            val unconfigured = mutableListOf<String>()
            if (!fcsConfigured) unconfigured.add("FCS")
            if (!openRouterConfigured) unconfigured.add("OpenRouter")
            if (!alphaVantageConfigured) unconfigured.add("AlphaVantage")
            return unconfigured
        }
    }

    // ========================================================================
    // KEY CLEANUP
    // ========================================================================
    
    /**
     * Clears all stored API keys (for logout or account switching).
     */
    fun clearAllKeys() {
        if (isClosed) return
        
        operationLock.lock()
        try {
            encryptedPreferences.edit().clear().apply()
            Log.i(TAG, "Cleared all API keys")
        } finally {
            operationLock.unlock()
        }
    }

    /**
     * Clears FCS API key only.
     */
    fun clearFcsApiKey() {
        if (isClosed) return
        
        operationLock.lock()
        try {
            encryptedPreferences.edit().remove(KEY_FCS_API).apply()
            Log.i(TAG, "Cleared FCS API key")
        } finally {
            operationLock.unlock()
        }
    }

    /**
     * Clears OpenRouter API key only.
     */
    fun clearOpenRouterApiKey() {
        if (isClosed) return
        
        operationLock.lock()
        try {
            encryptedPreferences.edit().remove(KEY_OPENROUTER_API).apply()
            Log.i(TAG, "Cleared OpenRouter API key")
        } finally {
            operationLock.unlock()
        }
    }
}
