# Preset Themes and MCU Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the color theme engine in Readylytics to support 5 preset color themes (explicitly mapped without MCU) and implement a dynamic M3 palette fallback using Google's Material Color Utilities for custom hex inputs.

---

### Task 1: Version Catalog & Build Configuration

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add material-color-utilities version and library to catalog**
  Add the dependency metadata under `gradle/libs.versions.toml`:
  ```toml
  [versions]
  materialColorUtilities = "0.12.0"

  [libraries]
  material-color-utilities = { group = "dev.material", name = "material-color-utilities", version.ref = "materialColorUtilities" }
  ```

- [ ] **Step 2: Add implementation dependency in Gradle build file**
  Add the dependency in `app/build.gradle.kts`:
  ```kotlin
  dependencies {
      // ...
      implementation(libs.material.color.utilities)
  }
  ```

- [ ] **Step 3: Run gradle compile check**
  Run: `.\gradlew compileDebugUnitTestKotlin` to ensure the library resolves and compiles successfully.

---

### Task 2: Domain, Protobuf, and Serialization Updates

**Files:**
- Modify: `app/src/main/proto/user_preferences.proto`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/FallbackThemeColor.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferences.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesSerializer.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/SettingsDefaults.kt`

- [ ] **Step 1: Update Protobuf enum names**
  Update the `FallbackThemeColorProto` enum values in `app/src/main/proto/user_preferences.proto` to match the new themes:
  ```proto
  enum FallbackThemeColorProto {
      FALLBACK_GREEN_PERFORMANCE = 0;
      FALLBACK_BLUE_TRUST = 1;
      FALLBACK_PURPLE_INSIGHT = 2;
      FALLBACK_ICON_SIGNATURE = 3;
      FALLBACK_ICON_ELEMENTS = 4;
  }
  ```

- [ ] **Step 2: Update FallbackThemeColor enum definition**
  Update `app/src/main/kotlin/app/readylytics/health/data/preferences/FallbackThemeColor.kt` to represent the 5 preset themes with primary, secondary, and tertiary colors:
  ```kotlin
  enum class FallbackThemeColor(
      val primaryColor: Long,
      val secondaryColor: Long,
      val tertiaryColor: Long,
  ) {
      GREEN_PERFORMANCE(0xFF2ECC71L, 0xFF3498DBL, 0xFFF1C40FL),
      BLUE_TRUST(0xFF4A90E2L, 0xFFF5A623L, 0xFF50E3C2L),
      PURPLE_INSIGHT(0xFF8E44ADL, 0xFFF39C12L, 0xFFE91E63L),
      ICON_SIGNATURE(0xFF9D6FFFL, 0xFF409FFFL, 0xFFC1A2F5L),
      ICON_ELEMENTS(0xFF409FFFL, 0xFF9D6FFFL, 0xFFFFB74DL);

      val seedColor: Long get() = primaryColor
  }
  ```

- [ ] **Step 3: Update UserPreferences domain class**
  Modify mappings in `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferences.kt` to resolve the new proto enum values.

- [ ] **Step 4: Update UserPreferencesSerializer mapping**
  Update serializer mapping functions in `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesSerializer.kt` to serialize the new enum constants.

- [ ] **Step 5: Update SettingsDefaults**
  Update default configuration values in `app/src/main/kotlin/app/readylytics/health/data/preferences/SettingsDefaults.kt`:
  * `FALLBACK_THEME_COLOR` = `FallbackThemeColor.GREEN_PERFORMANCE`
  * `CUSTOM_PRIMARY_COLOR` = `0xFF2ECC71L`

---

### Task 3: Palette Generation & Fallback Implementation

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/theme/Theme.kt`

- [ ] **Step 1: Add imports to Theme.kt**
  Import the Hct and SchemeTonalSpot classes in `app/src/main/kotlin/app/readylytics/health/ui/theme/Theme.kt`:
  ```kotlin
  import dev.material.color.hct.Hct
  import dev.material.color.scheme.SchemeTonalSpot
  ```

- [ ] **Step 2: Add mcuColorScheme helper function**
  Define `mcuColorScheme` in `Theme.kt` to build Compose `ColorScheme` instances from `SchemeTonalSpot` outputs for light and dark modes.

- [ ] **Step 3: Update FitDashboardTheme logic**
  Modify the `colorScheme` resolution to perform a check against the 5 preset themes. If matched, apply the explicit color roles bypassing MCU. Otherwise, call `mcuColorScheme` fallback.

---

### Task 4: UI Picker & Resource Localization Updates

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/settings/common/CustomColorPicker.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/settings/common/FallbackThemeColorSelector.kt`

- [ ] **Step 1: Define localized strings in strings.xml**
  Remove old color strings and add the 5 new theme strings under `<resources>` in `app/src/main/res/values/strings.xml`:
  * `fallback_color_green_performance`: "Green Performance"
  * `fallback_color_blue_trust`: "Blue Trust"
  * `fallback_color_purple_insight`: "Purple Insight"
  * `fallback_color_icon_signature`: "Icon Signature"
  * `fallback_color_icon_elements`: "Icon Elements"

- [ ] **Step 2: Update FallbackThemeColor label mappings**
  Update `FallbackThemeColor.labelRes()` mappings in both `CustomColorPicker.kt` and `FallbackThemeColorSelector.kt` to return the new string resource IDs.

---

### Task 5: Testing & Verification

**Files:**
- Create/Modify: Unit tests in `app/src/test/kotlin/app/readylytics/health/ui/theme/ThemeTest.kt`

- [ ] **Step 1: Implement unit tests verifying theme rules**
  Create/update tests to assert:
  * That preset theme inputs map *exactly* to the hardcoded hexes for primary, secondary, and tertiary.
  * That custom hex inputs produce MCU tonal spot schemes.

- [ ] **Step 2: Execute build and tests**
  Run: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` to verify all tests pass and formatting is correct.
