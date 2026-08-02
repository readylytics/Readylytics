# Shared Vital Ladders — Final Fix Report

Date: 2026-08-02
Implementation commit: `ac0db97` (`fix: align shared vital presentations`)

## Scope

This final wave addressed all six findings from the consolidated branch review plus the associated minor cleanup. It did not change scoring formulas, ingestion, Room schema, retention, or synchronization behavior.

## Findings and resolutions

1. **Weight/BMI chart bands could drift from the canonical BMI assessment.**
   - Removed the stale local BMI ladder from `HealthZone`.
   - Added canonical BMI reference-band metadata to `BodyCompositionAssessment` and derive chart bands from it.
   - Added literal boundary regressions covering the canonical `<18.5`, `18.5..<25`, `25..<30`, and `30+` behavior.

2. **Body Fat visual ranges were age-based and could imply a different health status than the canonical profile/gender assessment.**
   - Added explicit category-band and visual-reference metadata to `BodyCompositionAssessment`.
   - Changed `BodyFatDetailViewModel` and the chart to consume that metadata for both populated and empty states.
   - Removed age and `optimalRangeMin`/`optimalRangeMax` from detail UI state.
   - Reworded the tooltip to distinguish visual anchors/profile markers from the canonical status bands.
   - Preserved the existing status boundaries; this is a single-source refactor, not a formula change.

3. **Body Fat history collapsed distinct canonical categories into a local status approximation.**
   - Added the canonical `BodyFatCategory` to `BodyFatHistoryItem`.
   - Render history labels directly from all nine canonical categories using string resources.
   - Added a regression that verifies every category-to-label mapping.

4. **Blood Pressure charts restated thresholds separately from `HealthMetricsService`.**
   - Added systolic and diastolic reference-band accessors built from the same constants used by component classification.
   - Routed both single-component and trend charts through those accessors.
   - Added exact metadata boundary tests for systolic and diastolic bands.

5. **Heart Rate detail state contained the shared status, but the average card did not render it.**
   - Wired `averageStatus` into the average `HrStatCard` and reused the core status pill.
   - Added resource-backed status labels.
   - Added a full-screen Robolectric Compose regression that verifies a populated average visibly renders `Neutral`.

6. **Four touched regression files exceeded the repository file-size target/hard limit.**
   - Split dashboard visualization, dashboard card instrumentation, dashboard presentation factory, and local restore tests into shared base fixtures plus focused test classes.
   - Preserved all original test counts: 24 dashboard visualization, 27 dashboard card instrumentation, 41 presentation-factory, and 28 local-restore tests.
   - All resulting files are below 500 lines.

Minor cleanup completed:

- Removed the unused two-argument `bodyFatStatus` helper so it cannot become a second ladder.
- Synchronized `ABOUT.md`, `docs/about.md`, the rendered in-app About resources/section, and `internal-docs/DATA_FLOW.md` with the canonical chart-reference behavior.
- Normalized `ABOUT.md` line endings to LF; the large textual diff is line-ending normalization plus the synchronized paragraph.

## Test-first evidence

- Core canonical-reference tests initially failed to compile because body-composition bands and BP reference methods did not exist; they passed after the domain metadata implementation.
- Body Fat detail/history regressions initially failed to compile or assert because the UI state/history model did not carry canonical reference/category data; they passed after routing those consumers through `BodyCompositionAssessment`.
- The Heart Rate screen regression initially failed because `Neutral` was absent from the rendered screen; it passed after the average card consumed `averageStatus`.

## Verification evidence

- `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`
  - Final fresh run: **BUILD SUCCESSFUL** in 1m18s, 502 actionable tasks, zero test failures.
- `./gradlew lintRelease`
  - Final fresh run: **BUILD SUCCESSFUL** in 42s, 503 actionable tasks.
- `./gradlew :feature:dashboard:compileDebugAndroidTestKotlin`
  - **BUILD SUCCESSFUL** in 5s, 66 actionable tasks.
- `git diff --check`
  - Passed with no whitespace errors before the implementation commit.
- `codegraph index`
  - Passed after new code/test files: 957 files, 20,956 nodes, 43,928 edges.
- `codegraph sync`
  - Passed after structural test-file moves: 20 changed files synchronized.

One intermediate formatting run reported that shared test base class names did not match their filenames. The four base files were renamed to match, after which the complete mandatory verification sequence passed.

## Remaining concerns

- Dashboard instrumentation tests were compile-verified but not executed on a physical/emulated Android device in this wave.
- Compilation reports the existing Compose test-rule v1 deprecation warning in the moved dashboard base fixture; there are no errors.
- No functional blockers remain from the six review findings.
