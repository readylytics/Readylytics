# Plot Scaling and Zooming Specification

This document details the design and implementation specifications for improving plot scaling, zooming, dragging, and label density constraints on resting heart rate, sleep, steps, and other trend diagrams.

## 1. Objectives

- **7-Day View**: Completely lock scrolling and zooming. All 7 days fit exactly on the x-axis within the viewport width. No horizontal or vertical scroll is possible or necessary.
- **30-Day View**: Fit all 30 days within the viewport width on initial load (no initial horizontal scroll). Enable pinch-to-zoom and dragging. Lock zooming out beyond the initial full 30-day range.
- **180-Day View**: Fit all 180 days within the viewport width on initial load. Enable pinch-to-zoom and dragging with a higher maximum zoom factor to handle higher data density. Lock zooming out beyond the initial full 180-day range.
- **Label Density Control**: Ensure that x-axis dates remain clear, legible, and un-overlapped across all views when fully zoomed out.

---

## 2. Proposed Changes

We will implement the solution in two components: a custom `zoomState` and `scrollState` builder within the core chart component, and a dynamic tick/label density placer.

### A. Core Chart: `TrendCharts.kt`
We will update `TrendChart` in [TrendCharts.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/java/com/gregor/lauritz/healthdashboard/ui/components/TrendCharts.kt):
- Remove the external `scrollState` parameter since scrolling is now determined dynamically by `rangeDays`.
- Add import for `com.patrykandpatrick.vico.core.cartesian.Zoom`.
- Initialize `scrollState` locally:
  ```kotlin
  val scrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7)
  ```
- Initialize `zoomState` locally with custom constraints:
  ```kotlin
  val zoomState = rememberVicoZoomState(
      zoomEnabled = rangeDays > 7,
      initialZoom = Zoom.Content,
      minZoom = Zoom.Content,
      maxZoom = remember(rangeDays) {
          when (rangeDays) {
              30 -> Zoom.static(6f)    // Peak zoom detail: ~5 days visible
              180 -> Zoom.static(25f)  // Peak zoom detail: ~7 days visible
              else -> Zoom.Content
          }
      }
  )
  ```
- Pass the dynamic `scrollState` and `zoomState` to `CartesianChartHost`.

### B. Label Placer: `ChartDefaults.kt`
We will update `itemPlacerForRangeDays` in [ChartDefaults.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/java/com/gregor/lauritz/healthdashboard/ui/components/ChartDefaults.kt):
- Adjust tick and label spacing dynamically based on the day range:
  - **7 days**: spacing = 1 (every day is labeled)
  - **30 days**: spacing = 5 (every 5th day is labeled)
  - **180 days**: spacing = 30 (every 30th day is labeled)

```kotlin
fun itemPlacerForRangeDays(rangeDays: Int): HorizontalAxis.ItemPlacer =
    when (rangeDays) {
        7 -> HorizontalAxis.ItemPlacer.aligned(
            spacing = { _ -> 1 },
            addExtremeLabelPadding = true,
        )
        30 -> HorizontalAxis.ItemPlacer.aligned(
            spacing = { _ -> 5 },
            addExtremeLabelPadding = true,
        )
        180 -> HorizontalAxis.ItemPlacer.aligned(
            spacing = { _ -> 30 },
            addExtremeLabelPadding = true,
        )
        else -> HorizontalAxis.ItemPlacer.aligned(
            spacing = { _ -> 5 },
            addExtremeLabelPadding = true,
        )
    }
```

### C. Caller Simplification
Simplify call-sites to remove unused `scrollState` setups:
- [RestingHrDetailScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/java/com/gregor/lauritz/healthdashboard/ui/rhr/RestingHrDetailScreen.kt)
- [SleepScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/java/com/gregor/lauritz/healthdashboard/ui/sleep/SleepScreen.kt)
- [StepDetailScreen.kt](file:///C:/Users/lauri/git/MyHealthStatus/app/src/main/java/com/gregor/lauritz/healthdashboard/ui/steps/StepDetailScreen.kt)

---

## 3. Verification & Testing

### Manual Testing Plan
1. **7-Day View Verification**:
   - Verify the resting HR, sleep, and step charts fit entirely inside the viewport width.
   - Verify that trying to drag left/right or pinch-to-zoom has no effect.
   - Verify dates on the x-axis are labeled daily.
2. **30-Day View Verification**:
   - Verify the chart renders all 30 days without overflow on initial load.
   - Verify that trying to drag/scroll before zooming in has no effect.
   - Verify pinch-zooming out further than the initial full view is locked.
   - Verify pinch-zooming in works smoothly and enables horizontal dragging.
   - Verify labels are spaced every 5 days.
3. **180-Day View Verification**:
   - Verify the chart renders all 180 days without overflow on initial load.
   - Verify pinch-zooming out further than the initial full view is locked.
   - Verify pinch-zooming in is permitted up to a high degree of detail (week/day scale).
   - Verify labels are spaced every 30 days to avoid any overlap.
