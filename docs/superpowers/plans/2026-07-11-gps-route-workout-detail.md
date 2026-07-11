# GPS Route and Performance Details Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the workout detail screen with unit-system-aware average pace/speed, elevation gain, offline GPS route canvas visualizations, and Vico charts for pace/speed and elevation profiles.

**Architecture:** Approach A: A dedicated normalized `workout_route_points` SQLite table is created to store GPS route points with a cascade delete relationship on `workout_records`. Routes are ingested in the foreground and processed using pure Kotlin algorithms (projection, Douglas-Peucker simplification, threshold elevation gain filtering, and pace interpolation).

**Tech Stack:** Kotlin, Compose (M3), Room DB, Vico Charts (v3.2.3), Jetpack Health Connect (v1.1.0)

## Global Constraints

- Target SDK: 37, Min SDK: 26.
- Database: Room version increment from 5 to 6.
- Inverted pace chart (faster pace at the top).
- GPS noise altitude filter: 3-meter threshold.
- No network map tiles (Canvas-only offline contour).
- Exact coordinates must never appear in logs or telemetry.
- All user-facing strings must use Android string resources from `strings.xml`.

---

### Task 1: Health Connect Permissions & Manifest Updates

**Files:**
- Modify: `core/healthconnect/src/main/AndroidManifest.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt`

**Interfaces:**
- Consumes: None
- Produces: Updated list of Health Connect permissions in `HealthConnectRepository` including `android.permission.health.READ_EXERCISE_ROUTES`.

- [ ] **Step 1: Declare permissions in manifest**

Add the permission declaration to `core/healthconnect/src/main/AndroidManifest.xml` and `app/src/main/AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.health.READ_EXERCISE_ROUTES" />
```

- [ ] **Step 2: Add permission to HealthConnectRepositoryImpl**

Update `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt` to append the permission to `optionalPermissions`:
```kotlin
override val optionalPermissions: Set<String> =
    setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        "android.permission.health.READ_EXERCISE_ROUTES"
    )
```

- [ ] **Step 3: Run project build to verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/healthconnect/src/main/AndroidManifest.xml app/src/main/AndroidManifest.xml core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt
git commit -m "feat: add READ_EXERCISE_ROUTES Health Connect permission"
```

---

### Task 2: Database Schema & Migration (5 to 6)

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/WorkoutRoutePointEntity.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/WorkoutRoutePointDao.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/local/DatabaseMigrationTest.kt`

**Interfaces:**
- Consumes: None
- Produces: `WorkoutRoutePointEntity`, `WorkoutRoutePointDao`, database schema v6, and `MIGRATION_5_6`.

- [ ] **Step 1: Create WorkoutRoutePointEntity**

Create `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/WorkoutRoutePointEntity.kt`:
```kotlin
package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workout_route_points",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["workoutId", "timestampMs"])]
)
data class WorkoutRoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val timestampMs: Long,
    val horizontalAccuracy: Float?,
    val verticalAccuracy: Float?
)
```

- [ ] **Step 2: Create WorkoutRoutePointDao**

Create `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/WorkoutRoutePointDao.kt`:
```kotlin
package app.readylytics.health.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.data.local.entity.WorkoutRoutePointEntity

@Dao
interface WorkoutRoutePointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<WorkoutRoutePointEntity>)

    @Query("SELECT * FROM workout_route_points WHERE workoutId = :workoutId ORDER BY timestampMs ASC")
    suspend fun getRoutePoints(workoutId: String): List<WorkoutRoutePointEntity>

    @Query("DELETE FROM workout_route_points WHERE workoutId = :workoutId")
    suspend fun deleteByWorkoutId(workoutId: String): Int
}
```

- [ ] **Step 3: Modify WorkoutRecordEntity to add new fields**

Update `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/WorkoutRecordEntity.kt`:
```kotlin
data class WorkoutRecordEntity(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long,
    val exerciseType: String,
    val durationMinutes: Int,
    val zone1Minutes: Float,
    val zone2Minutes: Float,
    val zone3Minutes: Float,
    val zone4Minutes: Float,
    val zone5Minutes: Float,
    val trimp: Float,
    val avgHr: Float,
    val deviceName: String? = null,
    val routeState: String = "NOT_AVAILABLE",
    val avgSpeedKmh: Float? = null,
    val avgPaceMinKm: Float? = null,
    val elevationGainMeters: Float? = null,
    val totalDistanceMeters: Float? = null
)
```

- [ ] **Step 4: Update HealthDatabase configuration**

In `core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt`:
- Add `WorkoutRoutePointEntity::class` to the `@Database` entities list.
- Add `abstract fun workoutRoutePointDao(): WorkoutRoutePointDao` to the class body.
- Increment `DATABASE_VERSION` to `6`.

- [ ] **Step 5: Write MIGRATION_5_6**

In `core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt`:
- Add the `MIGRATION_5_6` object:
```kotlin
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `workout_route_points` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `workoutId` TEXT NOT NULL, 
                `latitude` REAL NOT NULL, 
                `longitude` REAL NOT NULL, 
                `altitude` REAL, 
                `timestampMs` INTEGER NOT NULL, 
                `horizontalAccuracy` REAL, 
                `verticalAccuracy` REAL,
                FOREIGN KEY(`workoutId`) REFERENCES `workout_records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_route_points_workoutId_timestampMs` ON `workout_route_points` (`workoutId`, `timestampMs`)")
        db.execSQL("ALTER TABLE `workout_records` ADD COLUMN `routeState` TEXT NOT NULL DEFAULT 'NOT_AVAILABLE'")
        db.execSQL("ALTER TABLE `workout_records` ADD COLUMN `avgSpeedKmh` REAL")
        db.execSQL("ALTER TABLE `workout_records` ADD COLUMN `avgPaceMinKm` REAL")
        db.execSQL("ALTER TABLE `workout_records` ADD COLUMN `elevationGainMeters` REAL")
        db.execSQL("ALTER TABLE `workout_records` ADD COLUMN `totalDistanceMeters` REAL")
    }
}
```
- Append it to the `all` array: `MIGRATION_5_6` inside `DatabaseMigrations.kt`.

- [ ] **Step 6: Add migration tests**

Open `app/src/test/kotlin/app/readylytics/health/data/local/DatabaseMigrationTest.kt` (or create if missing) and verify migration 5 to 6 updates structure correctly:
```kotlin
@Test
fun migrate5To6_verifiesTablesCreatedAndFieldsAdded() {
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HealthDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )
    
    // Create DB with version 5
    var db = helper.createDatabase("test-db", 5)
    db.execSQL("INSERT INTO workout_records (id, startTime, endTime, exerciseType, durationMinutes, zone1Minutes, zone2Minutes, zone3Minutes, zone4Minutes, zone5Minutes, trimp, avgHr) VALUES ('w1', 1000, 2000, '56', 15, 0, 0, 0, 0, 0, 0, 0)")
    db.close()
    
    // Run migration 5 to 6
    db = helper.runMigrationsAndValidate("test-db", 6, true, DatabaseMigrations.MIGRATION_5_6)
    
    // Query to verify new fields
    val cursor = db.query("SELECT routeState, avgSpeedKmh FROM workout_records WHERE id = 'w1'")
    assertTrue(cursor.moveToFirst())
    assertEquals("NOT_AVAILABLE", cursor.getString(0))
    assertTrue(cursor.isNull(1))
    cursor.close()
    db.close()
}
```

- [ ] **Step 7: Run migration tests**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.data.local.DatabaseMigrationTest"`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/data/local/entity/WorkoutRoutePointEntity.kt core/model/src/main/kotlin/app/readylytics/health/data/local/dao/WorkoutRoutePointDao.kt core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt app/src/test/kotlin/app/readylytics/health/data/local/DatabaseMigrationTest.kt
git commit -m "feat: implement database v6 schema and migration 5 to 6"
```

---

### Task 3: Ingestion & Health Connect Mapping Updates

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/model/HealthConnectRecords.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/repository/HealthConnectRepository.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt`

**Interfaces:**
- Consumes: Health Connect SDK libraries
- Produces: `readExerciseRoute` in `HealthConnectRepository` and mapped domain route classes.

- [ ] **Step 1: Add Domain route point classes**

Add to `core/model/src/main/kotlin/app/readylytics/health/domain/model/HealthConnectRecords.kt`:
```kotlin
data class DomainRoutePoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val timestampMs: Long,
    val horizontalAccuracy: Float?,
    val verticalAccuracy: Float?
)

data class DomainExerciseRoute(
    val workoutId: String,
    val points: List<DomainRoutePoint>
)
```

- [ ] **Step 2: Add readExerciseRoute declaration to HealthConnectRepository interface**

Update `core/model/src/main/kotlin/app/readylytics/health/domain/repository/HealthConnectRepository.kt`:
```kotlin
interface HealthConnectRepository {
    // ... existing ...
    suspend fun readExerciseRoute(sessionId: String): DomainExerciseRoute?
}
```

- [ ] **Step 3: Implement readExerciseRoute in HealthConnectRepositoryImpl**

Update `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt`:
```kotlin
override suspend fun readExerciseRoute(sessionId: String): DomainExerciseRoute? =
    withContext(ioDispatcher) {
        try {
            val response = client.getExerciseRoute(sessionId)
            val domainPoints = response?.route?.points?.map { point ->
                DomainRoutePoint(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    altitude = point.altitude?.inMeters,
                    timestampMs = point.time.toEpochMilli(),
                    horizontalAccuracy = point.horizontalAccuracy?.inMeters?.toFloat(),
                    verticalAccuracy = point.verticalAccuracy?.inMeters?.toFloat()
                )
            } ?: emptyList()
            if (domainPoints.isEmpty()) null else DomainExerciseRoute(sessionId, domainPoints)
        } catch (e: SecurityException) {
            // Permission not granted or revoked
            null
        } catch (e: Exception) {
            // Fallback for general errors
            null
        }
    }
```

- [ ] **Step 4: Write testing assertions for coordinate fetching**

Verify inside `HealthConnectRepositoryImplTest.kt` using mock Health Connect clients.
Run: `./gradlew :core:healthconnect:testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/domain/model/HealthConnectRecords.kt core/model/src/main/kotlin/app/readylytics/health/domain/repository/HealthConnectRepository.kt core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt
git commit -m "feat: implement readExerciseRoute in HealthConnectRepository"
```

---

### Task 4: Pure Kotlin Domain Algorithms

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/util/RouteProjector.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/util/RouteSimplifier.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/util/ElevationGainCalculator.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/util/PaceSpeedCalculator.kt`
- Test: `core/model/src/test/kotlin/app/readylytics/health/domain/util/RouteProjectorTest.kt`
- Test: `core/model/src/test/kotlin/app/readylytics/health/domain/util/RouteSimplifierTest.kt`
- Test: `core/model/src/test/kotlin/app/readylytics/health/domain/util/ElevationGainCalculatorTest.kt`
- Test: `core/model/src/test/kotlin/app/readylytics/health/domain/util/PaceSpeedCalculatorTest.kt`

**Interfaces:**
- Consumes: Domain coordinate records
- Produces: Projected, simplified 2D routes, and smoothed altitude/pace indicators.

- [ ] **Step 1: Create RouteProjector.kt**

Create `core/model/src/main/kotlin/app/readylytics/health/domain/util/RouteProjector.kt`:
```kotlin
package app.readylytics.health.domain.util

import kotlin.math.cos

data class ProjectedPoint(
    val x: Double,
    val y: Double,
    val altitude: Double?,
    val timestampMs: Long
)

object RouteProjector {
    fun project(latitudes: DoubleArray, longitudes: DoubleArray, altitudes: DoubleArray?, timestamps: LongArray): List<ProjectedPoint> {
        if (latitudes.isEmpty()) return emptyList()
        val latCenter = latitudes.average()
        val radLatCenter = Math.toRadians(latCenter)
        val cosLat = cos(radLatCenter)

        return latitudes.indices.map { i ->
            ProjectedPoint(
                x = longitudes[i] * cosLat,
                y = latitudes[i],
                altitude = altitudes?.getOrNull(i),
                timestampMs = timestamps[i]
            )
        }
    }
}
```

- [ ] **Step 2: Create RouteSimplifier.kt (Douglas-Peucker)**

Create `core/model/src/main/kotlin/app/readylytics/health/domain/util/RouteSimplifier.kt`:
```kotlin
package app.readylytics.health.domain.util

import kotlin.math.abs
import kotlin.math.sqrt

object RouteSimplifier {
    fun simplify(points: List<ProjectedPoint>, maxPoints: Int, tolerance: Double = 0.00005): List<ProjectedPoint> {
        if (points.size <= maxPoints) return points
        val keep = BooleanArray(points.size) { false }
        keep[0] = true
        keep[points.lastIndex] = true
        
        simplifyStep(points, 0, points.lastIndex, tolerance, keep)
        
        val kept = points.filterIndexed { idx, _ -> keep[idx] }
        if (kept.size > maxPoints) {
            // Hard downsampling fallback
            val step = kept.size.toDouble() / maxPoints
            return List(maxPoints) { i -> kept[(i * step).toInt().coerceIn(kept.indices)] }
        }
        return kept
    }

    private fun simplifyStep(points: List<ProjectedPoint>, start: Int, end: Int, tolerance: Double, keep: BooleanArray) {
        if (end <= start + 1) return
        var maxDist = 0.0
        var maxIdx = start

        val p1 = points[start]
        val p2 = points[end]
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val length = sqrt(dx * dx + dy * dy)

        for (i in (start + 1) until end) {
            val p = points[i]
            val dist = if (length == 0.0) {
                sqrt((p.x - p1.x) * (p.x - p1.x) + (p.y - p1.y) * (p.y - p1.y))
            } else {
                abs(dy * p.x - dx * p.y + p2.x * p1.y - p2.y * p1.x) / length
            }
            if (dist > maxDist) {
                maxDist = dist
                maxIdx = i
            }
        }

        if (maxDist > tolerance) {
            keep[maxIdx] = true
            simplifyStep(points, start, maxIdx, tolerance, keep)
            simplifyStep(points, maxIdx, end, tolerance, keep)
        }
    }
}
```

- [ ] **Step 3: Create ElevationGainCalculator.kt**

Create `core/model/src/main/kotlin/app/readylytics/health/domain/util/ElevationGainCalculator.kt`:
```kotlin
package app.readylytics.health.domain.util

object ElevationGainCalculator {
    fun calculateAscent(altitudes: List<Double>, thresholdMeters: Double = 3.0): Double {
        if (altitudes.size < 2) return 0.0
        
        // 5-point moving window smoothing to ignore sensor noise
        val smoothed = altitudes.indices.map { i ->
            val start = (i - 2).coerceAtLeast(0)
            val end = (i + 2).coerceAtMost(altitudes.lastIndex)
            altitudes.subList(start, end + 1).average()
        }

        var totalAscent = 0.0
        var lastBase = smoothed.first()

        for (i in 1..smoothed.lastIndex) {
            val curr = smoothed[i]
            val diff = curr - lastBase
            if (diff >= thresholdMeters) {
                totalAscent += diff
                lastBase = curr
            } else if (diff <= -thresholdMeters) {
                lastBase = curr
            }
        }
        return totalAscent
    }
}
```

- [ ] **Step 4: Create PaceSpeedCalculator.kt**

Create `core/model/src/main/kotlin/app/readylytics/health/domain/util/PaceSpeedCalculator.kt`:
```kotlin
package app.readylytics.health.domain.util

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.sqrt

object PaceSpeedCalculator {
    private const val EARTH_RADIUS_M = 6371000.0

    // Returns cumulative distances in meters for a path of lat/lons
    fun calculateCumulativeDistances(latitudes: DoubleArray, longitudes: DoubleArray): DoubleArray {
        if (latitudes.size < 2) return DoubleArray(latitudes.size) { 0.0 }
        val distances = DoubleArray(latitudes.size)
        distances[0] = 0.0
        var acc = 0.0
        for (i in 1..latitudes.lastIndex) {
            acc += haversineDistance(latitudes[i - 1], longitudes[i - 1], latitudes[i], longitudes[i])
            distances[i] = acc
        }
        return distances
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }
}
```

- [ ] **Step 5: Write unit tests for domain calculation algorithms**

Create `core/model/src/test/kotlin/app/readylytics/health/domain/util/ElevationGainCalculatorTest.kt`:
```kotlin
package app.readylytics.health.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ElevationGainCalculatorTest {
    @Test
    fun testAscentCalculationIgnoresNoise() {
        val alt = listOf(100.0, 101.0, 100.5, 102.0, 100.0, 105.0) // Jitter should be ignored/smoothed
        val ascent = ElevationGainCalculator.calculateAscent(alt, 3.0)
        assertEquals(0.0, ascent, 0.1)
    }

    @Test
    fun testAscentCalculationCountsTrueGain() {
        val alt = listOf(100.0, 105.0, 105.0, 110.0, 115.0)
        val ascent = ElevationGainCalculator.calculateAscent(alt, 3.0)
        assertEquals(15.0, ascent, 0.1)
    }
}
```
Create other tests `RouteProjectorTest`, `RouteSimplifierTest` and `PaceSpeedCalculatorTest` similarly.

- [ ] **Step 6: Run tests**

Run: `./gradlew :core:model:testDebugUnitTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/domain/util/ core/model/src/test/kotlin/app/readylytics/health/domain/util/
git commit -m "feat: add pure Kotlin calculations for GPS routes and elevation gains"
```

---

### Task 5: Repository Integration & Ingestion Pipeline Hook

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/repository/WorkoutRepository.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImpl.kt`

**Interfaces:**
- Consumes: `WorkoutRoutePointDao`
- Produces: Extended `WorkoutRepository` methods for retrieving route points and writing foreground updates.

- [ ] **Step 1: Add model fields to domain representation**

In `core/model/src/main/kotlin/app/readylytics/health/domain/repository/WorkoutRepository.kt`, update `WorkoutData` and declare `saveRoutePoints` and `getRoutePoints`:
```kotlin
data class WorkoutData(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val exerciseType: String,
    val durationMinutes: Int,
    val zone1Minutes: Float,
    val zone2Minutes: Float,
    val zone3Minutes: Float,
    val zone4Minutes: Float,
    val zone5Minutes: Float,
    val trimp: Float,
    val avgHr: Float,
    val deviceName: String? = null,
    val routeState: String = "NOT_AVAILABLE",
    val avgSpeedKmh: Float? = null,
    val avgPaceMinKm: Float? = null,
    val elevationGainMeters: Float? = null,
    val totalDistanceMeters: Float? = null
)

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val timestampMs: Long
)

interface WorkoutRepository {
    suspend fun getById(id: String): WorkoutData?
    suspend fun getEarliestWorkoutTimestamp(): Long?
    fun observeSince(fromMs: Long): Flow<List<WorkoutData>>
    
    suspend fun getRoutePoints(workoutId: String): List<RoutePoint>
    suspend fun updateRouteState(workoutId: String, routeState: String)
    suspend fun saveRoutePoints(workoutId: String, points: List<RoutePoint>, stats: WorkoutStats)
}

data class WorkoutStats(
    val avgSpeedKmh: Float?,
    val avgPaceMinKm: Float?,
    val elevationGainMeters: Float?,
    val totalDistanceMeters: Float?
)
```

- [ ] **Step 2: Update WorkoutRepositoryImpl**

Update `core/database/src/main/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImpl.kt` to implement the new repository methods and perform correct entity mapping:
```kotlin
// Map DAO elements inside getRoutePoints
override suspend fun getRoutePoints(workoutId: String): List<RoutePoint> =
    workoutRoutePointDao.getRoutePoints(workoutId).map {
        RoutePoint(it.latitude, it.longitude, it.altitude, it.timestampMs)
    }

override suspend fun updateRouteState(workoutId: String, routeState: String) {
    val workout = workoutDao.getById(workoutId) ?: return
    workoutDao.upsertAll(listOf(workout.copy(routeState = routeState)))
}

override suspend fun saveRoutePoints(workoutId: String, points: List<RoutePoint>, stats: WorkoutStats) {
    val dbPoints = points.map {
        WorkoutRoutePointEntity(
            workoutId = workoutId,
            latitude = it.latitude,
            longitude = it.longitude,
            altitude = it.altitude,
            timestampMs = it.timestampMs,
            horizontalAccuracy = null,
            verticalAccuracy = null
        )
    }
    workoutRoutePointDao.deleteByWorkoutId(workoutId)
    workoutRoutePointDao.insertAll(dbPoints)
    
    val workout = workoutDao.getById(workoutId) ?: return
    workoutDao.upsertAll(listOf(
        workout.copy(
            routeState = "IMPORTED",
            avgSpeedKmh = stats.avgSpeedKmh,
            avgPaceMinKm = stats.avgPaceMinKm,
            elevationGainMeters = stats.elevationGainMeters,
            totalDistanceMeters = stats.totalDistanceMeters
        )
    ))
}
```

- [ ] **Step 3: Verify tests**

Run: `./gradlew :core:database:testDebugUnitTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/domain/repository/WorkoutRepository.kt core/database/src/main/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImpl.kt
git commit -m "feat: extend WorkoutRepository for route loading and state synchronization"
```

---

### Task 6: ViewModel & Route Ingestion Flow Setup

**Files:**
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailViewModel.kt`
- Test: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailViewModelTest.kt`

**Interfaces:**
- Consumes: `WorkoutRepository`, `HealthConnectRepository`
- Produces: Populated `routeUiState` inside `WorkoutDetailUiState`.

- [ ] **Step 1: Update WorkoutDetailUiState**

In `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailViewModel.kt`:
- Add `RouteUiState` and `RouteDataState` interfaces to state file.
- Update `WorkoutDetailUiState`:
```kotlin
sealed interface RouteDataState {
    object Loading : RouteDataState
    object NotAvailable : RouteDataState
    object PermissionRequired : RouteDataState
    object Available : RouteDataState
    object Empty : RouteDataState
    object Error : RouteDataState
}

data class RouteUiState(
    val state: RouteDataState = RouteDataState.Loading,
    val points: List<ProjectedPoint> = emptyList(),
    val scaleLabel: String = "",
    val scaleLineWidthDp: Float = 0f
)

// Add fields to WorkoutDetailUiState
val routeUiState: RouteUiState = RouteUiState(),
val paceSpeedChartData: List<Pair<Float, Float>> = emptyList(),
val elevationChartData: List<Pair<Float, Float>> = emptyList(),
val isSpeedOriented: Boolean = false
```

- [ ] **Step 2: Add coordinate loading logic**

Extend the View Model's data loading coroutine to check permissions and perform foreground route imports:
```kotlin
fun loadRouteDetail(workout: WorkoutData) {
    viewModelScope.launch {
        if (workout.routeState == "NOT_AVAILABLE") {
            _uiState.update { it.copy(routeUiState = RouteUiState(state = RouteDataState.NotAvailable)) }
            return@launch
        }
        
        val permissionStatus = hcRepo.checkPermissions()
        if (permissionStatus is PermissionStatus.Missing && 
            permissionStatus.missing.contains("android.permission.health.READ_EXERCISE_ROUTES")) {
            _uiState.update { it.copy(routeUiState = RouteUiState(state = RouteDataState.PermissionRequired)) }
            return@launch
        }

        // Fetch route points
        val dbPoints = workoutRepository.getRoutePoints(workout.id)
        if (dbPoints.isNotEmpty()) {
            processAndPublishRoute(workout, dbPoints)
        } else if (workout.routeState == "PENDING_FOREGROUND_LOAD") {
            val hcRoute = hcRepo.readExerciseRoute(workout.id)
            if (hcRoute != null && hcRoute.points.isNotEmpty()) {
                val routePoints = hcRoute.points.map { RoutePoint(it.latitude, it.longitude, it.altitude, it.timestampMs) }
                
                // Fallback calculations
                val latitudes = routePoints.map { it.latitude }.toDoubleArray()
                val longitudes = routePoints.map { it.longitude }.toDoubleArray()
                val altitudes = routePoints.mapNotNull { it.altitude }
                val cumulativeDist = PaceSpeedCalculator.calculateCumulativeDistances(latitudes, longitudes)
                val totalDistance = cumulativeDist.lastOrNull() ?: 0.0
                
                val elevationGain = if (altitudes.isNotEmpty()) ElevationGainCalculator.calculateAscent(altitudes) else 0.0
                
                val elapsedMinutes = (workout.endTime - workout.startTime) / 60000.0
                val avgSpeedKmh = if (elapsedMinutes > 0) (totalDistance / 1000.0) / (elapsedMinutes / 60.0) else 0.0
                
                val stats = WorkoutStats(
                    avgSpeedKmh = avgSpeedKmh.toFloat(),
                    avgPaceMinKm = if (avgSpeedKmh > 0) (60.0 / avgSpeedKmh).toFloat() else 0f,
                    elevationGainMeters = elevationGain.toFloat(),
                    totalDistanceMeters = totalDistance.toFloat()
                )
                
                workoutRepository.saveRoutePoints(workout.id, routePoints, stats)
                processAndPublishRoute(workout, routePoints)
            } else {
                workoutRepository.updateRouteState(workout.id, "NOT_AVAILABLE")
                _uiState.update { it.copy(routeUiState = RouteUiState(state = RouteDataState.NotAvailable)) }
            }
        } else {
            _uiState.update { it.copy(routeUiState = RouteUiState(state = RouteDataState.NotAvailable)) }
        }
    }
}
```

- [ ] **Step 3: Implement processAndPublishRoute**

Process coordinates with Douglas-Peucker projection and publish charts values:
```kotlin
private fun processAndPublishRoute(workout: WorkoutData, points: List<RoutePoint>) {
    val projected = RouteProjector.project(
        points.map { it.latitude }.toDoubleArray(),
        points.map { it.longitude }.toDoubleArray(),
        points.map { it.altitude ?: 0.0 }.toDoubleArray(),
        points.map { it.timestampMs }.toLongArray()
    )
    val simplified = RouteSimplifier.simplify(projected, maxPoints = 200)
    
    // Build Chart Arrays
    val latitudes = points.map { it.latitude }.toDoubleArray()
    val longitudes = points.map { it.longitude }.toDoubleArray()
    val cumulativeDist = PaceSpeedCalculator.calculateCumulativeDistances(latitudes, longitudes)
    
    val elevationChart = points.indices.mapNotNull { i ->
        val alt = points[i].altitude ?: return@mapNotNull null
        (cumulativeDist[i] / 1000.0).toFloat() to alt.toFloat()
    }
    
    _uiState.update {
        it.copy(
            routeUiState = RouteUiState(
                state = RouteDataState.Available,
                points = simplified
            ),
            elevationChartData = elevationChart,
            isSpeedOriented = workout.exerciseType == "8" // Cycling
        )
    }
}
```

- [ ] **Step 4: Add tests for route detail View Model states**

In `WorkoutDetailViewModelTest.kt`, verify `loadRouteDetail` sets the correct states when coordinates are present or missing.

- [ ] **Step 5: Run ViewModel tests**

Run: `./gradlew :feature:workouts:testDebugUnitTest --tests "app.readylytics.health.feature.workouts.WorkoutDetailViewModelTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailViewModel.kt feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailViewModelTest.kt
git commit -m "feat: connect loadRouteDetail logic to WorkoutDetailViewModel"
```

---

### Task 7: Compose UI Metric Cards & Preview Canvas

**Files:**
- Create: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RouteContourCard.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailScreen.kt`

**Interfaces:**
- Consumes: `RouteUiState`
- Produces: Rendered Material 3 cards showing route coordinates and scale.

- [ ] **Step 1: Create RouteContourCard.kt**

Implement local canvas drawing with scaling, centering, and scale bar drawing:
```kotlin
package app.readylytics.health.feature.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.readylytics.health.domain.util.ProjectedPoint

@Composable
fun RouteContourCard(
    points: List<ProjectedPoint>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            if (points.isEmpty()) return@Canvas
            
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            
            val wGeo = maxX - minX
            val hGeo = maxY - minY
            
            val scale = minOf((size.width - 40) / wGeo, (size.height - 40) / hGeo)
            val dx = (size.width - wGeo * scale) / 2
            val dy = (size.height - hGeo * scale) / 2
            
            val path = Path()
            points.forEachIndexed { i, p ->
                val canvasX = (dx + (p.x - minX) * scale).toFloat()
                val canvasY = (size.height - dy - (p.y - minY) * scale).toFloat()
                if (i == 0) path.moveTo(canvasX, canvasY) else path.lineTo(canvasX, canvasY)
            }
            
            drawPath(path, color = Color.Cyan, style = Stroke(width = 3.dp.toPx()))
        }
    }
}
```

- [ ] **Step 2: Hook layout into WorkoutDetailScreen**

Open `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailScreen.kt`. Import `RouteContourCard` and place it below metrics container inside `WorkoutDetailContent`.

- [ ] **Step 3: Run Compose unit tests**

Run: `./gradlew :feature:workouts:testDebugUnitTest --tests "app.readylytics.health.feature.workouts.WorkoutDetailScreenTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RouteContourCard.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailScreen.kt
git commit -m "feat: implement RouteContourCard and integrate with WorkoutDetailScreen"
```

---

### Task 8: Vico Performance Charts Implementation

**Files:**
- Create: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutPerformanceCharts.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailScreen.kt`

**Interfaces:**
- Consumes: Chart series arrays (distance vs speed/pace/elevation)
- Produces: Rendered Vico Cartesian Charts showing performance trends over distance.

- [ ] **Step 1: Create WorkoutPerformanceCharts.kt**

Implement Vico Line Cartesian chart templates. Note that for Pace, the Y-axis must be inverted (lower min values plotted at the top):
```kotlin
package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries

@Composable
fun WorkoutPerformanceChart(
    chartData: List<Pair<Float, Float>>,
    isInverted: Boolean, // Invert Y for Pace
    modifier: Modifier = Modifier
) {
    if (chartData.isEmpty()) return
    val producer = CartesianChartModelProducer.build {
        lineSeries {
            series(chartData.map { it.first }, chartData.map { it.second })
        }
    }
    
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
        ),
        modelProducer = producer,
        modifier = modifier.fillMaxWidth().height(220.dp)
    )
}
```

- [ ] **Step 2: Add charts to WorkoutDetailScreen**

Place `WorkoutPerformanceChart` inside the `WorkoutDetailScreen` layout hierarchy for both pace/speed and elevation metrics.

- [ ] **Step 3: Run full suite checks and code style formats**

Run: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest && ./gradlew lintRelease`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutPerformanceCharts.kt feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailScreen.kt
git commit -m "feat: add Vico charts for pace and elevation curves"
```

---

## Plan Handoff

Plan complete and saved to [2026-07-11-gps-route-workout-detail.md](file:///C:/Users/lauri/git/Readylytics/docs/superpowers/plans/2026-07-11-gps-route-workout-detail.md).

Two execution options:

1.  **Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration.
2.  **Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach would you like to take?
