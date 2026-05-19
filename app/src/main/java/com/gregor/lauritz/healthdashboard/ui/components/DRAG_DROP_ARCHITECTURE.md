# Drag-and-Drop Architecture Guide

## Overview

The dashboard grid supports intuitive drag-and-drop reordering of health metric cards. This document explains the architecture, design decisions, and regression testing procedures.

## Design Principles

1. **Predictable Visual Feedback**: Drag target should match visual position, even when scrolled
2. **Adaptive Thresholds**: Different card heights require different swap thresholds for consistent UX
3. **Accessibility**: Drag indicators visible in edit mode, proper semantic labels
4. **Scroll Awareness**: Reordering works reliably in scrolled and unscrolled viewports

## Threshold Calculation

### Formula

The drag-drop system uses **adaptive bidirectional thresholds** to determine when a card should swap positions.

```kotlin
downThreshold = (currentHeight + nextHeight) / 8f
upThreshold = (currentHeight + prevHeight) / 8f
```

Where:
- `currentHeight`: Height of the card being dragged
- `nextHeight`: Height of the card below current position
- `prevHeight`: Height of the card above current position
- Division by 8 provides responsive swap trigger (1/4 of average card height)

### Why Bidirectional?

Cards have different heights depending on their content:
- Sleep Score / Readiness cards: 140dp (centered with wrapper)
- Step cards: full-width, typically 130dp
- Other metric cards: ~130dp

Using separate thresholds for up/down movement ensures:
- Smooth transition when dragging large→small card
- Consistent feel when dragging small→large card
- Natural swap timing regardless of adjacent card heights

### Default Values

When adjacent card is unavailable (first/last card):
```kotlin
private const val DEFAULT_CARD_HEIGHT = 130
```

## Scroll-Aware Detection

### The Problem

Early implementation accumulated drag offsets without accounting for LazyColumn scroll:
```kotlin
// ❌ OLD: Absolute offset, not scroll-aware
state.dragOffset += IntOffset(x.roundToInt(), y.roundToInt())
if (state.dragOffset.y > downThreshold) { /* swap */ }
```

Result: When user scrolled LazyColumn down, card visual position changed but dragOffset didn't adjust, causing misalignment.

### The Solution

Track global Y coordinates in screen space:

```kotlin
// ✅ NEW: Global position aware
val cardGlobalY: SnapshotStateMap<Int, Float> = mutableStateMapOf()
var pointerY by mutableStateOf(0f)

// Updated via onGloballyPositioned
.onGloballyPositioned { coordinates ->
    onGlobalPositionChanged(coordinates.positionInWindow().y)
}
```

### How It Works

1. **Position Tracking**: `onGloballyPositioned` updates card's window Y coordinate whenever layout changes
2. **Pointer Tracking**: `pointerY` accumulates absolute pointer movement in screen coordinates
3. **Target Detection**: Current target + pointer position determines drop target
4. **Scroll Resilience**: Works whether LazyColumn is scrolled to top, middle, or bottom

## State Management

### ReorderableCardState

```kotlin
@Stable
class ReorderableCardState {
    var draggedIndex: Int?                          // Which card is being dragged
    var dragOffset: IntOffset                       // Accumulated drag movement
    var targetIndex: Int?                           // Where card would be dropped
    var pointerY: Float                             // Absolute pointer Y position
    val cardHeights: Map<CardId, Int>               // Measured heights
    val cardGlobalY: Map<Int, Float>                // Global Y positions
}
```

### Lifecycle

1. **onDragStart(index)**
   - Set draggedIndex = index
   - Reset dragOffset, targetIndex to starting values
   - Reset pointerY = 0

2. **onDrag(x, y)**
   - Accumulate offset: dragOffset += (x, y)
   - Update pointer: pointerY += y
   - Calculate thresholds based on current/prev/next card heights
   - Update targetIndex if threshold exceeded
   - Reset dragOffset when target changes (for next threshold comparison)

3. **onDragEnd()**
   - If targetIndex == displayableCards.size: **Delete card**
   - If targetIndex != draggedIndex: **Reorder cards**
   - Clear all state: draggedIndex, targetIndex, pointerY = null

## Visual Feedback

### Dragged Card
- **Alpha**: 0.75 (slightly transparent)
- **Elevation**: 16dp shadow (floats above other cards)
- **Scale**: 1.02x (slightly enlarged)
- **Offset**: Applied to show drag movement

### Target Card (where card will land)
- **Alpha**: 0.6 (ghosted appearance)
- Indicates destination without affecting layout

### Deletion Drop Zone
- **Height**: 80dp
- **Position**: Below last card
- **Color**: Transitions from surfaceContainer → errorContainer on hover
- **Icon**: Delete icon with label

## Performance Considerations

### Recomposition Optimization

- `@Stable` annotation on ReorderableCardState prevents unnecessary recompositions
- `SnapshotStateMap` for cardHeights and cardGlobalY avoids unnecessary allocations
- State updates only trigger recomposition of affected cards

### Scroll Performance

- LazyColumn naturally handles large lists
- Drag detection uses efficient pointer input handling
- No manual layout calculations for visible cards

## Testing Strategy

### Unit Tests

See `ReorderableCardGridThresholdTest.kt`:
- Threshold formula validation
- Edge cases (first/last card, equal/different heights)
- Boundary conditions (at threshold vs. past threshold)
- Fallback to DEFAULT_CARD_HEIGHT

**Run:**
```bash
./gradlew testDebugUnitTest --tests ReorderableCardGridThresholdTest
```

### UI Tests

See `DashboardScreenTest.kt`:
- FAB visibility in edit mode
- Drag interactions
- Card reordering feedback

**Run:**
```bash
./gradlew connectedAndroidTest --tests DashboardScreenTest
```

### Manual Testing Checklist

#### Unscrolled Viewport
- [ ] Drag card from position 0 to position 1 (swap down)
- [ ] Drag card from position 1 to position 0 (swap up)
- [ ] Drag card to deletion zone (remove card)
- [ ] Verify card positions update correctly

#### Scrolled Viewport
- [ ] Scroll LazyColumn to bottom
- [ ] Drag card from top (after scroll)
- [ ] Verify target matches visual position
- [ ] Drag card from middle (after scroll)
- [ ] Verify no jank during drag + scroll

#### Different Card Heights
- [ ] Drag Sleep Score card (large, 140dp)
- [ ] Drag Step card (full-width, 130dp)
- [ ] Verify consistent swap behavior
- [ ] Check thresholds adapt to height differences

#### Edge Cases
- [ ] Drag first card upward (no-op)
- [ ] Drag last card downward to deletion zone
- [ ] Rapid drag input (fast swaps)
- [ ] Drag while filtering cards off-screen

#### Device Configurations
- [ ] Phone portrait (5" screen)
- [ ] Phone landscape (5" screen)
- [ ] Tablet portrait (10" screen)
- [ ] Tablet landscape (10" screen)
- [ ] Screen rotation during drag

## Regression Prevention

### Known Issues Fixed

| Issue | Fix | Commit |
|-------|-----|--------|
| Drag only works in unscrolled view | Scroll-aware detection with onGloballyPositioned | e8f6058 |
| Unpredictable swap timing | Adaptive thresholds based on card heights | Initial PR |
| No unit test coverage | 16 test cases for threshold logic | 1a1098f |

### Future Considerations

1. **Animation**: Consider adding crossfade animation when cards reorder
2. **Undo Support**: Track undo history for accidental reorders
3. **Accessibility**: Ensure screen readers announce drag events
4. **Haptic Feedback**: Add haptic vibration on successful swap
5. **Drag Limits**: Prevent dragging cards beyond visible grid

## Debugging Tips

### Logcat Filters
```bash
adb logcat | grep "Drag\|Reorder\|CardGrid"
```

### Breakpoints
- `ReorderableCardState.updateHeight()`: Verify card heights
- `renderCardItem.onDrag`: Check threshold calculations
- `renderCardItem.onDragEnd`: Verify card reorder callback

### Layout Inspector
- Verify card positions in screen space
- Check z-order (FAB above nav bar)
- Validate drag indicator visibility in edit mode

## References

- Compose Pointer Input: https://developer.android.com/develop/ui/compose/gestures/pointer-input
- Material Design Drag-and-Drop: https://m3.material.io/foundations/interaction/gestures
- Android Accessibility: https://developer.android.com/guide/topics/ui/accessibility
