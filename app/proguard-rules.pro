# Remove Log.d/v in release (no side effects, R8 removes as dead code)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Add project specific ProGuard rules here.

# Keep Application class (referenced in manifest)
-keep class com.deatrg.dnsfilter.DnsFilterApplication { *; }

# Keep ServiceLocator
-keep class com.deatrg.dnsfilter.ServiceLocator { *; }

# Keep VPN Service (referenced in manifest)
-keep class com.deatrg.dnsfilter.service.DnsVpnService { *; }

# Keep domain model classes
-keep class com.deatrg.dnsfilter.domain.model.** { *; }

# Keep DataStore
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }

# WorkManager (R8 removes WorkDatabase class needed at runtime)
-keep class androidx.work.impl.** { *; }
-keep class androidx.work.WorkerFactory { *; }
-keep class androidx.work.impl.workers.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Kotlinx Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}