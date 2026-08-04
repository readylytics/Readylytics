# Dashboard Single-Line Titles Design

## Overview
The goal is to ensure that all dashboard metric cards display their titles on a single line, rather than wrapping to two lines. This involves slightly reducing the font size and shortening several specific string resources that are otherwise too long to fit.

## Changes

### 1. Typography & Layout (`DashboardMetricCard.kt`)
* Change the metric card title text style from `MaterialTheme.typography.titleMedium` to `MaterialTheme.typography.titleSmall` to reduce the footprint.
* Update the `Text` configuration to enforce a single-line layout by setting both `minLines` and `maxLines` to `1` (these are currently hardcoded to `2`).
* The existing `overflow = TextOverflow.Ellipsis` property will remain in place to gracefully truncate any titles that are still unexpectedly long.

### 2. String Updates
Several titles will be shortened to comfortably fit within the single-line constraint. We will ensure these changes are applied globally across the app (including other tabs like the Sleep tab), updating all string resources that represent these titles.

**Target strings to update (in `feature/dashboard`, `feature/sleep`, `core/ui`, etc.):**
* `card_title_circadian_consistency`: "Circadian consistency" → "Circadian"
* `card_title_oxygen_saturation`: "Oxygen saturation" → "SpO2"
* `card_title_resting_hr`: "Resting heart rate" → "Resting HR"
* `card_title_sleep_duration`: "Sleep duration" → "Sleep Time"
* `card_title_sleep_efficiency`: "Sleep Efficiency" → "Sleep Eff."
