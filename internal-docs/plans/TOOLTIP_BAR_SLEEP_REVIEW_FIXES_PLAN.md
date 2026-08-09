# Tooltip / Bar / Sleep-Pipeline Review Fixes — Implementation Plan

Source: `/code-review changes against main` on branch `claude/progress-bar-dot-indicator-t9pc28`
(diff base: commits up to `6f12e053 rm file`, `7f4fc869 implement bar fix`, `9a9d3e03 Add plan doc for progress bar dot indicator and tick coverage fix`, `37e184a2 bar migration filled (#194)`).

10 findings reported. This doc fixes 8 of them (skips 1 low-value duplication note, folds 2 tooltip findings into one fix). Grouped by root cause, ordered for implementation.

---

## Implementation order

1. `DataPointTooltip.kt` alignment + width (findings #1 + #2)
2. `M3MetricBar.kt` divide-by-zero guard (finding #5)
3. `SyncProgressScreen.kt` marker size (finding #4)
4. `SleepTrendDay.kt` + `SleepTrendDayAssembler.kt` + `SleepTrendMarkerListener.kt` offset key (finding #7)
5. `SleepViewModel.kt` combine restructure (finding #3) — do after #4 since it touches the same trend-day construction code
6. `UniversalMetricRenderers.kt` layout revert (finding #6)
7. `UniversalMetricRenderers.kt` duplicated thickness constant (finding #9)
8. `SleepTrendChart.kt` unmemoized formatter (finding #8)
9. *(Skipped — optional)* `M3MetricBar.kt` vs `M3MetricGauge.kt` duplicated tick-overhang math (finding #10) — geometry genuinely differs (linear vs angular), no real dedup possible; not worth a cross-file comment on its own.

Rationale for ordering: (1)-(3) are isolated, single-file, zero-risk — do first to shrink the diff fast. (4) touches the `SleepTrendDay` data model that (5) also touches, so the offset field must land before the combine restructure to avoid rebasing that logic twice. (6)-(8) are independent of the sleep-specific work and can be done in any order after.

---

## 1. `DataPointTooltip.kt` — alignment regression + bubble-width regression

**File:** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DataPointTooltip.kt`
**Lines:** 147-164

**Root cause:** `Column horizontalAlignment` was flipped from `Alignment.CenterHorizontally` to `Alignment.Start` to support the new `preDateLines` (sleep bedtime/naps) content. Only `valueText` was compensated with `Modifier.fillMaxWidth() + TextAlign.Center` — `dateText`, `preDateLines`, and `extraLine` were left with no alignment override.

**Two failure modes from this one change:**
- Every `DataPointTooltip` caller that does **not** use `preDateLines` (AcwrChart, TrimpBreakdownChart, StepsBar, HrTimelineChart, BloodPressureTrendChart/SplitChart/SingleBloodPressureChart, SleepStagesChart, SleepHrChart, TrendCharts.kt) now renders its first line centered and second/third lines flush-left underneath — visible layout regression across nearly every chart tooltip in the app.
- `Modifier.fillMaxWidth()` on `valueText` forces the `Column` (and the parent `Surface`, `widthIn(min=70dp, max=150dp)`) to always measure at the 150dp max. Short tooltips ("Duration: 8h") now always render as a fixed ~150dp bubble with dead space, instead of shrinking toward the 70dp minimum.

**Fix:** make alignment conditional on whether the tooltip actually has the new multi-line content, and drop the `fillMaxWidth` hack entirely so the bubble goes back to sizing to its content.

```kotlin
val hasExtraContent = data.preDateLines.isNotEmpty() || data.extraLine != null
Column(
    horizontalAlignment = if (hasExtraContent) Alignment.Start else Alignment.CenterHorizontally,
    modifier =
        Modifier
            .padding(
                horizontal = MaterialTheme.spacing.small,
                vertical = MaterialTheme.spacing.extraSmallMedium,
            )
            .padding(bottom = MaterialTheme.spacing.extraSmallMedium),
) {
    Text(
        text = data.valueText,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.inverseOnSurface,
        // no fillMaxWidth(), no textAlign — let Column's horizontalAlignment handle it
    )
    Text(
        text = data.dateText,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.9f),
    )
    data.preDateLines.forEach { line -> Text(text = line, ...) }
    data.extraLine?.let { extra -> Text(text = extra, ...) }
}
```

One change fixes both bugs: non-sleep callers (no `preDateLines`/`extraLine`) go back to `CenterHorizontally` exactly as before this diff, and the bubble shrinks to content again since nothing forces max width.

**Verify:** manually check tooltips on Acwr, Trimp breakdown, Steps, HR timeline, Blood pressure, Sleep stages, Sleep HR charts (should be centered, narrow-as-content) and the Sleep trend chart tooltip (should be left-aligned, showing bedtime + naps lines).

---

## 2. `M3MetricBar.kt` — divide-by-zero on zero-width canvas

**File:** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt`
**Line:** 99

**Root cause:** `capCoverageFraction` divides `strokeWidth / 2f` by `size.width` with no guard for `size.width == 0`, unlike `M3MetricGauge`'s equivalent (`arcTickCapCoverageFraction`) which explicitly returns `0f` for non-positive input.

**Failure scenario:** if the `Canvas` measures at `size.width == 0f` on an early/collapsing composition frame, the division yields `Infinity`, which feeds into `visibleTickFractions`' `it > progress + capCoverageFraction` filter and silently hides every tick mark for that frame instead of degrading gracefully.

**Fix:**
```kotlin
val capCoverageFraction =
    if (progressToDraw > 0f && size.width > 0f) (strokeWidth / 2f) / size.width else 0f
```

**Verify:** existing `M3MetricBar` unit/screenshot tests still pass; no dedicated regression test needed since this is a defensive guard for a transient composition frame — same treatment `M3MetricGauge` already has.

---

## 3. `SyncProgressScreen.kt` — marker overflows thinner sync bar

**File:** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/sync/SyncProgressScreen.kt`
**Line:** 99-106

**Root cause:** `M3MetricBar` is drawn with `barHeight = MaterialTheme.dimens.syncProgressBarThickness` (4dp) but `markerDiameter` is left at its default `MaterialTheme.dimens.metricGaugeMarkerDiameter` (6dp) — the marker circle is larger than the bar it's drawn on. Canvas drawing isn't clipped to layout bounds, so the 6dp marker (radius 3dp) centered on a 4dp bar extends ~1dp above/below the bar's own bounds, overlapping the surrounding `Spacer` whitespace on the sync/resync progress screen.

**Fix:**
```kotlin
M3MetricBar(
    progressFraction = progress?.fraction(),
    activeColor = MaterialTheme.colorScheme.primary,
    trackColor = MaterialTheme.colorScheme.secondaryContainer,
    barHeight = MaterialTheme.dimens.syncProgressBarThickness,
    markerDiameter = MaterialTheme.dimens.syncProgressBarThickness, // match bar height
    animateProgress = false,
    modifier = Modifier.fillMaxWidth(),
)
```

**Verify:** run the resync flow (Settings → Resync Health Connect data), visually confirm the marker dot sits flush within the thin progress bar with no overhang.

---

## 4. `SleepTrendDay` offset field — fixes index/map desync

**Files:**
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDay.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepTrendDayAssembler.kt`
- `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendMarkerListener.kt`

**Root cause:** `SleepTrendMarkerListener.kt:81` looks up `trendDay` via `currentTrendDays.value.getOrNull(resolvedOffset)` (raw list index), while the sibling `startOffsetMap`/`durationSpanMap`/`actualDurationMap` are all offset-keyed maps built via `associateBy { it.dayOffset }`. This is currently safe only because `SleepTrendDayAssembler.assemble()` always emits exactly one contiguous entry per offset in `[0, rangeDays)` — but `SleepTrendDay` itself carries no offset field, so there's no way to build a real keyed map today, and any future change that sparsifies `trendDays` (mirroring how the point lists are already sparse — they use `List<DailyDataPoint>` with a `dayOffset` field per point) would silently desync the index lookup, showing the wrong day's bedtime/naps in the tooltip with no compile-time signal.

**Fix — attack the root cause, not just the symptom:** give `SleepTrendDay` its own `dayOffset` so a real keyed map is possible.

`SleepTrendDay.kt`:
```kotlin
data class SleepTrendDay(
    val dayOffset: Int,
    val scoreDay: LocalDate,
    val coreStartTimeMs: Long?,
    val coreEndTimeMs: Long?,
    val totalDurationMinutes: Int?,
    val naps: List<SleepTrendNap>,
)
```

`SleepTrendDayAssembler.kt` (`assemble()`, currently lines 18-46) — populate it from the loop's existing `offset`:
```kotlin
return (0 until rangeDays).map { offset ->
    val scoreDay = rangeStart.plusDays(offset.toLong())
    val aggregate = aggregatesByDay[scoreDay]
    if (aggregate == null) {
        SleepTrendDay(
            dayOffset = offset,
            scoreDay = scoreDay,
            coreStartTimeMs = null,
            coreEndTimeMs = null,
            totalDurationMinutes = null,
            naps = emptyList(),
        )
    } else {
        SleepTrendDay(
            dayOffset = offset,
            scoreDay = scoreDay,
            coreStartTimeMs = aggregate.coreCluster.startTimeMs,
            coreEndTimeMs = aggregate.coreCluster.endTimeMs,
            totalDurationMinutes = aggregate.totalDurationMinutes,
            naps = /* unchanged */,
        )
    }
}
```

`SleepTrendMarkerListener.kt` — replace the `rememberUpdatedState(trendDays)` + `getOrNull` pattern with a real keyed map, consistent with the other three maps:
```kotlin
val trendDayMap = remember(trendDays) { trendDays.associateBy { it.dayOffset } }
val currentTrendDayMap = rememberUpdatedState(trendDayMap)
...
val trendDay = currentTrendDayMap.value[resolvedOffset]
```
(keep `rememberUpdatedState` wrapping since `handleTargets` is called from a listener object captured outside recomposition, same reason the original code wrapped `trendDays`)

**Callers to check:** grep for other `SleepTrendDay(` constructions (tests, previews) that will need the new required `dayOffset` param — likely `SleepTrendDayAssemblerTest` and any Compose `@Preview` sample data in `SleepTrendChart.kt`/`SleepTrendCard` previews.

**Verify:** existing `SleepTrendDayAssemblerTest` (add a case asserting `dayOffset` matches list index for both populated and gap days); manual check — tap through multiple days on the sleep trend chart, confirm bedtime/nap tooltip always matches the tapped day.

---

## 5. `SleepViewModel.kt` — unrelated pref change reloads entire sleep pipeline

**File:** `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt`
**Lines:** 138-353 (the `uiState` builder)

**Root cause:** `settingsRepo.userPreferences` was moved from the final inner `combine` (line 283-291, the summary/session/stages/metrics/trend/yesterday/HR combine) into the **outer** `combine` (line 139-143) that feeds `flatMapLatest` (line 144). Any `UserPreferences` emission — theme, retention days, HR zones, anything — now cancels and resubscribes the entire inner pipeline (`sleepSessionRepository.observeSince`/`observeFirstSessionEndingInRange`, `dailySummaryRepository`, `heartRateRepository`, the trend-day assembly), not just changes to the 5 fields that actually affect this screen's computation: `scoringZone()` (derived), `coreMergeGapMinutes`, `supplementalCutoffMinutesOfDay`, `minimumCountedSleepSegmentMinutes`, `supplementalArchitectureCoveragePercent` — plus `goalSleepHours` which is read but doesn't need a flow restart, only a value refresh.

**Failure scenario:** user changes an unrelated setting while on the Sleep screen → `flatMapLatest` cancels and restarts every observe* flow → visible reload/flicker of the trend chart and stage timeline, and `SleepTrendDayAssembler`'s O(n log n) aggregation reruns for no reason.

**Fix:** extract only the fields the inner block actually consumes into a small `distinctUntilChanged`-gated projection, so the outer `combine` only re-triggers `flatMapLatest` when one of *those* fields changes.

```kotlin
private data class SleepScoringPrefs(
    val scoringZoneId: ZoneId,
    val coreMergeGapMinutes: Int,
    val supplementalCutoffMinutesOfDay: Int,
    val minimumCountedSleepSegmentMinutes: Int,
    val supplementalArchitectureCoveragePercent: Int,
    val goalSleepHours: Float,
)

private fun UserPreferences.toSleepScoringPrefs() =
    SleepScoringPrefs(
        scoringZoneId = scoringZone(),
        coreMergeGapMinutes = coreMergeGapMinutes,
        supplementalCutoffMinutesOfDay = supplementalCutoffMinutesOfDay,
        minimumCountedSleepSegmentMinutes = minimumCountedSleepSegmentMinutes,
        supplementalArchitectureCoveragePercent = supplementalArchitectureCoveragePercent,
        goalSleepHours = goalSleepHours,
    )
```

Inside the class:
```kotlin
private val sleepScoringPrefsFlow =
    settingsRepo.userPreferences
        .map { it.toSleepScoringPrefs() }
        .distinctUntilChanged()
```

Replace the outer `combine`'s third argument:
```kotlin
combine(
    selectedDateRepository.selectedDate,
    selectedTrendRangeFlow,
    sleepScoringPrefsFlow,
) { date, range, prefs -> Triple(date, range, prefs) }
    .flatMapLatest { (date, range, prefs) ->
        val scoringZoneId = prefs.scoringZoneId
        ...
        val policy = SleepDayPolicy(
            coreMergeGapMinutes = prefs.coreMergeGapMinutes,
            supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
            minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
            supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
            scoringZoneId = scoringZoneId,
        )
        ...
        goalSleepHours = prefs.goalSleepHours,
        sleepTimeGaugeData = buildSleepTimeGaugeData(..., goalSleepHours = prefs.goalSleepHours),
    }
```

Every other `prefs.xxx` reference in the `flatMapLatest` body (lines 146, 229-234, 319, 324) updates to read off the new `SleepScoringPrefs` shape instead of the full `UserPreferences`.

**Do this after finding #4** — the `flatMapLatest` body constructs `SleepDayPolicy` and the trend list right next to where `SleepTrendDay` gets built, so touch that code once, not twice.

**Verify:** add/extend a `SleepViewModel` test that emits two `UserPreferences` values differing only in an unrelated field (e.g. theme or retention) and asserts the inner repositories are *not* re-subscribed (no new `observeSince`/`observeFirstSessionEndingInRange` collection) — vs. a test that changes `coreMergeGapMinutes` and asserts it *is* re-subscribed. Manual: toggle an unrelated setting while Sleep screen is open, confirm no flicker/reload.

---

## 6. `UniversalMetricRenderers.kt` — fixed-offset layout can clip at small heights

**File:** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt`
**Lines:** 169-235 (`UniversalValueUnitColumn`)

**Root cause:** rewritten from a flexible `Column` (value row `weight(1f, fill=false)`, shrinks under pressure) to a `Box` with absolutely bottom-anchored children whose offsets are a hand-summed constant (`UNIVERSAL_SECONDARY_SLOT_HEIGHT + UNIVERSAL_TRACK_SECONDARY_GAP + metricTrackThickness + UNIVERSAL_BAR_TRACK_EXTRA_THICKNESS`), independent of actual available height.

**Failure scenario:** in a short card slot, or at large accessibility font scales where the title row grows and leaves less height below it, the fixed bottom reservation can exceed truly available space — the track/secondary `Box`es (positioned by fixed padding, not measured against remaining space) can clip or overlap the value/unit text instead of shrinking like the old `Column` did. `DashboardVisualizationLayoutTest.kt` only exercises 240dp-height cards, so this isn't caught by existing tests.

**Fix:** revert to a single flowing `Column`: value row keeps `weight(1f, fill=false)` so it shrinks first under pressure; a `Spacer(Modifier.weight(1f))` after it absorbs any leftover space and pushes track+secondary to the bottom when there's room, collapsing to zero when there isn't.

```kotlin
@Composable
private fun UniversalValueUnitColumn(
    presentation: UniversalMetricPresentation,
    contentColor: Color,
    secondaryUsesPill: Boolean,
    modifier: Modifier = Modifier,
    track: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Text(text = presentation.valueText, ..., modifier = Modifier.alignByBaseline())
            if (presentation.unitText.isNotBlank()) {
                Text(text = presentation.unitText, ..., modifier = Modifier.alignByBaseline())
            }
        }

        Spacer(Modifier.weight(1f)) // absorbs slack, shrinks to 0 under pressure

        Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
        Box(
            modifier = Modifier.fillMaxWidth().height(UNIVERSAL_BAR_TRACK_THICKNESS), // see finding #9 for this constant
        ) {
            track()
        }

        Spacer(Modifier.height(UNIVERSAL_TRACK_SECONDARY_GAP))
        Box(
            modifier = Modifier.fillMaxWidth().height(UNIVERSAL_SECONDARY_SLOT_HEIGHT),
            contentAlignment = Alignment.BottomStart,
        ) {
            presentation.secondaryText?.takeIf(String::isNotBlank)?.let { deltaText ->
                if (secondaryUsesPill) UniversalMetricDeltaPill(deltaText) else Text(...)
            }
        }
    }
}
```

Note: `UniversalGaugeRenderer` (lines 68-125) already uses this exact `Column` + `weight(1f, fill=false)` + bottom-anchored secondary pattern (with `Arrangement.Bottom` instead of a `weight(1f)` spacer, since its gauge fills the remaining space itself) — this brings `UniversalValueUnitColumn` back in line with that sibling composable's structure instead of diverging into `Box`-based absolute positioning.

**Verify:** run `DashboardVisualizationLayoutTest.kt`, add a case at a shorter card height (e.g. 160dp) and/or larger font scale to actually catch the regression class this fix addresses; manual check on a device/emulator with font scale set to largest (Settings → Accessibility → Font size) on the dashboard bar-mode and value-mode metric cards.

---

## 7. `UniversalMetricRenderers.kt` — duplicated bar-thickness formula

**File:** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt`
**Lines:** ~154 (`UniversalBarRenderer`), ~186/232 (post-fix-#6 `UniversalValueUnitColumn`), ~307 (`UniversalValueRenderer`)

**Root cause:** `MaterialTheme.dimens.metricTrackThickness + UNIVERSAL_BAR_TRACK_EXTRA_THICKNESS` is written inline 4 times. A future change to the bar's rendered height in one spot but not all four reintroduces the exact "bar sized differently than its reserved slot" bug commit `7f4fc869 implement bar fix` was written to fix.

**Fix:** single composable-scoped val, computed once:
```kotlin
private val UNIVERSAL_BAR_TRACK_EXTRA_THICKNESS = 4.dp

private val Dp.plusBarExtraThickness get() = this + UNIVERSAL_BAR_TRACK_EXTRA_THICKNESS

// or, simplest — a top-level @Composable getter:
private val universalBarTrackThickness: Dp
    @Composable get() = MaterialTheme.dimens.metricTrackThickness + UNIVERSAL_BAR_TRACK_EXTRA_THICKNESS
```
Replace all 4 call sites (`UniversalBarRenderer`'s `M3MetricBar(barHeight = ...)`, both `Box.height(...)` in the fixed `UniversalValueUnitColumn` from finding #6, and `UniversalValueRenderer`'s `Spacer.height(...)`) with `universalBarTrackThickness`.

**Verify:** no behavior change expected (pure extraction) — existing `DashboardVisualizationLayoutTest.kt` should pass unchanged; confirms the refactor is behavior-preserving.

---

## 8. `SleepTrendChart.kt` — unmemoized formatter defeats `remember` key

**File:** `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt`
**Line:** 157

**Root cause:** `val clockFormatter = DateFormat.getTimeFormat(LocalContext.current)` allocates a new `DateFormat` object every recomposition, but it's used as a key in the `remember(selectedState, rangeStartMs, scoringZoneId, ..., clockFormatter)` block (lines 179-205) that builds `tooltipState`. If `DateFormat.equals()` isn't value-based, `remember` never hits its cache — `tooltipState` (and the `Settings`/locale lookup inside `buildSleepTrendTooltipData`) recomputes on *every* recomposition instead of only when the selection changes.

**Fix:**
```kotlin
val context = LocalContext.current
val clockFormatter = remember(context) { DateFormat.getTimeFormat(context) }
```
(`DateFormat.getTimeFormat` reads the user's 12h/24h system setting from the context at call time — keying on `context` re-derives it if the context changes, e.g. locale/config change, while still memoizing across ordinary recompositions.)

**Verify:** no behavior change expected; if a layout-inspector/recomposition-count tool is available, confirm `tooltipState`'s `remember` block no longer re-executes on unrelated recompositions (e.g. scroll) while a tooltip is showing. Otherwise this is a perf-only fix — manual smoke test that tooltip content is still correct (12h/24h format matches device setting) is sufficient.

---

## Skipped: finding #10 — duplicated tick-overhang math (`M3MetricBar.kt:372` region)

`M3MetricBar` reimplements "hide ticks under the fill's round-cap overhang" as inline linear arithmetic (`capCoverageFraction`, see fix #2 above), duplicating the concept `M3MetricGauge.kt` already extracted as `arcTickCapCoverageFraction` (arc/radians-based). The two can't literally share code — one is linear-pixel geometry, the other is angular — so there's no real extraction to do here. Not worth a standalone change; if either file's overhang math changes in the future, check the other for the same fix.

---

## Post-fix checklist (per `.claude/CLAUDE.md`)

- [ ] `./gradlew ktlintFormat`
- [ ] `./gradlew testDebugUnitTest`
- [ ] `./gradlew lintRelease` (after all fixes land)
- [ ] Manual verification pass per "Verify" step above for each fix (this branch touches Compose UI — type-checking alone doesn't confirm the visual regressions are actually resolved)
- [ ] No `internal-docs/DATA_FLOW.md` update needed — none of these fixes touch ingestion, Room schema, or scoring formulas (finding #5's `SleepViewModel` change is data-flow *plumbing* around an existing `combine`/`flatMapLatest`, not a change to what gets ingested or how `ScoringRepository.computeDailySummary` computes)
- [ ] `codegraph index` after any new/deleted files (none expected — all fixes are in-place edits to existing files)
