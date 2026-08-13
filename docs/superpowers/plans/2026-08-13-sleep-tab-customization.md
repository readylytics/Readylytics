# Sleep Tab Layout Customization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement full layout customization (reordering, visibility toggle, display modes) for the Sleep tab, matching the Vitals customization architecture established in PR #211.

**Architecture:** Add `SleepTopCardId`, `SleepChartId`, and `SleepMetricCardId` models and DataStore layout repository (`SleepLayoutRepository`). Integrate layout persistence with `LocalBackupManager` and `LocalRestoreManager`. Wire card/chart management delegates into `SleepViewModel` and build `SleepManagementBottomSheet` with Edit Mode FAB controls on `SleepScreen`.

**Tech Stack:** Kotlin, Jetpack Compose (Material Design 3), Hilt, DataStore (Proto/Preferences), WorkManager, JUnit4, MockK, Robolectric.

## Global Constraints

- **Single Source of Truth:** Room DB is single source of truth; layout preferences stored via DataStore and domain models.
- **Idempotency & Pre-Commit Validation:** `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` must pass for every task.
- **Material 3 UI:** Use native Material Design 3 components and spacing tokens (`MaterialTheme.spacing`).
- **Strings:** All user-facing strings must be defined in `strings.xml`.

---

### Task 1: Domain Models & Sleep Layout Repository Interface

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepTopCardId.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepChartId.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepMetricCardId.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepTopCardConfiguration.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepChartConfiguration.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepMetricCardConfiguration.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepLayoutRepository.kt`

**Interfaces:**
- Produces: `SleepTopCardId`, `SleepChartId`, `SleepMetricCardId`, layout configuration classes, and `SleepLayoutRepository` domain interface.

- [ ] **Step 1: Write failing domain model and layout configuration tests**
- [ ] **Step 2: Run test to verify failure**
- [ ] **Step 3: Define enums, configuration classes, and repository interface**
- [ ] **Step 4: Run test to verify pass**
- [ ] **Step 5: Commit task**

---

### Task 2: Data Persistence, Proto DataStore & Layout Repository Implementation

**Files:**
- Create: `app/src/main/proto/sleep_layout_configurations.proto`
- Create: `app/src/main/kotlin/app/readylytics/health/data/preferences/SleepLayoutConfigurationsSerializer.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/preferences/SleepLayoutMapper.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/data/preferences/SleepLayoutRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/di/DataStoreModule.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/di/RepositoryModule.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/preferences/SleepLayoutRepositoryTest.kt`

**Interfaces:**
- Consumes: Domain interfaces from Task 1.
- Produces: DataStore proto storage, DI bindings, and `SleepLayoutRepositoryImpl`.

- [ ] **Step 1: Write failing SleepLayoutRepository test**
- [ ] **Step 2: Run test to verify failure**
- [ ] **Step 3: Implement Proto DataStore, mapper, repository, and DI module bindings**
- [ ] **Step 4: Run test to verify pass**
- [ ] **Step 5: Commit task**

---

### Task 3: Local Backup & Restore Integration

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupModels.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalRestoreManager.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/backup/LocalBackupManagerTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/backup/LocalRestoreValidationTest.kt`

**Interfaces:**
- Consumes: `SleepLayoutRepository` from Task 2.
- Produces: Full backup and restore capability for Sleep layout settings.

- [ ] **Step 1: Write failing backup & restore tests for sleep layout**
- [ ] **Step 2: Run test to verify failure**
- [ ] **Step 3: Update backup models, export in LocalBackupManager, restore in LocalRestoreManager**
- [ ] **Step 4: Run test to verify pass**
- [ ] **Step 5: Commit task**

---

### Task 4: UI Strings & Display Name Extensions

**Files:**
- Modify: `feature/sleep/src/main/res/values/strings.xml`
- Create: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/overview/SleepCardIdExtensions.kt`
- Create: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/overview/SleepChartIdExtensions.kt`

**Interfaces:**
- Consumes: Domain IDs from Task 1.
- Produces: String resources and human-readable title extensions for Sleep cards, charts, and metrics.

- [ ] **Step 1: Add string resources to strings.xml**
- [ ] **Step 2: Implement extension functions `SleepTopCardId.displayNameResource()`, `SleepChartId.displayNameResource()`, `SleepMetricCardId.displayNameResource()`**
- [ ] **Step 3: Run ktlintFormat & unit tests**
- [ ] **Step 4: Commit task**

---

### Task 5: Sleep Management Bottom Sheet & Reorderable List Components

**Files:**
- Create: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/overview/SleepManagementBottomSheet.kt`

**Interfaces:**
- Consumes: UI strings and extension titles from Task 4.
- Produces: Unified bottom sheet for managing Sleep top cards, charts, and metric grid cards.

- [ ] **Step 1: Create `SleepManagementBottomSheet` composable with sectioned/tabbed reorderable items, visibility switches, and display mode pickers**
- [ ] **Step 2: Run ktlintFormat**
- [ ] **Step 3: Commit task**

---

### Task 6: SleepViewModel Layout Delegates Wiring & Data-Driven SleepScreen

**Files:**
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt`
- Test: `feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepViewModelLayoutManagementTest.kt`

**Interfaces:**
- Consumes: `SleepLayoutRepository`, `SleepManagementBottomSheet`, and `EditModeFab`.
- Produces: Fully customizable data-driven Sleep tab UI with Edit Mode FAB and bottom sheet interactions.

- [ ] **Step 1: Write failing SleepViewModel layout management test**
- [ ] **Step 2: Run test to verify failure**
- [ ] **Step 3: Wire delegates into `SleepViewModel` and render sections dynamically in `SleepScreen`**
- [ ] **Step 4: Run test to verify pass**
- [ ] **Step 5: Commit task**

---

### Task 7: Verification & Final Clean-Up

**Files:**
- Modify: `internal-docs/DATA_FLOW.md`

- [ ] **Step 1: Update `internal-docs/DATA_FLOW.md` to document Sleep layout persistence pipeline**
- [ ] **Step 2: Run mandatory pre-commit check `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`**
- [ ] **Step 3: Run release lint check `./gradlew lintRelease`**
- [ ] **Step 4: Commit final changes**

---
