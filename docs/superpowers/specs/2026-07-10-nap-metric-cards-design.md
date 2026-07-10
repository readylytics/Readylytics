# Design Specification: Nap Duration & Nap Count Metric Cards

## 1. Objective

Add two new metric cards to the Sleep screen — **Nap Duration** and **Naps Today** — surfacing supplemental (nap) sleep data produced by the biphasic aggregation pipeline. This work also drives the data model decision deferred in Step 9 of the biphasic implementation plan: new `supplementalSleepDurationMinutes` and `napCount` fields are added to `DailySummary`, `DailyMetrics`, and `DailySummaryEntity`.

## 2. Scope

Full-stack. This spec owns:
- domain model additions (`DailySummary`, `DailyMetrics`)
- DB persistence (`DailySummaryEntity`, migration `4 → 5`)
- mapper updates (`DailySummaryMapper`, `DailyMetricsMapper`)
- scoring pipeline wiring (`ComputeSleepMetricsUseCase`)
- UI (`SleepScreen` / `MetricsGrid`, `MetricsGridSkeleton`)
- strings (`strings.xml`)
- tests

## 3. Product Decisions

| Decision | Choice |
|---|---|
| Nap duration card value | Total supplemental sleep duration for the day |
| Nap count card value | Count of qualifying supplemental sessions |
| "Qualifying" threshold | Reuses `minimumCountedSleepSegmentMinutes` biphasic policy setting |
| Status / coloring | Both cards neutral (`MetricStatus.NO_DATA`) — no good/bad judgment |
| Layout | Always-visible 3rd row in `MetricsGrid` |
| Null display — nap count | Show `"0"` |
| Null display — nap duration | Show `DateFormatUtils.formatSleepDuration(0)` |

## 4. Architecture

### 4.1 Data Model

**`DailySummary`** — two new nullable fields:

```kotlin
val supplementalSleepDurationMinutes: Int? = null
val napCount: Int? = null
```

Null/zero semantics:
- `null` → no sleep aggregate was computed for this day (legacy row, no sleep data, or pre-biphasic scoring). UI falls back to zero display.
- `0` → aggregate ran and found no qualifying supplemental sessions.

Both fields are populated exclusively by the scoring engine via `SleepDayAggregate`. No other layer may set or derive them.

**`DailyMetrics`** — two new nullable fields:

```kotlin
val napDurationDisplay: String? = null   // formatted via DateFormatUtils.formatSleepDuration()
val napCount: Int? = null                // integer passthrough
```

### 4.2 Persistence

**`DailySummaryEntity`:** Two new nullable `Int` columns:
- `supplementalSleepDurationMinutes INTEGER`
- `napCount INTEGER`

Both default to `null` for existing rows. No existing column is touched.

**DB version:** bumped `4 → 5` in `HealthDatabase`.

**Migration `4 → 5`** in `DatabaseMigrations.kt`:
```sql
ALTER TABLE daily_summary ADD COLUMN supplementalSleepDurationMinutes INTEGER;
ALTER TABLE daily_summary ADD COLUMN napCount INTEGER;
```

### 4.3 Mappers

**`DailySummaryMapper`** (entity ↔ domain):
- Map `entity.supplementalSleepDurationMinutes` → `domain.supplementalSleepDurationMinutes` (null-safe passthrough)
- Map `entity.napCount` → `domain.napCount` (null-safe passthrough)

**`DailyMetricsMapper`** (domain → display):
- `napDurationDisplay = DateFormatUtils.formatSleepDuration(summary.supplementalSleepDurationMinutes ?: 0)` — always produces a formatted string when a metrics row exists; zero minutes formats consistently with all other sleep duration display
- `napCount = summary.napCount` (direct passthrough, nullable)

### 4.4 Scoring Pipeline

In `ComputeSleepMetricsUseCase` (or the equivalent coordinator that converts `SleepDayAggregate` → `DailySummary` contributions):

```kotlin
supplementalSleepDurationMinutes = aggregate.supplementalSegments.sumOf { it.durationMinutes }
napCount = aggregate.supplementalSegments.size
```

Where `aggregate.supplementalSegments` is the list of supplemental sessions that already passed the `minimumCountedSleepSegmentMinutes` filter in the aggregation layer — no double-filtering required.

When no aggregate is available (no sleep data for the day), both fields remain `null`.

### 4.5 UI — Sleep Screen

**`MetricsGrid` layout (always rendered):**

```
Row 1: Circadian Consistency  |  Sleep Efficiency
Row 2: Deep Sleep %           |  REM Sleep %
Row 3: Nap Duration           |  Naps Today         ← new
```

**Nap Duration card:**
```kotlin
MetricCard(
    title = stringResource(R.string.card_title_nap_duration),
    value = metrics?.napDurationDisplay ?: DateFormatUtils.formatSleepDuration(0),
    status = MetricStatus.NO_DATA,
    tooltip = stringResource(R.string.tooltip_nap_duration),
    onClick = null,
)
```

**Naps Today card:**
```kotlin
MetricCard(
    title = stringResource(R.string.card_title_nap_count),
    value = metrics?.napCount?.toString() ?: "0",
    status = MetricStatus.NO_DATA,
    tooltip = stringResource(R.string.tooltip_nap_count),
    onClick = null,
)
```

Both cards are always visible. Neither card has a `secondaryText` (no goal to compare against).

**`MetricsGridSkeleton`:** Add a matching third `Row` with two `MetricCardSkeleton` boxes.

### 4.6 Strings

All in `app/src/main/res/values/strings.xml`:

| Key | Suggested value |
|---|---|
| `card_title_nap_duration` | `"Nap Duration"` |
| `card_title_nap_count` | `"Naps Today"` |
| `tooltip_nap_duration` | `"Total supplemental sleep duration for this wake-day. Only sessions above the minimum counted duration threshold are included."` |
| `tooltip_nap_count` | `"Number of naps or supplemental sleep sessions counted for this wake-day. Sessions shorter than the minimum counted duration setting are excluded."` |

## 5. Files Changed

| File | Change |
|---|---|
| `core/model/.../DailySummary.kt` | +2 nullable fields |
| `core/model/.../DailyMetrics.kt` | +2 nullable fields |
| `core/model/.../DailySummaryMapper.kt` | Map new fields entity ↔ domain |
| `core/model/.../DailyMetricsMapper.kt` | Format `napDurationDisplay`, pass through `napCount` |
| `core/model/.../entity/DailySummaryEntity.kt` | +2 nullable columns |
| `core/database/.../HealthDatabase.kt` | version `4 → 5` |
| `core/database/.../DatabaseMigrations.kt` | Migration `4 → 5` |
| `core/scoring/.../ComputeSleepMetricsUseCase.kt` | Populate two new fields from aggregate |
| `feature/sleep/.../SleepScreen.kt` | Add 3rd `MetricsGrid` row + skeleton row |
| `app/src/main/res/values/strings.xml` | +4 string entries |

## 6. Test Plan

**`DailyMetricsMapperTest`** (existing, new cases):
- `supplementalSleepDurationMinutes = null` → `napDurationDisplay = formatSleepDuration(0)`, `napCount = null`
- `supplementalSleepDurationMinutes = 45, napCount = 2` → display formatted correctly
- `supplementalSleepDurationMinutes = 0, napCount = 0` → `napDurationDisplay = formatSleepDuration(0)`, `napCount = 0`

**`DailySummaryMapperTest`** (existing, new cases):
- Round-trip: entity with null new columns → domain null
- Round-trip: entity with values → domain fields populated

**`DatabaseMigrationTest`** (new or existing):
- Migration `4 → 5`: existing rows survive with new columns as `null`

**`SleepScreenAdaptersTest`** (existing, new cases):
- `napCount = null` → displays `"0"`
- `napCount = 3` → displays `"3"`
- `napDurationDisplay = null` → displays `formatSleepDuration(0)`
- `napDurationDisplay = "45 min"` → displays `"45 min"`

## 7. Constraints

- Scoring math is untouched — only field population and UI display are in scope.
- Both new fields are **read-only** from the UI perspective; only the scoring engine writes them.
- `minimumCountedSleepSegmentMinutes` filtering happens once in the aggregation layer — mappers and UI never re-apply it.
- All strings go through `strings.xml` — no hardcoded literals in composables.
