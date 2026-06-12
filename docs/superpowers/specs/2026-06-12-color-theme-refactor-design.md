# Design Spec: Readylytics Color Theme Engine Refactor

- **Date**: 2026-06-12
- **Objective**: Refactor the color theme implementation to support 5 rigid preset themes alongside a Google Material Color Utilities (MCU) fallback for custom primary hex colors.

---

## 1. Requirements

### 1.1 Hardcoded Theme Selection Bypass
When the user selects one of the 5 built-in presets, the app must explicitly assign the Primary, Secondary, and Tertiary color roles to the preset's hex codes, bypassing the MCU engine:
* **Theme 1: Green Performance**
  * Primary: `#2ECC71` | Secondary: `#3498DB` | Tertiary: `#F1C40F`
* **Theme 2: Blue Trust**
  * Primary: `#4A90E2` | Secondary: `#F5A623` | Tertiary: `#50E3C2`
* **Theme 3: Purple Insight**
  * Primary: `#8E44AD` | Secondary: `#F39C12` | Tertiary: `#E91E63`
* **Theme 4: Icon Signature**
  * Primary: `#9D6FFF` | Secondary: `#409FFF` | Tertiary: `#C1A2F5`
* **Theme 5: Icon Elements**
  * Primary: `#409FFF` | Secondary: `#9D6FFF` | Tertiary: `#FFB74D`

### 1.2 Custom Hex Input Library Fallback
If the user provides a custom primary hex code that does not match a preset, the app must use the official Google `material-color-utilities` library (`SchemeTonalSpot`) to derive a full, harmonized Material 3 palette.

---

## 2. Design Details

### 2.1 Dependencies
We will add Google's standalone `material-color-utilities` library to avoid restricted API lint warnings from the bundled version:
* **`gradle/libs.versions.toml`**:
  ```toml
  [versions]
  materialColorUtilities = "0.12.0"
  [libraries]
  material-color-utilities = { group = "dev.material", name = "material-color-utilities", version.ref = "materialColorUtilities" }
  ```
* **`app/build.gradle.kts`**:
  ```kotlin
  dependencies {
      implementation(libs.material.color.utilities)
  }
  ```

### 2.2 Domain & Preferences Changes
We will redefine the `FallbackThemeColor` enum to hold the explicit mapping for each theme. We will also update the proto enum to match:
* **`FallbackThemeColor.kt`**:
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
* **`user_preferences.proto`**:
  ```proto
  enum FallbackThemeColorProto {
      FALLBACK_GREEN_PERFORMANCE = 0;
      FALLBACK_BLUE_TRUST = 1;
      FALLBACK_PURPLE_INSIGHT = 2;
      FALLBACK_ICON_SIGNATURE = 3;
      FALLBACK_ICON_ELEMENTS = 4;
  }
  ```
* **`UserPreferences.kt` & `UserPreferencesSerializer.kt`**:
  Adjust mapping matches to align the new names.
* **`SettingsDefaults.kt`**:
  Update default color to `0xFF2ECC71L` (Green Performance Primary) and the default theme fallback enum to `FallbackThemeColor.GREEN_PERFORMANCE`.

### 2.3 Theme Rendering & Resolution (`Theme.kt`)
We will check if `customPrimaryColor` matches one of the preset theme primary colors. If so, we bypass the MCU engine and assign the exact preset colors to `primary`, `secondary`, and `tertiary`. If not, we generate the palette via `SchemeTonalSpot`:
```kotlin
val matchingPreset = FallbackThemeColor.entries.find { it.primaryColor == customPrimaryColor }

val colorScheme = if (matchingPreset != null) {
    colorSchemeFromSeed(
        primarySeed = Color(matchingPreset.primaryColor),
        secondarySeed = Color(matchingPreset.secondaryColor),
        tertiarySeed = Color(matchingPreset.tertiaryColor),
        isDark = darkTheme
    ).copy(
        primary = Color(matchingPreset.primaryColor),
        secondary = Color(matchingPreset.secondaryColor),
        tertiary = Color(matchingPreset.tertiaryColor)
    )
} else {
    mcuColorScheme(
        seedColor = Color(customPrimaryColor),
        secondaryColor = secondarySeed,
        tertiaryColor = tertiarySeed,
        isDark = darkTheme
    )
}
```

### 2.4 UI Updates & Localization
* **`strings.xml`**:
  Redefine string resources to name our new presets:
  ```xml
  <string name="fallback_color_green_performance">Green Performance</string>
  <string name="fallback_color_blue_trust">Blue Trust</string>
  <string name="fallback_color_purple_insight">Purple Insight</string>
  <string name="fallback_color_icon_signature">Icon Signature</string>
  <string name="fallback_color_icon_elements">Icon Elements</string>
  ```
* **`CustomColorPicker.kt` & `FallbackThemeColorSelector.kt`**:
  Update `FallbackThemeColor.labelRes()` mappings to associate the enum constants with their new string IDs.

---

## 3. Verification & Testing

### 3.1 Unit Testing
We will write a unit test in `ThemeTest.kt` (or similar UI theme test file) to verify:
1. Preset themes map to their exact preset values.
2. Custom hex inputs derive a dynamic scheme via MCU (generating different secondary and tertiary colors compared to HSL).

### 3.2 Compilation & formatting
* Run `./gradlew compileDebugUnitTestKotlin` to check that the dependencies and imports compile correctly.
* Run `./gradlew ktlintFormat` to ensure formatting guidelines are met.
