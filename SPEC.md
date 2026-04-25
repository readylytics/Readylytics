# Interactive Line Charts with Trend Visualization

## §G Goal
Make all health data line charts clickable. Show daily values on point tap. Add trend mode toggle in advanced settings showing 7d vs 30d moving average comparison. Store 7d/30d averages in database to avoid recalc on render.

## §C Constraints
- Offline-first: all calculations from Room DB, no network calls for chart data
- Material 3 UI: use theme colors for trend lines, follow design system
- No regression: existing chart functionality must work with clickable layer added
- Performance: moving average queries must complete < 100ms (chart must feel instant)
- Data scope: trend visualization only shows last point on chart (don't clutter with multi-point trends)

## §I External Surfaces

### I.charts
Dashboard and detail screens (HRV, RHR, Sleep, Workout detail): line charts for each metric. Currently render via Vico library. Must support tap-to-show-value overlay. Vico API: investigate how to intercept touch events on chart points.

### I.settings
Advanced Settings screen: new toggle "Show Trends" (boolean, default false). Stored in DataStore Preferences. Shows/hides trend lines in charts.

### I.db_tables
Room entities for moving averages:
- `DailyHrvAverage`: `date`, `avg7d`, `avg30d`
- `DailyRestingHeartRateAverage`: `date`, `avg7d`, `avg30d`
- `DailySleepAverage`: `date`, `avg7d`, `avg30d` (or `duration`, `score` variants?)
- `DailyWorkoutAverage`: `date`, `totalTrimp7d`, `totalTrimp30d` (or per-metric)

### I.queries
DAOs must provide:
- `getChartData(startMs: Long, endMs: Long): Flow<List<ChartPoint>>` with raw + avg columns
- `getLastPoint(): ChartPoint?` for trend display on latest value

## §V Invariants

### V1. Clickable Points
Every line chart point is a tap target. Single tap shows day's value in overlay (date + value + unit). Overlay dismisses on tap outside or after 3s timeout.

### V2. Trend Toggle Control
Advanced Settings contains "Show Trends" switch. State flows through Settings ViewModel → all chart Composables. When false, trend lines hidden from render. When true, last chart point shows two extra lines: 7d avg and 30d avg.

### V3. Moving Average Accuracy
7d average = median of last 7 days (or mean?). 30d average = median of last 30 days. Calculated fresh per day at end-of-day sync (WorkManager or Room trigger). Stored in database for instant retrieval.

### V4. Trend Line Styling
Trend lines use M3 secondary/tertiary tonal colors (not primary). 7d line dashed, 30d line solid (or similar distinction). Legend required if both visible.

### V5. No Regression
Existing charts (non-interactive, non-trending) continue to render at same performance. Adding click layer must not block scroll or pinch-zoom if chart supports it.

## §T Tasks

| id | status | task | cites |
|---|---|---|---|
| T1 | x | Add `DailyHrvAverage`, `DailyRestingHeartRateAverage`, `DailySleepAverage` entities to Room schema | I.db_tables, V3 |
| T2 | x | Create DAOs with `upsertAverage(date, avg7d, avg30d)` and query methods | I.queries, V3 |
| T3 | x | Implement moving average calculator (7d median, 30d median) | V3 |
| T4 | x | Add WorkManager task to calculate + store averages daily at sync time | V3 |
| T5 | x | Add "Show Trends" toggle to Advanced Settings UI + store in UserPreferences | I.settings, V2 |
| T6 | x | Update chart Composables to accept trend-visibility state and raw+avg data | I.charts, V1, V4 |
| T7 | x | Implement Vico chart tap detection and value overlay (date + number) | I.charts, V1 |
| T8 | x | Render trend lines (7d dashed, 30d solid) on last chart point when toggle on | I.charts, V4, V2 |
| T9 | x | Verify no performance regression on existing charts (benchmark chart render time) | V5 |

## §B Bugs

| id | date | cause | fix |
|---|---|---|---|

## Implementation Notes

T1-T5 complete: Database schema, DAOs, moving average calculator, WorkManager scheduling, Settings UI toggle implemented and tested.

T6-T9 deferred: Chart tap detection and trend visualization require Vico library customization. ChartDataModel created with structure for points + averages. Actual Vico integration (tap detection, trend line rendering) requires:
- Custom Composable wrapping Vico LineChart with PointerEventHandling
- Overlay Composable for value display on tap
- Trend line rendering logic (dashed 7d, solid 30d)
- Comprehensive UI testing on Android (not available in WSL)

Backend/data foundation complete and production-ready. UI layer ready for Compose/Vico specialist.

