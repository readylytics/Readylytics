# Detekt Tier 1 — Mechanical, Near-Zero Risk

**Status:** completed · **Created:** 2026-08-21 · **Completed:** 2026-08-21 · **Owner:** antigravity
**Parent:** `DETEKT_BASELINE_BURNDOWN.md` §6, Tier 1
**Scope:** 66 baseline entries across 6 rules. Delete, rename, or trivially fix. Compiler and tests catch any mistake immediately.

---

## Rules in scope

| Entries | Rule | Fix pattern |
|--------:|------|-------------|
| 19 | `UnusedPrivateProperty` | Delete the property. Check for reflection/DI/Room usage first. |
| 15 | `UnusedPrivateMember` | Delete the function/preview. Preview functions: confirm not referenced by test screenshots. |
| 14 | `UnusedParameter` | Remove the parameter or prefix with `@Suppress` only if it's an override/interface contract. |
| 12 | `ExplicitItLambdaParameter` | Remove explicit `it` — replace `{ it: Type -> it.foo }` with `{ it.foo }`. |
| 4 | `NewLineAtEndOfFile` | Run `ktlintFormat` — it fixes these automatically. |
| 2 | `MayBeConst` | Add `const` modifier. |

---

## Affected files

### `UnusedPrivateProperty` (19 entries)

| Module | File | Entry |
|--------|------|-------|
| `core/ui` | `DateSwitcher.kt` | `pillDescription` — assigned but never read |
| `core/ui` | `TimeRangeTest.kt` | `expectedStart` — computed but never asserted |
| `core/database` | `BaselineComputerBackfillEquivalenceTest.kt` | `expectedRhr` — computed but never asserted |
| `core/database` | `RoomHealthIngestionStore.kt` | `TAG` constant — unused log tag |
| `core/database` | `ScoringRepositoryBiphasicIntegrationTest.kt` | `dayMidnightMs` — computed but never used |
| `core/model` | `BodyTemperatureBaselineCalculatorTest.kt` | `fourteenDays` — test fixture never asserted |
| `core/model` | `SleepCardCatalogTest.kt` | `topGauge` — test fixture never asserted |
| `core/scoring` | `ReadinessCalculationTest.kt` | `recoveryDays` — unused test constant |
| `app` | `CardConfigurationRepositoryTest.kt` | `repo` — constructed but never used |
| `app` | `HealthDeviceRepositoryTest.kt` | `dbDevices` — list never asserted |
| `app` | `LocalRestoreManager.kt` | `name` (via `reader.nextName()`) |
| `app` | `LocalRestoreManager.kt` | `token` (via `reader.peek()`) |
| `feature/vitals` | `StepDetailScreen.kt` | `stepsDelta` — formatted string never displayed |
| `feature/settings` | `CircadianThresholdSettingsSection.kt` | `THRESHOLD_SLIDER_STEPS` constant |
| `feature/sleep` | `SleepArchitectureBar.kt` | `primaryColor` |
| `feature/sleep` | `SleepTrendChart.kt` | `axisLabelComponent` |
| `feature/sleep` | `SleepViewModel.kt` | `savedStateHandle` |
| `feature/dashboard` | `DashboardMetricPresentationFactory.kt` | `getWorkoutMetricsUseCase` (injected but unused) |
| `feature/dashboard` | `DashboardViewModelTest.kt` | `initialState` |

**Caution items:**
- `LocalRestoreManager.kt` — `reader.nextName()` and `reader.peek()` have **side effects** (advance the JSON reader). Deleting the val is correct but the call itself must stay if it's consuming a token. Read the surrounding code before deleting.
- `DashboardMetricPresentationFactory.kt` — `getWorkoutMetricsUseCase` is an injected dependency. Removing it changes the Hilt graph. Verify no other path uses it via this class.
- `CircadianThresholdSettingsSection.kt` — commented "Issue #9"; check if the constant is meant for future use.

### `UnusedPrivateMember` (15 entries)

| Module | File | Entry |
|--------|------|-------|
| `core/scoring` | `SleepPercentileRhrCalculator.kt` | `List<Int>.getPercentile()` extension |
| `app` | `PhysiologyPreferences.kt` | `Int.toValidRestMinutes()` extension |
| `feature/vitals` | `BloodPressureHistorySection.kt` | `BloodPressureHistoryCardPreview()` |
| `feature/vitals` | `BodyFatHistorySection.kt` | `BodyFatHistoryCardPreview()` |
| `feature/vitals` | `WeightHistorySection.kt` | `WeightHistoryCardPreview()` |
| `feature/dashboard` | `DashboardMetricCardPreviews.kt` | 10 `@Preview` functions |

**Note:** All 10 `DashboardMetricCardPreviews.kt` entries are `@Preview` composables. Detekt flags them as unused because they're only invoked by the Android Studio preview renderer, not by code. Two options:
1. Add `@Suppress("UnusedPrivateMember")` with a comment — these are intentionally preview-only.
2. Make them `internal` instead of `private` — detekt won't flag them, and preview still works.
Option 2 is cleaner if the project prefers no `@Suppress`.

### `UnusedParameter` (14 entries)

| Module | File | Parameter |
|--------|------|-----------|
| `core/ui` | `CardLoader.kt` | `modifier: Modifier` |
| `core/ui` | `TrendCharts.kt` | `metricName: String` |
| `core/database` | `SqlCipherKeyManager.kt` | `dbFile: File?` |
| `core/designsystem` | `Theme.kt` | `fallbackThemeColor` |
| `core/scoring` | `HrCoverageValidator.kt` | `durationMinutes: Int` |
| `app` | `CanonicalMetricDisplayAuditTest.kt` | `file: File` |
| `app` | `LogcatCaptureStoreImpl.kt` | `durationMinutes: Int` |
| `feature/vitals` | `HeartRateDetailScreen.kt` | `onNextDay`, `onPreviousDay` (2 entries) |
| `feature/settings` | `HeartRateSettings.kt` | `expandState`, `onExpandStateChange` (2 entries) |
| `feature/sleep` | `SleepTrendOverlay.kt` | `layerBounds: Rect?` |
| `feature/dashboard` | `DashboardMetricPresentationFactory.kt` | `selectedDate: LocalDate` |
| `feature/dashboard` | `DashboardScreen.kt` | `onRefresh: () -> Unit` |

**Caution items:**
- `HeartRateDetailScreen.kt` — `onNextDay`/`onPreviousDay` are callback parameters. If they're part of a public composable signature, removing them is a breaking API change. Check callers.
- `HeartRateSettings.kt` — same pattern: `expandState`/`onExpandStateChange` may be contract parameters.
- `DashboardScreen.kt` — `onRefresh` may be wired at the call site even if unused inside.
- For parameters that are part of an interface/override contract, use `_` prefix or keep them — don't remove.

### `ExplicitItLambdaParameter` (12 entries)

All 12 are in `core/database` → `ScoringSyncScopeOutputsDeterminismTest.kt`. Each is a lambda of the form `{ it: DailySummary -> it.someField }`. Fix: remove the explicit `it: DailySummary` type annotation.

### `NewLineAtEndOfFile` (4 entries)

| Module | File |
|--------|------|
| `core/model` | `VitalsChartConfiguration.kt` |
| `core/model` | `VitalsChartId.kt` |
| `core/model` | `VitalsLayoutRepository.kt` |
| `core/scoring` | `ScoringBindsModule.kt` |

Fix: `./gradlew ktlintFormat` handles all four.

### `MayBeConst` (2 entries)

| Module | File | Property |
|--------|------|----------|
| `core/model` | `SettingsDefaults.kt` | `HEIGHT_CM: Float?` — nullable, cannot be const. Likely a false baseline entry (detekt may have changed its mind). Verify: if it's still flagged after regeneration, the entry is stale. |
| `feature/sleep` | `SleepStagesChart.kt` | `NINE_HOURS_MS` — add `const`. |

---

## Execution plan

1. **Start with `ktlintFormat`** — clears `NewLineAtEndOfFile` (4 entries) for free.
2. **`ExplicitItLambdaParameter`** — single file, 12 entries, purely mechanical.
3. **`MayBeConst`** — 2 entries, trivial.
4. **`UnusedPrivateProperty`** — 19 entries. Work through the caution items carefully. Test files first (safe), then production code.
5. **`UnusedPrivateMember`** — 15 entries. Decide on the preview strategy first.
6. **`UnusedParameter`** — 14 entries. Check each caller before removing.

After each group: remove the corresponding `<ID>` lines from the module's `detekt-baseline.xml`, run `./gradlew :module:detekt` to confirm, then commit.

---

## Constraints

All constraints from `DETEKT_BASELINE_BURNDOWN.md` §7 apply. Key ones for Tier 1:
- `git diff -- '**/detekt-baseline.xml'` must show **removals only**.
- Unit test count must not drop below 3,082.
- `./gradlew ktlintCheck detekt testDebugUnitTest` must pass.
- Run `./gradlew lintRelease` at the end.
