# Startup Readiness Regression Design

**Date:** 2026-07-25  
**Status:** Approved

## Problem

`HealthDashboardApplication` eagerly injects `SettingsRepository`. Constructing that repository
constructs `UIPreferences`, then `HealthDeviceRepository`, then Room DAOs. On a database requiring
the external v7 migration, Hilt therefore requests `HealthDatabase` while creating the
`Application`, before the readiness controller can start migration. `DatabaseModule` correctly
rejects that premature open, causing process startup to crash.

## Design

Inject `dagger.Lazy<SettingsRepository>` into `HealthDashboardApplication` and pass that lazy
dependency to `DatabaseReadyStartupInitializer`.

The initializer must not call `settingsRepository.get()` for any non-Ready state. After
`DatabaseReadiness.Ready`, it resolves the repository once within the existing guarded
initialization attempt and reads backup/background-sync settings as before. Existing cancellation,
bounded retry, and one-shot scheduling behavior remain unchanged.

This keeps the fix at the application readiness boundary. It avoids a broader preferences
refactor and preserves the existing database guard.

## Regression Test

Extend `DatabaseReadyStartupInitializerTest` with a mocked `Lazy<SettingsRepository>`.

- Migration-required initialization must not resolve settings, sync, or backfill lazies.
- Ready initialization resolves the settings lazy and performs the existing schedules.
- Retry and cancellation tests continue to prove the guard resets correctly.

Add a static production-graph assertion that the application field is
`Lazy<SettingsRepository>`, preventing accidental eager injection from recurring.

## Verification

Run focused startup tests, Hilt/KSP generation, inspect the generated members injector for
`DoubleCheck.lazy(settingsRepositoryProvider)`, then run formatting, the full debug unit suite,
and release lint.

The repository has no `SPEC.md`, so the backprop §B/§V ledger cannot be amended. The executable
startup and generated-graph regression tests carry the invariant instead.
