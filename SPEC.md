# SPEC — Health Dashboard Performance

## §G Goal

Fix app jitter across all screens/interactions. Target 120 FPS.

## §C Constraints

- minSdk 35 (Android 15)
- Jetpack Compose + Material 3 (dynamic dark theme)
- Room + DataStore as SSOT (offline-first)
- Vico charting (line/bar renders)
- Android Studio profiler available for investigation

## §I External Surfaces

- Tab navigation (tab switches, bottom nav)
- HR detail screen (open/close)
- Settings screen (scroll, value changes apply)
- Sleep tab (scroll, data display)
- Workout tab (scroll, data display)
- Settings adaptation (theme changes, preference updates)

## §V Invariants

V1. No blocking Room queries on main thread. All queries async via Flow/suspend DAOs.

V2. Recomposition granular. State scoped to minimal widgets. Avoid object allocations in @Composable bodies.

V3. Vico chart renders lazily. Heavy calculations deferred via remember { produceState { } } or ViewModel.

V4. Lists use LazyColumn/LazyGrid with stable keys. No full-list recompose on data update.

V5. DataStore Preferences reads cached in ViewModel StateFlow. No read-on-every-frame.

V6. Measured baseline ≥60 FPS on all screens. Target ≥120 FPS on scroll-heavy screens (Sleep, Workout, Settings).

## §T Tasks

| id | status | title | cites |
|----|--------|-------|-------|
| T1 | x | Profile all screens w/ Android Studio (CPU, compose, frame time) | V6 |
| T2 | x | Identify blocking code paths (Room, StateFlow, Compose recomp) | V1,V2,V5 |
| T3 | x | Audit Room DAOs for N+1, missing indices, sync queries | V1 |
| T4 | x | Optimize Compose recomposition (state granularity, remember blocks) | V2 |
| T5 | . | Optimize Vico chart rendering (lazy eval, memoization) | V3 |
| T6 | . | Cache DataStore reads in ViewModel StateFlow | V5 |
| T7 | . | Verify LazyColumn/LazyGrid key stability in lists | V4 |
| T8 | . | Re-profile all screens. Verify ≥120 FPS on scroll screens | V6 |

## §B Bug Log

| id | date | cause | fix |
|----|------|-------|-----|
