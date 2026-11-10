package com.devcode940.ForexAnalyst

import android.app.Application
import android.util.Log
import com.devcode940.ForexAnalyst.data.utils.CrashReporter
import com.devcode940.ForexAnalyst.data.utils.Logger
import com.devcode940.ForexAnalyst.data.utils.RetryUtils
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Forex Application class for global initialization.
 * 
 * Features:
 * - Firebase initialization
 * - Crash reporting setup
 * - Logger configuration
 * - Retry configuration
 * - Application lifecycle management
 */
class ForexApplication : Application() {
    
    companion object {
        private const val TAG = "ForexApplication"
        
        @JvmStatic
        private var instance: ForexApplication? = null
        
        /**
         * Get the application instance.
         */
        fun getInstance(): ForexApplication? = instance
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        Log.i(TAG, "ForexApplication starting")
        
        // Initialize Firebase
        initializeFirebase()
        
        // Initialize Crash Reporter
        initializeCrashReporter()
        
        // Configure Logger
        configureLogger()
        
        // Configure Retry settings
        configureRetry()
        
        Log.i(TAG, "ForexApplication initialized")
    }
    
    /**
     * Initialize Firebase services.
     */
    private fun initializeFirebase() {
        try {
            FirebaseApp.initializeApp(this)
            Log.i(TAG, "Firebase initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase", e)
            // Firebase may already be initialized
        }
    }
    
    /**
     * Initialize Crash Reporter.
     */
    private fun initializeCrashReporter() {
        try {
            CrashReporter.initialize(this)
            
            // Set application version info
            try {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                CrashReporter.setCustomKey("app_version", packageInfo.versionName)
                CrashReporter.setCustomKey("app_version_code", packageInfo.versionCode)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get package info for crash reporting", e)
            }
            
            Log.i(TAG, "CrashReporter initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CrashReporter", e)
        }
    }
    
    /**
     * Configure Logger settings.
     */
    private fun configureLogger() {
        Logger.minLogLevel = if (com.devcode940.ForexAnalyst.BuildConfig.DEBUG) {
            Logger.Level.DEBUG
        } else {
            Logger.Level.INFO
        }
        
        Logger.isPerformanceLoggingEnabled = true
        Logger.includeTimestamps = true
        Logger.includeThreadInfo = true
        
        Log.i(TAG, "Logger configured with level: ${Logger.minLogLevel}")
    }
    
    /**
     * Configure Retry settings.
     */
    private fun configureRetry() {
        // Default retry configuration is already good
        // You can customize it here if needed
        
        Log.i(TAG, "Retry configuration set to defaults")
    }
    
    /**
     * Get the application context.
     */
    fun getAppContext(): ForexApplication = this
    
    /**
     * Record a custom event for analytics and crash reporting.
     */
    fun recordEvent(name: String, attributes: Map<String, Any>? = null) {
        CrashReporter.recordEvent(name, attributes)
        Logger.i("Application event: $name", context = attributes)
    }
    
    /**
     * Add a breadcrumb for debugging.
     */
    fun addBreadcrumb(message: String) {
        CrashReporter.addBreadcrumb(message)
        Logger.d("Breadcrumb: $message")
    }
    
    /**
     * Report an exception.
     */
    fun reportException(throwable: Throwable, message: String? = null) {
        CrashReporter.report(throwable, message)
        Logger.e("Application exception", throwable)
    }
    
    /**
     * Set a custom key for crash reporting.
     */
    fun setCustomKey(key: String, value: Any) {
        CrashReporter.setCustomKey(key, value)
        Logger.d("Set custom key: $key")
    }
}
