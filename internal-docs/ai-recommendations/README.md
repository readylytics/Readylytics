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
