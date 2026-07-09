# Design Specification: Native Biphasic Sleep Support

## 1. Objective
Add native segmented/biphasic sleep support to Readylytics without breaking scoring determinism, privacy boundaries, offline-first behavior, or current monophasic behavior.

Target outcome:
- preserve raw Health Connect sleep sessions and stages exactly as imported
- move sleep interpretation to explicit sleep-day aggregation in pure Kotlin domain logic
- support segmented nocturnal sleep and daytime supplemental sleep on same wake-day
- isolate readiness/RHR/HRV/nadir to overnight core sleep only
- keep identical outputs across daily sync, historical resync, app restart, and retention-window changes when raw records + settings are unchanged

## 2. Confirmed Product Policy

### 2.1 Sleep-Day Assignment
Readylytics will use `Wake-Day with Metric Isolation`.

- score day is anchored to final wake date of core nocturnal cluster
- supplemental sleep after core wake can still belong to same score day
- readiness, overnight HRV, overnight RHR, and nadir remain locked to core cluster only

Example:
- block A `23:00-03:00`
- block B `05:00-07:00`
- block C `14:00-14:45`

Result:
- A + B can form core cluster for wake-day ending `07:00`
- C belongs to same wake-day if before configured supplemental cutoff
- C can change duration and eligible architecture inputs for that day
- C must not reopen readiness / HRV / RHR / nadir for that day

### 2.2 Core Cluster Rule
- build clusters by merging adjacent segments whose wake gap is `<= coreMergeGapMinutes`
- choose longest cluster as core cluster
- `coreMergeGapMinutes` is user-configurable
- default `180 min`
- range `30..240 min`
- step `30 min`

### 2.3 Supplemental Sleep Rule
- supplemental sleep belongs to same wake-day only before configurable same-day cutoff
- default cutoff `20:00`
- range `14:00..23:00`
- step `30 min`

### 2.4 Minimum Counted Segment Rule
- any sleep session shorter than configurable minimum is ignored for aggregation
- default `15 min`
- range `5..60 min`
- step `5 min`

### 2.5 Architecture Contribution Rule
- supplemental sleep can contribute to architecture only when staged enough
- staged-enough is user-configurable by stage-coverage threshold
- default `75%`
- range `25..100%`
- step `5%`
- supplemental architecture also requires at least one non-awake sleep stage

### 2.6 Provider Overlap Rule
Use `selection-first`.

- existing per-data-type device selection remains primary
- if overlapping sleep from multiple providers remains in raw DB, aggregation must choose one canonical raw set deterministically
- if selected device exists, prefer that device
- if no selected device exists, this design leaves deterministic tie-break policy as explicit blocker

### 2.7 Circadian Consistency Rule
Use `hybrid`.

- primary circadian metric uses core-cluster anchors only
- supplemental sleep metadata remains separate for future UI/insight use

### 2.8 Time Semantics Rule
Use `stored scoring timezone first`.

- sleep-day assignment
- gap merging
- cutoff checks
- circadian anchors
- trend bucketing

All above must use stored scoring timezone from `UserPreferences.scoringZoneId`, not current device timezone and not session-local offsets.

## 3. Current-State Audit

### 3.1 Ingestion and Raw Persistence
Current flow already preserves raw sleep sessions and stages separately.

Key files:
- `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/SleepDataMapper.kt`
- `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/SleepSessionEntity.kt`
- `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/SleepStageEntity.kt`
- `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt`
- `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthChangeSynchronizerImpl.kt`

Current behavior:
- `SleepDataMapper.mapSleepSession()` derives per-session duration/efficiency/stage totals
- `SleepDataMapper.mapSleepSessionStages()` stores raw stage rows
- `HealthChangeSynchronizerImpl` upserts raw session and fully replaces raw stages for same session id
- raw sleep is keyed by stable Health Connect record id, already compatible with idempotent resync

Implication:
- no destructive merge exists today
- ingestion path can remain largely intact
- sleep-day aggregation should be added after raw persistence, not during import

### 3.2 Storage and DAO Layer
Key files:
- `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/SleepSessionDao.kt`
- `core/model/src/main/kotlin/app/readylytics/health/data/local/dao/SleepStageDao.kt`
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/SleepSessionRepositoryImpl.kt`
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringHistoryRepositoryImpl.kt`

Current assumptions:
- `getSessionEndingInRange(fromMs, toMs)` returns first session ending that day
- `observeFirstSessionEndingInRange(...)` powers sleep UI selected-day rendering
- `getSince()` / `observeSince()` expose raw sessions only
- no aggregate projection exists for sleep day, core cluster, or supplemental sleep

Impact:
- DAO/repo surface must expand or aggregation service must own raw-session querying from existing DAOs
- UI and scoring both currently depend on single-session semantics

### 3.3 Scoring and Daily Summary
Key files:
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeSleepMetricsUseCase.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/BaselineComputer.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/CurrentNightHrvResolver.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepPercentileRhrCalculator.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/SleepNadirAnalyzer.kt`
- `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/DailySummaryEntity.kt`
- `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailySummaryMapper.kt`

Current assumptions:
- `ScoringRepositoryImpl.computeDailySummary()` chooses one sleep session by `getSessionEndingInRange(dayMidnight, nextDayMidnight)`
- same one session supplies:
  - sleep duration
  - deep/rem percentages
  - everyday-HR sleep exclusion interval
  - HRV window
  - RHR percentile window
  - nadir timing
  - SpO2 sleep window
- `ComputeSleepMetricsUseCase` is session-centric end to end
- `BaselineComputer` collects historical baselines from raw sleep sessions directly
- calibration gate uses raw sleep session count, not aggregated sleep-day count

Impact:
- this is main architectural break point
- segmented sleep support cannot be implemented safely as scattered patches here

### 3.4 Circadian Consistency
Key files:
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/CircadianConsistencyRepository.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/components/CircadianConsistencyConfig.kt`
- `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/CircadianThresholdSettingsSection.kt`

Current assumptions:
- raw sessions shorter than `180 min` are excluded from circadian calculation
- one session supplies bedtime and wake time
- naps are excluded by duration heuristic only
- docs already admit biphasic weakness

Impact:
- circadian logic must move from raw-session heuristics to aggregate/core-anchor semantics

### 3.5 Sync, Resync, and Recompute
Key files:
- `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/HealthSyncUseCase.kt`
- `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailySyncUseCase.kt`
- `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ResyncRangeUseCase.kt`
- `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/DailyRecomputeSupport.kt`
- `core/model/src/main/kotlin/app/readylytics/health/domain/sync/link/SessionLinkReconciler.kt`

Strengths already present:
- daily sync widens ingest one extra day backward for overnight sessions
- resync ingests in 30-day chunks
- resync reconciles session links after chunked ingest
- recompute is walk-forward and baseline-aware
- scoring timezone already centralizes deterministic day boundaries

Current gaps for biphasic sleep:
- change attribution still reasons about raw record dates, not aggregate sleep-day impact
- recompute depends on single-session scoring semantics
- late-arriving nap must update same-day sleep score inputs without reopening readiness, which current code cannot express

### 3.6 UI and Read Models
Key files:
- `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepViewModel.kt`
- `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepTrendChart.kt`
- `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepStagesChart.kt`
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt`
- `core/database/src/main/kotlin/app/readylytics/health/data/repository/DailyMetricsRepositoryImpl.kt`
- `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailyMetricsMapper.kt`

Current assumptions:
- selected-day sleep UI uses `observeFirstSessionEndingInRange(...)`
- trend chart groups raw sessions by end date and takes `firstOrNull()`
- dashboard duration/score read only what daily summary persisted
- `lastSleepSession` on dashboard remains one session summary

Impact:
- sleep detail screen and trends need aggregate-aware projection
- dashboard can remain mostly daily-summary based if scoring writes correct aggregate totals
- any raw-session summary shown beside score must clarify whether it reflects core cluster or aggregate envelope

### 3.7 Settings and Preferences
Key files:
- `core/model/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferences.kt`
- `core/model/src/main/kotlin/app/readylytics/health/data/preferences/SettingsDefaults.kt`
- `core/model/src/main/kotlin/app/readylytics/health/domain/preferences/FeatureSettingsPorts.kt`
- `core/model/src/main/kotlin/app/readylytics/health/domain/validation/SettingsValidators.kt`
- `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SleepSettingsViewModel.kt`
- `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/ThresholdSettings.kt`
- `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/AdvancedSettings.kt`
- backup/restore surfaces in `app/src/main/kotlin/app/readylytics/health/data/backup/`

Current state:
- sleep settings are spread across Sleep, Threshold, and Advanced sections
- no segmented-sleep policy settings exist
- backup/restore already mirrors many preference fields and must stay in sync

## 4. Proposed Architecture

### 4.1 Recommended Approach
Use `domain-first aggregation`.

- keep raw HC session/stage persistence unchanged
- add one pure Kotlin sleep aggregation layer shared by scoring, circadian, and UI projections
- avoid adding derived Room table initially
- avoid storing fake merged sessions

Rejected alternatives:
- compute-only patch across existing scoring/UI readers
  - too much duplicated interpretation logic
  - high drift risk
- persisted aggregate table first
  - more migration and invalidation burden than needed for first rollout

### 4.2 Proposed Components
Conceptual components:
- `SleepAggregationPolicy`
- `SleepDayAggregator`
- `SleepDayAggregate`
- `CoreSleepCluster`
- `SupplementalSleepBlock`
- `RecoveryWindow`
- `CircadianAnchor`
- aggregate projection adapters for scoring/UI

Suggested placement:
- pure Kotlin under `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/sleep/`
or adjacent pure domain package if file grouping becomes cleaner

### 4.3 Boundaries
- ingestion remains raw import only
- aggregation owns sleep-day interpretation only
- scoring engine consumes aggregate result, does not rebuild grouping ad hoc
- UI reads aggregate projections, not raw-session heuristics
- persistence remains raw source tables plus existing derived `daily_summaries`

## 5. Proposed Domain Model

### 5.1 Raw Inputs
- `RawSleepSession`
  - HC record id
  - source/device label
  - start/end timestamps
  - start/end offsets
  - total sleep minutes
  - deep/rem/light/awake minutes
  - derived efficiency
- `RawSleepStage`
  - session id
  - stage type
  - start/end timestamps
  - duration

### 5.2 Normalized Aggregation Inputs
- `SleepSegment`
  - one raw session after minimum-duration filtering
  - local start/end in stored scoring timezone
  - stage coverage summary
  - provider/source metadata
- `SleepCluster`
  - ordered list of segments
  - total sleep minutes
  - cluster start/end
  - cluster wake gaps
  - stage totals

### 5.3 Aggregate Result
- `SleepDayAggregate`
  - score day local date
  - policy snapshot used
  - chosen core cluster
  - accepted supplemental blocks
  - rejected segments with reason codes
  - aggregate duration totals
  - aggregate architecture totals
  - recovery window
  - circadian anchors
  - overlap/ambiguity/debug flags

### 5.4 Policy Snapshot
- `coreMergeGapMinutes`
- `supplementalCutoffMinutesOfDay`
- `minimumCountedSegmentMinutes`
- `supplementalArchitectureCoveragePercent`
- scoring timezone id

## 6. Sleep-Day Grouping Algorithm

### 6.1 High-Level Algorithm
For target score day `D`:

1. fetch raw sleep sessions in window wide enough to detect:
   - previous-evening segments that may merge into core cluster
   - same-day supplemental naps
   - early next-night segments near cutoff boundary
2. convert raw sessions into `SleepSegment` using stored scoring timezone
3. drop segments shorter than `minimumCountedSegmentMinutes`
4. sort deterministically by start time, then end time, then stable id
5. merge adjacent segments into clusters when wake gap `<= coreMergeGapMinutes`
6. choose longest cluster as core cluster candidate
7. assign score day from core cluster final wake local date
8. accept supplemental segments on same wake-day only if:
   - they occur after core wake
   - they start before configured same-day cutoff
   - they are not absorbed into next score day’s core cluster by grouping rules
9. build aggregate totals:
   - duration = core + accepted supplemental
   - architecture = core + eligible supplemental
10. build recovery window from core cluster only
11. build circadian anchors from core cluster only

### 6.2 Core Cluster Selection
Confirmed:
- longest merged cluster wins

Open blocker:
- deterministic tie-breakers for equal total sleep minutes are not yet user-approved

Required tie-break policy for implementation:
- must be explicit
- must not depend on insertion order, sync order, or retention window

Recommended candidate:
1. greater total sleep minutes
2. later final wake
3. earlier start
4. lexicographically smallest stable session-id tuple

This recommendation is not yet user-approved and should be treated as blocker until confirmed.

### 6.3 Supplemental Sleep Acceptance
Confirmed:
- same wake-day only
- before same-day cutoff
- can update duration and eligible architecture
- cannot update readiness/RHR/HRV/nadir

Open blocker:
- exact rule to prevent borderline evening segments from being counted both as supplemental for day `D` and seed of next core cluster for day `D+1`

Required behavior:
- each raw segment must belong to at most one `SleepDayAggregate`
- ownership must be deterministic across adjacent days

### 6.4 Stage Aggregation
Core cluster:
- always eligible for duration and architecture

Supplemental block:
- duration always eligible once accepted
- architecture eligible only if:
  - stage-covered minutes / segment sleep minutes `>= supplementalArchitectureCoveragePercent`
  - at least one non-awake sleep stage exists

Open blocker:
- exact coverage denominator and rounding rule are not yet user-approved

Required implementation rule:
- use exact stored sleep-stage durations
- define whether denominator is total session span, total sleep minutes, or stage-covered minutes versus `durationMinutes`
- define threshold comparison as exact `>=` after integer-minute math

### 6.5 Missing Stages
Confirmed direction:
- missing stages must not block duration aggregation
- missing or insufficient stages only block architecture contribution for that block

### 6.6 Overlapping Providers and Duplicates
Confirmed direction:
- `selection-first`

Required implementation behavior:
- if user selected sleep device, only segments from that device can participate when overlapping alternatives exist
- if no sleep device selected and overlaps remain, implementation needs deterministic canonicalization policy

Open blocker:
- no-device overlap tie-breaker is not yet user-approved

### 6.7 Timezone, DST, and Travel
Confirmed:
- grouping uses stored scoring timezone

Implementation consequences:
- convert raw instants to stored scoring timezone before date/gap/cutoff tests
- preserve raw offset metadata for diagnostics only
- DST transitions must use real zoned local times, not fixed 24-hour subtraction

## 7. HRV / RHR / Readiness Rules

### 7.1 Recovery Window
Recovery window is core cluster only.

Impacted helpers:
- `CurrentNightHrvResolver`
- `SleepPercentileRhrCalculator`
- `SleepNadirAnalyzer`
- any SpO2 sleep-window extraction used in summary

### 7.2 Readiness Lock
Once core wake occurs for score day:
- readiness
- nocturnal HRV
- nocturnal RHR
- late nadir

must be computed strictly from core cluster and stay isolated from later supplemental sleep.

Late-arriving nap:
- may update sleep duration
- may update sleep score if architecture eligibility exists
- must not change readiness or overnight physiology metrics

### 7.3 Monophasic Backward Compatibility
When exactly one valid overnight sleep exists:
- aggregate result should collapse to current behavior
- duration, architecture, HRV, RHR, readiness, and circadian output should remain unchanged except where bug fixes explicitly alter prior edge cases

### 7.4 Baseline History
Historical baseline windows should use historical core clusters, not every raw segment, otherwise nap-heavy users bias:
- RHR baseline
- HRV baseline
- calibration progress
- circadian medians

## 8. Database and Migration Plan

### 8.1 Preferred Path
Do not add new Room entity/table in first rollout.

Reason:
- raw source tables already contain all needed data
- `daily_summaries` is already derived cache
- new aggregate table increases drift surface and migration cost

### 8.2 DailySummaryEntity
Current audit does not prove immediate schema change is required.

First rollout should attempt:
- keep aggregate ephemeral
- persist only existing summary values unless UI/debug requires more fields

Possible future additive fields if needed:
- `coreSleepDurationMinutes`
- `supplementalSleepDurationMinutes`
- aggregate/core start-end anchors
- overlap/ambiguity flags
- segmentation mode flags for UI/explanations

### 8.3 Migration Strategy If Schema Changes Become Necessary
- bump `HealthDatabase` from `4` to `5`
- use additive nullable columns only
- extend `DatabaseMigrations.kt`
- update schema snapshots under `core/database/schemas/...`
- add migration tests in existing migration suites
- no destructive migration

### 8.4 Backfill Strategy
No special one-off SQL backfill.

Use existing recompute model:
- clear frozen baselines over affected range
- walk-forward recompute summaries from raw source tables
- let new aggregation layer rebuild sleep projections deterministically

## 9. Sync and Recompute Plan

### 9.1 Daily Sync
Keep existing one-extra-day ingest widening in `DailySyncUseCase`.

Why:
- overnight segmented sleep may start previous evening
- pre-midnight HR/HRV samples must remain visible to core recovery window

### 9.2 Historical Resync
Keep existing chunked ingest + reconcile + walk-forward recompute flow.

Requirement:
- aggregation results must be chunk-independent
- same raw rows + same policy snapshot must produce same outputs across chunk size and retention ranges

### 9.3 Affected-Date Attribution
Current `HealthChangeSynchronizerImpl` records affected dates by raw record dates.

Needed change:
- recompute expansion logic must account for aggregate ownership
- example: nap added on wake-day should trigger recompute for that wake-day even if raw record is afternoon-only

Potential approach:
- keep raw affected dates as coarse trigger
- aggregate recompute support determines true impacted score days when rebuilding target window

### 9.4 Late-Arriving Data Cases
- late-arriving nap:
  same score day sleep score inputs only
- late-arriving stage rows for core cluster:
  can affect sleep score and readiness
- late-arriving HR/HRV within core cluster:
  can affect readiness and frozen baselines
- late-arriving duplicate provider session:
  must resolve deterministically under selection-first policy

### 9.5 Determinism Requirements
Must hold across:
- normal daily sync
- full resync
- retention-bounded resync
- app restart
- background/foreground timing differences

## 10. UI and Localization Plan

### 10.1 Settings
Create coherent sleep policy controls under Sleep settings rather than scattering everything across Advanced/Threshold.

Target groups:
- sleep duration goal
- circadian controls
- segmented sleep controls
- advanced recovery knobs

New user-facing settings:
- `coreMergeGapMinutes`
- `supplementalCutoffLocalTime`
- `minimumCountedSleepSegmentMinutes`
- `supplementalArchitectureCoveragePercent`

All new labels, descriptions, tooltips must be strings resources.

### 10.2 Sleep Screen
Needs aggregate-aware selected-day model.

Likely changes:
- show aggregate sleep duration for day
- distinguish core cluster versus supplemental blocks in timeline/summary
- avoid creating fake merged raw session
- trend chart must stop using `firstOrNull()` raw session by end date

### 10.3 Dashboard and Cards
Sleep score and duration cards should reflect aggregate result.

Readiness card remains morning core-only.

Potential UX need:
- clarify in tooltip or about copy that naps can improve sleep score inputs for same day without altering readiness

### 10.4 Widgets
Audit any widget or external card reader that assumes one session per day.

### 10.5 Strings
No hardcoded UI text.

Likely new string groups:
- segmented sleep settings labels/descriptions
- supplemental sleep explanations
- core versus supplemental terminology
- possible low-confidence or overlap explanatory text

## 11. Testing Plan

### 11.1 Pure Kotlin Aggregation Tests
Add exhaustive unit tests for:
- one overnight session unchanged
- two nocturnal segments merged within threshold
- same segments split when threshold reduced
- core sleep plus same-day nap
- evening doze before cutoff included
- segment after cutoff excluded from same day
- minimum duration filtering
- supplemental architecture threshold pass/fail
- missing-stage fallback
- no-session / no-core cases
- DST transition nights
- travel-day scoring timezone stability
- overlap and duplicate provider cases
- equal-length cluster tie-break determinism

### 11.2 Scoring Regression Tests
- existing monophasic regression snapshots unchanged
- readiness unchanged after late-arriving nap
- nocturnal HRV/RHR unchanged after supplemental nap
- baseline history built from core clusters only
- calibration uses aggregated sleep days, not raw fragments

### 11.3 Sync / Resync Tests
- daily sync widens enough for segmented overnight sleep
- resync outputs invariant across chunk sizes
- resync outputs invariant across retention windows
- late-arriving nap changes only same-day sleep score inputs
- late-arriving core data reopens correct day

### 11.4 Database / Migration Tests
Only if schema changes:
- migration from version `4` to `5`
- preserved existing rows
- nullable additive fields
- recompute after migration produces valid summaries

### 11.5 UI / Resource Tests
- settings ranges/defaults/steps/validation
- sleep screen aggregate rendering
- trend chart aggregate projection
- string resource presence and no new hardcoded strings

## 12. Documentation Updates

### 12.1 Mandatory
Update `internal-docs/DATA_FLOW.md` in same change.

Must explain:
- raw HC sleep sessions/stages remain stored unchanged
- pure domain aggregation now constructs sleep day
- wake-day with metric isolation
- longest-cluster core selection
- configurable gap/cutoff/min-duration/stage-coverage policies
- core-only recovery window
- supplemental duration/architecture behavior
- circadian uses core anchors only
- recompute/resync deterministic guarantees

### 12.2 Public and In-App Explanations
Update in same change if implementation proceeds:
- `ABOUT.md`
- `docs/about.md`
- in-app About strings / tooltips if user-facing explanation changes

Current docs already note biphasic caveat; those sections must be replaced with explicit supported behavior once feature ships.

## 13. Safe Rollout Order
1. lock remaining blockers
2. add new settings/defaults/validators/serialization/backup support
3. add pure Kotlin aggregation model and tests
4. adapt circadian repository to aggregate/core anchors
5. adapt baseline / HRV / RHR / nadir helpers to core recovery window
6. refactor `ScoringRepositoryImpl` and `ComputeSleepMetricsUseCase` to consume aggregate
7. update sleep screen and trend consumers
8. update dashboard/widget consumers if needed
9. add DB migration only if derived persistence proves necessary
10. update docs
11. run full regression suite

## 14. Main Risks
- hidden single-session assumptions outside audited files
- calibration drift if raw-session count logic survives
- baseline drift if naps leak into historical physiology windows
- chunk/retention drift if tie-breakers are not explicit
- overlap ambiguity when no explicit device is selected
- UI drift if some screens use aggregate projections and others still use raw sessions
- backup/restore drift if new policy settings are missed in serializers

## 15. Explicit Blockers Before Implementation
These are not resolved by current code or approved product policy and should be answered before code work starts:

1. equal-length core-cluster tie-break order
2. no-device overlap canonicalization rule when multiple providers remain
3. exact ownership rule for borderline evening segments near same-day cutoff versus next-day core cluster
4. exact stage-coverage denominator and rounding rule for supplemental architecture eligibility
5. storage representation for supplemental cutoff setting
   - minutes-from-midnight versus separate hour/minute fields
   - this is lower-risk than behavioral blockers but should still be locked before implementation

## 16. Recommendation Summary
- implement biphasic support as shared pure domain aggregation, not ingestion merge and not UI-only patch
- keep raw source tables unchanged
- keep readiness physiology core-only
- allow same-day supplemental duration and eligible architecture contributions
- avoid Room schema change unless later proven necessary by UI/debug requirements
