# Idea: Android Material 3 Glance Home Screen Widgets

> **Status:** CONCEPT / BACKLOG — Captured 2026-09-03.

## 1. Problem Statement & Motivation

Readylytics is a daily health & recovery dashboard. In current usage, a user must explicitly open the app each morning or after a workout to see their:
- Readiness score
- Sleep score & duration
- Circadian consistency
- Today's strain / TRIMP / steps

**The Opportunity:** Providing Android Home Screen widgets using Jetpack Glance (Compose for RemoteViews) provides at-a-glance access to recovery and strain metrics right on the user's home screen, adhering to Readylytics' offline-first, privacy-respecting design.

---

## 2. Proposed Widget Types & Form Factors

### 2.1 2x2 Recovery Glance (Compact)
- Circular M3 Readiness gauge (or score number with color container).
- Secondary row: Sleep score + Sleep duration (e.g., "7h 42m").
- Tap action: Deep links directly to Dashboard tab.

### 2.2 4x2 Daily Strain & Recovery (Standard)
- Left pane: Readiness gauge + Calibration/Phase status badge.
- Middle pane: Sleep score breakdown (Deep + REM percentages).
- Right pane: Today's Strain / TRIMP progress vs. daily target, plus step count.
- Tap action: Directly opens relevant tab (Readiness → Dashboard, Sleep → Sleep tab, Strain → Workouts tab).

### 2.3 4x1 Minimal Vitals Strip
- Horizontal strip displaying:
  - Resting HR (today vs. baseline)
  - Nocturnal HRV lnRMSSD
  - Overnight SpO2 / Skin Temp deviation
  - Daily steps

---

## 3. Technical Architecture & Offline Guarantees

### 3.1 Jetpack Glance Integration
- Use `androidx.glance:glance-appwidget` and `androidx.glance:glance-material3`.
- Follow strict M3 dynamic color palette (`GlanceTheme`) to blend seamlessly with Android 14+ Material You theming.

### 3.2 Update Mechanism
- **Event-Driven:** Whenever `DailySyncUseCase`, `HealthResyncWorker`, or workout ingestion commits a new `DailySummaryEntity`, broadcast an update via `GlanceAppWidgetManager.updateAll()`.
- **Periodic Fallback:** WorkManager periodic task or Glance periodic update (15–30 min) to tick residual fatigue / live everyday load.
- **Security & Keystore:** Widgets read pre-formatted presentation DTOs or snapshot tables, ensuring zero sensitive key leakage outside process boundaries.
