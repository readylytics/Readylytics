# Dashboard Insight Cards — Design Spec

**Date:** 2026-06-11
**Approach:** Compute-on-read + dismissal-only Room (Approach B)

## Overview

Surface actionable health insights (late HR nadir, possible illness, overreaching) as
dismissible full-width cards inside the existing reorderable dashboard grid. Insight
activation derives from `RecoveryFlag` values already computed by the scoring engine —
no new evaluation logic needed. Only dismissal state is persisted to Room, ensuring
insights always reflect current scored data without staleness risk.

## Architecture Decision

The scoring engine already computes three `RecoveryFlag` values that map 1:1 to the
desired insight types:

| InsightType        | RecoveryFlag     | Source                           |
|--------------------|------------------|----------------------------------|
| `LATE_NADIR`       | `NADIR_DELAYED`  | `DailySummary.recoveryFlags`     |
| `SICK_INDICATOR`   | `ILLNESS_ONSET`  | `DailySummary.recoveryFlags`     |
| `OVERREACHING`     | `OVERREACHING`   | `DailySummary.recoveryFlags`     |

Rather than duplicating activation state into a second table (Approach A), insights are
derived at read time from the existing `DailySummary`. Only dismissal state requires
persistence. This eliminates staleness concerns when physiology profile settings change
between resyncs and keeps the new table minimal.

---

## 1. Data Layer

### 1.1 InsightType enum

**File:** `domain/model/InsightType.kt`

```kotlin
enum class InsightType {
    LATE_NADIR,
    SICK_INDICATOR,
    OVERREACHING;

    companion object {
        fun fromRecoveryFlag(flag: RecoveryFlag): InsightType? = when (flag) {
            RecoveryFlag.NADIR_DELAYED  -> LATE_NADIR
            RecoveryFlag.ILLNESS_ONSET  -> SICK_INDICATOR
            RecoveryFlag.OVERREACHING   -> OVERREACHING
            else -> null
        }
    }
}
```

Pure Kotlin, zero Android dependencies.

### 1.2 InsightDismissalEntity

**File:** `data/local/entity/InsightDismissalEntity.kt`

```kotlin
@Entity(
    tableName = "insight_dismissals",
    primaryKeys = ["dateMidnightMs", "type"]
)
data class InsightDismissalEntity(
    val dateMidnightMs: Long,
    val type: String,       // InsightType.name (follows existing enum-as-string pattern)
)
```

**Semantics:** Row existence = dismissed. Insert to dismiss, delete to restore. No
`isDismissed` boolean — the table only contains records for dismissed insights.

**Conventions followed:**
- Date stored as `Long` epoch milliseconds (matches `DailySummaryEntity.dateMidnightMs`)
- Enum stored as `String` (matches `HeartRateRecordEntity.recordType`, `WorkoutRecordEntity.exerciseType`)
- Composite primary key on `(dateMidnightMs, type)`

### 1.3 InsightDismissalDao

**File:** `data/local/dao/InsightDismissalDao.kt`

```kotlin
@Dao
interface InsightDismissalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun dismiss(entity: InsightDismissalEntity)

    @Query("DELETE FROM insight_dismissals WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun restoreAllForDate(dateMidnightMs: Long)

    @Query("SELECT * FROM insight_dismissals WHERE dateMidnightMs = :dateMidnightMs")
    fun observeForDate(dateMidnightMs: Long): Flow<List<InsightDismissalEntity>>
}
```

**Conventions followed:**
- `@Insert` with `IGNORE` for idempotent dismissal (matches existing DAO patterns)
- Suspend for one-shot mutations, `Flow` for reactive observation
- Flow consumers should apply `.distinctUntilChanged()` at the call site

### 1.4 Database Migration (version 27 → 28)

**File:** `data/local/DatabaseMigrations.kt` (append to `all` array)

```sql
CREATE TABLE IF NOT EXISTS insight_dismissals (
    dateMidnightMs INTEGER NOT NULL,
    type TEXT NOT NULL,
    PRIMARY KEY (dateMidnightMs, type)
)
```

**File:** `data/local/HealthDatabase.kt`
- Bump `version = 28`
- Add `InsightDismissalEntity` to `@Database(entities = [...])`
- Add `abstract fun insightDismissalDao(): InsightDismissalDao`

---

## 2. Domain Layer

### 2.1 InsightDeriver

**File:** `domain/dashboard/InsightDeriver.kt`

Pure-Kotlin derivation logic extracted for testability:

```kotlin
object InsightDeriver {
    fun derive(
        recoveryFlags: Set<RecoveryFlag>,
        dismissedTypes: Set<InsightType>,
    ): DerivedInsights {
        val active = recoveryFlags.mapNotNull { InsightType.fromRecoveryFlag(it) }.toSet()
        return DerivedInsights(
            active = active,
            visible = active - dismissedTypes,
            dismissedCount = (active intersect dismissedTypes).size,
        )
    }
}

data class DerivedInsights(
    val active: Set<InsightType>,
    val visible: Set<InsightType>,
    val dismissedCount: Int,
)
```

Zero Android dependencies. Called by `transformToUiState()` in the ViewModel.

---

## 3. ViewModel Integration

### 3.1 Dismissal flow

Fold the dismissal observation into `createDashboardBasicInputsFlow()` in
`DashboardFlowIntermediate.kt`, since it is date-dependent:

```kotlin
val dismissalFlow = selectedDateRepository.selectedDate
    .flatMapLatest { date ->
        insightDismissalDao.observeForDate(date.toEpochMs())
    }
    .map { entities ->
        entities.mapNotNull { runCatching { InsightType.valueOf(it.type) }.getOrNull() }.toSet()
    }
    .distinctUntilChanged()
```

Add `dismissedInsightTypes: Set<InsightType>` to the `DashboardBasicInputs` data class.

### 3.2 State derivation in transformToUiState()

```kotlin
val derived = InsightDeriver.derive(
    recoveryFlags = summary?.recoveryFlags ?: emptySet(),
    dismissedTypes = inputs.dismissedInsightTypes,
)
// ... build DashboardUiState with:
//     activeInsightTypes = derived.active,
//     visibleInsights = derived.visible,
//     dismissedInsightCount = derived.dismissedCount,
```

### 3.3 DashboardUiState additions

```kotlin
data class DashboardUiState(
    // ... existing 19 fields unchanged ...
    val activeInsightTypes: Set<InsightType> = emptySet(),
    val visibleInsights: Set<InsightType> = emptySet(),
    val dismissedInsightCount: Int = 0,
)
```

- `activeInsightTypes` — insights whose recovery flag is active (regardless of dismissal). Used by the card factory to decide whether to include the CardId in the map.
- `visibleInsights` — active AND not dismissed. Drives the `AnimatedVisibility` `visible` parameter.
- `dismissedInsightCount` — drives the restore button visibility.

### 3.4 Events

Add to `DashboardEvent`:

```kotlin
data class DismissInsight(val type: InsightType) : DashboardEvent
data object RestoreInsights : DashboardEvent
```

Handlers in `DashboardViewModel`:

```kotlin
is DashboardEvent.DismissInsight -> viewModelScope.launch {
    val dateMs = _selectedDate.value.toEpochMs()
    insightDismissalDao.dismiss(InsightDismissalEntity(dateMs, event.type.name))
}
is DashboardEvent.RestoreInsights -> viewModelScope.launch {
    val dateMs = _selectedDate.value.toEpochMs()
    insightDismissalDao.restoreAllForDate(dateMs)
}
```

No scoring recalculation — Room mutation → Flow emission → UI recomposition.

---

## 4. UI Layer

### 4.1 CardId additions

**File:** `domain/dashboard/CardConfiguration.kt`

```kotlin
enum class CardId {
    // ... existing 19 entries ...
    INSIGHT_LATE_NADIR,
    INSIGHT_SICK_INDICATOR,
    INSIGHT_OVERREACHING,
}
```

### 4.2 Full-width registration

**File:** `ui/components/ReorderableCardGrid.kt`

```kotlin
private val FULL_WIDTH_CARDS = setOf(
    CardId.STEPS,
    CardId.INSIGHT_LATE_NADIR,
    CardId.INSIGHT_SICK_INDICATOR,
    CardId.INSIGHT_OVERREACHING,
)
```

All insight cards span the full grid width (their own row). Fully reorderable — users
can drag them anywhere in the grid alongside existing cards.

### 4.3 InsightCard composable

**File:** `ui/components/InsightCard.kt` (~60 lines)

```
┌──────────────────────────────────────────────┐
│  ◷  Late HR Nadir                        [✕] │
│     Your heart rate reached its lowest       │
│     point later than usual                   │
└──────────────────────────────────────────────┘
```

**Design tokens (neutral M3 dark palette):**

| Element    | Token                                                      |
|------------|------------------------------------------------------------|
| Container  | `OutlinedCard`, `containerColor = surfaceVariant(alpha=0.3f)` |
| Border     | `BorderStroke(1.dp, outlineVariant)`                       |
| Shape      | `MaterialTheme.shapes.large` (16dp)                       |
| Title      | `titleSmall` / `onSurface`                                 |
| Body       | `bodySmall` / `onSurfaceVariant`                           |
| Icon tint  | `onSurfaceVariant`                                         |
| Close icon | `Icons.Default.Close` / `onSurfaceVariant`                 |

**Composable signature:**

```kotlin
@Composable
fun InsightCard(
    title: String,
    body: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Layout:** `Row` → leading icon (24dp) + 12dp spacing + `Column(title, body)` +
weight spacer + `IconButton(Close)`.

**Icons per type:**
- `LATE_NADIR` → `Icons.Outlined.Schedule`
- `SICK_INDICATOR` → `Icons.Outlined.MonitorHeart`
- `OVERREACHING` → `Icons.Outlined.TrendingUp`

### 4.4 Card factory integration

**File:** `ui/dashboard/DashboardCardFactory.kt`

Add parameter `onDismissInsight: (InsightType) -> Unit` to `buildCardDataMap()`.

For each insight type, the factory includes the CardId in the map when the recovery
flag is **active** (regardless of dismissal). The `AnimatedVisibility` wrapper handles
the visual transition:

```kotlin
if (InsightType.LATE_NADIR in uiState.activeInsightTypes) {
    cardMap[CardId.INSIGHT_LATE_NADIR] = {
        AnimatedVisibility(
            visible = InsightType.LATE_NADIR in uiState.visibleInsights,
            enter = expandVertically(tween(300)) + fadeIn(),
            exit = shrinkVertically(tween(300)) + fadeOut(),
        ) {
            InsightCard(
                title = stringResource(R.string.insight_late_nadir_title),
                body = stringResource(R.string.insight_late_nadir_body),
                icon = Icons.Outlined.Schedule,
                onDismiss = { onDismissInsight(InsightType.LATE_NADIR) },
            )
        }
    }
}
```

Same pattern for `SICK_INDICATOR` and `OVERREACHING`.

**Three-layer visibility logic:**
1. `CardConfiguration.isVisible` — user has globally enabled this insight type via card management (filtered before reaching the factory)
2. `activeInsightTypes` — recovery flag is active for the selected date → card enters the map (slot allocated in grid)
3. `visibleInsights` — active AND not dismissed → `AnimatedVisibility(visible = true)` renders content

All three must be true for the card to render. Dismissing flips layer 3 only, triggering the shrink/fade exit animation while the grid slot remains until the condition itself clears.

### 4.5 Restore button

**File:** `ui/dashboard/DashboardScreen.kt`

New LazyColumn item placed between `item("metric_grid")` and `item("spacer_bottom")`.
Visible only when `dismissedInsightCount > 0`:

```kotlin
if (uiState.dismissedInsightCount > 0) {
    item("restore_insights") {
        TextButton(
            onClick = { viewModel.onEvent(DashboardEvent.RestoreInsights) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.insight_restore_dismissed, uiState.dismissedInsightCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

### 4.6 String resources

**File:** `app/src/main/res/values/strings.xml`

```xml
<!-- Dashboard insight cards -->
<string name="insight_late_nadir_title">Late HR Nadir</string>
<string name="insight_late_nadir_body">Your heart rate reached its lowest point later than usual</string>
<string name="insight_sick_indicator_title">Possible Illness</string>
<string name="insight_sick_indicator_body">Elevated resting heart rate with depressed HRV may indicate illness onset</string>
<string name="insight_overreaching_title">Overreaching</string>
<string name="insight_overreaching_body">Your training load significantly exceeds your chronic fitness level</string>
<string name="insight_restore_dismissed">Show %1$d dismissed insight(s)</string>
<string name="insight_dismiss_description">Dismiss insight</string>
```

### 4.7 Zero-insights behavior

When no `RecoveryFlag` maps to an `InsightType`, no insight `CardId` enters the map.
The grid renders identically to today — no empty slots, no spacing artifacts. The
restore button is hidden (`dismissedInsightCount == 0`).

---

## 5. Testing

### 5.1 Unit tests (pure Kotlin, zero Android deps)

**`InsightTypeTest`** — `fromRecoveryFlag()` mapping:

| Input               | Expected            |
|---------------------|---------------------|
| `NADIR_DELAYED`     | `LATE_NADIR`        |
| `ILLNESS_ONSET`     | `SICK_INDICATOR`    |
| `OVERREACHING`      | `OVERREACHING`      |
| `CALIBRATING`       | `null`              |
| `HRV_MISSING`       | `null`              |
| `STAGES_MISSING`    | `null`              |

**`InsightDeriverTest`** — derivation boundary cases:

| Active flags                     | Dismissed types                  | → Visible              | → Count |
|----------------------------------|----------------------------------|------------------------|---------|
| `{OVERREACHING}`                 | `{}`                             | `{OVERREACHING}`       | 0       |
| `{OVERREACHING, NADIR_DELAYED}`  | `{OVERREACHING}`                 | `{LATE_NADIR}`         | 1       |
| `{}`                             | `{OVERREACHING}`                 | `{}`                   | 0       |
| all 3 active                     | all 3 dismissed                  | `{}`                   | 3       |
| all 3 active                     | `{}`                             | all 3 visible          | 0       |
| `{}`                             | `{}`                             | `{}`                   | 0       |
| `{CALIBRATING, HRV_MISSING}`     | `{}`                             | `{}`                   | 0       |

### 5.2 Manual verification

- **Process death:** Dismiss a card → kill app process via ADB/Android Studio → relaunch → card remains hidden (Room persistence)
- **Layout regression:** Zero active recovery flags → Daily Steps card snaps cleanly beneath gauges, no spacing artifacts from absent insight slots
- **Date switching:** Dismiss insight on today → switch to yesterday → switch back → card stays dismissed for today
- **Restore flow:** Dismiss 2 cards → "Show 2 dismissed insight(s)" appears → tap → both cards reappear with vertical expand animation

---

## 6. Files Changed (Summary)

| Action  | File                                              | Scope                              |
|---------|---------------------------------------------------|------------------------------------|
| Create  | `domain/model/InsightType.kt`                     | Enum + RecoveryFlag mapping        |
| Create  | `domain/dashboard/InsightDeriver.kt`              | Pure derivation logic + DerivedInsights |
| Create  | `data/local/entity/InsightDismissalEntity.kt`     | Room entity                        |
| Create  | `data/local/dao/InsightDismissalDao.kt`           | Room DAO                           |
| Create  | `ui/components/InsightCard.kt`                    | Card composable                    |
| Create  | `test/.../InsightTypeTest.kt`                     | Mapping tests                      |
| Create  | `test/.../InsightDeriverTest.kt`                  | Derivation tests                   |
| Modify  | `data/local/HealthDatabase.kt`                    | Version 28, add entity + DAO       |
| Modify  | `data/local/DatabaseMigrations.kt`                | Migration 27→28                    |
| Modify  | `domain/dashboard/CardConfiguration.kt`           | 3 new CardId entries               |
| Modify  | `ui/components/ReorderableCardGrid.kt`            | Add to FULL_WIDTH_CARDS            |
| Modify  | `ui/dashboard/DashboardViewModel.kt`              | Event handlers, DAO injection      |
| Modify  | `ui/dashboard/DashboardFlowIntermediate.kt`       | Dismissal flow in BasicInputs      |
| Modify  | `ui/dashboard/DashboardCardFactory.kt`            | Insight card entries + param       |
| Modify  | `ui/dashboard/DashboardScreen.kt`                 | Restore button item                |
| Modify  | `ui/dashboard/DashboardEvent.kt`                  | 2 new events                       |
| Modify  | `app/src/main/res/values/strings.xml`             | 7 new strings                      |
| Update  | `docs/DATA_FLOW.md`                               | Document new entity + flow         |

All paths relative to `app/src/main/java/com/gregor/lauritz/healthdashboard/`.
