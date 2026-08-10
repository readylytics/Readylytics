# AI Recommendation Prompts

This directory defines the content for a manual **copy-to-external-AI-chat**
workflow. The app ships an "AI Training Recommendation" dashboard card that lets the
user copy the setup prompt and today's populated prompt into any AI chat app of their
choice. There is no in-app LLM call, HTTP client, API-key storage, or Settings toggle —
the workflow is intentionally offline-first and manual.

- `BASE_SYSTEM_PROMPT.md` — the static system prompt: persona, tone/terminology
  rules, domain vocabulary, reasoning priorities, and the required output contract.
  Sent once per session. Source of truth for the in-app `ai_init_prompt` string
  resource (`feature/dashboard/src/main/res/values/strings.xml`).
- `DAILY_PROMPT_TEMPLATE.md` — the per-day user-turn template, with `{{placeholder}}`
  fields mapped to the app's real `DailySummary`, `WorkoutRecordEntity`, and
  `WorkoutLoadMetrics` fields. Source of truth for the `DailyPromptFormatter`
  output (`core/scoring/.../domain/airecommendation/DailyPromptFormatter.kt`).

## Scope notes

- **Relationship to `feature/insights`:** this AI layer is conceptually adjacent to
  the existing rule-based Insights feature but is a separate, opt-in concept — it does
  not modify `feature/insights` or its output.
- **Relationship to `docs/insights.md`:** that file states (line ~31) "No machine
  learning or AI" — this is scoped specifically to the deterministic Insights rule
  engine, not the whole app, so these prompt templates don't contradict it.
- **Typical workout pattern data (Section G of the daily template):** implemented by
  `ComputeWorkoutPatternSummaryUseCase` in
  `core/scoring/.../domain/airecommendation/`, which groups
  `WorkoutRepository.getInRange(...)` results over the three-month lookback window
  (tunable via `ScoringConstants.AiRecommendation.LOOKBACK_MONTHS`).
- **No AI-provider integration:** there is no HTTP client wired to an LLM provider, no
  API-key storage, and no in-app AI response rendering. The user pastes the copied
  prompts into an external app of their choice; the output contract is consumed there.
- **Output contract:** the system prompt requires **strict JSON** (single object,
  every field present, `null` where unknown). If a provider is later wired up,
  enforce it with Structured Outputs / JSON Schema rather than natural language alone.

## Data-contract gaps (`OPEN QUESTION` / `UPSTREAM DATA REQUIRED`)

The revised system prompt expects Readylytics to supply derived states rather than
letting the LLM reconstruct them. Status below reflects the actual wiring in
`GetDailyPromptDataUseCase` / `DailyPromptFormatter`, not just what the templates
describe:

| Expected field | Purpose | Status |
|---|---|---|
| `readiness_band` (POOR/WARNING/NEUTRAL/OPTIMAL/CALIBRATING) | Readylytics' own Readiness classification so the AI never invents cutoffs | `RESOLVED` — `GetDailyPromptDataUseCase.mapToday()` sets `readinessBand = metricStatus.name` via `Float?.scoreStatus()`; rendered in Section B |
| `load_context` (BELOW_TYPICAL/SWEET_SPOT/ELEVATED/HIGH/UNKNOWN) | Current accumulated load state, distinct from recommended session load | `RESOLVED` — `mapLoadState()` sets `loadContext` via `strainRatio.toLoadContext()`; rendered in Section E |
| `permitted_recommendation_ceiling` (REST/ACTIVE_RECOVERY/TRAIN/UNKNOWN) | Hard ceiling so the AI never invents its own allowed recommendation type | `RESOLVED` — `PermittedRecommendationMapper.resolve()` (`core/model/.../PermittedRecommendationMapper.kt`) + `PermittedRecommendation` enum; rendered in Section B as `today.permittedRecommendation`. Defaults to `UNKNOWN` when no `DailySummary` exists for the day — the system prompt now treats `UNKNOWN` as an effective `REST` ceiling |
| `recommended_load` envelope (`{qualitative}`) | Deterministic load ceiling so the AI never fabricates a target load | `UPSTREAM DATA REQUIRED` — still no field on `DailyPromptData` and no computation; not rendered by `DailyPromptFormatter`. This is the one remaining gap from this list. Shape corrected to `{ qualitative: LIGHT/MODERATE/NORMAL/HIGH | null }` (matches `DAILY_PROMPT_TEMPLATE.md`'s `{{recommendedLoad}}`) — drop the earlier `min_trimp`/`max_trimp` idea, the system prompt never wants the AI reasoning about raw TRIMP ranges |
| `today_completed_workouts`, `today_trimp`, `today_training_minutes`, `data_current_until` | So the recommendation is for *remaining* training today | `RESOLVED` — computed in `GetDailyPromptDataUseCase.execute()` from `WorkoutRepository.getInRange(todayMidnight, tomorrowMidnight)`; rendered in Section B |
| `advisor_data_confidence` (LOW/MEDIUM/HIGH) | Deterministic confidence from Readylytics | `RESOLVED` — `resolveAdvisorConfidence()` always populates it (not gated on a `DailySummary` existing); rendered in Section A. The system prompt's prompt-side fallback mapping (Section 7) is now dead code in practice but stays as a documented fallback in case this field is ever absent |

**Remaining gap:** `recommended_load` is the only field above not yet wired end-to-end.
Everything else the revised system prompt (`BASE_SYSTEM_PROMPT.md`, synced to the
in-app `ai_init_prompt` string resource in
`feature/dashboard/src/main/res/values/strings.xml`) depends on is already emitted by
`DailyPromptFormatter`.

