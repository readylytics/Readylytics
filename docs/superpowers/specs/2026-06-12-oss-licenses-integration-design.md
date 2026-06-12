# Design Spec: Google OSS Licenses Integration & Settings Screen Reorganization

**Date:** 2026-06-12  
**Status:** Approved  

---

## 1. Overview
To prepare the Readylytics app for Play Store release, we need to add an Open Source Licenses page. We will integrate Google's Play Services OSS Licenses Gradle plugin and library, which automatically parses and generates the licenses for all runtime dependencies. Additionally, we will reorganize the bottom of the Settings screen by removing the standalone "About Readylytics" button and replacing it with a new collapsible "Miscellaneous" section containing links to both "About Readylytics" and "Open Source Licenses".

---

## 2. Dependencies & Build Configuration

We will register the Google OSS Licenses plugin and dependency using Gradle Version Catalog (`libs.versions.toml`) and apply them in the project and application level build files.

### 2.1 `gradle/libs.versions.toml`
```toml
[versions]
playServicesOssLicenses = "17.1.0"
ossLicensesPlugin = "0.10.6"

[libraries]
play-services-oss-licenses = { group = "com.google.android.gms", name = "play-services-oss-licenses", version.ref = "playServicesOssLicenses" }

[plugins]
google-oss-licenses = { id = "com.google.android.gms.oss-licenses-plugin", version.ref = "ossLicensesPlugin" }
```

### 2.2 Root `build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.google.oss-licenses) apply false
}
```

### 2.3 `app/build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.google.oss-licenses)
}

dependencies {
    implementation(libs.play-services-oss-licenses)
}
```

---

## 3. UI Reorganization (`SettingsScreen.kt`)

Currently, `SettingsScreen.kt` has a standalone "About Readylytics" text button at the bottom of the screen. We will:
1. Remove the bottom `Box` containing that button.
2. Add a new `Miscellaneous` section to the list of settings sections.
3. Render a collapsible `M3CollapsibleSection` for `Miscellaneous` containing two standard Material 3 `ListItem`s:
   * **About Readylytics**: Clicking navigates to the existing About screen via `onNavigateToAbout()`.
   * **Open Source Licenses**: Clicking sets the activity title and launches `OssLicensesMenuActivity`.

### 3.1 Code Changes details

* Update `SettingsExpandState` to include `collapseMiscellaneous`:
  ```kotlin
  val collapseMiscellaneous: Boolean = false,
  ```
* Add the metadata to `settingsSections`:
  ```kotlin
  SettingsSectionMetadata(
      id = "miscellaneous",
      name = "Miscellaneous",
      keywords = listOf("miscellaneous", "about", "licenses", "open source", "legal"),
  )
  ```
* Inside `SettingsScreen`'s content column, insert:
  ```kotlin
  // Miscellaneous
  if (matchingSections.any { it.id == "miscellaneous" }) {
      M3CollapsibleSection(
          header = stringResource(R.string.settings_section_miscellaneous),
          expanded = !expandState.collapseMiscellaneous || shouldExpandSection("miscellaneous"),
          onExpandedChange = { expandState = expandState.copy(collapseMiscellaneous = !it) },
      ) {
          Column {
              ListItem(
                  colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                  headlineContent = {
                      Text(
                          text = stringResource(R.string.settings_about_button),
                          style = MaterialTheme.typography.bodyLarge,
                      )
                  },
                  modifier = Modifier.clickable { onNavigateToAbout() }
              )
              HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
              ListItem(
                  colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                  headlineContent = {
                      Text(
                          text = stringResource(R.string.settings_item_licenses),
                          style = MaterialTheme.typography.bodyLarge,
                      )
                  },
                  modifier = Modifier.clickable {
                      com.google.android.gms.oss.licenses.OssLicensesMenuActivity.setActivityTitle(
                          context.getString(R.string.settings_item_licenses_title)
                      )
                      context.startActivity(
                          android.content.Intent(context, com.google.android.gms.oss.licenses.OssLicensesMenuActivity::class.java)
                      )
                  }
              )
          }
      }
  }
  ```

---

## 4. Manifest Registration (`AndroidManifest.xml`)

Since the application uses a `NoActionBar` theme (`Theme.FitDashboard`), we will explicitly register the Play Services OSS Licenses activities in the manifest and apply a standard dark Action Bar theme so that the activity renders properly and supports back navigation.

```xml
        <activity
            android:name="com.google.android.gms.oss.licenses.OssLicensesMenuActivity"
            android:theme="@style/Theme.AppCompat.DayNight.DarkActionBar"
            android:exported="false" />
        <activity
            android:name="com.google.android.gms.oss.licenses.OssLicensesActivity"
            android:theme="@style/Theme.AppCompat.DayNight.DarkActionBar"
            android:exported="false" />
```

---

## 5. Strings (`strings.xml`)

We will add the following localization strings in `app/src/main/res/values/strings.xml`:

```xml
    <!-- Miscellaneous Section -->
    <string name="settings_section_miscellaneous">Miscellaneous</string>
    <string name="settings_item_licenses">Open source licenses</string>
    <string name="settings_item_licenses_title">Open Source Licenses</string>
```

---

## 6. Verification Plan

1. **Gradle Build Verification:** Run `./gradlew assembleDebug` to verify dependency resolution and manifest merging.
2. **Settings UI Verification:**
   * Verify the bottom "About Readylytics" button is gone.
   * Verify a new collapsible card "Miscellaneous" is present at the bottom of the Settings screen.
   * Verify "About Readylytics" inside the section opens the custom About screen.
   * Verify "Open source licenses" inside the section launches the Google OSS Licenses screen with the title "Open Source Licenses".
3. **Pre-Commit Verification:** Run `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` to make sure all formatting and tests pass.
