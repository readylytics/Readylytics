# Scroll Performance Optimization Design

## Context
The Vitals and Workouts screens suffer from scroll stuttering and initial lag. Both screens use a `Column` with `Modifier.verticalScroll`, which renders multiple heavy `TrendChart` instances (using Vico Canvas drawing) and lists of items at once. 

## Requirements
1. Eliminate continuous stutter during vertical scrolling.
2. Reduce initial load lag where possible without violating constraint #3.
3. **Constraint:** Keep all charts pre-rendered. They must not be disposed or re-rendered when they scroll out of the visible screen estate.

## Architecture & State
To satisfy the constraint of keeping everything pre-rendered, we will **not** migrate to `LazyColumn`, as it destroys off-screen items. We will keep the existing `Column(Modifier.verticalScroll)`.

To fix the performance issues, we will introduce **Hardware-Backed Render Nodes** to isolate the heavy drawing operations.

### Changes
1. **Chart Isolation (`Modifier.graphicsLayer`)**:
   - Apply `Modifier.graphicsLayer { }` to all `TrendCard` wrappers in `VitalsScreen`.
   - Apply `Modifier.graphicsLayer { }` to all chart wrappers in `WorkoutStatsSection` (on the `WorkoutsScreen`).
   - This modifier forces Compose to draw the complex Canvas instructions into a dedicated off-screen GPU buffer.
   - When the parent `Column` scrolls, the system only needs to translate the existing GPU buffer rather than re-executing the heavy Vico Canvas drawing on every frame.

2. **List Optimization (`Modifier.graphicsLayer`)**:
   - For `WorkoutListSection`, the individual `WorkoutHistoryItem` cards can also receive a `Modifier.graphicsLayer { }` to prevent re-measurement and re-drawing during parent scroll.

3. **State Preservation**:
   - `rememberScrollState()` will remain unchanged.
   - The `parentScrollInProgress` boolean will continue to be passed to the charts, which allows them to gracefully hide tooltips when scrolling begins.

## Success Criteria
- Scrolling up and down on the Vitals and Workouts tabs is smooth.
- Charts do not flash or recalculate when scrolled back into view.
