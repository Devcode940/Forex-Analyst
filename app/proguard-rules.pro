# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Security hardening (Phase 3) ---
# R8 full mode + shrinking for release builds.
# Moshi reflection-based adapters (KotlinJsonAdapterFactory) need model classes kept.
-keep class com.example.forex.data.repository.**Dto* { *; }
-keep class com.example.forex.data.remote.**Dto* { *; }
-dontwarn com.squareup.moshi.**
-dontwarn org.bouncycastle.**
-dontwarn javax.security.**
-dontwarn java.lang.management.**

# Keep Retrofit service interfaces (methods are parsed reflectively by Retrofit).
-keep,allowobfuscation interface com.example.forex.data.repository.FcsApi
-keep,allowobfuscation interface com.example.forex.data.remote.AlphaVantageApi

# Moshi KotlinJsonAdapterFactory uses reflection/kotlin-reflect: keep data models.
-keep class com.example.forex.data.model.** { *; }
-keepclassmembers class com.example.forex.data.repository.** { *; }
-keepclassmembers class com.example.forex.data.remote.** { *; }
# Room entities/DAOs are compiled (no reflection), no keeps needed.
