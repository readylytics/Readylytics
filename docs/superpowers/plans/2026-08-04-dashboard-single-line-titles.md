# Dashboard Single-Line Titles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure all dashboard metric cards display titles on a single line by adjusting typography and shortening string resources globally.

**Architecture:** Update Compose `Text` parameters in `DashboardMetricCard.kt` and modify `strings.xml` values across the app to use shorter equivalents.

**Tech Stack:** Kotlin, Jetpack Compose, Android Resources

## Global Constraints

- Strings must be updated everywhere they appear in the app to maintain consistency.
- Pre-Commit (Mandatory Verification): `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`

---

### Task 1: Update DashboardMetricCard layout

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt`

**Interfaces:**
- Consumes: N/A
- Produces: `DashboardMetricCard` UI component with single-line constraint.

- [ ] **Step 1: Update title styling and line constraints**

In `DashboardMetricCard.kt`, locate the `Text` component displaying `presentation.title`.
Change `style = MaterialTheme.typography.titleMedium.copy(...)` to `style = MaterialTheme.typography.titleSmall.copy(...)`.
Change `minLines = 2` to `minLines = 1`.
Change `maxLines = 2` to `maxLines = 1`.

```kotlin
            Text(
                text = presentation.title,
                style =
                    MaterialTheme.typography.titleSmall.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle =
                            LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both,
                            ),
                    ),
                color = contentColor,
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
```

- [ ] **Step 2: Run ktlint to verify formatting**

Run: `./gradlew ktlintFormat`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt
git commit -m "ui: update dashboard metric card title to titleSmall and single line"
```

### Task 2: Update String Resources globally

**Files:**
- Modify: `*/src/main/res/values/strings.xml` (all files containing these target strings)

**Interfaces:**
- Consumes: N/A
- Produces: Updated string values for dashboard titles globally.

- [ ] **Step 1: Search and replace `card_title_circadian_consistency`**

Search for `name="card_title_circadian_consistency"` across all `strings.xml` files and replace its text content with `Circadian`.

- [ ] **Step 2: Search and replace `card_title_oxygen_saturation`**

Search for `name="card_title_oxygen_saturation"` across all `strings.xml` files and replace its text content with `SpO2`.

- [ ] **Step 3: Search and replace `card_title_resting_hr`**

Search for `name="card_title_resting_hr"` across all `strings.xml` files and replace its text content with `Resting HR`.

- [ ] **Step 4: Search and replace `card_title_sleep_duration`**

Search for `name="card_title_sleep_duration"` across all `strings.xml` files and replace its text content with `Sleep Time`.

- [ ] **Step 5: Search and replace `card_title_sleep_efficiency`**

Search for `name="card_title_sleep_efficiency"` across all `strings.xml` files and replace its text content with `Sleep Eff.`

- [ ] **Step 6: Run tests to verify string updates didn't break layout tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add */src/main/res/values/strings.xml
git commit -m "chore: shorten metric card titles globally"
```
