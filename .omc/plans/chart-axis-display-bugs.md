# Chart Axis Display Bug Fixes

## Context
The Readylytics app has two chart axis display bugs:
1. **Missing months with no data:** Vitals charts (HRV, Blood Pressure) don't show months/periods that have no data, unlike the working Sleep chart
2. **360-day view axis labels too narrow:** X-axis labels get truncated to "W..." instead of showing full period labels

The root cause: BloodPressureTrendChart and related vitals charts are using the wrong axis formatter and itemPlacer configuration compared to the working SleepTrendChart.

## Implementation Plan

### Root Cause Analysis
- **BloodPressureTrendChart** (line 165): Uses `rememberDayOffsetFormatter(rangeStartMs)` — designed only for DAILY granularity data points
- **Expected:** Should use `rememberPeriodFormatter(rangeStartMs, granularity)` — handles all granularities and shows all periods
- **itemPlacer** (lines 321-323): Doesn't pass pointOffsets parameter, which Vico needs to know about actual data locations for proper axis spacing
- **SleepTrendChart** (lines 437-447, 588-598): Already implements the correct pattern — use this as the reference

### Files to Modify

1. **`feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureTrendChart.kt`**
   - Line 165: Replace `rememberDayOffsetFormatter` with `rememberPeriodFormatter`
   - Lines 321-323: Add `pointOffsets` parameter to itemPlacer call using valid systolic/diastolic data point offsets
   - The chart currently only handles DAILY granularity, so granularity parameter will always be `TrendGranularity.DAILY` initially

### Verification
- Run the app and navigate to vitals charts (HRV, Blood Pressure)
- Verify all months show on x-axis even with data gaps (e.g., Apr through Aug all visible)
- Test 360-day view and confirm axis labels display fully without truncation to "W..."
- Compare behavior with working Sleep chart to ensure parity
- Run unit tests: `./gradlew testDebugUnitTest`

### Notes
- BloodPressureTrendChart uses `dayOffset` values directly (days since rangeStartMs), not period-based indexing
- When passing pointOffsets, use the dayOffsets from valid data points (filter for non-null values)
- The fix aligns with SleepTrendChart's proven pattern but simplified for DAILY-only granularity
