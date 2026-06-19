# Health Recovery Dashboard

[![CI](https://github.com/gregorlauritz/MyHealthStatus/actions/workflows/ci.yml/badge.svg)](https://github.com/gregorlauritz/MyHealthStatus/actions/workflows/ci.yml)
[![Coverage](https://img.shields.io/badge/coverage-25%25%2B-brightgreen)](https://github.com/gregorlauritz/MyHealthStatus/actions/workflows/ci.yml)

An offline-first Android health and recovery dashboard that turns biometric data into actionable insights. Integrates with Android Health Connect to read sleep, heart rate, and workout data, then calculates three daily scores to answer: "How is your body doing today, and what should you do with that information?"

## Three Core Scores

The app tracks three evidence-based metrics:

- **Sleep Score (0-100):** Quality of last night's sleep, combining duration (50%), sleep architecture—deep and REM sleep (25%)—and physiological recovery markers like HRV and resting heart rate (25%).
- **Circadian Consistency (0-100):** Regularity of your sleep schedule. Measures how close bedtime and wake time stay to your median over the last 14 days.
- **Readiness (0-100):** How prepared your body is for today's training load, based on the acute-to-chronic workload ratio (Strain Ratio) from workout TRIMP calculations.

All three scores adapt to your physiological profile (Athlete, Active, or Sedentary) for fair, personalized interpretation.

## Key Features

- **Health Connect Integration:** Reads sleep, heart rate variability (HRV), resting heart rate (RHR), and exercise data from Android Health Connect.
- **Advanced Metrics:** Calculates TRIMP (Training Impulse), Strain Ratio (ACWR), and baselines using 30-day rolling medians.
- **Personalized Profiles:** Three profile options tune baselines and thresholds to match your lifestyle (sleep consistency targets, HRV sensitivity, etc.).
- **Material 3 UI:** Native Jetpack Compose design with semantic color roles (Success, Error, Tertiary) for health status indicators and dark mode by default.
- **Offline-First:** Local SQLite database (Room) stores all data; UI is driven entirely by local cache. Health Connect is purely a data source.
- **Smart Syncing:** Configurable foreground sync on app return (Never, Always, or by time interval up to 24 hours).
- **Detailed Charts:** Interactive Vico charts with 7-day, 30-day, 180-day, and 1-year views for trend analysis.
- **Workouts Tracking:** View workout history with intensity badges and ACWR trends.
- **Data Backup:** Encrypted local backup and restore for your app data.
- **Background Workers:** Daily sync and cleanup tasks via WorkManager.

## Getting Started

### Prerequisites

- Android 8.0 (API 26) or higher on your device
- Android Health Connect installed and configured with biometric data sources
- Android Studio (latest stable version)

### Installation

1. Clone the repository:

   ```bash
   git clone https://github.com/gregorlauritz/MyHealthStatus.git
   cd MyHealthStatus
   ```

2. Open in Android Studio.

3. Sync Gradle and download dependencies.

4. Create a `local.properties` file in the project root with:

   ```properties
   sdk.dir=/path/to/Android/sdk
   ```

5. Build and run:
   ```bash
   ./gradlew installDebug
   ```

## Architecture

This project follows clean architecture and MVVM patterns:

- **Single Source of Truth:** UI observes the local Room database exclusively. Health Connect is a data ingestion source only.
- **Repository Pattern:** Data access is abstracted behind repositories (e.g., `SleepRepository`, `WorkoutRepository`).
- **ViewModels:** Use `StateFlow` and `SharedFlow` for reactive state management.
- **Dependency Injection:** Hilt handles all service and module injection.
- **Algorithm Layer:** Pure Kotlin business logic (score calculations, baselines) is decoupled from the Android framework for easy testing.

### Tech Stack

- Language: Kotlin (JVM target 17)
- UI Framework: Jetpack Compose + Material 3
- Local Database: Room Database (SQLite with KSP code generation)
- Dependency Injection: Hilt
- State Management: StateFlow, SharedFlow
- Background Work: WorkManager
- Backup: Encrypted local backup and restore
- Charting: Vico
- Preferences: DataStore
- Data Sync: Health Connect API

### Project Structure

```
app/src/main/
  java/com/gregor/lauritz/healthdashboard/
    data/
      local/          # Room database entities and DAOs
      remote/         # Health Connect adapters
      repository/     # Data repositories
    domain/
      model/          # Domain models
      calculator/     # Pure business logic (scores, baselines)
    ui/
      dashboard/      # Main dashboard screen
      sleep/          # Sleep details
      hrv/            # Heart rate variability
      rhr/            # Resting heart rate
      workouts/       # Workout history and ACWR
      about/          # Educational content
      scaffold/       # Navigation and main layout
      theme/          # Material 3 theme and colors
    workers/          # Background tasks
```

## Notes

- **Offline First:** All calculations run locally. No internet required after syncing from Health Connect.
- **Calibration:** Scores are grayed out until at least 7 days of data is available for proper baseline calculation.
- **Wellness, Not Medical:** Sleep stages and HRV are wearable estimates with measurement error. Scores are wellness indicators, not clinical diagnoses.
- **Privacy:** All data is stored locally on your device. Local backups are encrypted and controlled by you. See [Privacy Policy](docs/privacy.md).

## License

[Apache License 2.0](LICENSE)
