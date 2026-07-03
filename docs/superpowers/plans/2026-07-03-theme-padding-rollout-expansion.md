# Theme Padding Rollout Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand the theme spacing migration to the remaining user-facing modules (`dashboard`, `about`, `insights`, `sleep`, and `onboarding`) using the newly introduced semantic spacing aliases.

**Architecture:** Use `MaterialTheme.spacing` (defined in `core:designsystem`) semantic aliases (`pageHorizontal`, `pageTop`, `pageBottom`, `pageSectionGap`, `pageSectionGapSmall`, `pageSectionGapLarge`) for outermost page layout padding and page section/divider/spacer rhythm. Keep deep card internals untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Gradle, Android Lint

## Global Constraints
* Keep `core:designsystem` spacing single source of truth.
* Do not sweep card interiors (e.g. `MetricCard`, `TrendCard`, `M3ScoreGaugeCard` internals) or one-off styling details like `48.dp` FAB sizes.
* Add `import app.readylytics.health.core.designsystem.spacing` to all modified Compose files.

---

### Task 1: Migrate About Screen & Components

**Files:**
* Modify: `feature/about/src/main/kotlin/app/readylytics/health/feature/about/AboutScreen.kt`
* Modify: `feature/about/src/main/kotlin/app/readylytics/health/feature/about/AboutComponents.kt`
* Modify: `feature/about/src/main/kotlin/app/readylytics/health/feature/about/AppInfoSection.kt`
* Modify: `feature/about/src/main/kotlin/app/readylytics/health/feature/about/ContributorsSection.kt`
* Modify: `feature/about/src/main/kotlin/app/readylytics/health/feature/about/FeedbackSection.kt`
* Modify: `feature/about/src/main/kotlin/app/readylytics/health/feature/about/LicenseSection.kt`

**Interfaces:**
* Consumes: Spacing aliases from `core:designsystem`
* Produces: Consistently spaced About UI components

- [ ] **Step 1: Modify `AboutScreen.kt`**
Add spacing import:
```kotlin
import app.readylytics.health.core.designsystem.spacing
```
Replace the outer LazyColumn spacing and top Spacer:
```kotlin
<<<<
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(Modifier.height(32.dp)) }
====
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = MaterialTheme.spacing.pageBottom),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGap),
        ) {
            item { Spacer(Modifier.height(MaterialTheme.spacing.extraLarge)) }
>>>>
```

- [ ] **Step 2: Modify `AboutComponents.kt`**
Replace standard `medium`, `small`, `extraSmall` references with semantic aliases where they represent page-facing rhythm:
```kotlin
<<<<
@Composable
fun SectionHeader(text: String) {
    val cleanText = text.removePrefix("# ").trim()
    Text(
        text = cleanText,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
    )
}

@Composable
fun SubHeader(text: String) {
    val cleanText = text.removePrefix("## ").trim()
    Text(
        text = cleanText,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
    )
}

@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    fontStyle: FontStyle? = null,
) {
    Text(
        text = parseMarkdown(text),
        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = fontStyle ?: FontStyle.Normal),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
    )
}

@Composable
fun BulletItem(text: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium, vertical = 2.dp),
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = MaterialTheme.spacing.small),
        )
        Text(
            text = parseMarkdown(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun HighlightBox(content: @Composable () -> Unit) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(vertical = MaterialTheme.spacing.small)) { content() }
    }
}

@Composable
fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
====
@Composable
fun SectionHeader(text: String) {
    val cleanText = text.removePrefix("# ").trim()
    Text(
        text = cleanText,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.pageSectionGapSmall,
                ),
    )
}

@Composable
fun SubHeader(text: String) {
    val cleanText = text.removePrefix("## ").trim()
    Text(
        text = cleanText,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.extraSmall,
                ),
    )
}

@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    fontStyle: FontStyle? = null,
) {
    Text(
        text = parseMarkdown(text),
        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = fontStyle ?: FontStyle.Normal),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.extraSmall,
                ),
    )
}

@Composable
fun BulletItem(text: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal, vertical = 2.dp),
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = MaterialTheme.spacing.pageSectionGapSmall),
        )
        Text(
            text = parseMarkdown(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun HighlightBox(content: @Composable () -> Unit) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.pageSectionGapSmall,
                ),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(vertical = MaterialTheme.spacing.pageSectionGapSmall)) { content() }
    }
}

@Composable
fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(
            horizontal = MaterialTheme.spacing.pageHorizontal,
            vertical = MaterialTheme.spacing.pageSectionGapSmall,
        ),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
>>>>
```

- [ ] **Step 3: Modify `AppInfoSection.kt`**
Add spacing import and update ScoreTable padding:
```kotlin
import app.readylytics.health.core.designsystem.spacing
```
```kotlin
<<<<
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
====
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.pageSectionGapSmall,
                ),
>>>>
```

- [ ] **Step 4: Modify `ContributorsSection.kt`**
Add spacing import and update Spacers:
```kotlin
import androidx.compose.material3.MaterialTheme
import app.readylytics.health.core.designsystem.spacing
```
Replace all instances of `Spacer(Modifier.height(8.dp))` with `Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))`.

- [ ] **Step 5: Modify `FeedbackSection.kt`**
Add spacing import and update modifier paddings:
```kotlin
import androidx.compose.material3.MaterialTheme
import app.readylytics.health.core.designsystem.spacing
```
```kotlin
<<<<
    Column(
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Button(
            onClick = onDismiss,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp),
        )
====
    Column(
        modifier = Modifier.padding(top = MaterialTheme.spacing.pageSectionGap),
    ) {
        Button(
            onClick = onDismiss,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.pageHorizontal)
                    .padding(top = MaterialTheme.spacing.pageSectionGap),
        )
>>>>
```

- [ ] **Step 6: Modify `LicenseSection.kt`**
Add spacing import and update Spacers:
```kotlin
import androidx.compose.material3.MaterialTheme
import app.readylytics.health.core.designsystem.spacing
```
Replace all instances of `Spacer(Modifier.height(8.dp))` with `Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))`.

- [ ] **Step 7: Compile and verify feature/about**
Run:
```powershell
.\gradlew :feature:about:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit Task 1**
```bash
git add feature/about/src/main/kotlin/app/readylytics/health/feature/about/
git commit -m "refactor: migrate about screen and subcomponents to theme spacing"
```

---

### Task 2: Migrate Insights Detail Sheet

**Files:**
* Modify: `feature/insights/src/main/kotlin/app/readylytics/health/feature/insights/InsightDetailSheet.kt`

**Interfaces:**
* Consumes: Spacing aliases from `core:designsystem`
* Produces: Clean sheet paddings in Insights

- [ ] **Step 1: Modify `InsightDetailSheet.kt`**
Add spacing import:
```kotlin
import app.readylytics.health.core.designsystem.spacing
```
Update the outer Column padding and bottom Spacer:
```kotlin
<<<<
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
...
            Spacer(Modifier.height(16.dp))
        }
====
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = MaterialTheme.spacing.pageSectionGapLarge,
                        vertical = MaterialTheme.spacing.pageSectionGapSmall,
                    ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
...
            Spacer(Modifier.height(MaterialTheme.spacing.pageBottom))
        }
>>>>
```

- [ ] **Step 2: Compile and verify feature/insights**
Run:
```powershell
.\gradlew :feature:insights:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit Task 2**
```bash
git add feature/insights/src/main/kotlin/app/readylytics/health/feature/insights/InsightDetailSheet.kt
git commit -m "refactor: migrate insights detail sheet spacing to theme spacing"
```

---

### Task 3: Migrate Sleep Screen

**Files:**
* Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt`

**Interfaces:**
* Consumes: Spacing aliases from `core:designsystem`
* Produces: Theme-aligned Sleep screen shell

- [ ] **Step 1: Modify `SleepScreen.kt`**
Modify standard spacing references to semantic aliases in `SleepScreen.kt`:
```kotlin
<<<<
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(vertical = MaterialTheme.spacing.medium),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.medium),
        ) {
...
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = MaterialTheme.spacing.medium,
                        end = MaterialTheme.spacing.medium,
                        top = MaterialTheme.spacing.medium,
                        bottom = MaterialTheme.spacing.small,
                    ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
====
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = MaterialTheme.spacing.pageTop, bottom = MaterialTheme.spacing.pageBottom),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        ) {
...
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = MaterialTheme.spacing.pageHorizontal,
                        end = MaterialTheme.spacing.pageHorizontal,
                        top = MaterialTheme.spacing.pageSectionGap,
                        bottom = MaterialTheme.spacing.pageSectionGapSmall,
                    ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
>>>>
```

Also, modify all subsequent occurrences of `MaterialTheme.spacing.medium` to `MaterialTheme.spacing.pageHorizontal` or `MaterialTheme.spacing.pageSectionGap` based on their visual roles (margins vs. gaps), and all instances of `MaterialTheme.spacing.small` to `MaterialTheme.spacing.pageSectionGapSmall` and `MaterialTheme.spacing.large` to `MaterialTheme.spacing.pageSectionGapLarge` within the page layout.

- [ ] **Step 2: Compile and verify feature/sleep**
Run:
```powershell
.\gradlew :feature:sleep:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit Task 3**
```bash
git add feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt
git commit -m "refactor: migrate sleep screen to semantic spacing aliases"
```

---

### Task 4: Migrate Dashboard Screen & Bottom Sheet

**Files:**
* Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt`
* Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardManagementBottomSheet.kt`

**Interfaces:**
* Consumes: Spacing aliases from `core:designsystem`
* Produces: Page horizontal alignment matching across Dashboard

- [ ] **Step 1: Modify `DashboardScreen.kt`**
Add spacing import:
```kotlin
import app.readylytics.health.core.designsystem.spacing
```
Replace the hardcoded paddings and heights:
* Change `contentPadding = PaddingValues(vertical = 16.dp)` in `LazyColumn` to `PaddingValues(top = MaterialTheme.spacing.pageTop, bottom = MaterialTheme.spacing.pageBottom)`.
* Change `horizontal = 16.dp` to `horizontal = MaterialTheme.spacing.pageHorizontal`.
* Change `Spacer(modifier = Modifier.height(16.dp))` to `Spacer(modifier = Modifier.height(MaterialTheme.spacing.pageSectionGap))`.
* Change `.padding(vertical = 48.dp)` for no-data placeholder to `.padding(vertical = MaterialTheme.spacing.doubleExtraLarge)`.
* Change `.padding(vertical = 16.dp)` for customize button box to `.padding(vertical = MaterialTheme.spacing.pageSectionGap)`.
* Change `SnackbarHost` padding:
```kotlin
<<<<
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = if (uiState.isManagingCards) 88.dp else 16.dp,
                    ),
====
                    .padding(
                        start = MaterialTheme.spacing.pageHorizontal,
                        end = MaterialTheme.spacing.pageHorizontal,
                        top = MaterialTheme.spacing.pageSectionGap,
                        bottom = if (uiState.isManagingCards) 88.dp else MaterialTheme.spacing.pageBottom,
                    ),
>>>>
```
* Change EditModeFab padding from `16.dp` to `MaterialTheme.spacing.pageHorizontal`.
* Change calibration banner outer item padding `horizontal = 16.dp, vertical = 4.dp` to `horizontal = MaterialTheme.spacing.pageHorizontal, vertical = MaterialTheme.spacing.extraSmall`.

- [ ] **Step 2: Modify `CardManagementBottomSheet.kt`**
Add spacing import:
```kotlin
import app.readylytics.health.core.designsystem.spacing
```
Replace internal bottom sheet padding patterns:
* Change column `vertical = 16.dp` to `vertical = MaterialTheme.spacing.pageSectionGap`.
* Change row `start = 16.dp, end = 16.dp, bottom = 16.dp` to `start = MaterialTheme.spacing.pageHorizontal, end = MaterialTheme.spacing.pageHorizontal, bottom = MaterialTheme.spacing.pageSectionGap`.
* Change Item vertical padding `vertical = 4.dp` to `vertical = MaterialTheme.spacing.extraSmall`.
* Change Button `end = 16.dp, top = 16.dp` to `end = MaterialTheme.spacing.pageHorizontal, top = MaterialTheme.spacing.pageSectionGap`.

- [ ] **Step 3: Compile and verify feature/dashboard**
Run:
```powershell
.\gradlew :feature:dashboard:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit Task 4**
```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardManagementBottomSheet.kt
git commit -m "refactor: migrate dashboard screen and bottom sheet layout to theme padding"
```

---

### Task 5: Migrate Onboarding Screen & Sub-Screens

**Files:**
* Modify: `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/OnboardingScreen.kt`
* Modify: `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/DeviceSelectionScreen.kt`
* Modify: `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/PrivacyRationaleScreen.kt`
* Modify: `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/RestoreBackupScreen.kt`

**Interfaces:**
* Consumes: Spacing aliases from `core:designsystem`
* Produces: Roomy but standard spacing for onboarding UI

- [ ] **Step 1: Modify `OnboardingScreen.kt`**
Add spacing import:
```kotlin
import app.readylytics.health.core.designsystem.spacing
```
Replace the hardcoded paddings and heights:
* Outermost column `.padding(24.dp)` -> `.padding(MaterialTheme.spacing.pageSectionGapLarge)`
* Spacers:
  * `24.dp` -> `MaterialTheme.spacing.pageSectionGapLarge`
  * `16.dp` -> `MaterialTheme.spacing.pageSectionGap`
  * `8.dp` -> `MaterialTheme.spacing.pageSectionGapSmall`
  * `32.dp` -> `MaterialTheme.spacing.extraLarge`
  * `4.dp` -> `MaterialTheme.spacing.extraSmall`
  * `20.dp` -> `MaterialTheme.spacing.large`
  * `12.dp` -> `MaterialTheme.spacing.small`

- [ ] **Step 2: Modify `DeviceSelectionScreen.kt`**
Add spacing import:
```kotlin
import app.readylytics.health.core.designsystem.spacing
```
Replace layout spacing:
* Change Column `.padding(24.dp)` to `.padding(MaterialTheme.spacing.pageSectionGapLarge)`.
* Change `8.dp` spacers to `MaterialTheme.spacing.pageSectionGapSmall`.
* Change `24.dp` spacer to `MaterialTheme.spacing.pageSectionGapLarge`.
* Change `16.dp` button padding and spacer to `MaterialTheme.spacing.pageHorizontal` / `pageSectionGap`.

- [ ] **Step 3: Modify `PrivacyRationaleScreen.kt`**
Add spacing import:
```kotlin
import app.readylytics.health.core.designsystem.spacing
```
Replace layout spacing:
* Change Column `.padding(24.dp)` to `.padding(MaterialTheme.spacing.pageSectionGapLarge)`.
* Change `16.dp` spacer to `MaterialTheme.spacing.pageSectionGap`.
* Change `32.dp` spacer to `MaterialTheme.spacing.extraLarge`.

- [ ] **Step 4: Modify `RestoreBackupScreen.kt`**
Add spacing import:
```kotlin
import app.readylytics.health.core.designsystem.spacing
```
Replace layout spacing:
* Change Column `.padding(24.dp)` to `.padding(MaterialTheme.spacing.pageSectionGapLarge)`.
* Change height/width spacers of `8.dp`, `16.dp`, `24.dp` to `pageSectionGapSmall`, `pageSectionGap`, `pageSectionGapLarge`.

- [ ] **Step 5: Compile and verify feature/onboarding**
Run:
```powershell
.\gradlew :feature:onboarding:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit Task 5**
```bash
git add feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/
git commit -m "refactor: migrate onboarding screens to theme spacing"
```

---

### Task 6: Whole-Change Verification and Audit

**Files:**
* Verify all modified files

- [ ] **Step 1: Run ktlintFormat**
Run:
```powershell
.\gradlew ktlintFormat
```
Expected: BUILD SUCCESSFUL (formatting issues auto-fixed)

- [ ] **Step 2: Run all unit tests**
Run:
```powershell
.\gradlew testDebugUnitTest
```
Expected: BUILD SUCCESSFUL (all unit tests pass)

- [ ] **Step 3: Run project lint**
Run:
```powershell
.\gradlew lint
```
Expected: BUILD SUCCESSFUL (no lint errors introduced)

- [ ] **Step 4: Final commit of verified state**
```bash
git commit --allow-empty -m "style: verified theme spacing rollout expansion completes successfully"
```
