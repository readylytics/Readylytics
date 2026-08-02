# Dashboard Branch Review — Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the correctness, architecture, performance, and hygiene findings raised by the principal-level review of the dashboard card-visualization branch, without altering the scoring engine.

**Scope:** Branch `claude/readylytics-code-review-4vvjsk` (tree-identical to `feature/card-switch`, head `8fcbf82`) vs. merge base `dfc5850`. 59 commits, 81 files, +11,094/−1,493.

**Architecture:** The branch's structure is sound and is preserved. `BodyCompositionAssessment` stays the BMI/body-fat single source of truth; `DashboardMetricPresentationFactory` / `DashboardRecoveryMetricPresentationFactory` stay the presentation seam; `DashboardMetricCard` stays the fixed M3 shell. The fixes converge duplicated logic onto those existing seams rather than adding new ones.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Room, Hilt, Coroutines/Flow, JUnit, MockK, Robolectric, Compose UI test.

## Global Constraints

- **Scoring math is off-limits.** No changes to `domain/scoring/**` formulas, coefficients, baselines, or `ScoringRepository.computeDailySummary`. `calculateDailyStrainIncrease` may be re-wired but its arithmetic must not change.
- **No Health Connect, ingestion, Room schema, or migration changes.** None of these findings require them.
- Any change to a threshold, band, or status ladder must land with the matching update to `ABOUT.md`, `docs/about.md`, the relevant `internal-docs/DATA_FLOW.md` section, and the in-app `about_*`/`tooltip_*` strings in the same commit (Documentation Synchronization Rule).
- All user-facing strings go in the owning module's `strings.xml` and are read via `stringResource` / `ResourceProvider`.
- Use native M3 components and `MaterialTheme` tokens; no hardcoded colors or ad-hoc typography sizes.
- Pre-commit, every task: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`. `./gradlew lintRelease` once at the end.

## Findings Index

Severity as assessed in review. IDs are referenced by the tasks below.

| ID | Sev | Finding | Location |
| --- | --- | --- | --- |
| F1 | High | Band-boundary values classify one status too low; Readiness hits it deterministically | `DashboardMetricVisualHelper.kt:10` |
| F2 | High | Sleep Efficiency card tint and TalkBack description use two disagreeing ladders | `DashboardMetricPresentationFactory.kt:304`, `:347` |
| F3 | Med | Dead `spo2Status` hides a silent change to SpO2 classification — *resolution decided, see Decisions* | `DashboardMetricPresentationFactory.kt:364` |
| F4 | Med | Weight card re-inlines the BMI thresholds this branch centralised | `DashboardMetricPresentationFactory.kt:176-182` |
| F5 | Med | `BodyFatStatus.Calibrating` is now unreachable — *resolution decided, see Decisions* | `BodyCompositionAssessment.kt:104`, `BodyFatStatus.kt:12` |
| F6 | Med | Strain-delta observer refetches 42 days of summaries per workout, per emission | `ObserveDashboardStrainIncreaseUseCase.kt:63-100` |
| F7 | Med | Everyday-HR users with no workouts never see a strain delta | `ObserveDashboardStrainIncreaseUseCase.kt:64-79` |
| F8 | Med | Drag handle captures `deleteZoneTopPx`/`performDragEnd` stalely | `ReorderableCardGrid.kt:420-438` |
| F9 | Med | `WorkoutsViewModel` restarts four DB subscriptions on any preference change | `WorkoutsViewModel.kt:138-139` |
| F10 | Med | Card-level `mergeDescendants` swallows the info-tooltip action | `DashboardMetricCard.kt:77` |
| F11 | Med | Catalog/delegate API shapes invite silent breakage | `DashboardCardCatalog.kt:29-31`, `CardManagementDelegate.kt:131`, `:158` |
| F12 | Med | Computed bands are never rendered — they only feed a status lookup | `DashboardMetricScalePreparer.kt:44-51`, `DashboardMetricRenderers.kt:50-139` |
| F13 | Med | 1.6 MB `idea.png` committed to repo root | `idea.png` |
| F14 | Low | Dead `bodyFatMidpoint`, computed from a dummy value, plus a duplicate `assessBodyFat` call | `DashboardMetricPresentationFactory.kt:228`, `:248` |
| F15 | Low | Hardcoded user-facing strings, unit scraping, duplicated tenure guard, gauge delta-pill inconsistency, magic dimensions, duplicate status strings, CRLF churn | see Task 8 |
| F16 | Low | Strongest new UI coverage sits in `androidTest` and may not run in CI | `feature/dashboard/src/androidTest/**` |

### Root cause

F1, F2, F3 and F12 are one defect wearing four hats: **each metric's status is derived twice, from two different ladders, and the two disagree.** Task 1 and Task 7 exist to make it derived once. Fix Task 1 before anything else — it is the only finding that changes what a user is told about their health today.

## Decisions

The review left two behavioural questions open. Both are now settled; the tasks below are written against these answers, not against the alternatives.

| Date | Question | Decision |
| --- | --- | --- |
| 2026-07-31 | F3 — which SpO2 ladder is authoritative, the dead `spo2Status` (`>= 95 OPTIMAL`) or the live bands (`95–98 NEUTRAL`, `98–100 OPTIMAL`)? | **The live bands.** Delete `spo2Status`. A normal 96 % overnight reading therefore classifies as Neutral, so the bands must be documented (Task 1). |
| 2026-07-31 | F5 — should the body-fat `Calibrating` state be restored for unset gender, or dropped now that unset gender falls into the fixed 10–30 reference band? | **Dropped.** Delete `BodyFatStatus.Calibrating` and its `toMetricStatus()` arm. `ABOUT.md` already documents the fixed-band behaviour, so no doc change is required (Task 3). |

---

### Task 1: Make status classification single-source and boundary-correct

Resolves **F1, F2, F3**.

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricVisualHelper.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricScalePreparerTest.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactoryTest.kt`
- Modify: `ABOUT.md`, `docs/about.md`, `internal-docs/DATA_FLOW.md` (required — the SpO2 bands become the documented ladder, see Decisions)

**Interfaces:**
- `DashboardMetricVisual.getResolvedStatus()` matches bands half-open (`start <= f < end`), with the final band closed at its top so `f == 1.0` resolves.
- Each `DashboardMetricPresentation` exposes exactly one status; `accessibilityDescription` is always rendered from that same value.

**Why F1 matters concretely:** `main` classified with `score >= 85f -> OPTIMAL`. The branch's `firstOrNull { f >= start && f <= end }` lets both adjacent bands match at a shared boundary and the lower one wins. Readiness feeds in `m?.readinessRounded?.toFloat()` — a rounded integer — so a Readiness of exactly 85 renders NEUTRAL, contradicting `ABOUT.md` ("85–100 Excellent").

- [ ] Write a failing test asserting scores of exactly 40, 60, 85 and 100 resolve to WARNING, NEUTRAL, OPTIMAL, OPTIMAL respectively.
- [ ] Write a failing test asserting Sleep Efficiency of 78 % and 68 % report the same status in `presentation.status` and in `presentation.accessibilityDescription`.
- [ ] Change the band predicate to half-open with a closed final band:
  ```kotlin
  bands.firstOrNull { f >= it.startFraction && f < it.endFraction }?.status
      ?: bands.lastOrNull()?.takeIf { f >= it.startFraction }?.status
      ?: MetricStatus.NEUTRAL
  ```
  Collapse the four identical `is …Score/Goal/PersonalBaseline/ReferenceRange` arms onto one shared helper while there.
- [ ] Delete `effStatus` (`:304`); wire `status` and the description to `effVisual.getResolvedStatus()`, matching the STRAIN_RATIO card whose comment at `:579` already states this rule.
- [ ] Delete the dead `spo2Status` (`:364`). **Decided: the live bands are authoritative** — `80–90 POOR`, `90–95 WARNING`, `95–98 NEUTRAL`, `98–100 OPTIMAL`. The card already reads them via `spo2Visual.getResolvedStatus()` at `:405`, so this is a deletion, not a rewire.
- [ ] Because a normal 96 % overnight reading now classifies as Neutral rather than Optimal, add the SpO2 bands to `ABOUT.md` and `docs/about.md` alongside the BMI/body-fat section this branch added, and add the `internal-docs/DATA_FLOW.md` row. Required by the Documentation Synchronization Rule — this is a user-visible threshold change, not an internal cleanup.
- [ ] Write a test pinning the four SpO2 bands so the ladder cannot silently drift back.
- [ ] Add a suite-wide test that iterates every card and asserts `classificationText(presentation.status)` appears in `presentation.accessibilityDescription`. This is the regression guard for the whole finding family.
- [ ] `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`

---

### Task 2: Remove the stray binary

Resolves **F13**.

**Files:**
- Delete: `idea.png`
- Modify: `.gitignore`

- [ ] `git rm idea.png` (1,682,393 bytes, added in `0b692a4` "style align", unrelated to the change).
- [ ] Add a root-level image ignore rule so it cannot recur.
- [ ] Because the blob is already in branch history, drop it from history while the branch is unmerged — interactive rebase or `git filter-repo --path idea.png --invert-paths`, then force-with-lease. After merge this becomes permanent and far more expensive.

---

### Task 3: Finish the body-composition single source of truth

Resolves **F4, F5, F14**.

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/model/BodyCompositionAssessment.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/model/BodyFatStatus.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt`
- Modify: `core/model/src/test/kotlin/app/readylytics/health/domain/model/BodyCompositionAssessmentTest.kt`
- Modify: `ABOUT.md`, `docs/about.md` only if the fixed-band wording is found to be inaccurate (dropping `Calibrating` is not itself a behaviour change — see Decisions)

**Interfaces:**
- `BodyCompositionAssessment` exposes its BMI bands (mirroring the existing `BodyFatReference` pattern) so callers build `RawMetricBand`s from them instead of restating `18.5 / 25 / 30`.

- [ ] Write a failing test asserting the weight card's bands match `BodyCompositionAssessment.assessBmi` at 18.4, 18.5, 24.9, 25, 29.9 and 30.
- [ ] Expose the BMI bands from `BodyCompositionAssessment` and consume them at `DashboardMetricPresentationFactory.kt:176-182`. The axis anchors `15 / 21.7 / 35` are currently magic numbers absent from `ABOUT.md` — name them as constants and document them.
- [ ] **Decided: drop `BodyFatStatus.Calibrating`.** Unset gender stays in the fixed 10–30 reference band; the state is not restored. Delete the subclass at `BodyFatStatus.kt:12` and the `toMetricStatus()` arm at `BodyCompositionAssessment.kt:196`. Verified clean — those two lines are the only references in the codebase; no test, ViewModel, or composable mentions it, and `MetricStatus.CALIBRATING` stays in use by other metrics. Sealed-class exhaustiveness means the compiler will surface anything missed.
- [ ] No `ABOUT.md` / `docs/about.md` change needed: both already describe the fixed-band behaviour for Other / Prefer not to say / unset. Confirm this while editing rather than assuming it.
- [ ] Delete dead `bodyFatMidpoint` (`:228`). It is computed by passing a dummy `20f` into `assessBodyFat`, correct only because the midpoint happens to be value-independent — a coupling that will break silently. Call `assessBodyFat` once at `:248` and read `.reference.referenceMidpoint` from that single result.
- [ ] Replace the inline `app.readylytics.health.domain.model.BodyCompositionAssessment.…` at `:248` with the alias already imported at the top of the file.
- [ ] `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`

---

### Task 4: Correct and de-cost the strain-delta observer

Resolves **F6, F7**, and the duplicated guard in **F15**.

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/ObserveDashboardStrainIncreaseUseCase.kt`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/GetWorkoutDisplayMetricsUseCase.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/usecase/ObserveDashboardStrainIncreaseUseCaseTest.kt`
- Modify: `internal-docs/DATA_FLOW.md` (the `GetWorkoutDisplayMetricsUseCase` row and the dashboard-observer paragraph both describe this path)

**Interfaces:**
- Tenure source is selected by `strainLoadSourceMode`: `WorkoutRepository.getEarliestWorkoutTimestamp()` for `WORKOUT_ONLY`, `DailySummaryDao.getEarliestDateMs()` (already exists) for `EVERYDAY_HEART_RATE`.
- `GetWorkoutDisplayMetricsUseCase.execute` gains an optional pre-fetched `historicalSummaries` parameter, following the `preferences` parameter this branch already added for the same reason.

Decision (2026-08-02, see `docs/superpowers/plans/2026-08-02-strain-delta-observer-remediation.md`): tenure is derived from data **already being observed** rather than either DB method named above — `LoadSourceSelector.selectEarliestDataDate(workouts, summaries, mode, zoneId)` (new pure function, `core/model`) reads the earliest date out of the already-fetched `workouts`/`summaries` lists per mode. This fully eliminates the suspend call from the hot path (stronger than "hoist out of `mapLatest`") because `dataTenureDays` is only ever compared against the 7-day threshold and both callers already fetch a 48+-day window. `WorkoutRepository.getEarliestWorkoutTimestamp()` lost both its callers and was deleted (DAO/interface/impl). The same fix was also applied to `WorkoutsViewModel` (mirrored F6/F7/F15, not originally in this task's Findings-Index scope but present and real — see the sub-plan's Decisions table).

- [x] Write a failing test: a user in `EVERYDAY_HEART_RATE` mode with 30 days of daily summaries and **zero** workouts must receive a non-null strain delta. Today `getEarliestWorkoutTimestamp()` returns null → `dataTenureDays = 0` → permanent null, so the delta silently never appears for that cohort. (Both `ObserveDashboardStrainIncreaseUseCaseTest` and `WorkoutsViewModelTest`.)
- [x] Write a failing test asserting `dailySummaryRepository.getSince` is called once per emission, not once per workout. (`GetWorkoutDisplayMetricsUseCaseTest`: `coVerify(exactly = 0) { dailySummaryRepository.getSince(any()) }` when `historicalSummaries` is pre-fetched.)
- [x] Select the tenure source by load-source mode. (Via `LoadSourceSelector.selectEarliestDataDate`, see Decision above.)
- [x] Hoist tenure out of `mapLatest` — it is a suspend DB query re-run on every emission of either observed flow, though it only changes on ingestion. Derive it from the already-observed workouts or give it its own flow. (Derived from already-observed data; suspend call removed entirely, not just relocated.)
- [x] Thread the already-combined `summaries` into `GetWorkoutDisplayMetricsUseCase` so N workouts stop triggering N full 42-day history reads. This is the N+1 and it re-runs on every Room invalidation during a sync. (Both `ObserveDashboardStrainIncreaseUseCase`'s `WORKOUT_ONLY` branch and `WorkoutsViewModel`'s `recentItems` mapping.)
- [x] Delete the `dataTenureDays < 7` pre-guards in both callers (`ObserveDashboardStrainIncreaseUseCase.kt:79`, `WorkoutsViewModel.kt:349`) — `calculateDailyStrainIncrease` already owns that rule, which is why it was extracted.
- [x] `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` (completed 2026-08-02, `./gradlew lintRelease` also clean)

---

### Task 5: Compose and lifecycle hygiene

Resolves **F8, F9, F10**.

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ReorderableCardGrid.kt`
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt`
- Modify: `feature/dashboard/src/androidTest/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardTest.kt`
- Modify: `feature/workouts/src/test/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModelTest.kt`

- [ ] **F8** — wrap `deleteZoneTopPx` and `performDragEnd` in `rememberUpdatedState` and read `.value` inside the gesture block. `pointerInput(card.cardId)` never re-keys, so the running gesture keeps its original captures; `deleteZoneTopPx` is null until the delete zone reports position at `:269`. The staleness predates this branch, but the payload is new: `performDragEnd` closes over `configByCardId`, which now carries `requestedDisplayMode`, so changing a card's mode and then dragging to reorder writes back the stale config and reverts the mode. Add a test covering mode-change-then-reorder.
- [ ] **F9** — narrow the outer preference flow to the fields the boundary math reads:
  ```kotlin
  settingsRepo.userPreferences
      .map { it.scoringZone() to it.strainLoadSourceMode }
      .distinctUntilChanged()
  ```
  keeping the full snapshot inside the inner `combine`. Moving `userPreferences` outside `flatMapLatest` was necessary to get `scoringZone()` into the boundaries, but it now cancels and rebuilds `observeLatest`, three `observeSince` Room flows, and all per-workout computation whenever *any* preference field changes.
- [ ] **F10** — make the info tooltip reachable. `Modifier.clickable` is not a merging boundary, so the deliberately built 48dp target at `:243` is absorbed into the card's merged node and its description replaced by the parent's. Either drop `mergeDescendants` on the card and describe the content subtree, or expose the tooltip as a `CustomAccessibilityAction` on the merged node. Replace the hand-rolled `Popup` with M3 `TooltipBox`/`PlainTooltip` per the project's M3 rule. Add a TalkBack-shaped test asserting the tooltip action is reachable.
- [ ] `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`

---

### Task 6: Tighten the catalog and delegate APIs

Resolves **F11**.

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/domain/dashboard/DashboardCardCatalog.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/domain/dashboard/CardManagementDelegate.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt`
- Modify: the corresponding `DashboardCardCatalogTest.kt` / `CardManagementDelegateTest.kt`

- [ ] Collapse `legacyDefaults` and `supportedModesMap` into a single `Map<CardId, DashboardCardSpec>`. Two parallel maps over 16 `CardId`s degrade silently to `listOf(legacyDefault)` when only one is updated.
- [ ] Add a test asserting every `CardId` rendered on the dashboard has a spec, so an omission fails loudly.
- [ ] Delete `renderMode()` — it is a pure alias of `requestedMode()` — and drop the duplicate parameter from `DashboardMetricCard`, which currently takes both `requestedMode` and `renderMode` as separate params that can never differ.
- [ ] Delete the dead `CardManagementDelegate.toggleCardManagement()`. It sets `_isManagingCards = true` without `_pendingConfigs` — exactly the state that makes `DisplayModeChanged` throw — and contradicts the class KDoc's "all mutations route through onEvent". The ViewModel already defines its own that calls `enterEditMode`.
- [ ] Make `DisplayModeChanged` a no-op when not editing instead of `error(...)`; an unrecoverable throw from a UI callback is the wrong failure mode for a recoverable state.
- [ ] `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`

---

### Task 7: Decide the fate of the band model

Resolves **F12**. Do this after Task 1, which depends on the bands staying in place.

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricScalePreparer.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt`
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricGauge.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricPresentation.kt`

Every `DashboardMetricVisual` carries `List<DashboardMetricBand>` mapped into fraction space with ~190 lines of dedicated tests, but neither renderer reads `bands` — they draw only `markerFraction` and one `activeColor`. The sole consumer is `getResolvedStatus()`. This indirection is also what caused F1: classification happens after `coerceIn(0f, 1f)` and two float divisions rather than against the thresholds themselves. `bands = emptyList()` on the BODY_FAT visual (`:260`) makes that card's `getResolvedStatus()` structurally meaningless — harmless today only because BODY_FAT uses `bodyFatStatusVal` instead.

Decision: **Option B — remove them** is selected and is intentionally executed together with Task 1 by `docs/superpowers/plans/2026-08-01-dashboard-raw-status-classification.md`. Status is now classified from raw presentation values, `DashboardMetricVisual` / `RawMetricBand` band transport is removed, and geometry remains in `DashboardMetricScalePreparer`. Task 7 is not complete until its implementation and verification steps have passed.

- [ ] **Option A (unselected) — render them.** Draw the bands as coloured track segments in `M3MetricGauge` and the Bar track. This is presumably why they were built, makes the gauges considerably more informative, and gives the model a reason to exist. Fill in the BODY_FAT bands so that card stops being a special case.
- [x] **Option B (selected) — remove them.** Classify on raw values in the presentation factories and delete `bands` from `DashboardMetricVisual`, `RawMetricBand`, and the band-mapping half of `DashboardMetricScalePreparer`. Simpler, and removes the clamping artifact permanently.
- [x] `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` (completed 2026-08-01)

---

### Task 8: Low-severity sweep

Resolves **F15**.

**Files:** as listed per item.

- [ ] Move user-facing strings out of Kotlin into the owning `strings.xml` — `"—"` (×8), `"100"`, `"kg"`/`"lbs"` in `DashboardMetricPresentationFactory.kt:116,124,143,188-196`, and the `", "` separator concatenated into a TalkBack description at `DashboardMetricCard.kt:68`. `unit_mmHg` is already a resource two lines from the hardcoded units.
- [ ] Replace `m?.weightKgDisplay?.replace(" kg", "")?.replace(" lbs", "")` (`:188`) with a raw value plus formatter. String-scraping a formatted value breaks the moment the formatter is localised.
- [ ] Make Gauge mode honour `CardId.usesDeltaPill()` like Bar and Value do (`DashboardMetricRenderers.kt:88`). Today Sleep Duration / Circadian / Heart Rate get a pill in Gauge mode only, contradicting the comment at `:244-247`.
- [ ] Replace magic dimensions with design-system tokens: the fixed `120.dp × 60.dp` canvas inside a `fillMaxWidth` box, `fontSize = 11.sp` in three places overriding the M3 type scale, `offset(y = (-8).dp)`, and the literal `20.dp` at `DashboardMetricRenderers.kt:85` duplicating `DASHBOARD_SECONDARY_SLOT_HEIGHT` declared 15 lines below its first use.
- [ ] Remove unused `TextAlign`/`TextOverflow` imports in `M3MetricGauge.kt:23-24` and replace the fully-qualified `androidx.compose.material3.Text` / `…layout.Column` at `:129-149` with imports; likewise use the `DashboardR`/`CoreUiR` aliases already imported in `DashboardMetricPresentationFactory.kt`.
- [ ] Delete `bmi_warning`/`bmi_optimal`/`bmi_neutral`/`bmi_poor` from `feature/vitals/src/main/res/values/strings.xml`; they duplicate `metric_status_*` added to `core/ui` in the same branch.
- [ ] Normalise the three CRLF lines introduced into `ABOUT.md`. `git diff --ignore-all-space` reduces the file to its 40 intended new lines; the rest is line-ending churn in a load-bearing doc.
- [ ] `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`

---

### Task 9: Full verification

Resolves **F16** and closes the plan.

The review itself could not run any Gradle task: the review environment has no Android SDK (`ANDROID_HOME` unset, no `sdkmanager`/`adb`, no Gradle dependency cache) and every module — including `core/scoring` and `core/model` — applies `readylytics.android-library-conventions`, so even the pure-Kotlin scoring tests cannot configure. **The branch has not been confirmed to compile.** All findings above are from source and diff analysis.

- [ ] `./gradlew ktlintFormat`
- [ ] `./gradlew testDebugUnitTest` — first run confirms the branch compiles at all. Pay attention to `BodyFatStatus`: it gained a `Warning` subclass, so any non-exhaustive `when` over it is a compile error. Review found only one consumer (`toMetricStatus` at `BodyCompositionAssessment.kt:190`) and it handles all five, but a build is the only proof.
- [ ] `./gradlew lintRelease`
- [ ] `./gradlew assembleDebug`
- [ ] Confirm CI actually executes `feature/dashboard/src/androidTest/**`. `DashboardMetricCardTest` (941 lines) and `DashboardScreenTest` (315 lines) are the strongest new UI coverage but are instrumented tests needing a device, while `feature/dashboard/build.gradle.kts` adds the Compose test dependencies to `testImplementation` only. If CI is JVM-only, either migrate them to Robolectric under `src/test` or add an instrumented CI job.
- [ ] Verify on-device: Readiness of exactly 85 shows Optimal; Sleep Efficiency tint matches its TalkBack announcement; the info tooltip is reachable with TalkBack; changing a card's display mode and then reordering preserves the mode.

---

## Merge Assessment

**Ready after minor corrections.**

The architecture is sound and the discipline better than average — a 640-line use case properly decomposed, a real single source of truth for body composition, tolerant additive persistence for the new proto field, complete documentation sync, and scoring formulas correctly untouched. What blocks a clean merge is the F1/F2/F3 cluster: the dashboard currently reports health status incorrectly at band boundaries and tells sighted and screen-reader users different things about the same card. None of it is architectural, and Tasks 1–2 alone clear the merge blockers.
