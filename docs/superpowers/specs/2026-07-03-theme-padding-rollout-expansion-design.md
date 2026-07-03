# Design Doc: Theme Spacing Rollout Expansion

## Status: Approved

## Goal
Expand the theme spacing migration initiated in `docs/superpowers/plans/2026-07-03-theme-padding-rollout.md` to all remaining user-facing modules (`dashboard`, `about`, `insights`, `sleep`, and `onboarding`). Migrate both top-level screen containers and internal page/section rhythm (margin, spacing, separators) to the semantic spacing aliases defined in `Spacing.kt`.

## Spacing Aliases Reference
The target semantic aliases from `Spacing.kt` mapped to underlying values:
* `pageHorizontal`: `medium` (16.dp)
* `pageTop`: `medium` (16.dp)
* `pageBottom`: `medium` (16.dp)
* `pageSectionGap`: `medium` (16.dp)
* `pageSectionGapSmall`: `small` (8.dp)
* `pageSectionGapLarge`: `large` (24.dp)
* `extraSmall`: `extraSmall` (4.dp)
* `extraLarge`: `extraLarge` (32.dp)

---

## Detailed Component Specifications

### 1. Dashboard Module (`feature/dashboard`)

* **File:** `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt`
  * Modify `contentPadding` on the primary `LazyColumn` from `vertical = 16.dp` to `top = MaterialTheme.spacing.pageTop, bottom = MaterialTheme.spacing.pageBottom`.
  * Replace horizontal margins (`horizontal = 16.dp`) on lists, grids, date switcher containers, customize button, and snackbars with `MaterialTheme.spacing.pageHorizontal`.
  * Replace raw height spacers (`16.dp`, `48.dp`) with `MaterialTheme.spacing.pageSectionGap` and `MaterialTheme.spacing.doubleExtraLarge`.
  * Replace the FAB padding (`16.dp`) with `MaterialTheme.spacing.pageHorizontal`.
* **File:** `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardManagementBottomSheet.kt`
  * Replace root Column `padding(vertical = 16.dp)` with `padding(vertical = MaterialTheme.spacing.pageSectionGap)`.
  * Replace Row `padding(start = 16.dp, end = 16.dp, bottom = 16.dp)` with `padding(start = MaterialTheme.spacing.pageHorizontal, end = MaterialTheme.spacing.pageHorizontal, bottom = MaterialTheme.spacing.pageSectionGap)`.
  * Replace button `padding(end = 16.dp, top = 16.dp)` with `padding(end = MaterialTheme.spacing.pageHorizontal, top = MaterialTheme.spacing.pageSectionGap)`.
* **File:** `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CalibrationBanner.kt`
  * Update `DashboardScreen.kt` container padding for the calibration banner item from `horizontal = 16.dp` to `horizontal = MaterialTheme.spacing.pageHorizontal`.

### 2. About Module (`feature/about`)

* **File:** `feature/about/src/main/kotlin/app/readylytics/health/feature/about/AboutScreen.kt`
  * Modify `contentPadding` on the main scrolling `LazyColumn` from `bottom = 16.dp` to `bottom = MaterialTheme.spacing.pageBottom`.
  * Replace `verticalArrangement = Arrangement.spacedBy(16.dp)` with `Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGap)`.
  * Replace top spacer (`32.dp`) with `Spacer(Modifier.height(MaterialTheme.spacing.extraLarge))`.
* **File:** `feature/about/src/main/kotlin/app/readylytics/health/feature/about/AboutComponents.kt`
  * Change horizontal padding on `SectionHeader`, `SubHeader`, `BodyText`, `BulletItem`, `HighlightBox`, and `SectionDivider` from `MaterialTheme.spacing.medium` to `MaterialTheme.spacing.pageHorizontal`.
* **File:** `feature/about/src/main/kotlin/app/readylytics/health/feature/about/AppInfoSection.kt`
  * Change outer table surface padding from `horizontal = 16.dp, vertical = 8.dp` to `horizontal = MaterialTheme.spacing.pageHorizontal, vertical = MaterialTheme.spacing.pageSectionGapSmall`.
* **Files:** `ContributorsSection.kt`, `LicenseSection.kt`, `FeedbackSection.kt`
  * Replace all `8.dp` spacers with `MaterialTheme.spacing.pageSectionGapSmall`.
  * Replace horizontal padding `16.dp` in `FeedbackSection` with `MaterialTheme.spacing.pageHorizontal`.
  * Replace vertical/top paddings `16.dp` in `FeedbackSection` with `MaterialTheme.spacing.pageSectionGap`.

### 3. Insights Module (`feature/insights`)

* **File:** `feature/insights/src/main/kotlin/app/readylytics/health/feature/insights/InsightDetailSheet.kt`
  * Modify container padding from `horizontal = 24.dp, vertical = 8.dp` to `horizontal = MaterialTheme.spacing.pageSectionGapLarge, vertical = MaterialTheme.spacing.pageSectionGapSmall`.
  * Replace bottom spacer `16.dp` with `MaterialTheme.spacing.pageBottom`.

### 4. Sleep Module (`feature/sleep`)

* **File:** `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt`
  * Update vertical scroll padding from `vertical = MaterialTheme.spacing.medium` to `top = MaterialTheme.spacing.pageTop, bottom = MaterialTheme.spacing.pageBottom`.
  * Update all horizontal paddings from `MaterialTheme.spacing.medium` to `MaterialTheme.spacing.pageHorizontal`.
  * Replace `MaterialTheme.spacing.small` vertical spacers with `MaterialTheme.spacing.pageSectionGapSmall`.
  * Replace `MaterialTheme.spacing.medium` vertical spacers with `MaterialTheme.spacing.pageSectionGap`.
  * Replace `MaterialTheme.spacing.large` vertical spacers with `MaterialTheme.spacing.pageSectionGapLarge`.
  * Update gauge row padding to use `pageHorizontal`, `pageSectionGap`, and `pageSectionGapSmall`.

### 5. Onboarding Module (`feature/onboarding`)

* **File:** `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/OnboardingScreen.kt`
  * Update outermost vertical scroll paddings from `24.dp` to `MaterialTheme.spacing.pageSectionGapLarge`.
  * Map vertical/horizontal spacing (`8.dp`, `16.dp`, `20.dp`, `24.dp`, `32.dp`) to `pageSectionGapSmall`, `pageSectionGap`, `large`, `pageSectionGapLarge`, and `extraLarge` respectively.
* **File:** `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/DeviceSelectionScreen.kt`
  * Update container padding from `24.dp` to `MaterialTheme.spacing.pageSectionGapLarge`.
  * Map height spacers (`8.dp`, `16.dp`, `24.dp`) to `pageSectionGapSmall`, `pageSectionGap`, `pageSectionGapLarge`.
  * Change list item gap `spacedBy(8.dp)` to `spacedBy(MaterialTheme.spacing.pageSectionGapSmall)`.
  * Change button padding `16.dp` to `MaterialTheme.spacing.pageHorizontal`.
* **File:** `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/PrivacyRationaleScreen.kt`
  * Update container padding from `24.dp` to `MaterialTheme.spacing.pageSectionGapLarge`.
  * Map spacers (`16.dp`, `32.dp`) to `pageSectionGap` and `extraLarge` respectively.
* **File:** `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/RestoreBackupScreen.kt`
  * Update container padding from `24.dp` to `MaterialTheme.spacing.pageSectionGapLarge`.
  * Map spacers (`8.dp`, `16.dp`, `24.dp`) to their respective page-level semantic spacing equivalents.

---

## Out of Scope
* Deep card internals (such as specific item row internals, step progress circles, chart rendering bounds).
* Internal UI components with one-off layout requirements (e.g., custom form inputs, unit system pickers).

---

## Verification & Testing Plan

### 1. Build Verification
Verify compiling all touched modules:
```powershell
.\gradlew :feature:dashboard:compileDebugKotlin :feature:about:compileDebugKotlin :feature:insights:compileDebugKotlin :feature:sleep:compileDebugKotlin :feature:onboarding:compileDebugKotlin
```

### 2. Format & Lints
Run standard checks:
```powershell
.\gradlew ktlintFormat
.\gradlew testDebugUnitTest
.\gradlew lint
```

### 3. Visual Checklist
* Ensure horizontal alignment matches exactly between all page content, date switchers, and section headers across sleep, dashboard, and settings screens.
* Ensure onboarding margins do not clip text on smaller screens.
