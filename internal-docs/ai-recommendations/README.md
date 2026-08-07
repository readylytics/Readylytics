# AI Recommendation Prompts

This directory contains **prompt content only** — no app code, no UI, no AI-service
integration exists yet anywhere in the repo. These are design artifacts for a future
AI-driven training recommendation layer.

- `BASE_SYSTEM_PROMPT.md` — the static system prompt: persona, tone/terminology
  rules, domain vocabulary, reasoning priorities, and the required output contract.
  Sent once per session.
- `DAILY_PROMPT_TEMPLATE.md` — the per-day user-turn template, with `{{placeholder}}`
  fields mapped to the app's real `DailySummary`, `WorkoutRecordEntity`, and
  `WorkoutLoadMetrics` fields.

## Scope notes

- **Relationship to `feature/insights`:** this AI layer is conceptually adjacent to
  the existing rule-based Insights feature but is a separate, opt-in concept — it does
  not modify `feature/insights` or its output.
- **Relationship to `docs/insights.md`:** that file states (line ~31) "No machine
  learning or AI" — this is scoped specifically to the deterministic Insights rule
  engine, not the whole app, so these prompt templates don't contradict it. If an AI
  recommendation feature is actually shipped in-app later, revisit whether
  `docs/insights.md` needs a clarifying note distinguishing "Insights" (rule-based)
  from a separate AI recommendation surface — that's a future decision, not something
  this deliverable needs to resolve.
- **Typical workout pattern data (Section G of the daily template):** no aggregation
  use case exists yet for "workout frequency/pattern over the last N months." A future
  implementation would most naturally extend the existing
  `WorkoutDao.getWorkoutsInRange(fromMs, toMs)` pattern
  (`core/model/src/main/kotlin/app/readylytics/health/data/local/dao/WorkoutDao.kt`)
  with a new grouping/aggregation use case — not part of this deliverable.
- **No integration plumbing exists:** no HTTP client wired to an LLM provider, no
  API-key storage, no Settings UI toggle. Building the actual feature would require
  all of that in addition to these prompts.
