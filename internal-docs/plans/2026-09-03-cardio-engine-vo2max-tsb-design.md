# Design: Cardio Engine (VO2 Max & Training Stress Balance)

> **Date:** 2026-09-03  
> **Status:** APPROVED  
> **Target Module(s):** `:core:model`, `:core:database-schema`, `:core:database`, `:core:healthconnect`, `:core:scoring`, `:feature:vitals`, `:feature:workouts`, `:feature:dashboard`, `:feature:settings`, `:app`

---

## 1. Overview & Problem Statement

Readylytics tracks autonomic sleep recovery (HRV, RHR, sleep architecture), internal training impulse (TRIMP/iTRIMP), and acute-to-chronic workload ratios (ACWR). However, it currently lacks two foundational pillars of sports science and cardiovascular fitness:

1. **Cardio Fitness / VO2 Max:** Maximum oxygen uptake ($\text{ml/kg/min}$) is the gold standard metric of cardiorespiratory fitness and all-cause mortality risk reduction. Currently, Readylytics neither ingests Health Connect `Vo2MaxRecord`s nor estimates aerobic capacity for users without wearable VO2 Max support.
2. **Training Stress Balance (TSB / Form & Freshness):** While ACWR measures injury-risk spike probability, classical Banister impulse-response modeling uses $\text{TSB} = \text{CTL} - \text{ATL}$ to evaluate whether an athlete is fatigued, building productively, or fresh/tapered for peak performance.

This design implements a complete offline-first **Cardio Engine**:
- Ingests wearable `Vo2MaxRecord`s from Android Health Connect.
- Provides a pure-Kotlin scientific estimation fallback using the **Uth et al. (2004) Heart Rate Ratio Method** based on nocturnal RHR baselines.
- Benchmarks scores against **The Cooper Institute / ACSM age- and sex-stratified normative bands**.
- Provides a user-configurable source resolution mode (`Auto`, `Wearable only`, `Resting HR ratio only`).
- Integrates **Training Stress Balance (TSB)** into the Workouts tab and Dashboard as an actionable complement to ACWR.

---

## 2. Health Connect Ingestion Layer

### 2.1 Permissions
- Add `android.permission.health.READ_VO2_MAX` to `app/src/main/AndroidManifest.xml`.
- Add permission declaration to `core/model/.../HealthConnectRepository.kt` in `OPTIONAL_PERMISSIONS`.
- Expose `hasVo2MaxPermission()` on `HealthConnectPermissionChecker`.

### 2.2 Domain DTO
In `core/model/.../HealthConnectRecords.kt`:
```kotlin
data class DomainVo2MaxRecord(
    val id: String,
    val time: Instant,
    val vo2MillilitersPerMinuteKilogram: Double,
    val measurementMethod: Int?,
    val deviceName: String,
)
```

### 2.3 Reader & Mapping
In `core/healthconnect/.../HealthConnectRepositoryImpl.kt`:
- Add `readVo2MaxRecords(start: Instant, end: Instant): List<DomainVo2MaxRecord>`.
- Paginate via `readAllPages<Vo2MaxRecord>()`.
- Map Android Health Connect SDK `Vo2MaxRecord` to `DomainVo2MaxRecord`, recording `record.vo2MillilitersPerMinuteKilogram.value` and `record.measurementMethod`.
- Guard with `HealthConnectRetryPolicy` against transient I/O or rate limits.

---

## 3. Database & Schema Migration (Room v17 → v18)

### 3.1 Entity Definitions
In `core/database-schema`:
1. **New Entity `Vo2MaxRecordEntity`:**
   ```kotlin
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
2. **Update `DailySummaryEntity`:**
   Add two columns:
   ```kotlin
   val vo2Max: Float? = null,
   val vo2MaxSource: String? = null, // "WEARABLE" | "ESTIMATED_UTH" | null
   ```

### 3.2 DAO & Migration
1. **New `Vo2MaxRecordDao`:**
   - `@Upsert suspend fun upsertAll(records: List<Vo2MaxRecordEntity>)`
   - `@Query("SELECT * FROM vo2_max_records WHERE timestampMs >= :startMs AND timestampMs < :endMs ORDER BY timestampMs DESC")`
   - `@Query("SELECT * FROM vo2_max_records ORDER BY timestampMs DESC LIMIT 1")`
   - `@Query("DELETE FROM vo2_max_records WHERE timestampMs < :cutoffMs")`
2. **Database Version:**
   Bump `HealthDatabase.DATABASE_VERSION` from `17` to `18`.
3. **Migration `MIGRATION_17_18`:**
   ```kotlin
   val MIGRATION_17_18 = object : Migration(17, 18) {
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
               """.trimIndent()
           )
           db.execSQL("CREATE INDEX IF NOT EXISTS `index_vo2_max_records_timestampMs` ON `vo2_max_records` (`timestampMs`)")
           db.execSQL("ALTER TABLE `daily_summaries` ADD COLUMN `vo2Max` REAL DEFAULT NULL")
           db.execSQL("ALTER TABLE `daily_summaries` ADD COLUMN `vo2MaxSource` TEXT DEFAULT NULL")
       }
   }
   ```

### 3.3 Lifecycle Maintenance Integration
- **Retention & Data Cleanup:** Add `vo2_max_records` to `RetentionCleanup.deleteBefore()` and `DataCleanupWorker`.
- **Encrypted Local Backup/Restore:** Include `vo2_max_records` in `LocalBackupManager` table exports and restore verification schema.
- **Historical Resync:** `HealthIngestionCoordinator.ingestWindow` persists `vo2_max_records`. `ResyncRangeUseCase` recalculates daily summaries with VO2 Max walk-forward.

---

## 4. Pure-Kotlin Calculation Engine (`:core:scoring`)

### 4.1 Uth Heart Rate Ratio VO2 Max Estimator
* **Source:** Uth N, Sørensen H, Overgaard K, Pedersen PK. *Estimation of VO2max from the ratio between maximal and resting heart rate – the Heart Rate Ratio Method.* Eur J Appl Physiol. 2004 Jan;91(1):111-5.
* **Algorithm:**
  $$\text{VO}_2\text{max} = 15.3 \times \frac{\text{HR}_{\max}}{\text{RHR}_{\text{baseline}}}$$
* **Implementation (`UthVo2MaxCalculator.kt`):**
  - Inputs: `hrMax: Float`, `rhrBaselineBpm: Float`, `isCalibrating: Boolean`.
  - Returns `Float?`:
    - Returns `null` if `isCalibrating == true` (<7 valid baseline nights).
    - Returns `null` if `rhrBaselineBpm < 30f` or `hrMax < 100f` (unphysiological inputs).
    - Computes raw estimate and clamps to physiological human limits $[15.0\text{f}, 95.0\text{f}]$.

### 4.2 Source Resolution Policy (`Vo2MaxSourceResolver.kt`)
* **Modes (`Vo2MaxSourceMode`):**
  - `AUTO`: Checks for a wearable reading on date (or within trailing 30-day window). If present, resolves as `(wearableVo2Max, "WEARABLE")`. If absent, computes Uth estimate as `(uthVo2Max, "ESTIMATED_UTH")`.
  - `WEARABLE_ONLY`: Uses only wearable reading. If absent, resolves to `null`.
  - `ESTIMATED_ONLY`: Always computes and returns Uth estimate.

### 4.3 Cooper Norms Classifier (`CooperNormsClassifier.kt`)
* **Categories:** `SUPERIOR`, `EXCELLENT`, `GOOD`, `FAIR`, `POOR`.
* **Standard Tables:** Stratified by biological sex (`MALE`, `FEMALE`, `UNSPECIFIED` / midpoint) and age decades (20–29, 30–39, 40–49, 50–59, 60+).
* Maps to standard M3 semantic status:
  - `SUPERIOR`, `EXCELLENT` $\rightarrow$ `Optimal` (Green)
  - `GOOD` $\rightarrow$ `Neutral` (Blue)
  - `FAIR` $\rightarrow$ `Warning` (Amber)
  - `POOR` $\rightarrow$ `Poor` (Red)

### 4.4 Training Stress Balance Calculator (`TrainingStressBalanceCalculator.kt`)
* **Formula:** $\text{TSB} = \text{CTL} - \text{ATL}$
* **Input:** Daily chronic training load (`ctl`) and acute training load (`atl`) from `DailySummaryEntity`.
* **Physiological State Zones:**
  - $> +25$: `VERY_FRESH_OR_TRANSITION` (High freshness; monitor for detraining if sustained $>14$ days)
  - $+5 \text{ to } +25$: `FRESH_PEAKED` (Optimal race/event performance window)
  - $-10 \text{ to } +5$: `OPTIMAL_PRODUCTIVE` (Sweet spot: building fitness while absorbing fatigue)
  - $-30 \text{ to } -10$: `FATIGUED_OVERLOAD` (Overload training block; prioritize sleep)
  - $< -30$: `HIGH_RISK_OVERREACHED` (Severe fatigue; injury/overtraining risk elevated)

---

## 5. UI, Navigation & Presentation

### 5.1 Vitals Tab
- **`CardioFitnessCard`:**
  - Metric gauge displaying current VO2 Max (e.g., `48.2 ml/kg/min`).
  - Status badge: e.g. `Excellent` (Green).
  - Source pill: `Wearable (Pixel Watch)` or `Estimated (Resting HR)`.
  - Tap navigates to `AppDestination.CardioFitnessDetail`.
- **`CardioFitnessDetailScreen` (`AppDestination.CardioFitnessDetail`):**
  - Metric summary header with Cooper category explanation.
  - Vico historical trend chart (Cubic Bezier curve, shaded Cooper reference bands, 30D / 90D / 1Y / All chips).
  - Cooper Institute Normative Ladder card showing cohort cutoffs.
  - Scientific Methodology card explaining the Uth et al. formula and wearable source.

### 5.2 Workouts Tab
- **In `WeeklyTrainingSection` / `AcwrSection`:**
  - Add native M3 `SingleChoiceSegmentedButtonRow`: `[ Strain Ratio (ACWR) | Training Stress Balance (TSB) ]`.
  - When TSB selected:
    - Displays today's TSB value and badge (e.g. `+12 Fresh`).
    - Plots the 84-day TSB curve centered around zero, color-shaded by the 5 TSB physiological zones.

### 5.3 Dashboard Tab
- Register `CardioFitnessCard` and `TsbCard` as independent optional cards in `CardManagementBottomSheet`.
- When enabled, appear on Dashboard following standard M3 card sizing.

### 5.4 Settings Screen
- Add "Cardio Fitness (VO2 Max) Source" under Physiology Profile:
  - Options: `Auto (Recommended)`, `Wearable only`, `Resting HR ratio only`.
  - Shows Health Connect `READ_VO2_MAX` status with one-tap recovery link.

---

## 6. Verification & Documentation Sync

### 6.1 Documentation Synchronization (Mandatory)
1. **`internal-docs/DATA_FLOW.md`:** Update Room schema table count (17 → 18), document `Vo2MaxRecordEntity`, Uth formula, and TSB data flow.
2. **`ABOUT.md` & `docs/about.md`:** Add sections for VO2 Max / Cardio Fitness, Cooper classification, Uth formula, and TSB vs ACWR.
3. **`app/src/main/res/values/strings.xml`:** Add all localized strings for Cooper categories, TSB zones, card titles, and tooltips.

### 6.2 Testing Plan
1. **Unit Tests (`:core:scoring`):**
   - `UthVo2MaxCalculatorTest`: Verify calculation, boundary clamping, calibration gating.
   - `CooperNormsClassifierTest`: Matrix test of age bands and sex categories.
   - `TrainingStressBalanceCalculatorTest`: Verify boundary transitions (+25, +5, -10, -30).
2. **Migration Test (`:core:database`):**
   - `HealthDatabaseMigrationTest`: Test `MIGRATION_17_18` verifying existing data survives and new table/columns exist.
3. **Ingestion Test (`:core:healthconnect`):**
   - `Vo2MaxRecordMapperTest`: Validate HC SDK to domain DTO mapping.
4. **Pre-Commit Verification:**
   - `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest && ./gradlew lintRelease`
