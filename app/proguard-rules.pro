# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Room entities and DAOs
-keep @androidx.room.Entity class *
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Dao interface *
-keepclassmembers class * {
    @androidx.room.PrimaryKey *;
    @androidx.room.ColumnInfo *;
}

# Hilt
-keep @dagger.hilt.android.lifecycle.HiltViewModel class *
-keep @javax.inject.Inject class *
-keep class * extends androidx.lifecycle.ViewModel

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# WorkManager
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Health Connect
-keep class androidx.health.connect.client.records.** { *; }

# Vico Charts
-keep class com.patrykandpatrick.vico.** { *; }

# Domain Scoring Components (Issue #3.2)
# Keep these classes and their fields from obfuscation to ensure hash stability
# CRITICAL: These are used for audit trail hashing and must not be renamed

-keep class com.gregor.lauritz.healthdashboard.domain.scoring.components.RestorationWeights {
    *** *;
}

-keep class com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargets {
    *** *;
}

-keep class com.gregor.lauritz.healthdashboard.domain.scoring.components.EmergencyFlagThresholds {
    *** *;
}

-keep class com.gregor.lauritz.healthdashboard.domain.scoring.components.CircadianConsistencyConfig {
    *** *;
}

-keep class com.gregor.lauritz.healthdashboard.domain.scoring.components.AuditTrail {
    *** *;
}

# Keep field names (required for hash computation)
-keepclassmembers class com.gregor.lauritz.healthdashboard.domain.scoring.components.** {
    *** *;
}
