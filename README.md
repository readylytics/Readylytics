# Readylytics — Health Recovery Dashboard

![Readylytics Logo](docs/assets/img/logo.png)

[![GitHub stars](https://img.shields.io/github/stars/gregorlauritz/MyHealthStatus?style=social)](https://github.com/gregorlauritz/MyHealthStatus)
[![CI](https://github.com/gregorlauritz/MyHealthStatus/actions/workflows/ci.yml/badge.svg)](https://github.com/gregorlauritz/MyHealthStatus/actions/workflows/ci.yml)

An offline‑first Android app that turns health data from Android **Health Connect** into daily wellness scores (Sleep, Readiness, and Circadian Consistency).

For full details and user guides, please visit our [website](https://readylytics.com) or explore the local [docs/](docs/) directory.

---

## Key Features

- **Health Connect Ingestion:** Locally imports sleep, heart rate, HRV, and exercise data.
- **Offline-First scoring:** All physiological calculations run locally in Kotlin without network calls.
- **Material 3 UI:** Built using native Jetpack Compose components, support for dynamic color theming, and beautiful charts powered by Vico.
- **Secure Backups:** Local, user-controlled encrypted backups.

## Getting Started

### Prerequisites

- Android 8.0 (API 26) or higher
- Android Health Connect configured on-device
- Android Studio (latest stable version)

### Quick Setup

1. **Clone the repository:**

   ```bash
   git clone https://github.com/gregorlauritz/MyHealthStatus.git
   cd MyHealthStatus
   ```

2. **Open in Android Studio** and sync the project dependencies.
3. **Build & Install:**

   ```bash
   ./gradlew installDebug
   ```

## Development & Documentation

Detailed guidelines are available in:

- [Architecture & Data Flow Details](internal-docs/DATA_FLOW.md)
- [Release Signing Setup](internal-docs/RELEASE_SIGNING.md)
- [Privacy Policy](docs/privacy.md)

## License

This project is licensed under the [Apache License 2.0](LICENSE).
