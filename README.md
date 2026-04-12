# MyHealthStatus 🩺

MyHealthStatus is a native Android health dashboard application that integrates directly with Huawei wearables via the **Huawei Health Kit SDK**. Rather than just showing raw numbers, the app calculates dynamic 30-day historical baselines to provide actionable, color-coded insights into your daily health metrics.

## ✨ Key Features

- **Native Huawei Integration:** Secure, seamless authentication using HMS Core Account Kit and on-device data syncing via Health Kit (No backend server required).
- **Smart Dashboard:** Daily overview of core metrics (HRV, SpO2, Resting Heart Rate, Skin Temperature, Sleep, Workouts).
- **Dynamic Baselines:** Calculates up to a 30-day rolling average to determine personal baselines.
- **Intelligent Color Coding:** Automatically highlights metrics in Red (negative deviation) or Green (optimal/positive deviation) based on your personal historical data.
- **Trend Analysis:** Detailed line/bar charts with 7-day, 30-day, 180-day, and 1-year interactive views.
- **Offline Caching:** Utilizes a local SQLite database (Room) to minimize API calls and allow fully offline viewing of historical data.
- **Data Portability:** Complete freedom over your health data with JSON Export and Restore capabilities via the Android Storage Access Framework.
- **Theme Support:** Full System, Light, and Dark mode integration.

## 🛠️ Tech Stack

- **Platform:** Android (100% Kotlin)
- **UI Framework:** Jetpack Compose
- **Architecture:** MVVM (Model-View-ViewModel) + Repository Pattern
- **Local Database:** Room (SQLite)
- **Preferences:** Preferences DataStore
- **SDKs:**
  - Huawei Mobile Services (HMS) Account Kit
  - Huawei Mobile Services (HMS) Health Kit
- **Charting:** [Vico](https://github.com/patrykandpatrick/vico) / MPAndroidChart

## 🚀 Getting Started

### Prerequisites

To build and run this app, you must configure it with the Huawei Developer Console:

1. Register a developer account at [Huawei Developers](https://developer.huawei.com/).
2. Create a project in **AppGallery Connect**.
3. Enable the **Health Kit** API for your project.
4. Generate an `agconnect-services.json` file and place it in the `app/` directory of this project.
5. Generate a signing certificate (Keystore), extract the SHA-256 fingerprint, and register it in AppGallery Connect.

### Installation

1. Clone this repository: `git clone https://github.com/yourusername/MyHealthStatus.git`
2. Open the project in Android Studio.
3. Sync Gradle and build the project.
4. Run on a physical Android device with Huawei Mobile Services (HMS Core) installed (Emulators may not support Health Kit auth flows reliably).

## 📁 Architecture Notes

This project adheres to a "Single Source of Truth" pattern.
The UI strictly observes the local Room Database. When a user requests a timeframe, the Repository calculates the delta between the requested dates and the fully cached dates (checked against an 11:59:59 PM timestamp rule) to ensure the Huawei SDK is only queried for missing or incomplete days.

## 📝 License

[MIT License](LICENSE)
