# Vico Chart Rendering & State Preservation Design Spec

This document details the technical investigation, architectural options, and implementation design for resolving the Vico chart rendering and entry animation glitches on scroll within **Readylytics**.

## Problem & Context

* **App Tech Stack:** Kotlin, Jetpack Compose, Material 3, Vico Charting Library (v3.2.2).
* **The Glitch:** Charts are placed inside scrollable containers. When a chart scrolls out of the visible viewport bounds and comes back into view, it undergoes a complete re-rendering/recomposition cycle. This causes the entry animations (like lines "flying in") to trigger repeatedly.
* **Goal:** Eliminate the repetitive entry animation/re-rendering visual glitch on scroll, while preserving optimal UI performance and retaining pan/zoom states.
* **Key Constraints:**
  1. Maximum of 3 charts on the dashboard.
  2. Pan and zoom states must be preserved during vertical scrolling (they can be reset if the user switches tabs or leaves the screen).

---

## Architectural Options Evaluated

### Option 1: Disabling Entry Animations Explicitly

#### 1. Technical Mechanism
In Vico v3.2.2, `CartesianChartHost` has an `animateIn` parameter that controls the entry transition, and `animationSpec` that dictates data change transitions. By setting `animateIn = false` and `animationSpec = null`, Vico is forced to draw the chart data instantly.
Under the hood, when the item is recycled in a lazy list, its slot table entry is disposed. When scrolling back in, it enters composition again. It registers the `CartesianChartModelProducer` and performs an async data load transaction. Because animations are disabled, Vico renders the model instantly once loaded without a "fly-in" animation.

#### 2. Implementation Blueprint
```kotlin
CartesianChartHost(
    chart = rememberCartesianChart(...),
    modelProducer = modelProducer,
    animateIn = false,        // Disables fly-in animations on entry/scroll-in
    animationSpec = null,     // Disables transition animations on model update
    scrollState = scrollState,
    zoomState = zoomState,
    modifier = modifier
)
```

#### 3. Pros & Cons
* **Pros:**
  * Extremely easy to implement (two parameter additions).
  * Retains the asynchronous model loading pipeline.
* **Cons:**
  * **Asynchronous Loading Flicker:** Because the model transaction runs asynchronously, there is a 1-to-2 frame layout delay when the chart is recycled back into view. The user will see a brief empty placeholder or flicker before the lines render.
  * **Loss of Desirable Transitions:** Disables smooth transitions when updating/filtering data ranges (e.g., swapping from weekly to monthly views).

#### 4. Performance Impact
* **Memory:** Low; matches current footprint.
* **CPU/UI Thread:** Reduces animation interpolation math. However, the overhead of disposing, recomposing, and running the async coroutine pipeline remains high during rapid scrolls.

#### 5. State Preservation
* **No.** Scroll and zoom states are destroyed when the composable is disposed of. They must be manually hoisted to the parent screen container using custom state keys.

---

### Option 2: Supplying Synchronous Data Models Directly

#### 1. Technical Mechanism
Instead of passing a `CartesianChartModelProducer`, we use the `CartesianChartHost` overload that takes a direct `model: CartesianChartModel` object.
Under the hood, this bypasses the asynchronous background processing thread of Vico. The data model is constructed synchronously (either on the UI thread inside composition or inside the ViewModel before exposing UI state). When the recycled chart scrolls back into view, Vico is supplied with the model immediately, eliminating any asynchronous rendering delay (the 1-2 frame flicker).

#### 2. Implementation Blueprint
```kotlin
// In the parent Composable or ViewModel
val chartModel = remember(points) {
    CartesianChartModel(
        LineCartesianLayerModel.build {
            series(
                x = points.map { it.dayOffset },
                y = points.mapNotNull { it.value?.toDouble() }
            )
        }
    )
}

CartesianChartHost(
    chart = rememberCartesianChart(...),
    model = chartModel,
    animateIn = false, // Prevents entry animations
    animationSpec = null,
    scrollState = scrollState,
    zoomState = zoomState,
    modifier = modifier
)
```

#### 3. Pros & Cons
* **Pros:**
  * **Eliminates Flicker:** The chart renders immediately on the first frame it enters the viewport.
  * **Deterministic Layout:** Eliminates complex coroutine synchronization issues in recyclers.
* **Cons:**
  * **Blocks UI Thread for Complex Models:** Building the chart model synchronously during composition can drop frames if the data set is massive (thousands of points).
  * **Requires Heavy Hoisting:** Data transformations must be fully managed outside the chart component.

#### 4. Performance Impact
* **Memory:** Slightly lower since no asynchronous model producer machinery is allocated.
* **CPU/UI Thread:** Slight risk of rendering lag if the synchronous model creation takes longer than 16ms.

#### 5. State Preservation
* **No.** Re-evaluation of the parameters and disposal of the slot table node resets scroll and zoom states unless hoisted to the parent list context.

---

### Option 3: Column + verticalScroll Container Architecture (Recommended)

#### 1. Technical Mechanism
By replacing `LazyColumn` with a standard `Column` wrapped in `Modifier.verticalScroll(rememberScrollState())`, we change Compose's layout lifecycle behavior.
Under the hood, all items in a standard scrollable column remain in the Compose slot table throughout the lifespan of the screen. When a chart scrolls off-screen, Compose simply changes its coordinates during the placement phase, but it **never disposes of the node**.
As a result:
1. Vico's internal animation controllers are not discarded.
2. The coroutine scopes and data-transactions do not restart.
3. The zoom, pan, and tooltip states remain active in the memory tree.

#### 2. Implementation Blueprint
```kotlin
val scrollState = rememberScrollState()

Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(scrollState),
    verticalArrangement = Arrangement.spacedBy(16dp)
) {
    // Keep local rememberVicoScrollState / rememberVicoZoomState inside components
    SingleBloodPressureChart(points = systolicPoints, isDiastolic = false, parentScrollInProgress = scrollState.isScrollInProgress)
    SingleBloodPressureChart(points = diastolicPoints, isDiastolic = true, parentScrollInProgress = scrollState.isScrollInProgress)
    SleepTrendChart(points = sleepPoints, parentScrollInProgress = scrollState.isScrollInProgress)
}
```

#### 3. Pros & Cons
* **Pros:**
  * **Zero Glitch & Zero Flicker:** Animations execute exactly once on screen load.
  * **Out-of-the-Box State Preservation:** Natively retains zoom, horizontal scroll offset, and selection tooltip without hoisting states out of the child components.
  * **Simplest Codebase:** Zero boilerplate or custom state dictionary mapping.
* **Cons:**
  * **No Recycling:** Off-screen views consume layout resources. However, this is negligible since the dashboard has a strict budget of maximum 3 charts.

#### 4. Performance Impact
* **Memory:** Negligible increase (since all 3 charts remain allocated in memory).
* **CPU/UI Thread:** **Lowest scroll jank.** Scrolling is purely canvas translation. No recompositions, re-measuring, or async layout transactions happen during scrolling.

#### 5. State Preservation
* **Yes, 100% native.** Both zoom levels and scroll offsets are completely preserved as long as the parent dashboard screen is alive.

---

### Option 4: Advanced State Hoisting and Retained Keying

#### 1. Technical Mechanism
This approach retains the `LazyColumn` container but hoists the state map `Map<String, Pair<VicoScrollState, VicoZoomState>>` to the screen-level container. We track whether each chart ID has completed its initial load in a tracking set.
Under the hood, we conditionally disable entry animations using `animateIn = !loadedCharts.contains(chartId)`. Once a chart successfully loads, we add its ID to the tracking set. When it scrolls back into view, `animateIn` is evaluated as `false` while preserving horizontal scroll/zoom states retrieved from the hoisted map.

#### 2. Implementation Blueprint
```kotlin
// In the parent Dashboard screen
val chartStates = remember { mutableMapOf<String, Pair<VicoScrollState, VicoZoomState>>() }
val loadedChartIds = remember { mutableStateListOf<String>() }

LazyColumn {
    items(chartsList, key = { it.id }) { chartData ->
        val (scrollState, zoomState) = chartStates.getOrPut(chartData.id) {
            VicoScrollState(...) to VicoZoomState(...)
        }
        val isLoaded = loadedChartIds.contains(chartData.id)

        CartesianChartHost(
            chart = rememberCartesianChart(...),
            modelProducer = chartData.producer,
            scrollState = scrollState,
            zoomState = zoomState,
            animateIn = !isLoaded,
            animationSpec = null // Optionally enable/disable update specs
        )

        LaunchedEffect(chartData.id) {
            loadedChartIds.add(chartData.id)
        }
    }
}
```

#### 3. Pros & Cons
* **Pros:**
  * Scale-friendly if the list grows dynamically in the future.
  * Allows keeping the entry animations for the *very first* time the screen displays, but turns them off specifically during scrolling.
* **Cons:**
  * **High Complexity:** Requires custom key management, map handling, and conditional animation flags.
  * Still suffers from the 1-2 frame async model loading flicker on scroll-in unless combined with Option 2 (synchronous data loading).

#### 4. Performance Impact
* **Memory:** Higher due to maintaining multiple hoisted scroll and zoom state instances in a Map.
* **CPU/UI Thread:** Moderate overhead during rapid scrolls due to map lookups, key resolution, and item recompositions.

#### 5. State Preservation
* **Yes.** Preserved through manual screen-level state hoisting.

---

## Comparison Summary Matrix

| Metric | Option 1: Disable Animation (Async) | Option 2: Synchronous Model | Option 3: Column + verticalScroll (Recommended) | Option 4: Hoisting & Keys |
| :--- | :--- | :--- | :--- | :--- |
| **Performance (Scroll Smoothness)** | Medium (Recomposing on scroll) | Medium-Low (Main thread layout) | **High (Zero composition on scroll)** | Medium (Recycling overhead) |
| **Implementation Complexity** | Low | Low-Medium | **Lowest** | High |
| **Initial Entry Animation** | None | None | **Yes (Plays once on load)** | **Yes (Plays once on load)** |
| **Data Update Transitions** | None | None | **Yes (Fully supported)** | Yes (Conditional) |
| **Scroll / Zoom State Retention** | No (Requires Hoisting) | No (Requires Hoisting) | **Yes (Out of the Box)** | Yes (via State Map) |
| **Rendering Flicker on Scroll-In** | Yes (1-2 frames) | **No** | **No** | Yes (unless using sync models) |

---

## Final Recommendation

For the **Readylytics** dashboard, **Option 3 (Column + verticalScroll)** is the recommended approach. 

Since the dashboard contains a fixed budget of **maximum 3 charts**, the memory and layout overhead of avoiding recycling is trivial, while the benefits—buttery-smooth scrolling, out-of-the-box zoom/pan state retention, and zero rendering flicker—fully resolve the rendering issue with no boilerplate code.
