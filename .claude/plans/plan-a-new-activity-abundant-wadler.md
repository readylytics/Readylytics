# Activity Volume section — Workout tab

## Context

The Workout tab currently shows ACWR/Training Load and a "Weekly training" block (3 stat
cards + a this-week-vs-last-week line chart), but gives no per-activity-type breakdown of
*what kind* of training happened this week vs last week. A Strava-inspired mockup calls for
adding an "Activity volume" list: one row per activity type, each showing the metric that's
actually meaningful for that type (distance for outdoor/GPS activities, duration for
indoor/equipment activities), this week's value, last week's value, and a relative change.

Investigation of the codebase found that the domain-layer computation for exactly this already
exists and is fully unit-tested — `ComputeWeeklyTrainingStatsUseCase` already produces
`WeeklyTrainingStats.activityVolumes: List<ActivityVolume>`, grouped via the existing
`WorkoutLayoutType`/`WorkoutLayoutTypeMapper` classification and `ActivityMetricTypeMapper`
(which already encodes "outdoor → distance, indoor/equipment → duration" exactly as required).
Nothing in `feature/` reads `activityVolumes` yet — this is a UI-and-wiring task, not a
new-data-model task. No Room/Health Connect/domain-model changes are needed.

## Decisions locked in for this change

- **Ranking/threshold**: rank activity types by this week's share of total training time
  (reusing the already-computed `trainingMix`), show the top 3 inline, rest behind "View all".
- **"View all" affordance**: opens a Material3 `ModalBottomSheet` listing every activity type's
  comparison (no existing "view all" precedent in the app — this establishes one with a
  standard M3 component rather than a new navigation destination).
- **Icons**: monochrome (`onSurfaceVariant`-tinted), matching the existing `WeeklyStatCard`
  icon style. No new per-activity-type accent-color system.
- **Hiking**: distance only in this change. Elevation-as-secondary-metric for Hiking is
  explicitly out of scope (deferred as a possible fast-follow) to avoid extending the
  `ActivityVolume` domain model and the associated `internal-docs/DATA_FLOW.md` update in this
  change.
- **Existing users**: auto-heal (`LayoutDefaultsMerger.mergeWithDefaults` in
  `WorkoutsLayoutRepositoryImpl.ensureDefaultChartsArePresent()`) injects
  `ACTIVITY_VOLUME` (`isVisible = true`, `position = 2`) into already-stored layouts, so the
  section is visible by default for existing users too — hideable via Customize (confirmed).
- **Zero-distance rows**: a DISTANCE-type row whose current-week value is 0 (e.g., pool swim
  logged without distance) displays the `"—" that `UnitConverter.formatDistance` returns for
  `<= 0m`; the row is NOT dropped (confirmed).
- **Delta direction**: uniform `DeltaDirection.HIGHER_IS_BETTER` for every activity type — more
  volume always reads as improved (optimal color up / warning down), matching the Weekly
  training cards' duration treatment; no per-type direction mapping (confirmed).
- **Strings home**: new keys go in `feature/workouts/src/main/res/values/strings.xml`, matching
  existing precedent (`workout_layout_type_*` live there) rather than the generic app-level
  `app/src/main/res/values/strings.xml` rule (confirmed).

## Domain layer — no changes

Reuse as-is:
- `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/workouts/weekly/WeeklyTrainingModels.kt` — `ActivityVolume(activityType, metricType, currentWeekValue, previousWeekValue, absoluteChange, percentChange)`, `TrainingMixItem(activityType, durationMinutes, percentage)`.
- `core/scoring/.../workouts/weekly/ComputeWeeklyTrainingStatsUseCase.kt` — already computes both `activityVolumes` and `trainingMix` from the same pass, exposed on `WeeklyTrainingStats` (already loaded into `WorkoutsUiState.weeklyTraining` by `WorkoutsViewModel.loadWeeklyTraining()` in `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt`).
- `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/detail/WorkoutLayoutType(Mapper).kt` — activity classification/grouping (13 types incl. `OTHER`), and `core/scoring/.../workouts/weekly/ActivityMetricTypeMapper.kt` — distance vs. duration per type.
- `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutDetailItemExtensions.kt` — `WorkoutLayoutType.displayNameResId` (existing per-type strings already in `feature/workouts/src/main/res/values/strings.xml`, e.g. `workout_layout_type_running`).

**Row selection/ranking is UI-layer logic, not domain logic**: join `activityVolumes` (values to
display) with `trainingMix` (this week's duration, for ranking) by `activityType` — both come
from the same `WeeklyTrainingStats`, so they can't drift. `trainingMix`'s keys are already
exactly "activity types with current-week activity" (`WeeklyActivityBreakdown.trainingMix`
groups only over current-week workouts), which already satisfies "only show activity types with
meaningful recent activity" — no extra threshold logic needed. Sort by
`trainingMix.durationMinutes` descending; top 3 → inline rows; full sorted list → the "View all"
sheet. If `trainingMix` is empty (no workouts this week), hide the whole section (same
convention as other cards that use `null`/empty to signal "nothing to show").

## New files (`feature/workouts`)

1. **`ActivityVolumeRows.kt`** — pure Kotlin, unit-testable, zero Android deps:
   ```kotlin
   internal fun buildActivityVolumeRows(stats: WeeklyTrainingStats): List<ActivityVolume> =
       stats.trainingMix
           .sortedByDescending { it.durationMinutes }
           .mapNotNull { mix -> stats.activityVolumes.find { it.activityType == mix.activityType } }
   ```
   Add a test `ActivityVolumeRowsTest.kt` (mirrors `core/scoring/src/test/kotlin/.../weekly/WeeklyActivityBreakdownTest.kt`'s `workoutOn(...)`-style fixture builder) covering: ranking by duration share, a distance-type and a duration-type both present, and the empty-week case.

2. **`ActivityVolumeFormatter.kt`** — pure formatting, sibling to `WeeklyTrainingDeltaFormatter.kt`:
   - `formatValue(value: Float, metricType: ActivityMetricType, unitSystem: UnitSystem): String` — `ActivityMetricType.DISTANCE` → `UnitConverter.formatDistance(value, unitSystem)` (`core/model/.../domain/util/UnitConverter.kt`; returns `"—"` for `<= 0m`, which is the intended zero-distance display); `ActivityMetricType.DURATION` → `WeeklyTrainingDeltaFormatter.formatDuration(value.roundToInt())`.
   - `formatPercentDelta(percentChange: Float?): String` — `"+24%"` / `"-18%"` signed, and a distinct string (new `activity_volume_new` resource, "New") when `percentChange == null` (previous week had zero volume for that type) instead of a misleading `+∞%` or `0%`.
   Add `ActivityVolumeFormatterTest.kt` covering the distance/duration branch, the null-percent-change case, and the zero-distance `"—"` case.

3. **`ActivityVolumeSection.kt`** — the section composable, following the structure of `WeeklyTrainingSection.kt`:
   - `ActivityVolumeSection(stats: WeeklyTrainingStats?, isLoading: Boolean, unitSystem: UnitSystem, modifier: Modifier)`: builds rows via `buildActivityVolumeRows`, renders nothing when the result is empty (post-loading), shows a skeleton while loading (reuse `SkeletonCard` from `core/ui/common`, matching `WeeklyTrainingSkeleton`'s pattern).
   - Section header: title `stringResource(R.string.activity_volume_title)` ("Activity volume") plus a trailing "View all" `TextButton`, shown only when there are more than 3 rows. Requires a small **reusable addition to `SectionHeader`** (`core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/SectionHeader.kt`): add an optional `trailingContent: (@Composable () -> Unit)? = null` parameter (default `null`, fully backward-compatible with every existing caller) and lay it out in a trailing slot. This benefits other sections later instead of a one-off custom header just for this section.
   - Row composable `ActivityVolumeRow`: leading monochrome `Icon` (new mapping below) + `Text(displayNameResId)`, then this-week value / last-week value / delta, styled with the **same delta pattern already used by `WeeklyTrainingSection.weeklyDeltaDisplay`** — `assessDeltaOutcome(current.roundToInt(), previous.roundToInt(), DeltaDirection.HIGHER_IS_BETTER)` (`core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/ScoreDeltaFormatter.kt`) → `LocalStatusColors.current.optimal` (up) / `.warning` (down) / `.neutral` (flat or "New"). Use Material3 `ListItem` (per this repo's UI rule to prefer native M3 components over custom rows), consistent with `WorkoutListSection.kt`'s `WorkoutHistoryItem`.
   - Top-level: local `var showAllSheet by rememberSaveable { mutableStateOf(false) }` — ephemeral UI state kept in the composable, not on `WorkoutsUiState`/the ViewModel (per this repo's rule that ephemeral UI state belongs in Composables only, unless domain-relevant). "View all" click sets it true; sheet dismiss sets it false.

4. **`ActivityVolumeBottomSheet.kt`** — `ModalBottomSheet` (M3) showing every row from `buildActivityVolumeRows` (not just the top 3), reusing `ActivityVolumeRow`.

5. **`ActivityTypeIcons.kt`** — new `WorkoutLayoutType -> ImageVector` mapping (none exists today; the History list currently has no icons at all). Monochrome, `onSurfaceVariant`-tinted, using `material-icons-extended` (already a project dependency, see `gradle/libs.versions.toml`): `RUNNING→DirectionsRun`, `WALKING→DirectionsWalk`, `CYCLING→DirectionsBike`, `SWIMMING→Pool`, `STRENGTH→FitnessCenter`, `HIKING→Hiking`, `YOGA→SelfImprovement`, `PILATES→SportsGymnastics`, `ELLIPTICAL→FitnessCenter` (no dedicated elliptical glyph available), `ROWING→Rowing`, `STAIRS→Stairs`, `HIIT→Whatshot`, `OTHER→SportsScore`. Exhaustive `when`, mirroring the `displayNameResId` extension style in `WorkoutDetailItemExtensions.kt`.

## Wiring into the Workout tab (customizable/reorderable, matching existing sections)

The two existing weekly sections (`ACWR_TRIMP`, `WEEKLY_TRAINING`) are both reorderable/
toggleable "chart" slots via `WorkoutChartId` plus the existing layout-customization system.
Add Activity Volume the same way rather than hardcoding it into `WorkoutsScreen.kt`:

- **`core/model/src/main/kotlin/app/readylytics/health/core/model/domain/workouts/WorkoutChartId.kt`** — add `ACTIVITY_VOLUME` enum entry.
- **`core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/SettingsDefaults.kt`** — append `WorkoutChartConfiguration(WorkoutChartId.ACTIVITY_VOLUME, isVisible = true, position = 2)` to `DEFAULT_WORKOUT_CHARTS`.
- **`feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutChartIdExtensions.kt`** — add `WorkoutChartId.ACTIVITY_VOLUME -> R.string.activity_volume_title`.
- **`feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsChartFactory.kt`** — register `WorkoutChartId.ACTIVITY_VOLUME to { _ -> ActivityVolumeSection(stats = uiState.weeklyTraining, isLoading = uiState.isLoading, unitSystem = uiState.unitSystem, modifier = Modifier.fillMaxWidth()) }`.
- No changes needed to `app/src/main/kotlin/app/readylytics/health/data/preferences/WorkoutsLayoutMapper.kt` (`WorkoutChartId.valueOf(proto.chartId)` is already generic) or `WorkoutsManagementBottomSheet.kt` (already iterates all configured charts generically) — new enum entries are picked up automatically, via the same "auto-healing defaults" mechanism already documented in `internal-docs/DATA_FLOW.md` for existing chart ids.

## `WorkoutsUiState.unitSystem`

`ActivityVolumeSection` needs `UnitSystem` for distance formatting (`UnitConverter.formatDistance`),
but `WorkoutsUiState` doesn't currently expose it (unlike `WorkoutDetailUiState`, which already
does via `prefs.unitSystem`). Add:
- `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsStateFactory.kt`: new `WorkoutsUiState.unitSystem: UnitSystem = UnitSystem.METRIC` field; set it in `assembleWorkoutsUiState` (~line 483) as `unitSystem = prefs.unitSystem` — `prefs: UserPreferences` is already threaded through `WorkoutsStateInputs`, no new plumbing required.

## Strings

Add to `feature/workouts/src/main/res/values/strings.xml` (decided: feature-local wins over the
app-level strings rule, matching existing `workout_layout_type_*` precedent): `activity_volume_title` ("Activity
volume"), a "View all" label, `activity_volume_new` ("New", for the null-percent-change case),
and a content-description string for the row icons. Reuse the existing `workout_layout_type_*`
strings for row labels — no changes needed there.

## Documentation

`internal-docs/DATA_FLOW.md` already documents `ComputeWeeklyTrainingStatsUseCase` and notes
`activityVolumes`/`trainingMix` are pure aggregation ("not a scoring formula"). This change adds
a UI consumer of an already-documented field, not a new computation — add one short sentence in
that existing section noting `activityVolumes` is now rendered by the Workout tab's Activity
Volume section (chart id `ACTIVITY_VOLUME`), so the doc's data-flow map stays accurate
end-to-end. No formula/coefficient documentation changes apply (out of scope for
`ABOUT.md`/`docs/about.md` per the Documentation Synchronization Rule, since no scoring formula
changed).

## Verification

- `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew testDebugUnitTest` — must stay clean, per repo convention (boyscout rule applies if any touched file already has detekt issues).
- New unit tests: `ActivityVolumeRowsTest.kt` (ranking/filtering) and `ActivityVolumeFormatterTest.kt` (value/percent formatting incl. the null-percent "New" case, and duration vs. distance branch).
- `./gradlew installDebug`, open the Workout tab on a device/emulator with workouts in both the current and previous week across at least one distance-type (e.g. Running) and one duration-type (e.g. Strength) activity: confirm the section appears with correct top-3 ranking, correct up/down coloring, "View all" opens the bottom sheet with the full list, and the section disappears cleanly on a week with zero workouts.
- Confirm the section is toggleable/reorderable via the existing "Customize" bottom sheet (drag to reorder, hide/show), consistent with ACWR and Weekly training.
- `./gradlew lintRelease` after all coding tasks are resolved, per repo convention.
