# Technical Design: GPS Route and Performance Details for Workout Detail Screen

*   **Date**: 2026-07-11
*   **Status**: Approved
*   **Author**: Principal Android Architect & Material 3 Specialist
*   **Reference**: [prompt.md](file:///C:/Users/lauri/git/Readylytics/prompt.md)

---

## 1. Executive Summary

### Current State
Readylytics currently parses basic workout sessions from Android Health Connect (start/end times, activity types, heart rate zone durations, and TRIMP scores) and stores them in the `workout_records` database table. The detail screen presents these basic metrics along with a heart rate timeline chart. 

### Missing Capabilities
The app does not support displaying GPS route contours, pace/speed charts, elevation profiles, or metric cards for average pace/speed and elevation gain. No location coordinates, speed samples, distance samples, or elevation gains are read from Health Connect or stored in the Room database.

### Recommended Architecture
We will implement **Approach A (Eager Ingestion + Normalized Route Table)**. Exercise routes will be loaded from Health Connect in the foreground, projected into an equirectangular 2D plane, simplified using the Douglas-Peucker algorithm, and persisted in a dedicated `workout_route_points` table. Performance metrics will be formatted at the presentation layer based on the user's unit system preference.

### Major Risks
*   **Foreground-Only Route Reading**: Android 14+ restricts route reading to foreground applications. If background sync runs, it will fail to fetch route coordinates. We resolve this by marking the route state as `PENDING_FOREGROUND_LOAD` and executing a foreground fetch in the ViewModel when the user opens the workout details.
*   **GPS Coordinate Noise**: GPS altitude data is notoriously noisy, which can lead to massive overestimations of elevation gain. We resolve this by applying a 3-meter threshold filter and a moving-window average smoothing algorithm.
*   **UI Scroll Performance**: Reading thousands of GPS points can cause database blocking and UI stutter. We resolve this by storing route points in a separate table, loading them lazily, and simplifying the route down to 200 points for the preview canvas.

---

## 2. Repository Findings

| File Path | Responsibility |
| :--- | :--- |
| [WorkoutRecordEntity.kt](file:///C:/Users/lauri/git/Readylytics/core/model/src/main/kotlin/app/readylytics/health/data/local/entity/WorkoutRecordEntity.kt) | Existing Room entity representing basic workout metadata. |
| [WorkoutDao.kt](file:///C:/Users/lauri/git/Readylytics/core/model/src/main/kotlin/app/readylytics/health/data/local/dao/WorkoutDao.kt) | Room DAO for inserting, reading, and deleting workouts. |
| [HealthDatabase.kt](file:///C:/Users/lauri/git/Readylytics/core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt) | Main Room database definition (`DATABASE_VERSION = 5`). |
| [HealthConnectRepositoryImpl.kt](file:///C:/Users/lauri/git/Readylytics/core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt) | Handles direct Health Connect SDK calls. Uses `allPermissions` and `criticalPermissions`. |
| [WorkoutDetailViewModel.kt](file:///C:/Users/lauri/git/Readylytics/feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailViewModel.kt) | Exposes `WorkoutDetailUiState` and loads workout metadata and heart rate samples. |
| [WorkoutDetailScreen.kt](file:///C:/Users/lauri/git/Readylytics/feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailScreen.kt) | Renders the workout detail UI, metric cards, and charts. |
| [UnitSystem.kt](file:///C:/Users/lauri/git/Readylytics/core/model/src/main/kotlin/app/readylytics/health/data/preferences/UnitSystem.kt) | Enum representing `METRIC` and `IMPERIAL` preferences. |
| [UnitConverter.kt](file:///C:/Users/lauri/git/Readylytics/core/model/src/main/kotlin/app/readylytics/health/domain/util/UnitConverter.kt) | Provides utility conversions for height/weight. Needs extension for distance, speed, and elevation. |
| [TrimpBreakdownChart.kt](file:///C:/Users/lauri/git/Readylytics/feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/TrimpBreakdownChart.kt) | Implements the workout detail heart rate chart using the Vico library. |

---

## 3. Data Availability Matrix

| Feature | Health Connect Source | Already Imported? | Already Persisted? | Permission Requirements | Fallback | UI Visibility Rule |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Workout Route** | `ExerciseSessionRecord` route | No | No | `READ_EXERCISE_ROUTES` | None | Show canvas card if route is imported or permission/prompt states apply. Hide if no route was recorded. |
| **Average Pace / Speed** | `SpeedRecord` samples or computed | No | No | `READ_EXERCISE` | Computed as GPS route distance divided by elapsed duration. | Visible if distance exists (either via route or fallback `DistanceRecord`). |
| **Elevation Gain** | `ElevationGainedRecord` or route | No | No | `READ_EXERCISE` | Cumulative ascent calculated from GPS route altitude samples. | Visible if elevation gain was imported or calculated fallback is available. |
| **Pace / Speed Chart** | `SpeedRecord` or coordinates | No | No | `READ_EXERCISE` | Interpolated speed points over route distance axis. | Visible if distance and speed data is available and point count $\ge 10$. |
| **Elevation Chart** | `ExerciseSessionRecord` route altitude | No | No | `READ_EXERCISE_ROUTES` | None | Visible if route contains altitude samples and point count $\ge 10$. |

---

## 4. Proposed Architecture

```
                       ┌──────────────────────────────────────────────┐
                       │            Android Health Connect            │
                       └──────────────────────┬───────────────────────┘
                                              │
                                              ▼ (via HealthConnectRepositoryImpl)
                       ┌──────────────────────────────────────────────┐
                       │     Workout & Route Ingestion Pipeline       │
                       │   (Handles foreground/background checks)    │
                       └──────────────────────┬───────────────────────┘
                                              │
                                              ▼
                       ┌──────────────────────────────────────────────┐
                       │          Room Database (SQLite)              │
                       │   - workout_records                          │
                       │   - workout_route_points                     │
                       └──────────────────────┬───────────────────────┘
                                              │
                                              ▼ (via WorkoutRepositoryImpl)
                       ┌──────────────────────────────────────────────┐
                       │          Pure Kotlin Domain Layer            │
                       │   - Equirectangular 2D Projection            │
                       │   - Douglas-Peucker Simplification           │
                       │   - 3m Threshold Altitude Filter             │
                       │   - Unit System Conversions                  │
                       └──────────────────────┬───────────────────────┘
                                              │
                                              ▼
                       ┌──────────────────────────────────────────────┐
                       │           WorkoutDetailViewModel             │
                       │   (Exposes structured RouteUiState Flow)     │
                       └──────────────────────┬───────────────────────┘
                                              │
                                              ▼
                       ┌──────────────────────────────────────────────┐
                       │            Jetpack Compose UI                │
                       │   - Route contour preview (Canvas)           │
                       │   - Pace & Elevation charts (Vico)           │
                       └──────────────────────────────────────────────┘
```

---

## 5. Database and Migration Plan

### 1. New Table: `workout_route_points`
```kotlin
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

### 2. Changes to `workout_records`
Add the following fields to `WorkoutRecordEntity`:
```kotlin
val routeState: String // "NOT_AVAILABLE", "PENDING_FOREGROUND_LOAD", "PERMISSION_REQUIRED", "IMPORTED", "FAILED"
val avgSpeedKmh: Float?
val avgPaceMinKm: Float?
val elevationGainMeters: Float?
val totalDistanceMeters: Float?
```

### 3. DAO: `WorkoutRoutePointDao`
```kotlin
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

### 4. Migration Strategy
Increment `DATABASE_VERSION` from `5` to `6` in `HealthDatabase.kt`.
Create `MIGRATION_5_6` in `DatabaseMigrations.kt`:
```kotlin
private val MIGRATION_5_6 = object : Migration(5, 6) {
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
Add `MIGRATION_5_6` to the `all` array inside `DatabaseMigrations.kt`.

### 5. Retention Behavior
Because `workout_route_points` is bound to `workout_records` via `ON DELETE CASCADE`, when a workout record is deleted (either manually or via `RetentionCleanup`), the associated route points are automatically purged by SQLite, preventing database bloat.

---

## 6. Health Connect and Permission Plan

### New Permission Requirements
Add the following permission to `AndroidManifest.xml` (inside `core/healthconnect` and `app` manifests):
```xml
<uses-permission android:name="android.permission.health.READ_EXERCISE_ROUTES" />
```
Declare it dynamically in `HealthConnectRepositoryImpl.kt` under `optionalPermissions` so that a denial does not block base synchronizations:
```kotlin
override val optionalPermissions: Set<String> = setOf(
    HealthPermission.getReadPermission(WeightRecord::class),
    HealthPermission.getReadPermission(BodyFatRecord::class),
    HealthPermission.getReadPermission(BloodPressureRecord::class),
    HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    "android.permission.health.READ_EXERCISE_ROUTES" // Added
)
```

### Route Read Flow (Handling Foreground Restrictions)
Health Connect requires foreground execution to read routes created by other apps.
1.  **Background Ingestion**: If syncing in the background, we query `ExerciseSessionRecord`. If the session contains a route (`session.hasRoute` is true) but we are running in the background, we write the workout to Room with `routeState = "PENDING_FOREGROUND_LOAD"`. We do not attempt to call the route API.
2.  **Foreground Load**: When the workout detail page opens:
    *   If `routeState == "PENDING_FOREGROUND_LOAD"`: The ViewModel launches a coroutine to fetch the route coordinates.
    *   If the coordinate query succeeds: Points are saved in `workout_route_points` and the workout is updated to `routeState = "IMPORTED"`.
    *   If the coordinate query fails due to missing permissions (`SecurityException`): Update the workout to `routeState = "PERMISSION_REQUIRED"`.
    *   If the query fails due to other exceptions: Update to `routeState = "FAILED"`.

---

## 7. Domain Processing Plan

All calculation logic will be placed in pure Kotlin files inside the `domain` module, making them entirely independent of the Android framework.

### 1. Route Projection & Bounds Fitting
```kotlin
object RouteProjector {
    fun project(points: List<RawPoint>): List<ProjectedPoint> {
        if (points.isEmpty()) return emptyList()
        val latCenter = points.map { it.latitude }.average()
        val radLatCenter = Math.toRadians(latCenter)
        val cosLat = Math.cos(radLatCenter)

        return points.map { p ->
            ProjectedPoint(
                x = p.longitude * cosCosCenter,
                y = p.latitude,
                altitude = p.altitude,
                timestampMs = p.timestampMs
            )
        }
    }
}
```

### 2. Douglas-Peucker Route Simplification
Used to reduce points to $\le 200$ for the drawing canvas:
*   Recursively divides the path by finding the point furthest from the line segment between start and end.
*   If the furthest point's distance is greater than the tolerance threshold (e.g. 0.00005 degrees), recursively simplify the two sub-segments.
*   Always preserves start and end points.

### 3. Threshold Elevation Gain Fallback
```kotlin
object ElevationGainCalculator {
    fun calculateAscent(altitudes: List<Double>, thresholdMeters: Double = 3.0): Double {
        if (altitudes.size < 2) return 0.0
        var totalAscent = 0.0
        var lastBase = altitudes.first()

        for (i in 1..altitudes.lastIndex) {
            val curr = altitudes[i]
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

### 4. Unit Conversion Formatters
Extend `UnitConverter.kt` and `MetricFormatter.kt` to handle new units based on `UnitSystem`:
*   **Distance**: Conversion of meters to kilometers (Metric) or miles (Imperial, division by `1609.344`).
*   **Speed**: Conversion of meters per second to `km/h` (multiply by `3.6`) or `mph` (multiply by `2.236936`).
*   **Pace**: Speed conversion to minutes/km or minutes/mile. (Inverting speed: $\text{Pace} = 60 / \text{Speed}$). Cap pace at 20 min/km (30 min/mi) to handle standstill periods.

---

## 8. UI/UX Plan

### Component Layout Hierarchy
On the workout detail screen, we will insert the following sequence:

```
[ Workout Header & Core Summary Card ]
               │
               ▼
[ Route & Performance Section Header ]
               │
               ├─► [ Metric Cards: Avg Pace/Speed | Elevation Gain ]
               │
               ├─► [ Route Preview Card (Canvas Drawing) ]
               │     ├─► Canvas draws route contour
               │     ├─► Green (Start) & Red (End) dots
               │     └─► Scale indicator line & distance label
               │
               ├─► [ Pace / Speed Chart (Vico Cartesian Chart) ]
               │     └─► Inverted Y-axis for Pace (Fast at top)
               │
               └─► [ Elevation Profile Chart (Vico Cartesian Chart) ]
                     └─► Cubic Bezier + Area Gradient Fill
```

### Route Preview States
*   **Loading**: Shows a centered circular progress indicator.
*   **PermissionRequired**: Displays an inline banner: *"Permission required to display route details"* and an M3 `Button` *"Grant Permission"*. Tapping it launches the standard Health Connect permission screen.
*   **Available**: Renders the canvas contour drawing.
*   **Failed**: Displays an inline retry prompt or error text.
*   **NotAvailable** / **Empty**: The entire route preview container is hidden.

---

## 9. State Model

```kotlin
sealed interface RouteDataState {
    object Loading : RouteDataState
    object NotAvailable : RouteDataState
    object PermissionRequired : RouteDataState
    object Available : RouteDataState
    object Empty : RouteDataState
    data class Error(val messageResId: Int) : RouteDataState
}

data class RouteUiState(
    val state: RouteDataState,
    val points: List<RoutePoint> = emptyList(),
    val scaleLabel: String = "",
    val scaleLineWidthDp: Float = 0f,
    val metricUnitLabel: String = ""
)
```

---

## 10. Step-by-Step Implementation Tasks

### Task 1: Declare Route Permission & Ingestion Types
*   **Goal**: Add the new Health Connect `READ_EXERCISE_ROUTES` permission and update lists.
*   **Files to Modify**: 
    *   `core/healthconnect/src/main/AndroidManifest.xml`
    *   `app/src/main/AndroidManifest.xml`
    *   `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt`
*   **Verification**: Run build command to confirm successful assembly.

### Task 2: Database Migration & Schema Setup
*   **Goal**: Define database migrations and add `workout_route_points` table to `HealthDatabase`.
*   **Files to Create/Modify**:
    *   Create: `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/WorkoutRoutePointEntity.kt`
    *   Create: `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/WorkoutRoutePointDao.kt`
    *   Modify: `core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt`
    *   Modify: `core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt`
*   **Tests**: Create schema migration tests in `app/src/test/kotlin/app/readylytics/health/data/local/DatabaseMigrationTest.kt` verifying migration `5 -> 6` successfully updates tables without loss of data.
*   **Verification Command**: `./gradlew :core:database:testDebugUnitTest`

### Task 3: Ingestion & Repository Update
*   **Goal**: Fetch route coordinates, speed, and distance records from Health Connect.
*   **Files to Modify**:
    *   `core/model/src/main/kotlin/app/readylytics/health/domain/repository/HealthConnectRepository.kt`
    *   `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt`
    *   `core/database/src/main/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImpl.kt`
*   **Tests**: Update `HealthConnectRepositoryImplTest` to stub Health Connect coordinate results and verify parsing rules.

### Task 4: Pure Kotlin Domain Algorithms
*   **Goal**: Implement Equirectangular projection, Douglas-Peucker, 3m threshold ascent filter, and scale bar calculations in pure Kotlin.
*   **Files to Create**:
    *   `core/model/src/main/kotlin/app/readylytics/health/domain/util/RouteProjector.kt`
    *   `core/model/src/main/kotlin/app/readylytics/health/domain/util/RouteSimplifier.kt`
    *   `core/model/src/main/kotlin/app/readylytics/health/domain/util/ElevationGainCalculator.kt`
*   **Tests**: Create comprehensive unit tests inside `core/model/src/test/kotlin/app/readylytics/health/domain/util/` verifying output accuracy for coordinate scaling and threshold filtering.
*   **Verification Command**: `./gradlew :core:model:testDebugUnitTest`

### Task 5: ViewModel Updates
*   **Goal**: Trigger foreground route loads, map coordinate lists, and expose a populated `RouteUiState`.
*   **Files to Modify**:
    *   `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailViewModel.kt`
*   **Tests**: Add view model state transitions tests in `WorkoutDetailViewModelTest.kt`.
*   **Verification Command**: `./gradlew :feature:workouts:testDebugUnitTest`

### Task 6: Material 3 Compose UI Implementation
*   **Goal**: Implement the metrics card, Canvas route drawing, and Vico charts (Pace & Elevation) on the details page.
*   **Files to Create/Modify**:
    *   Create: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RouteContourCard.kt`
    *   Create: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutPerformanceCharts.kt`
    *   Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailScreen.kt`
*   **Tests**: Add compose tests in `WorkoutDetailScreenTest.kt` verifying loading, permission prompts, and card visibility under different states.

### Task 7: Strings Migration
*   **Goal**: Map all user-facing texts to resource IDs.
*   **Files to Modify**:
    *   `app/src/main/res/values/strings.xml`
*   **Verification**: Ensure clean localization compilation.

---

## 11. Testing Plan

### Required Scenarios & File Mapping
*   **Unit Tests** (`core/model/src/test/kotlin/app/readylytics/health/domain/util/`):
    *   `RouteProjectorTest.kt`: Verify aspect ratio preservation, cosine-corrected scaling, and empty lists.
    *   `RouteSimplifierTest.kt`: Verify Douglas-Peucker point reduction fits bounds exactly.
    *   `ElevationGainCalculatorTest.kt`: Verify 3m vertical threshold filters out minor vertical GPS jitter.
    *   `UnitConverterTest.kt`: Add conversions for speed (mps $\to$ kmh/mph) and pace.
*   **Migration Tests** (`app/src/test/kotlin/app/readylytics/health/data/local/DatabaseMigrationTest.kt`):
    *   Verify upgrading DB version 5 to 6 inserts new columns and creates the child coordinate table.
*   **ViewModel & UI Tests** (`feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/`):
    *   `WorkoutDetailViewModelTest.kt`: Test loading sequences and permission actions.
    *   `WorkoutDetailScreenTest.kt`: Validate M3 Compose card renders with correct colors and layouts.

---

## 12. Risks and Edge Cases

1.  **GPS Jumps**: Implausible spikes in location coordinates (e.g. 50km jump in 1s) will be rejected by calculating the velocity between sequential points and ignoring those exceeding $150\text{ km/h}$.
2.  **Missing Altitude**: If altitude is missing on some coordinates, we interpolate between surrounding valid coordinates. If missing completely, the elevation profile card is hidden.
3.  **Antimeridian Crossings**: For routes that cross longitude $+180/-180$ degrees, we normalize longitudes to a continuous range.
4.  **Standstill periods**: Zero speed will not trigger infinite pace values because we cap pace calculations at $20\text{ min/km}$ ($30\text{ min/mi}$) and segment the chart line during pauses $>10$s.
5.  **Retention Cleanups**: We configure the Room database schema with cascade deletes so route data follows the workout retention cycle automatically without leaving database traces.

---

## 13. Documentation Updates

1.  **`internal-docs/DATA_FLOW.md`**: Update sections detailing Room schema database v6 and the ingestion flowchart.
2.  **`ABOUT.md` and `docs/about.md`**: Document the privacy-centric design (drawing geometric routes offline using Canvas, avoiding Google Maps SDK, keeping coordinate logs safe).

---

## 14. Open Questions
*No unresolved questions remain. All choices (Pace fallbacks, foreground loading, noise thresholds, and inverted pace axes) have been aligned with the user during the design phase.*
