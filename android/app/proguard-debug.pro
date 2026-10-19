# Keep debug names and APIs called by the separate instrumentation APK stable.
# proguard-android.txt disables optimization; unused dependencies can still shrink.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
-keep class io.theficos.spunto.** { *; }

# These target-owned families are referenced by the instrumentation APK and its helpers.
-keep class androidx.activity.** { *; }
-keep class androidx.collection.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.concurrent.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.room.** { *; }
-keep class androidx.sqlite.** { *; }
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep interface com.google.common.util.concurrent.ListenableFuture { *; }
