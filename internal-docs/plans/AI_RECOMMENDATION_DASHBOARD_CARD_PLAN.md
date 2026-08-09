# AI Recommendation Dashboard Card — Implementation Plan

## Context

`internal-docs/ai-recommendations/` (`BASE_SYSTEM_PROMPT.md`, `DAILY_PROMPT_TEMPLATE.md`)
already defines the content of two AI prompts: a static "init" prompt that establishes
persona/vocabulary/output-contract for an AI agent, and a per-day template describing
what data to feed it. Both were designed as prompt content only, with no
integration built yet.

This plan turns that content into an actual dashboard feature: a card, styled like the
existing `InsightCard`, that lets the user copy either prompt to their clipboard so
they can paste it into any AI chat app of their choice. There is no in-app AI/LLM
call — this is a manual "copy the setup, copy today's data" workflow, which keeps the
app offline-first and avoids building AI-provider integration, API-key storage, or a
network client (none of which exist in the repo today).

Two copy actions, matching the user's ask:
- **Copy Setup Prompt** — static, copies the "init" prompt verbatim (session
  persona/rules). Copied once per new AI conversation.
- **Copy Today's Prompt** — dynamic, copies a filled-in version of the daily
  template with the user's real data substituted for every `{{placeholder}}`.

## Architecture overview

Follows the existing Clean Architecture split: pure-Kotlin domain logic in
`core/scoring` (zero Android deps, matching the "Logic Isolation" rule), a small
repository extension in `core/model`/`core/database`, and UI/DI wiring in
`feature/dashboard` + `app`.

```
core/model     — WorkoutRepository: add getInRange(fromMs, toMs)
core/database  — WorkoutRepositoryImpl: delegate to existing WorkoutDao.getWorkoutsInRange
core/scoring   — new domain/airecommendation/ package:
                   DailyPromptData.kt              (pure data class)
                   RecoveryFlagGlossary.kt          (pure: RecoveryFlag -> plain-English gloss)
                   ComputeWorkoutPatternSummaryUseCase.kt  (pure: List<WorkoutData> -> pattern summary)
                   DailyPromptFormatter.kt          (pure: DailyPromptData -> String)
                   GetDailyPromptDataUseCase.kt      (impure, suspend, @Inject — orchestrates repos)
feature/dashboard — AiRecommendationCard.kt, CardId wiring, DashboardViewModel additions,
                     strings.xml (init prompt text + card copy)
```

## 1. Data layer: expose a bounded workout-range query

`WorkoutDao.getWorkoutsInRange(fromMs, toMs)` already exists
(`core/model/src/main/kotlin/app/readylytics/health/data/local/dao/WorkoutDao.kt`) but
is only called internally by `ScoringRepositoryImpl`. Add a thin pass-through to the
public repository interface (mirrors the existing `getById`/`observeSince` style):

- `core/model/src/main/kotlin/app/readylytics/health/domain/repository/WorkoutRepository.kt`
  — add `suspend fun getInRange(fromMs: Long, toMs: Long): List<WorkoutData>`.
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/WorkoutRepositoryImpl.kt`
  — implement by delegating to `workoutDao.getWorkoutsInRange(fromMs, toMs)` and mapping
  `WorkoutRecordEntity` → `WorkoutData` (reuse the existing entity→domain mapper already
  used by other methods in this file).

No DAO/schema changes — this is purely a new interface method + delegation.

## 2. Pure-Kotlin domain logic (`core/scoring/.../domain/airecommendation/`)

**`DailyPromptData.kt`** — one data class mirroring `DAILY_PROMPT_TEMPLATE.md`
sections A–G verbatim (profile/calibration, today's Readiness & baselines, yesterday's
sleep breakdown, yesterday's workout(s), current load state, active recovery flags with
glosses, and the workout-pattern summary). Nested types for the repeatable blocks
(`YesterdayWorkout`, `ExerciseTypePattern`).

**`RecoveryFlagGlossary.kt`** — a pure `object`/`fun` mapping each `RecoveryFlag` enum
value (`core/model/.../domain/model/ReadinessResult.kt`) to the one-line plain-English
gloss already drafted in the daily template's Section F (e.g. `OVERREACHING` →
"training load has risen faster than your fitness can currently absorb").

**`ComputeWorkoutPatternSummaryUseCase.kt`** — the one genuinely new piece of logic (no
prior aggregation exists in the codebase). Pure function:
`execute(workouts: List<WorkoutData>, today: LocalDate, lookbackMonths: Int = 3): WorkoutPatternSummary`.
Groups by `exerciseType` for frequency/avg TRIMP/avg duration/preferred days-of-week
(mirror the day-of-week walking style already used in
`feature/workouts/.../mappers/DailyRasBreakdownMapper.kt`, but keyed by exercise type
here); computes `restDaysPerWeekAvg`, `mostRecentRestDayGapDays`, and
`longestCurrentStreakDays` by walking calendar days backward from `today` and treating
any day with zero matching workouts as a rest day (structurally similar to the
day-streak walk in `core/scoring/.../domain/insights/HrvDeclineStreakRule.kt`, adapted
from HRV data to workout presence). Add `ScoringConstants.AiRecommendation.LOOKBACK_MONTHS`
alongside the other tunables in `ScoringConstants.kt` rather than hardcoding `3`.

**`DailyPromptFormatter.kt`** — pure `fun format(data: DailyPromptData): String`,
producing the final copy-ready text. Implemented as plain Kotlin string templates
(multi-line raw strings with interpolation) that mirror `DAILY_PROMPT_TEMPLATE.md`'s
section structure and headers, but with every `{{placeholder}}` replaced by a real
value — no generic templating engine, no `{{}}` token parsing at runtime. Section
labels/headers pulled from `feature/dashboard/src/main/res/values/strings.xml` so they
go through the same localization path as the rest of the app's UI text (per the
project's "all user-facing strings in strings.xml" rule); numeric/data values are
interpolated directly since they aren't translatable content.

**`GetDailyPromptDataUseCase.kt`** — the impure orchestrator, `@Inject constructor`,
`suspend fun execute(today: LocalDate): DailyPromptData`. Follows the exact DI/suspend
pattern already used by `GetWorkoutDisplayMetricsUseCase.kt`. Assembles the data by:
- `DailySummaryRepository.getByDate(...)` (not `ScoringRepository.computeDailySummary`
  — that's the raw/uncached compute path; ViewModels read the persisted result via
  `DailySummaryRepository`, same as `DashboardViewModel`/`WorkoutsViewModel` already do)
  for today and yesterday.
- `WorkoutRepository.getInRange(...)` for yesterday's workout(s) and for the
  lookback-months window feeding `ComputeWorkoutPatternSummaryUseCase`.
- `GetWorkoutDisplayMetricsUseCase.execute(...)` per yesterday workout, for
  `gainedStrain`/`loadClassification` (this already resolves prefs/samples/historical
  summaries internally — reuse it rather than calling `ComputeWorkoutLoadMetricsUseCase`
  directly).
- `UserPreferencesReader` for physiology profile and the active
  Strain/Training-Load-source setting (`strainLoadSourceMode`) — this is the setting
  that actually drives Readiness (per `ABOUT.md:162-166`); the daily prompt must label
  which source its Load/Readiness figures come from.
- `RecoveryFlag`s straight off the fetched `DailySummary.recoveryFlags`, glossed via
  `RecoveryFlagGlossary`.

## 3. Static "init" prompt text

Add `R.string.ai_init_prompt` to `feature/dashboard/src/main/res/values/strings.xml`,
sourced from `internal-docs/ai-recommendations/BASE_SYSTEM_PROMPT.md`. Leave a code
comment above the resource pointing back at that file as the source of truth, so a
future edit to the doc has an obvious place to sync (same spirit as the existing
`internal-docs/DATA_FLOW.md` sync convention, though this isn't part of
`DocumentationDriftTest`'s enforced scope). This string is fully static — the
Composable reads it directly with `stringResource(...)` and needs no ViewModel
involvement to copy it.

## 4. UI: `AiRecommendationCard.kt` (`feature/dashboard`)

New composable, structurally mirroring `InsightCard.kt`'s established look: `OutlinedCard`,
`shape = MaterialTheme.shapes.large`, `containerColor = surfaceVariant.copy(alpha = 0.3f)`,
`BorderStroke(dimens.borderThin, outlineVariant)`, leading icon + title + short static
body text ("Get an AI-generated training recommendation using an AI chat app of your
choice"). Below the body, two `OutlinedButton`/`TextButton`s in a `Row` — "Copy Setup
Prompt" and "Copy Today's Prompt" — instead of `InsightCard`'s single trailing
info/dismiss `IconButton`s (no established clipboard-copy convention exists in the repo
yet, so this introduces one: `LocalClipboardManager.current` + `Icons.Default.ContentCopy`,
consistent icon sizing/tinting with the rest of the card).

- **Copy Setup Prompt** — fully local to the Composable: read `stringResource(R.string.ai_init_prompt)`,
  `clipboardManager.setText(AnnotatedString(text))`, show a confirmation via the
  dashboard's existing `SnackbarHostState` (already present in `DashboardScreen`).
- **Copy Today's Prompt** — needs async data, so it goes through the ViewModel rather
  than the Composable calling a suspend use case directly (keeps ViewModels as the
  single owner of business-logic orchestration per the project's MVVM rule). Add:
  - `DashboardEvent.RequestDailyPromptCopy` (new case in the existing sealed interface,
    `feature/dashboard/DashboardEvent.kt`).
  - `DashboardViewModel`: a `private val _dailyPromptText = MutableStateFlow<String?>(null)`
    / `val dailyPromptText: StateFlow<String?>` pair, following the exact same
    set-then-clear idiom already used for `errorMessage` in this same ViewModel. On
    `RequestDailyPromptCopy`, launch a coroutine calling `GetDailyPromptDataUseCase.execute(today)`
    → `DailyPromptFormatter.format(...)`, set the result on `_dailyPromptText`.
  - `DashboardScreen`/`DashboardRoute`: collect `dailyPromptText` via
    `collectAsStateWithLifecycle()`; a `LaunchedEffect(dailyPromptText)` that, when
    non-null, writes it to the clipboard, shows the snackbar, then dispatches a
    "handled" event (or calls a `viewModel.clearDailyPromptText()`) to reset the
    `StateFlow` back to `null` — mirroring how `errorMessage` is already consumed once
    and cleared.

## 5. Wiring the card into the dashboard's card catalog

`AiRecommendationCard` is a first-class, user-manageable dashboard card (add/remove/
reorder via the existing "Customize Dashboard" flow), not an ephemeral single-slot
insight — so it goes through the same catalog path as `HeartRateCard`, not the
`CardId.INSIGHTS` single-slot/`MainNavHost` indirection (that indirection exists only
because the Insights card needs `feature/insights` types; this card's dependencies —
`GetDailyPromptDataUseCase`, `DailyPromptFormatter` — live in `core/scoring`, which
`feature/dashboard` already depends on, so no cross-module slot-injection is needed).

- `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardConfiguration.kt`
  — add `AI_RECOMMENDATION` to the `CardId` enum (purely additive; existing serialized
  configs are unaffected, same as when prior cards like `OXYGEN_SATURATION` were added).
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt`
  — add a `cardMap[CardId.AI_RECOMMENDATION] = { AiRecommendationCard(...) }` entry in
  `buildCardDataMap`, directly (no lambda-slot injection needed, unlike `insightsCard`).
- Locate and extend whatever default-card-list/migration logic added the most recent
  `CardId` (e.g. `OXYGEN_SATURATION`) — likely in `CardManagementDelegate.kt` or a
  default-configuration constant — so `AI_RECOMMENDATION` appears in the default catalog
  for both new and existing installs.
- `feature/dashboard/src/main/res/values/strings.xml` — add card title/body/button-label
  strings (`ai_recommendation_card_title`, `ai_recommendation_card_body`,
  `ai_recommendation_copy_setup_button`, `ai_recommendation_copy_daily_button`,
  `ai_recommendation_copied_snackbar`).

## Verification

- Unit tests (zero Android deps, mirroring source package structure per project
  convention): `ComputeWorkoutPatternSummaryUseCaseTest` (boundary cases: no workouts in
  window, all-rest-days, a mixed multi-type history, streak/gap edge cases around
  `today`) and `DailyPromptFormatterTest` (every field present/absent, calibrating vs.
  mature phase output, empty recovery-flags case, zero-vs-multiple yesterday workouts).
- `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`, then `./gradlew lintRelease`
  once all coding tasks are complete, per project convention.
- Manual verification: `./gradlew installDebug`, add the new card via "Customize
  Dashboard", tap both copy buttons, paste into a notes app to confirm the setup prompt
  matches `BASE_SYSTEM_PROMPT.md` and the daily prompt contains real (not placeholder)
  values for a day with data, a day with no workouts, and a still-calibrating profile.
- `codegraph index` after new files land, per this repo's file-lifecycle convention.
