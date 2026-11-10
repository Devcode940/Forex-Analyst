# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ========================================================================
# SECURITY HARDENING - PRODUCTION RULES
# ========================================================================

# Enable full R8 optimization for release builds
# This provides better tree-shaking and optimization
-optimizations !code/shrinking/*,!code/allocation/vector/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontskipnonpubliclibraryclassmembers

# ========================================================================
# DEBUGGING & STACK TRACES
# ========================================================================

# Preserve line numbers and source file information for better stack traces
# This is important for crash reporting and debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep stack traces readable
-keepclassmembers class * extends java.lang.Throwable {
    public <init>(...);
}

# ========================================================================
# ANDROID & KOTLIN SPECIFIC
# ========================================================================

# Keep all Android components (Activities, Services, etc.)
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application
-keep public class * extends android.app.backup.BackupAgent

# Keep all View classes
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Keep all Parcelable classes
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }
-keep class kotlin.internal.* { *; }
-keep class kotlinx.** { *; }

# ========================================================================
# FIREBASE
# ========================================================================

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Firebase Auth
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Firebase Crashlytics
-keep class com.google.firebase.crashlytics.** { *; }
-keep class com.crashlytics.** { *; }
-dontwarn com.google.firebase.crashlytics.**
-dontwarn com.crashlytics.**

# ========================================================================
# NETWORKING
# ========================================================================

# Retrofit - Keep service interfaces and their method signatures
-keep,allowobfuscation interface com.example.forex.data.repository.FcsApi
-keep,allowobfuscation interface com.example.forex.data.remote.AlphaVantageApi
-keep,allowobfuscation interface com.example.forex.data.remote.**

# Keep Retrofit response classes
-keep class com.example.forex.data.remote.** { *; }
-keep class com.example.forex.data.repository.** { *; }

# OkHttp
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Moshi - Keep models for JSON parsing
-keep class com.example.forex.data.model.** { *; }
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.Json class *
-dontwarn com.squareup.moshi.**

# ========================================================================
# ROOM DATABASE
# ========================================================================

# Room entities and DAOs
-keep class com.example.forex.data.db.** { *; }
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.Database
-keep class * extends androidx.room.Entity
-keep class * extends androidx.room.Dao
-dontwarn androidx.room.**

# ========================================================================
# WORKMANAGER
# ========================================================================

# Keep Worker classes
-keep class com.example.forex.worker.** { *; }
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker
-dontwarn androidx.work.**

# ========================================================================
# COMPOSE
# ========================================================================

# Keep Compose functions
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material.** { *; }
-dontwarn androidx.compose.**

# Keep Composable functions
-keep class * implements androidx.compose.runtime.Composable
-keep @androidx.compose.runtime.Composable fun *

# ========================================================================
# COROUTINES
# ========================================================================

# Keep coroutine classes
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ========================================================================
# CREDENTIALS & SECURITY
# ========================================================================

# Keep credentials classes
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**

# Security utilities
-keep class com.example.forex.data.security.** { *; }
-keep class com.example.forex.data.utils.** { *; }

# ========================================================================
# DATA MODELS & REPOSITORIES
# ========================================================================

# Keep all data models
-keep class com.example.forex.data.model.** { *; }

# Keep repository classes
-keep class com.example.forex.data.repository.** { *; }

# Keep validation classes
-keep class com.example.forex.data.validation.** { *; }

# ========================================================================
# VIEWMODELS & UI
# ========================================================================

# Keep ViewModel classes
-keep class com.example.forex.ui.viewmodel.** { *; }
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# Keep UI components
-keep class com.example.forex.ui.components.** { *; }
-keep class com.example.forex.ui.screens.** { *; }
-keep class com.example.forex.ui.theme.** { *; }

# ========================================================================
# UTILITY CLASSES
# ========================================================================

# Keep utility classes
-keep class com.example.forex.data.utils.** { *; }

# ========================================================================
# DON'T WARN RULES
# ========================================================================

# Don't warn about various libraries
-dontwarn com.squareup.moshi.**
-dontwarn org.bouncycastle.**
-dontwarn javax.security.**
-dontwarn java.lang.management.**
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**

# ========================================================================
# OPTIMIZATION RULES
# ========================================================================

# Allow optimization of synthetic classes
-allowobfuscation class *$* { *; }
-allowobfuscation class **$* { *; }

# Remove unused code
-shrinkresources

# ========================================================================
# KEEP FOR DEBUGGING (OPTIONAL)
# ========================================================================

# Uncomment to keep all classes for debugging
# -keep class * { *; }

# Uncomment to keep all method names
# -keepclassmembers class * { *; }
