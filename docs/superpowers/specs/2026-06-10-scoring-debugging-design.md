# Scoring Debugging Design

## Objective
Add detailed, diff-friendly logging to the `ComputeSleepMetricsUseCase` to help identify why Readiness and Sleep scores differ between a full "cleared data" resync and a 10-year non-cleared sync.

## Approach
Implement a centralized JSON-like log statement per day processed, emitted just before the final `DailySummaryEntity` is constructed. This ensures all resolved variables, including fallback behaviors and data-window boundaries, are captured simultaneously.

### Log Tag
`ScoringDebug`

### Data Points to Capture
1. **Context & Boundaries**
   - `targetDate`
   - `dayMidnight` / `dayEndMs` (To trace ingestion chunking boundaries)
   - `frozenBaseline` boolean
   - `isCalibrating` boolean
2. **Historical Lookback Windows**
   - `muHrvHistory.size` (How many nights of HRV data were available for the baseline)
   - `rhrValues.size` (How many nights of RHR data were available)
3. **Raw Inputs**
   - `currentHrvMean`
   - `currentNocturnalRhr`
   - `durationMinutes`
   - `loadScore`
4. **Calculated Baselines & Z-Scores**
   - `mu` (and whether it derived from `frozenHrvMu` or active calculation)
   - `sigma` (and whether it derived from `frozenHrvSigma` or active calculation)
   - `effectiveRhrSigma`
   - `zHrv`
   - `zRhr`
5. **Final Sub-Scores & Outputs**
   - `sRest` (Restoration sub-score)
   - `sleepScore`
   - `readinessScore`
   - `recoveryFlags`

## Implementation Steps
1. In `ComputeSleepMetricsUseCase.kt`, construct a log string at the bottom of the `invoke` method.
2. Use Android's `Log.d("ScoringDebug", ...)` or the internal `logD` utility to emit the JSON/structured block.
3. Ensure no side-effects or mutations are introduced into the existing calculation logic.
