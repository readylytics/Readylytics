# Phase 5 Remediation Design Spec

## Overview
This document outlines the design and implementation approach for Phase 5 of the Architecture, Health Connect, Performance & Scoring Engine Remediation Plan. It covers Database optimizations (DB-001), UI cleanup (UI-001), and Security hardening (SEC-001).

## 1. DB-001: DB Key Migration
* **Architecture:** We will create a chunked, resumable Room migration (v6 to v7) for the `heart_rate_records` (and `hrv_records` if applicable) table.
* **Data Flow:** The heavy `TEXT` primary key (`"${hcUuid}_${timestampMs}"`) will be replaced with a lightweight `INTEGER PRIMARY KEY`. The old composite fields will be moved to a `UNIQUE` index to preserve upsert idempotency.
* **UI Component:** A foreground progress UI will be implemented to block the app and show migration progress, as copying millions of rows will take time.

## 2. UI-001: ViewModel Cleanup
* **Implementation:** The dead conditional in `DashboardViewModel.resolveDashboardSleepSessionSummary` will be collapsed into a single return statement. 
* **Maintainability:** The original comment explaining the fallback behavior for biphasic days will be preserved.

## 3. SEC-001: Security Hardening
* **Testing & Redaction:** We will write a unit test suite for `SecureFileLogSink` that pushes representative log lines (health values, paths, etc.) and asserts they are redacted. We will then implement the redaction logic in `SecureFileLogSink` to make this test pass, ensuring no health values leak into user-shareable logs.
* **Permission Logging:** We will wrap the permission-set logging in `HealthConnectRepositoryImpl.checkPermissions` so that release builds only log the boolean outcome (`Granted` / `Missing(n)`), while keeping detailed sets in debug builds.
* **Documentation:** We will update `docs/privacy.md` (or the relevant doc) noting that DataStore protos do not need explicit exclusion because the app already blanket-excludes all files from cloud backups.
