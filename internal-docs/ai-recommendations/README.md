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
letting the LLM reconstruct them. These fields are declared in the system prompt and
daily template but are **not yet emitted** by the data layer. They are deterministic
data-assembly additions, not scoring changes:

| Expected field | Purpose | Status |
|---|---|---|
| `readiness_band` (POOR/WARNING/NEUTRAL/OPTIMAL/CALIBRATING) | Readylytics' own Readiness classification so the AI never invents cutoffs | `UPSTREAM DATA REQUIRED` — mapping exists as `Float?.scoreStatus()` (`core/model/.../MetricStatusExtensions.kt`), not yet passed to the prompt |
| `load_context` (BELOW_TYPICAL/SWEET_SPOT/ELEVATED/HIGH/UNKNOWN) | Current accumulated load state, distinct from recommended session load | `UPSTREAM DATA REQUIRED` — mapping exists as `Float.strainRatioStatus()`, not yet passed to the prompt |
| `recommended_load` envelope (`{qualitative, min_trimp, max_trimp}`) | Deterministic load target so the AI never fabricates TRIMP ranges | `UPSTREAM DATA REQUIRED` — no envelope computation exists |
| `today_completed_workouts`, `today_trimp`, `today_training_minutes`, `data_current_until` | So the recommendation is for *remaining* training today | `UPSTREAM DATA REQUIRED` — not yet fetched/emitted |
| `advisor_data_confidence` (LOW/MEDIUM/HIGH) | Deterministic confidence from Readylytics | `UPSTREAM DATA REQUIRED` — until provided, the system prompt applies a prompt-side deterministic mapping (calibration phase + data completeness + everyday-load coverage) |

**OPEN QUESTION:** the in-app `ai_init_prompt` string resource
(`feature/dashboard/src/main/res/values/strings.xml`) is synchronized with
`BASE_SYSTEM_PROMPT.md`. The daily prompt's implemented formatter still emits the
original field set; the new fields above are contract additions the data layer must
supply before the daily prompt can fully exercise the revised system prompt.

