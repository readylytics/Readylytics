# SPEC: Data Retention & Health Connect Resync

## §G: Goal
Add user-configurable data retention window (1y default, 180d–3y range, disableable) + manual Health Connect resync (drop & reload 60d). Daily auto-cleanup deletes expired data.

## §C: Constraints
- Offline-first: Room DB is SSOT, no remote sync except HC ingestion.
- MVVM + Clean Architecture; state via StateFlow in VM.
- Material 3 UI (Compose).
- WorkManager for background cleanup (daily check).
- DataStore (Preferences) stores retention config.
- No data loss on retention change — only forward deletions.
- Resync clears HC-sourced data only (preserves manual entries if present).
- Retention range: 180d (min) to 1095d (3y max), or "unlimited" (disabled).

## §I: External Surfaces
- **Settings UI screen**: retention period toggle/slider + resync button.
- **DataStore**: `retentionDaysEnabled` (bool), `retentionDays` (int: 180–365).
- **WorkManager**: daily `DataCleanupWorker` task.
- **Room DB queries**: bulk delete by date range (Sleep, HeartRate, HRV, Exercise).
- **Health Connect API**: resync queries (last 60d).

## §V: Invariants
- **V1**: Retention setting always: `disabled` OR `180 ≤ days ≤ 1095`. UI slider increments by 30d steps. Enforces range.
- **V2**: Resync clears HC-sourced tables (Sleep, HeartRate, HRV, Exercise) only; re-queries HC for last 60d.
- **V3**: Daily cleanup task runs (WorkManager) and only deletes records `createdAt < now - retentionDays`.
- **V4**: Disabling retention halts cleanup; re-enabling does not backfill or retroactive-delete.
- **V5**: Resync in progress blocks UI (shows spinner/progress); fails gracefully if HC unavailable.

## §T: Tasks

| id | status | title | cites |
|----|--------|-------|-------|
| T1 | x | Add retention config to DataStore schema | V1 |
| T2 | x | Build Settings UI: retention toggle + resync button | V1,I.ui |
| T3 | x | Implement resync VM logic (clear tables, re-query HC 60d) | V2,I.api |
| T4 | x | Implement DataCleanupWorker (daily, check & delete old records) | V3,V4,I.worker |
| T5 | x | Add unit tests: retention range validation, delete-by-date | V1,V3 |
| T6 | x | Test Settings UI flows (toggle, slider, resync button) | T2,T3 |

## §B: Bugs

| id | date | cause | fix |
|----|------|-------|-----|
