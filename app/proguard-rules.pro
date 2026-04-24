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
