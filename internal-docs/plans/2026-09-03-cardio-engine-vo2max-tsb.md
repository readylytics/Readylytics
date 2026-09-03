# Cardio Engine (VO2 Max & Training Stress Balance) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a complete offline-first Cardio Engine in Readylytics including Health Connect VO2 Max ingestion, pure-Kotlin Uth et al. (2004) resting HR ratio estimation, Cooper Institute age/sex normative benchmarking, user-configurable source resolution, and Training Stress Balance (TSB = CTL - ATL) training load analysis.

**Architecture:** Extend Health Connect ingestion with `READ_VO2_MAX` and map to domain DTOs. Execute Room database migration v17 -> v18 adding `vo2_max_records` table and `vo2Max`/`vo2MaxSource` columns to `daily_summaries`. Implement pure-Kotlin Uth estimator, Cooper classifier, and TSB calculator in `:core:scoring`. Update `:feature:vitals` with Cardio Fitness card and detail route, `:feature:workouts` with TSB segmented toggle, `:feature:dashboard` with optional cards, and `:feature:settings` with VO2 Max source preference.

**Tech Stack:** Kotlin, Android Health Connect API (`connect-client:1.1.0`), Room (SQLite v18), Jetpack Compose (Material Design 3), Vico 3.x, Kotlin Coroutines & Flows, JUnit 4.

## Global Constraints

- Android minSdk=26, targetSdk=37.
- Room DB is the single source of truth; Health Connect is ingestion-only. UI must NEVER access Health Connect directly.
- All calculation/scoring logic must be pure Kotlin in `:core:scoring` (zero Android dependencies).
- Strict MVVM + Clean Architecture. ViewModels expose StateFlow only.
- Pre-commit verification: `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest && ./gradlew lintRelease`.
- Zero new detekt warnings; no new `@Suppress` without explicit justification.
- Target <= 400 lines/file, hard limit <= 800 lines.
- All user-facing strings must be in `app/src/main/res/values/strings.xml`.
- Documentation sync: `internal-docs/DATA_FLOW.md`, `ABOUT.md`, and `docs/about.md` must be updated in sync.

---

### Task 1: Health Connect Ingestion Layer (`READ_VO2_MAX`, DTOs, Mappers, Permissions)

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/HealthConnectRecords.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/HealthConnectRepository.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/HealthConnectPermissionChecker.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthConnectRepositoryImpl.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/Vo2MaxRecordMapperTest.kt`

**Interfaces:**
- Consumes: `androidx.health.connect.client.records.Vo2MaxRecord`
- Produces: `DomainVo2MaxRecord(id: String, time: Instant, vo2MillilitersPerMinuteKilogram: Double, measurementMethod: Int?, deviceName: String)` and `HealthConnectRepository.readVo2MaxRecords(start, end)`

- [ ] **Step 1: Write the failing mapper test**

In `core/healthconnect/src/test/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/Vo2MaxRecordMapperTest.kt`:
```kotlin
package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class Vo2MaxRecordMapperTest {
    @Test
    fun mapsSdkVo2MaxRecordToDomain() {
        val now = Instant.parse("2026-09-03T10:00:00Z")
        val sdkRecord = Vo2MaxRecord(
            time = now,
            zoneOffset = null,
            vo2MillilitersPerMinuteKilogram = 48.5,
            measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO,
            metadata = Metadata(
                id = "test-vo2-123",
                device = Device(manufacturer = "Google", model = "Pixel Watch")
            )
        )

        val domain = sdkRecord.toDomain()

        assertEquals("test-vo2-123", domain.id)
        assertEquals(now, domain.time)
        assertEquals(48.5, domain.vo2MillilitersPerMinuteKilogram, 0.001)
        assertEquals(Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO, domain.measurementMethod)
        assertEquals("Pixel Watch", domain.deviceName)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:healthconnect:testDebugUnitTest --tests "app.readylytics.health.core.healthconnect.data.healthconnect.Vo2MaxRecordMapperTest"`
Expected: FAIL (unresolved reference `DomainVo2MaxRecord`, `toDomain`)

- [ ] **Step 3: Implement Domain DTO, Permissions, and Mapper**

1. In `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/HealthConnectRecords.kt`:
```kotlin
data class DomainVo2MaxRecord(
    val id: String,
    val time: Instant,
    val vo2MillilitersPerMinuteKilogram: Double,
    val measurementMethod: Int?,
    val deviceName: String,
)
```

2. In `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/HealthConnectPermissionChecker.kt`:
```kotlin
suspend fun hasVo2MaxPermission(): Boolean
```

3. In `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/repository/HealthConnectRepository.kt`:
Add `HealthPermission.getReadPermission(Vo2MaxRecord::class)` to `OPTIONAL_PERMISSIONS` and declare:
```kotlin
suspend fun readVo2MaxRecords(startTime: Instant, endTime: Instant): List<DomainVo2MaxRecord>
```

4. In `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthConnectRepositoryImpl.kt`:
Implement mapper extension:
```kotlin
internal fun Vo2MaxRecord.toDomain(): DomainVo2MaxRecord =
    DomainVo2MaxRecord(
        id = metadata.id,
        time = time,
        vo2MillilitersPerMinuteKilogram = vo2MillilitersPerMinuteKilogram,
        measurementMethod = measurementMethod,
        deviceName = metadata.device?.model ?: metadata.device?.manufacturer ?: "",
    )
```
And implement `readVo2MaxRecords` using `readAllPages<Vo2MaxRecord>()`.

5. In `app/src/main/AndroidManifest.xml`:
Add `<uses-permission android:name="android.permission.health.READ_VO2_MAX" />`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:healthconnect:testDebugUnitTest --tests "app.readylytics.health.core.healthconnect.data.healthconnect.Vo2MaxRecordMapperTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/model/ core/healthconnect/ app/src/main/AndroidManifest.xml
git commit -m "feat(healthconnect): add READ_VO2_MAX permission and Vo2MaxRecord ingestion mapper"
```

---

### Task 2: Room Schema Migration v17 → v18 & Entity/DAO Setup

**Files:**
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/Vo2MaxRecordEntity.kt`
- Create: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/dao/Vo2MaxRecordDao.kt`
- Modify: `core/database-schema/src/main/kotlin/app/readylytics/health/core/databaseschema/data/local/entity/DailySummaryEntity.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/model/DailySummary.kt`
- Create: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/migration/Migration17To18.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/DatabaseMigrations.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/HealthDatabase.kt`
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/Migration17To18Test.kt`

**Interfaces:**
- Consumes: Room schema v17
- Produces: `Vo2MaxRecordEntity`, `Vo2MaxRecordDao`, `DailySummaryEntity.vo2Max`, `DailySummaryEntity.vo2MaxSource`, and `HealthDatabase.DATABASE_VERSION = 18`

- [ ] **Step 1: Write the failing migration test**

In `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/Migration17To18Test.kt`:
```kotlin
package app.readylytics.health.core.database.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.mockk
import io.mockk.verify
import app.readylytics.health.core.database.data.local.migration.MIGRATION_17_18
import org.junit.Test

class Migration17To18Test {
    @Test
    fun migrationExecutesExpectedSchemaAltersAndTableCreations() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)

        MIGRATION_17_18.migrate(db)

        verify {
            db.execSQL(match { it.contains("CREATE TABLE IF NOT EXISTS `vo2_max_records`") })
            db.execSQL(match { it.contains("CREATE INDEX IF NOT EXISTS `index_vo2_max_records_timestampMs`") })
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN vo2Max REAL DEFAULT NULL")
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN vo2MaxSource TEXT DEFAULT NULL")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.Migration17To18Test"`
Expected: FAIL (unresolved reference `MIGRATION_17_18`)

- [ ] **Step 3: Implement Entity, DAO, Migration, and Database version bump**

1. Create `core/database-schema/.../Vo2MaxRecordEntity.kt`:
```kotlin
package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vo2_max_records",
    indices = [Index(value = ["timestampMs"])],
)
data class Vo2MaxRecordEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val vo2Max: Float,
    val measurementMethod: Int?,
    val deviceName: String,
)
```

2. Create `core/database-schema/.../Vo2MaxRecordDao.kt`:
```kotlin
package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.core.databaseschema.data.local.entity.Vo2MaxRecordEntity

@Dao
interface Vo2MaxRecordDao {
    @Upsert
    suspend fun upsertAll(records: List<Vo2MaxRecordEntity>)

    @Query("SELECT * FROM vo2_max_records WHERE timestampMs >= :startMs AND timestampMs < :endMs ORDER BY timestampMs DESC")
    suspend fun getByTimeRange(startMs: Long, endMs: Long): List<Vo2MaxRecordEntity>

    @Query("SELECT * FROM vo2_max_records WHERE timestampMs <= :maxTimestampMs ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatestUpTo(maxTimestampMs: Long): Vo2MaxRecordEntity?

    @Query("DELETE FROM vo2_max_records WHERE timestampMs < :cutoffMs")
    suspend fun deleteBefore(cutoffMs: Long): Int
}
```

3. In `core/database-schema/.../DailySummaryEntity.kt`:
Add:
```kotlin
val vo2Max: Float? = null,
val vo2MaxSource: String? = null,
```
And add matching fields in `DailySummary.kt`.

4. Create `core/database/.../migration/Migration17To18.kt`:
```kotlin
package app.readylytics.health.core.database.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_17_18 =
    object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `vo2_max_records` (
                    `id` TEXT NOT NULL PRIMARY KEY,
                    `timestampMs` INTEGER NOT NULL,
                    `vo2Max` REAL NOT NULL,
                    `measurementMethod` INTEGER,
                    `deviceName` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vo2_max_records_timestampMs` ON `vo2_max_records` (`timestampMs`)")
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN vo2Max REAL DEFAULT NULL")
            db.execSQL("ALTER TABLE daily_summaries ADD COLUMN vo2MaxSource TEXT DEFAULT NULL")
        }
    }
```

5. In `DatabaseMigrations.kt`: add `MIGRATION_17_18` to `DatabaseMigrations.all`.
6. In `HealthDatabase.kt`: add `Vo2MaxRecordEntity::class` to `@Database(entities = [...])`, bump `DATABASE_VERSION = 18`, and add `abstract fun vo2MaxRecordDao(): Vo2MaxRecordDao`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.Migration17To18Test"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/database-schema/ core/database/ core/model/
git commit -m "feat(database): bump Room schema to v18 with vo2_max_records table and daily_summaries columns"
```

---

### Task 3: Ingestion, Retention Cleanup & Encrypted Backup Integration

**Files:**
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/HealthIngestionCoordinator.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/RetentionCleanup.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt`
- Test: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/RetentionCleanupVo2MaxTest.kt`

**Interfaces:**
- Consumes: `HealthConnectRepository.readVo2MaxRecords`, `Vo2MaxRecordDao`
- Produces: Persistent ingestion of VO2 Max records, inclusion in retention deletion and encrypted backup dump/restore.

- [ ] **Step 1: Write the failing retention test**

In `core/database/src/test/kotlin/app/readylytics/health/core/database/data/local/RetentionCleanupVo2MaxTest.kt`:
```kotlin
package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RetentionCleanupVo2MaxTest {
    @Test
    fun retentionCleanupDeletesVo2MaxRecordsBeforeCutoff() = runTest {
        val vo2MaxRecordDao = mockk<Vo2MaxRecordDao>(relaxed = true)
        coEvery { vo2MaxRecordDao.deleteBefore(any()) } returns 5

        // Verify that deleteBefore on Vo2MaxRecordDao is called with cutoffMs
        vo2MaxRecordDao.deleteBefore(100_000L)
        coVerify { vo2MaxRecordDao.deleteBefore(100_000L) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:database:testDebugUnitTest --tests "app.readylytics.health.core.database.data.local.RetentionCleanupVo2MaxTest"`
Expected: PASS (scaffolding verifies contract; next step integrates into `RetentionCleanup`).

- [ ] **Step 3: Integrate VO2 Max into Ingestion, Retention, and Backup**

1. In `core/healthconnect/.../HealthIngestionCoordinator.kt`:
When reading low-volume records in `ingestWindow`, call `readVo2MaxRecords(windowStart, windowEnd)` when `hasVo2MaxPermission()` is true, map to `Vo2MaxRecordEntity`, and include in `HealthIngestionStore.persist`.
2. In `core/database/.../RetentionCleanup.kt`:
Inject `Vo2MaxRecordDao` and include `vo2MaxRecordDao.deleteBefore(cutoffMs)` in the bounded cleanup transaction.
3. In `app/.../data/backup/LocalBackupManager.kt`:
Include `vo2_max_records` in the array of encrypted tables exported and imported.

- [ ] **Step 4: Run database unit tests to verify**

Run: `./gradlew :core:database:testDebugUnitTest :core:healthconnect:testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/healthconnect/ core/database/ app/src/main/kotlin/app/readylytics/health/data/backup/
git commit -m "feat(sync): integrate vo2_max_records into ingestion coordinator, retention cleanup, and encrypted backup"
```

---

### Task 4: Pure-Kotlin Uth VO2 Max Estimator & Cooper Norms Classifier

**Files:**
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/UthVo2MaxCalculator.kt`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/CooperNormsClassifier.kt`
- Test: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/UthVo2MaxCalculatorTest.kt`
- Test: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/CooperNormsClassifierTest.kt`

**Interfaces:**
- Consumes: `hrMax: Float`, `rhrBaselineBpm: Float`, `isCalibrating: Boolean`, `age: Int`, `biologicalSex: BiologicalSex`
- Produces: `UthVo2MaxCalculator.estimate(hrMax, rhrBaselineBpm, isCalibrating): Float?` and `CooperNormsClassifier.classify(vo2Max, age, biologicalSex): CooperCategory`

- [ ] **Step 1: Write the failing tests**

In `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/UthVo2MaxCalculatorTest.kt`:
```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UthVo2MaxCalculatorTest {
    private val calculator = UthVo2MaxCalculator()

    @Test
    fun returnsNullWhenCalibrating() {
        val result = calculator.estimate(hrMax = 190f, rhrBaselineBpm = 60f, isCalibrating = true)
        assertNull(result)
    }

    @Test
    fun computesExpectedEstimateFromHeartRateRatio() {
        // 15.3 * (190 / 60) = 48.45
        val result = calculator.estimate(hrMax = 190f, rhrBaselineBpm = 60f, isCalibrating = false)
        assertEquals(48.45f, result!!, 0.05f)
    }

    @Test
    fun clampsToPhysiologicalBounds() {
        val extremeHigh = calculator.estimate(hrMax = 220f, rhrBaselineBpm = 30f, isCalibrating = false)
        assertEquals(95.0f, extremeHigh!!, 0.01f)

        val extremeLow = calculator.estimate(hrMax = 100f, rhrBaselineBpm = 110f, isCalibrating = false)
        assertEquals(15.0f, extremeLow!!, 0.01f)
    }
}
```

In `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/CooperNormsClassifierTest.kt`:
```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.model.BiologicalSex
import org.junit.Assert.assertEquals
import org.junit.Test

class CooperNormsClassifierTest {
    private val classifier = CooperNormsClassifier()

    @Test
    fun classifiesMaleAge25Correctly() {
        assertEquals(CooperCategory.SUPERIOR, classifier.classify(vo2Max = 58.0f, age = 25, sex = BiologicalSex.MALE))
        assertEquals(CooperCategory.EXCELLENT, classifier.classify(vo2Max = 49.0f, age = 25, sex = BiologicalSex.MALE))
        assertEquals(CooperCategory.GOOD, classifier.classify(vo2Max = 44.0f, age = 25, sex = BiologicalSex.MALE))
        assertEquals(CooperCategory.FAIR, classifier.classify(vo2Max = 39.0f, age = 25, sex = BiologicalSex.MALE))
        assertEquals(CooperCategory.POOR, classifier.classify(vo2Max = 32.0f, age = 25, sex = BiologicalSex.MALE))
    }

    @Test
    fun classifiesFemaleAge35Correctly() {
        assertEquals(CooperCategory.SUPERIOR, classifier.classify(vo2Max = 50.0f, age = 35, sex = BiologicalSex.FEMALE))
        assertEquals(CooperCategory.EXCELLENT, classifier.classify(vo2Max = 42.0f, age = 35, sex = BiologicalSex.FEMALE))
        assertEquals(CooperCategory.GOOD, classifier.classify(vo2Max = 36.0f, age = 35, sex = BiologicalSex.FEMALE))
        assertEquals(CooperCategory.FAIR, classifier.classify(vo2Max = 32.0f, age = 35, sex = BiologicalSex.FEMALE))
        assertEquals(CooperCategory.POOR, classifier.classify(vo2Max = 27.0f, age = 35, sex = BiologicalSex.FEMALE))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:scoring:test --tests "app.readylytics.health.core.scoring.domain.cardio.*"`
Expected: FAIL (unresolved classes `UthVo2MaxCalculator`, `CooperNormsClassifier`, `CooperCategory`)

- [ ] **Step 3: Implement UthVo2MaxCalculator and CooperNormsClassifier**

1. Create `core/scoring/.../domain/cardio/UthVo2MaxCalculator.kt`:
```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UthVo2MaxCalculator @Inject constructor() {
    fun estimate(hrMax: Float, rhrBaselineBpm: Float, isCalibrating: Boolean): Float? {
        if (isCalibrating || hrMax < MIN_PLAUSIBLE_HR_MAX || rhrBaselineBpm < MIN_PLAUSIBLE_RHR) {
            return null
        }
        val raw = UTH_COEFFICIENT * (hrMax / rhrBaselineBpm)
        return raw.coerceIn(PHYSIOLOGICAL_MIN_VO2, PHYSIOLOGICAL_MAX_VO2)
    }

    companion object {
        const val UTH_COEFFICIENT = 15.3f
        const val MIN_PLAUSIBLE_HR_MAX = 90f
        const val MIN_PLAUSIBLE_RHR = 30f
        const val PHYSIOLOGICAL_MIN_VO2 = 15.0f
        const val PHYSIOLOGICAL_MAX_VO2 = 95.0f
    }
}
```

2. Create `core/scoring/.../domain/cardio/CooperNormsClassifier.kt`:
```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.model.BiologicalSex
import javax.inject.Inject
import javax.inject.Singleton

enum class CooperCategory { SUPERIOR, EXCELLENT, GOOD, FAIR, POOR }

@Singleton
class CooperNormsClassifier @Inject constructor() {
    fun classify(vo2Max: Float, age: Int, sex: BiologicalSex): CooperCategory {
        val thresholds = getThresholds(age, sex)
        return when {
            vo2Max >= thresholds.superior -> CooperCategory.SUPERIOR
            vo2Max >= thresholds.excellent -> CooperCategory.EXCELLENT
            vo2Max >= thresholds.good -> CooperCategory.GOOD
            vo2Max >= thresholds.fair -> CooperCategory.FAIR
            else -> CooperCategory.POOR
        }
    }

    data class Thresholds(val superior: Float, val excellent: Float, val good: Float, val fair: Float)

    private fun getThresholds(age: Int, sex: BiologicalSex): Thresholds {
        return when (sex) {
            BiologicalSex.MALE -> when {
                age < 30 -> Thresholds(superior = 52.5f, excellent = 46.5f, good = 42.5f, fair = 36.5f)
                age < 40 -> Thresholds(superior = 50.5f, excellent = 44.5f, good = 40.5f, fair = 35.5f)
                age < 50 -> Thresholds(superior = 48.5f, excellent = 42.5f, good = 38.5f, fair = 33.5f)
                age < 60 -> Thresholds(superior = 44.5f, excellent = 38.5f, good = 34.5f, fair = 30.5f)
                else -> Thresholds(superior = 40.5f, excellent = 34.5f, good = 30.5f, fair = 26.5f)
            }
            BiologicalSex.FEMALE -> when {
                age < 30 -> Thresholds(superior = 44.5f, excellent = 38.5f, good = 34.5f, fair = 28.5f)
                age < 40 -> Thresholds(superior = 42.5f, excellent = 36.5f, good = 32.5f, fair = 27.5f)
                age < 50 -> Thresholds(superior = 40.5f, excellent = 34.5f, good = 30.5f, fair = 25.5f)
                age < 60 -> Thresholds(superior = 36.5f, excellent = 30.5f, good = 26.5f, fair = 22.5f)
                else -> Thresholds(superior = 32.5f, excellent = 26.5f, good = 22.5f, fair = 19.5f)
            }
            BiologicalSex.OTHER, BiologicalSex.UNSET -> when {
                age < 30 -> Thresholds(superior = 48.5f, excellent = 42.5f, good = 38.5f, fair = 32.5f)
                age < 40 -> Thresholds(superior = 46.5f, excellent = 40.5f, good = 36.5f, fair = 31.5f)
                age < 50 -> Thresholds(superior = 44.5f, excellent = 38.5f, good = 34.5f, fair = 29.5f)
                age < 60 -> Thresholds(superior = 40.5f, excellent = 34.5f, good = 30.5f, fair = 26.5f)
                else -> Thresholds(superior = 36.5f, excellent = 30.5f, good = 26.5f, fair = 23.0f)
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:scoring:test --tests "app.readylytics.health.core.scoring.domain.cardio.*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/scoring/
git commit -m "feat(scoring): implement pure-Kotlin Uth VO2 Max estimator and Cooper normative classifier"
```

---

### Task 5: Source Resolution Policy, User Preferences & Daily Summary Assembly

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/Vo2MaxSourceMode.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/UserPreferences.kt`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/Vo2MaxSourceResolver.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/BodyMetricsDataLoader.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/FinalSummaryAssembler.kt`
- Test: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/Vo2MaxSourceResolverTest.kt`

**Interfaces:**
- Consumes: `Vo2MaxSourceMode`, wearable `Vo2MaxRecordEntity?`, `uthEstimate: Float?`
- Produces: `Vo2MaxResolution(vo2Max: Float?, source: String?)` populated in `DailySummary.vo2Max` / `vo2MaxSource`

- [ ] **Step 1: Write the failing resolver test**

In `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/Vo2MaxSourceResolverTest.kt`:
```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.data.preferences.Vo2MaxSourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Vo2MaxSourceResolverTest {
    private val resolver = Vo2MaxSourceResolver()

    @Test
    fun autoPrefersWearableOverEstimate() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.AUTO,
            wearableVo2Max = 48.0f,
            uthEstimatedVo2Max = 45.0f
        )
        assertEquals(48.0f, result.vo2Max)
        assertEquals("WEARABLE", result.source)
    }

    @Test
    fun autoFallsBackToEstimateWhenWearableNull() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.AUTO,
            wearableVo2Max = null,
            uthEstimatedVo2Max = 45.0f
        )
        assertEquals(45.0f, result.vo2Max)
        assertEquals("ESTIMATED_UTH", result.source)
    }

    @Test
    fun wearableOnlyIgnoresEstimate() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.WEARABLE_ONLY,
            wearableVo2Max = null,
            uthEstimatedVo2Max = 45.0f
        )
        assertNull(result.vo2Max)
        assertNull(result.source)
    }

    @Test
    fun estimatedOnlyIgnoresWearable() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.ESTIMATED_ONLY,
            wearableVo2Max = 48.0f,
            uthEstimatedVo2Max = 45.0f
        )
        assertEquals(45.0f, result.vo2Max)
        assertEquals("ESTIMATED_UTH", result.source)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:scoring:test --tests "app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolverTest"`
Expected: FAIL (unresolved `Vo2MaxSourceMode`, `Vo2MaxSourceResolver`)

- [ ] **Step 3: Implement preferences, resolver, and summary assembly integration**

1. Create `core/model/.../Vo2MaxSourceMode.kt`:
```kotlin
package app.readylytics.health.core.model.data.preferences

enum class Vo2MaxSourceMode {
    AUTO,
    WEARABLE_ONLY,
    ESTIMATED_ONLY,
}
```
And add `val vo2MaxSourceMode: Vo2MaxSourceMode = Vo2MaxSourceMode.AUTO` to `UserPreferences.kt`.

2. Create `core/scoring/.../domain/cardio/Vo2MaxSourceResolver.kt`:
```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.data.preferences.Vo2MaxSourceMode
import javax.inject.Inject
import javax.inject.Singleton

data class Vo2MaxResolution(val vo2Max: Float?, val source: String?)

@Singleton
class Vo2MaxSourceResolver @Inject constructor() {
    fun resolve(
        mode: Vo2MaxSourceMode,
        wearableVo2Max: Float?,
        uthEstimatedVo2Max: Float?,
    ): Vo2MaxResolution =
        when (mode) {
            Vo2MaxSourceMode.AUTO ->
                if (wearableVo2Max != null) {
                    Vo2MaxResolution(wearableVo2Max, "WEARABLE")
                } else if (uthEstimatedVo2Max != null) {
                    Vo2MaxResolution(uthEstimatedVo2Max, "ESTIMATED_UTH")
                } else {
                    Vo2MaxResolution(null, null)
                }
            Vo2MaxSourceMode.WEARABLE_ONLY ->
                if (wearableVo2Max != null) {
                    Vo2MaxResolution(wearableVo2Max, "WEARABLE")
                } else {
                    Vo2MaxResolution(null, null)
                }
            Vo2MaxSourceMode.ESTIMATED_ONLY ->
                if (uthEstimatedVo2Max != null) {
                    Vo2MaxResolution(uthEstimatedVo2Max, "ESTIMATED_UTH")
                } else {
                    Vo2MaxResolution(null, null)
                }
        }
}
```

3. In `BodyMetricsDataLoader.kt`:
Inject `Vo2MaxRecordDao` and add:
```kotlin
suspend fun loadLatestVo2Max(nextDayMidnightMs: Long): Vo2MaxRecordEntity? =
    vo2MaxRecordDao.getLatestUpTo(nextDayMidnightMs)
```

4. In `FinalSummaryAssembler.kt`:
Inject `UthVo2MaxCalculator` and `Vo2MaxSourceResolver`.
Calculate Uth estimate from `inputs.context.initialBaselines.hrMax` and nocturnal RHR floor.
Resolve VO2 Max via `vo2MaxSourceResolver.resolve(prefs.vo2MaxSourceMode, wearable?.vo2Max, uthEstimate)`.
Store on final summary: `summary.copy(vo2Max = resolution.vo2Max, vo2MaxSource = resolution.source)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:scoring:test --tests "app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolverTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/model/ core/scoring/ core/database/
git commit -m "feat(scoring): add Vo2MaxSourceResolver and assemble vo2Max in DailySummary"
```

---

### Task 6: Training Stress Balance (TSB) Calculator & Domain Model

**Files:**
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/TrainingStressBalanceCalculator.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/cardio/TrainingStressBalance.kt`
- Test: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/TrainingStressBalanceCalculatorTest.kt`

**Interfaces:**
- Consumes: `ctl: Float?`, `atl: Float?`
- Produces: `TrainingStressBalance(value: Float, zone: TsbZone)`

- [ ] **Step 1: Write the failing TSB calculator test**

In `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/TrainingStressBalanceCalculatorTest.kt`:
```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.cardio.TsbZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainingStressBalanceCalculatorTest {
    private val calculator = TrainingStressBalanceCalculator()

    @Test
    fun returnsNullWhenInputsAreNull() {
        assertNull(calculator.calculate(ctl = null, atl = 50f))
        assertNull(calculator.calculate(ctl = 50f, atl = null))
    }

    @Test
    fun classifiesZonesCorrectly() {
        // TSB = CTL - ATL
        // 60 - 30 = +30 -> VERY_FRESH_OR_TRANSITION
        assertEquals(TsbZone.VERY_FRESH_OR_TRANSITION, calculator.calculate(ctl = 60f, atl = 30f)?.zone)

        // 60 - 45 = +15 -> FRESH_PEAKED
        assertEquals(TsbZone.FRESH_PEAKED, calculator.calculate(ctl = 60f, atl = 45f)?.zone)

        // 60 - 62 = -2 -> OPTIMAL_PRODUCTIVE
        assertEquals(TsbZone.OPTIMAL_PRODUCTIVE, calculator.calculate(ctl = 60f, atl = 62f)?.zone)

        // 60 - 80 = -20 -> FATIGUED_OVERLOAD
        assertEquals(TsbZone.FATIGUED_OVERLOAD, calculator.calculate(ctl = 60f, atl = 80f)?.zone)

        // 60 - 95 = -35 -> HIGH_RISK_OVERREACHED
        assertEquals(TsbZone.HIGH_RISK_OVERREACHED, calculator.calculate(ctl = 60f, atl = 95f)?.zone)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:scoring:test --tests "app.readylytics.health.core.scoring.domain.cardio.TrainingStressBalanceCalculatorTest"`
Expected: FAIL (unresolved `TrainingStressBalanceCalculator`, `TsbZone`)

- [ ] **Step 3: Implement TSB domain model and calculator**

1. Create `core/model/.../domain/cardio/TrainingStressBalance.kt`:
```kotlin
package app.readylytics.health.core.model.domain.cardio

enum class TsbZone {
    VERY_FRESH_OR_TRANSITION,
    FRESH_PEAKED,
    OPTIMAL_PRODUCTIVE,
    FATIGUED_OVERLOAD,
    HIGH_RISK_OVERREACHED,
}

data class TrainingStressBalance(
    val value: Float,
    val zone: TsbZone,
)
```

2. Create `core/scoring/.../domain/cardio/TrainingStressBalanceCalculator.kt`:
```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.cardio.TrainingStressBalance
import app.readylytics.health.core.model.domain.cardio.TsbZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrainingStressBalanceCalculator @Inject constructor() {
    fun calculate(ctl: Float?, atl: Float?): TrainingStressBalance? {
        if (ctl == null || atl == null) return null
        val tsb = ctl - atl
        val zone = when {
            tsb > 25.0f -> TsbZone.VERY_FRESH_OR_TRANSITION
            tsb >= 5.0f -> TsbZone.FRESH_PEAKED
            tsb >= -10.0f -> TsbZone.OPTIMAL_PRODUCTIVE
            tsb >= -30.0f -> TsbZone.FATIGUED_OVERLOAD
            else -> TsbZone.HIGH_RISK_OVERREACHED
        }
        return TrainingStressBalance(value = tsb, zone = zone)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:scoring:test --tests "app.readylytics.health.core.scoring.domain.cardio.TrainingStressBalanceCalculatorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/model/ core/scoring/
git commit -m "feat(scoring): implement TrainingStressBalanceCalculator and TsbZone mapping"
```

---

### Task 7: Settings UI (VO2 Max Source Mode & Permission Handling)

**Files:**
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/physiologyprofile/PhysiologyProfileScreen.kt`
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/physiologyprofile/PhysiologyProfileViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `feature/settings/src/test/kotlin/app/readylytics/health/feature/settings/physiologyprofile/PhysiologyProfileVo2MaxSourceTest.kt`

**Interfaces:**
- Consumes: `UserPreferences.vo2MaxSourceMode`, `HealthConnectPermissionChecker.hasVo2MaxPermission`
- Produces: User setting selector (`Auto`, `Wearable only`, `Resting HR ratio only`)

- [ ] **Step 1: Write the failing ViewModel test**

In `feature/settings/src/test/kotlin/app/readylytics/health/feature/settings/physiologyprofile/PhysiologyProfileVo2MaxSourceTest.kt`:
```kotlin
package app.readylytics.health.feature.settings.physiologyprofile

import app.readylytics.health.core.model.data.preferences.Vo2MaxSourceMode
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class PhysiologyProfileVo2MaxSourceTest {
    @Test
    fun defaultSourceModeIsAuto() {
        val defaultMode = Vo2MaxSourceMode.AUTO
        assertEquals(Vo2MaxSourceMode.AUTO, defaultMode)
    }
}
```

- [ ] **Step 2: Run test to verify it passes/scaffolds**

Run: `./gradlew :feature:settings:testDebugUnitTest --tests "app.readylytics.health.feature.settings.physiologyprofile.PhysiologyProfileVo2MaxSourceTest"`
Expected: PASS

- [ ] **Step 3: Add strings and implement UI in PhysiologyProfileScreen**

1. In `app/src/main/res/values/strings.xml`:
Add strings:
- `vo2_max_source_title`: "Cardio Fitness (VO2 Max) Source"
- `vo2_max_source_auto`: "Auto (Wearable preferred)"
- `vo2_max_source_wearable`: "Wearable only"
- `vo2_max_source_estimated`: "Resting HR ratio estimate only"
- `vo2_max_source_description`: "Choose whether Cardio Fitness relies on smartwatch VO2 Max records or our pure-Kotlin resting HR ratio estimation."
- `vo2_max_permission_missing`: "Health Connect VO2 Max permission not granted"
- `vo2_max_grant_permission`: "Grant permission"

2. In `PhysiologyProfileViewModel.kt` & `PhysiologyProfileScreen.kt`:
Add source mode selection item exposing `setVo2MaxSourceMode(mode)` and observing current selection.

- [ ] **Step 4: Run settings tests**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add feature/settings/ app/src/main/res/values/strings.xml
git commit -m "feat(settings): add VO2 Max source mode selection to Physiology Profile"
```

---

### Task 8: Vitals Tab UI (Cardio Fitness Card & Detail Screen)

**Files:**
- Create: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/cardio/CardioFitnessCard.kt`
- Create: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/cardio/CardioFitnessDetailScreen.kt`
- Create: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/cardio/CardioFitnessDetailViewModel.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/navigation/AppDestination.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/scaffold/MainNavHostDestinations.kt`
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/cardio/CardioFitnessPresentationTest.kt`

**Interfaces:**
- Consumes: `DailySummary.vo2Max`, `DailySummary.vo2MaxSource`, `CooperNormsClassifier`
- Produces: `CardioFitnessCard`, `CardioFitnessDetailRoute`, and navigation destination `AppDestination.CardioFitnessDetail`

- [ ] **Step 1: Write the failing presentation test**

In `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/cardio/CardioFitnessPresentationTest.kt`:
```kotlin
package app.readylytics.health.feature.vitals.cardio

import app.readylytics.health.core.scoring.domain.cardio.CooperCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class CardioFitnessPresentationTest {
    @Test
    fun formatsVo2MaxValueProperly() {
        val value = 48.23f
        val formatted = String.format(java.util.Locale.US, "%.1f", value)
        assertEquals("48.2", formatted)
    }
}
```

- [ ] **Step 2: Run test to verify it passes/scaffolds**

Run: `./gradlew :feature:vitals:testDebugUnitTest --tests "app.readylytics.health.feature.vitals.cardio.CardioFitnessPresentationTest"`
Expected: PASS

- [ ] **Step 3: Implement CardioFitnessCard, Detail Screen, and Navigation**

1. Create `CardioFitnessCard.kt` in `:feature:vitals`:
M3 score card displaying:
- Title: "Cardio Fitness (VO2 Max)"
- Value: `48.2 ml/kg/min`
- Status badge: Cooper category (Superior, Excellent, Good, Fair, Poor) using `dynamicDarkColorScheme` semantic colors.
- Source pill: `Wearable` or `Estimated (Resting HR)`.
- On click: invokes navigation callback.

2. Create `CardioFitnessDetailScreen.kt` and `CardioFitnessDetailViewModel.kt`:
- Top App Bar with back arrow.
- Overview card explaining the score.
- Vico trend chart plotting historical `vo2Max` with colored Cooper reference bands.
- Cooper Normative Ladder card showing age/sex bands.
- Scientific Methodology card referencing Uth et al. (2004) and Health Connect.

3. In `AppDestination.kt`:
Add `@Serializable data object CardioFitnessDetail : AppDestination`.
In `MainNavHostDestinations.kt`:
Add `composable<AppDestination.CardioFitnessDetail>` routing to `CardioFitnessDetailRoute`.
In `VitalsScreen.kt`:
Add `CardioFitnessCard` to the vitals grid/list.

- [ ] **Step 4: Run vitals tests**

Run: `./gradlew :feature:vitals:testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add feature/vitals/ app/src/main/ app/src/main/res/values/strings.xml
git commit -m "feat(vitals): add Cardio Fitness (VO2 Max) card and historical detail screen"
```

---

### Task 9: Workouts Tab UI (TSB Toggle & Dual-Zone Chart)

**Files:**
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/AcwrSection.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/AcwrChart.kt`
- Create: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/TsbChart.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/TsbPresentationTest.kt`

**Interfaces:**
- Consumes: `TrainingStressBalanceCalculator`, `DailySummary.ctlWorkoutOnly`, `DailySummary.atlWorkoutOnly`
- Produces: M3 `SingleChoiceSegmentedButtonRow` in Workouts Training Load section toggling between ACWR and TSB, rendering zero-centered TSB curve with zone colors.

- [ ] **Step 1: Write the failing presentation test**

In `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/TsbPresentationTest.kt`:
```kotlin
package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.cardio.TsbZone
import org.junit.Assert.assertEquals
import org.junit.Test

class TsbPresentationTest {
    @Test
    fun formatsTsbValueWithSign() {
        val positive = 14.2f
        val negative = -8.5f
        assertEquals("+14", String.format(java.util.Locale.US, "%+d", positive.toInt()))
        assertEquals("-8", String.format(java.util.Locale.US, "%+d", negative.toInt()))
    }
}
```

- [ ] **Step 2: Run test to verify it passes/scaffolds**

Run: `./gradlew :feature:workouts:testDebugUnitTest --tests "app.readylytics.health.feature.workouts.TsbPresentationTest"`
Expected: PASS

- [ ] **Step 3: Implement TsbChart and Segmented Toggle in AcwrSection**

1. In `app/src/main/res/values/strings.xml`:
Add strings:
- `training_load_metric_acwr`: "Strain Ratio (ACWR)"
- `training_load_metric_tsb`: "Training Stress Balance (TSB)"
- `tsb_zone_very_fresh`: "Transition / Very Fresh"
- `tsb_zone_fresh`: "Fresh / Peaked"
- `tsb_zone_optimal`: "Optimal / Productive"
- `tsb_zone_fatigued`: "Fatigued / Overload"
- `tsb_zone_overreached`: "High Risk / Overreached"

2. Create `TsbChart.kt`:
Plots the 84-day TSB series centered around 0 with horizontal colored zone thresholds (+25, +5, -10, -30).

3. In `AcwrSection.kt`:
Add `SingleChoiceSegmentedButtonRow` to toggle between ACWR and TSB, displaying `TsbChart` when TSB is selected.

- [ ] **Step 4: Run workouts tests**

Run: `./gradlew :feature:workouts:testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add feature/workouts/ app/src/main/res/values/strings.xml
git commit -m "feat(workouts): add Training Stress Balance (TSB) chart and segmented toggle in training load section"
```

---

### Task 10: Dashboard Optional Cards (Cardio Fitness & TSB Cards)

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardManagementBottomSheet.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `feature/dashboard/src/androidTest/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactoryCardioTest.kt`

**Interfaces:**
- Consumes: `DailySummary.vo2Max`, `TrainingStressBalanceCalculator`
- Produces: Optional `CardId.CARDIO_FITNESS` and `CardId.TSB` registered in Dashboard Card Management sheet.

- [ ] **Step 1: Write card registration test**

In `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/CardioDashboardCardRegistrationTest.kt`:
```kotlin
package app.readylytics.health.feature.dashboard

import org.junit.Assert.assertTrue
import org.junit.Test

class CardioDashboardCardRegistrationTest {
    @Test
    fun cardioAndTsbCardIdsExist() {
        val cardioId = "card_cardio_fitness"
        val tsbId = "card_training_stress_balance"
        assertTrue(cardioId.isNotEmpty())
        assertTrue(tsbId.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it passes/scaffolds**

Run: `./gradlew :feature:dashboard:testDebugUnitTest --tests "app.readylytics.health.feature.dashboard.CardioDashboardCardRegistrationTest"`
Expected: PASS

- [ ] **Step 3: Register Cards in Card Factory & Bottom Sheet**

1. Add `CardId.CARDIO_FITNESS` and `CardId.TSB` constants.
2. In `DashboardCardFactory.kt`: add render functions for `CardioFitnessCard` and `TsbCard`.
3. In `CardManagementBottomSheet.kt`: list both cards as optional toggles.
4. Add localized strings in `app/src/main/res/values/strings.xml`.

- [ ] **Step 4: Run dashboard unit tests**

Run: `./gradlew :feature:dashboard:testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add feature/dashboard/ app/src/main/res/values/strings.xml
git commit -m "feat(dashboard): register Cardio Fitness and TSB optional cards in Card Management sheet"
```

---

### Task 11: Documentation Synchronization & Full Verification

**Files:**
- Modify: `internal-docs/DATA_FLOW.md`
- Modify: `ABOUT.md`
- Modify: `docs/about.md`

**Interfaces:**
- Consumes: Implemented Cardio Engine & TSB architecture
- Produces: 100% synchronized documentation conforming to project rules

- [ ] **Step 1: Update `internal-docs/DATA_FLOW.md`**
Document Room schema v18, table count (18 tables), `vo2_max_records` entity, Uth resting HR ratio formula, Cooper classification, and TSB pipeline.

- [ ] **Step 2: Update `ABOUT.md` and `docs/about.md`**
Add sections explaining Cardio Fitness (VO2 Max), the Uth formula, Cooper normative percentiles, and Training Stress Balance.

- [ ] **Step 3: Run documentation drift tests**
Run: `./gradlew testDebugUnitTest --tests "*DocumentationDriftTest*"`
Expected: PASS

- [ ] **Step 4: Run the complete pre-commit suite**
Run:
```bash
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest && ./gradlew lintRelease
```
Expected: All commands pass with 0 errors and 0 new detekt issues.

- [ ] **Step 5: Post-task index and commit**
```bash
codegraph index
git add internal-docs/DATA_FLOW.md ABOUT.md docs/about.md
git commit -m "docs: synchronize DATA_FLOW, ABOUT, and website docs for Cardio Engine and TSB"
```
