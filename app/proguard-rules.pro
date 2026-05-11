# HydroLock ProGuard Rules

# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep Room entities
-keep class com.hydrolock.data.database.entities.** { *; }

# Keep Hilt generated classes
-keep class * extends dagger.hilt.android.internal.managers.** { *; }

# Keep WorkManager workers
-keep class com.hydrolock.workers.** { *; }

# Keep accessibility service
-keep class com.hydrolock.services.HydroAccessibilityService { *; }

# CameraX
-keep class androidx.camera.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
