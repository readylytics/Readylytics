# Production Logging Strategy & Architecture (Release Builds Only)
**Date:** 2026-07-09  
**Status:** Approved  
**Author:** Principal Android Observability Architect  

---

## 1. Executive Summary
Readylytics is an offline-first Android health app that ingests user biometric data from Health Connect and stores it in an encrypted local database. Currently, all telemetry and debug logs are fully disabled in release/production builds (`BuildConfig.DEBUG == false`), meaning developers have zero observability when production or beta users encounter sync issues, scoring engine failures, database errors, or worker bugs.

This implementation plan outlines the introduction of a **Production Logging Strategy** targeting **release builds only**. The strategy ensures that:
1. Safe, non-sensitive operational logs are printed to Logcat in production.
2. Logs are mirrored to an encrypted, size-bounded local cache file, which is packaged inside the app's standard issue-reporting tool.
3. Strict privacy rules are programmatically and architecturally enforced, preventing any biometric data (HRV, RHR, steps, sleep duration) or calculated scores (Sleep, Readiness, TRIMP, RAS) from leaking into Logcat or issue reports.
4. Observability into critical subsystems (Health Connect Sync, Scoring Engine, WorkManager, Migrations) is drastically improved.

---

## 2. Current State Assessment & Shortcomings

### 2.1 Complete Observability Blackout
In `HealthDashboardApplication.kt`, the installed `DomainLogSink` wraps all calls to `Log.d`, `Log.w`, and `Log.e` in `if (BuildConfig.DEBUG)` blocks. 
*   **Result:** In production (release) builds, the logging sink is effectively a no-op.
*   **Shortcoming:** If a user experiences issues in production, captured Logcat files are completely empty of Readylytics runtime info, preventing diagnostics.

### 2.2 Latent Privacy Violations (If Logging Were Enabled)
If logging were simply toggled on for release builds under the current codebase, several severe privacy leaks would occur:
1.  **HRV and Vitals Dumps:** In `ComputeSleepMetricsUseCase.kt` (lines 148 and 466), the app logs the exact nocturnal `currentHrvMean` and a massive JSON payload called `debugPayload` containing raw biometric inputs (nocturnal RHR, sleep durations, HRV histories, active baselines, and computed sleep/readiness scores).
2.  **Workout Load Details:** In `ScoringRepositoryImpl.kt` (line 233), the app logs raw `DailyTrimp`, `DailyRas`, and 7-day cumulative athletic load metrics.
3.  **Database Details:** Database errors and exception handlers sometimes dump raw exception messages or column values to the log output.

---

## 3. Recommended Release Logging Architecture

We will implement a unified logging pipeline utilizing a **Scoped DSL** and a **Secure File Sink** to ensure both structured and protected logging.

```
                  ┌─────────────────────────────────────┐
                  │            DomainLogger             │
                  └──────────────────┬──────────────────┘
                                     │
                     Dispatched to installed sinks
                                     │
                  ┌──────────────────┴──────────────────┐
                  ▼                                     ▼
      ┌───────────────────────┐             ┌───────────────────────┐
      │   Standard Logcat     │             │  SecureFileLogSink    │
      │   (Release-Sanitised) │             │  (Encrypted local log)│
      └───────────────────────┘             └───────────┬───────────┘
                                                        │
                                            Writes to cache/logs/prod.txt
                                            (Rotated & size-capped)
                                                        │
                                                        ▼
                                            ┌───────────────────────┐
                                            │ LogcatCaptureStore    │
                                            │ (Decrypted on-demand) │
                                            └───────────────────────┘
```

### 3.1 Scoped Logging Interface & DSL Builder (`AppLog.kt` in `core:model`)
The logging API will support structured correlation tracking using an optional `LogContext` containing a `sessionId`. This allows grouping related logs (e.g., all steps of a resync worker execution).

```kotlin
package app.readylytics.health.domain.util

enum class LogLevel { INFO, WARN, ERROR }

data class LogContext(val sessionId: String? = null)

interface DomainLogSink {
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        context: LogContext = LogContext()
    )
}

object DomainLogger {
    @Volatile
    private var sink: DomainLogSink? = null

    fun installSink(newSink: DomainLogSink) {
        sink = newSink
    }

    fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable? = null,
        context: LogContext = LogContext(),
        msg: () -> String
    ) {
        sink?.log(level, tag, msg(), throwable, context)
    }
}

// Scoped DSL helper class
class ScopedLogger(val tag: String, val context: LogContext) {
    fun info(msg: () -> String) = DomainLogger.log(LogLevel.INFO, tag, context = context, msg = msg)
    fun warn(throwable: Throwable? = null, msg: () -> String) = DomainLogger.log(LogLevel.WARN, tag, throwable, context, msg)
    fun error(throwable: Throwable? = null, msg: () -> String) = DomainLogger.log(LogLevel.ERROR, tag, throwable, context, msg)
}

inline fun DomainLogger.scoped(
    tag: String,
    correlationId: String?,
    block: ScopedLogger.() -> Unit
) {
    val logger = ScopedLogger(tag, LogContext(correlationId))
    logger.block()
}

// Global top-level inline helpers
inline fun logI(tag: String, msg: () -> String) = DomainLogger.log(LogLevel.INFO, tag, msg = msg)
inline fun logW(tag: String, throwable: Throwable? = null, msg: () -> String) = DomainLogger.log(LogLevel.WARN, tag, throwable, msg = msg)
inline fun logE(tag: String, throwable: Throwable? = null, msg: () -> String) = DomainLogger.log(LogLevel.ERROR, tag, throwable, msg = msg)
```

### 3.2 Encrypted Size-Bounded Secure File Logger (`app` module)
In release builds, logs will be written to standard Logcat (filtered to `INFO`, `WARN`, `ERROR` levels) and mirrored to an encrypted file using the Android Jetpack Security library.

1.  **Storage Target:** Internal cache folder (`context.cacheDir/logs/prod_logs.txt`).
2.  **Encryption:** Using `EncryptedFile` from `androidx.security:security-crypto` with an AES256-GCM master key managed via the `AndroidKeyStore`.
3.  **Rotation Rules:**
    *   **Max File Size:** 500 KB per log file.
    *   **Retention:** 2 backup files (`prod_logs.txt.1` and `prod_logs.txt.2`).
    *   **Total Log Cap:** 1.5 MB maximum footprint.
    *   **Rollover Mechanism:** When `prod_logs.txt` exceeds 500 KB, it is renamed/shifted, and a new empty file is created.
4.  **Concurrency Model:** All file write operations are serialized through a dedicated single-threaded dispatcher (`Dispatchers.IO.limitedParallelism(1)`) to avoid write-locks and file corruption under high logging volume.

### 3.3 Logcat Capture Tool Refactoring
We will refactor `LogcatCaptureStoreImpl.kt` to bypass the system shell `logcat -d` command in release builds. Instead, it will:
1.  Read `prod_logs.txt` (and rotated files if present).
2.  Decrypt the file streams using `EncryptedFile`.
3.  Expose the unified text content to `LogcatCaptureViewModel` for issue packaging and display in `OversizedReportDialog`.

---

## 4. Privacy & Play Store Safety Rules

To satisfy Google Play Store health-data policies, the application must **never** log raw biometric measurements, physical records, or calculations in release builds.

### 4.1 Data Sanitisation Dictionary

| Category | Prohibited In Release Logs | Sanitisation Standard |
| :--- | :--- | :--- |
| **Sleep Metrics** | Sleep duration, latency, wake duration, staging times. | Log presence and completeness of sleep sessions.<br>*(e.g., `Sleep ingestion: sessionsCount=1, validMetrics=true`)* |
| **Vitals (HRV / RHR)** | Raw values, sample lists, rolling means, SDNN/RMSSD. | Log sample counts and baseline calibration indicators.<br>*(e.g., `HRV resolution: samplesCount=48, meanPresent=true`)* |
| **Workouts / Load** | Exercise name, heart rate samples, TRIMP scores, RAS factors. | Log count of workouts and active zones matched.<br>*(e.g., `Workouts fetched: count=2, model=Trimp`)* |
| **Database Rows** | Record ID keys, raw SQL insert objects, custom names. | Log table name and affected row count.<br>*(e.g., `Room Insert: table=sleep_metrics, rows=1`)* |
| **Exception Details** | Network stack traces, custom error strings containing local file paths or decrypted preferences. | Log the standard exception class name and a categorized error tag.<br>*(e.g., `DatabaseException [CORRUPTED]`)* |

### 4.2 Logging Examples: Bad vs. Good

*   ❌ **Bad (Leaks Sleep & HRV values):**
    ```
    ComputeSleepMetrics: Calculated sleep metrics. duration=452m, hrvMean=54.2ms, sleepScore=82
    ```
*   **Good (Sanitised):**
    ```
    ComputeSleepMetrics: Sleep calculations finished. TargetDate=2026-07-09, InputsPresent=true, BaselineCalibration=Calibrated
    ```

*   ❌ **Bad (Leaks workout Details):**
    ```
    ScoringRepository: Completed workout ingestion. SessionId=work_9281, trimpRaw=124.5, rasValue=1.35
    ```
*   **Good (Sanitised):**
    ```
    ScoringRepository: Ingested workout summary. TargetDate=2026-07-09, WorkoutsCount=1, MetricsComputed=true
    ```

---

## 5. Subsystem-by-Subsystem Logging Specifications

Each subsystem's release logs should reconstruct decision-flow, parameters, and statuses without numeric values.

### 5.1 Health Connect Ingestion & Sync
*   **Permission Checks:** Log checking steps and outcomes.
    *   `HealthConnect: Permissions evaluated. MissingCount=1, GrantedCount=3`
*   **Sync Window Resolution:** Log boundary windows determined by retention logic.
    *   `HealthConnectSync: Sync range resolved. StartDate=2026-07-08, EndDate=2026-07-09, SyncMode=DailySync`
*   **Paging & Processing:** Log chunks and ingestion success per chunk.
    *   `HealthConnectSync: Processing chunk. ChunkStart=2026-07-01, ChunkEnd=2026-07-09, RecordsFetched=45`
    *   `HealthConnectSync: Chunk complete. DurationMs=320, IngestionSuccess=true`
*   **Failure & Backoff:** Log rate-limit backoff events.
    *   `HealthConnectSync: Rate limit hit. RetryAttempt=1, BackoffMs=2000`

### 5.2 Scoring Engine
*   **Triggers:** Log why a scoring calculation has started.
    *   `ScoringEngine: Computation started. TargetDate=2026-07-09, Trigger=WorkManagerSync`
*   **Prerequisites Validation:** Log checking of inputs and dependencies.
    *   `ScoringEngine: Prerequisites validated. HasSleepSession=true, HrvSamplesCount=24, RhrSamplesCount=1`
*   **Baseline Calibration State:** Log if scoring is using active or calibrating baselines.
    *   `ScoringEngine: Baseline status. ActiveBaseline=true, CalibrationDays=14`
*   **Outcome:** Log duration and completion status.
    *   `ScoringEngine: Computation complete. Success=true, DurationMs=95`

### 5.3 Incremental Recalculation
*   **Target Scope Resolving:** Log target recalculation window.
    *   `Recalculator: Target window resolved. StartDate=2026-07-01, EndDate=2026-07-09, Reason=ResyncWorker`
*   **Session-Link Reconcile:** Log completion of the session mapping pass.
    *   `SessionReconciler: Completed. SleepSessionsLinked=2, WorkoutSessionsLinked=3, DurationMs=45`

### 5.5 Database & Migrations
*   **Migrations:** Log Room migrations starting and finishing.
    *   `Database: Migration starting. SourceVersion=4, TargetVersion=5`
    *   `Database: Migration finished. Success=true`
*   **Integrity / Corruption:** Log SQL database integrity failures.
    *   `Database: Corruption detected. Running fallback verification...`
*   **Data Cleanup:** Log retention-related deletes.
    *   `DatabaseCleanup: Purge complete. CutoffDate=2026-05-25, RecordsDeleted=14200`

### 5.6 Background Workers (WorkManager)
*   **Work Lifecycle:** Log work parameters and state transition.
    *   `Worker: PeriodicSync started. Attempt=1`
    *   `Worker: PeriodicSync completed. Result=Success, DurationMs=4800`

### 5.7 App Startup
*   **Timing Profiles:** Log system bootstrap phases.
    *   `Startup: Dependency injection graph ready. DurationMs=62`
    *   `Startup: Database decrypted and opened. KeyVerification=SUCCESS, FileSizeKB=4096`

### 5.8 Widgets
*   **Update Triggers:** Log widget update broadcasts.
    *   `WidgetProvider: Broadcast received. Trigger=DailySyncCompleted, WidgetId=41`
    *   `WidgetProvider: Render complete. DurationMs=34`

### 5.9 Insights Engine
*   **Insight Evaluations:** Log rule evaluations.
    *   `InsightsEngine: Evaluation started. RulesEvaluatedCount=8`
*   **Insight Generation:** Log generated categories (no values).
    *   `InsightsEngine: Insight updated. Category=SleepConsistency, Priority=High`

---

## 6. Performance Considerations

Production logging must impose negligible overhead to protect battery life and CPU usage:
1.  **Lazy String Evaluation:** Always use Kotlin's lambda-based loggers (`logI(TAG) { "message" }`) to prevent string construction and concatenation allocations when logging sinks are bypassed or disabled.
2.  **Log-Filter Gatekeeping:** Perform immediate log-level checks at the entry point of the `DomainLogger` before invoking any formatter.
3.  **Bypass Logging inside Loops:** Avoid placing log calls inside large database loops (e.g., mapping 1,440 heart rate samples per day). Instead, log high-level summaries after loop completion.
4.  **Asynchronous File Serialization:** Offload file logging disk writes to `Dispatchers.IO.limitedParallelism(1)` to ensure the main thread and UI performance are never impacted.

---

## 7. Roadmap & Prioritized Implementation Plan

```
 Phase 1: Core API & Scoped DSL   Phase 2: Secure File Logger      Phase 3: Subsystem Integration
 ──────────────────────────────   ──────────────────────────────   ──────────────────────────────
 • Create AppLog.kt structures.   • Build SecureFileLogSink.       • Sanitise & log Sync/Recalc.
 • Implement ScopedLogger DSL.    • Setup encryption & rotation.   • Sanitise & log Scoring.
 • Enforce via Static Unit Test.  • Refactor CaptureStoreImpl.     • Add logs to Workers/Startup.
```

### Phase 1: Core API & Scoped DSL (Estimated effort: 1-2 days)
1.  Add `LogLevel` and `LogContext` structures to `AppLog.kt` in `core:model`.
2.  Implement `DomainLogSink` and `DomainLogger` changes, including the `scoped` DSL helper block.
3.  Update the existing `ProductionReadinessStaticTest.kt` to permit the new logging structures, and verify unit tests continue to pass.

### Phase 2: Secure File Logger & CaptureStore Refactor (Estimated effort: 2-3 days)
1.  Implement `SecureFileLogSink` within the `app` module using `androidx.security:security-crypto`.
2.  Create unit tests validating file rotation (exceeding 500 KB rolls over correctly, capping files at 2 backups).
3.  Refactor `LogcatCaptureStoreImpl.kt` to decrypt and package the file logs instead of using shell commands in release.
4.  Install the new `SecureFileLogSink` inside `HealthDashboardApplication.kt` under release builds.

### Phase 3: Subsystem Integration & Sanitisation (Estimated effort: 3-4 days)
1.  Sanitise the identified vitals leaks in `ComputeSleepMetricsUseCase.kt` and `ScoringRepositoryImpl.kt`.
2.  Add safe production logs to Health Connect sync/resync processes.
3.  Integrate logging inside WorkManager workers and database migrations.
4.  Run all unit tests and static checks (`./gradlew testDebugUnitTest && ./gradlew lintRelease`).

---

## 8. Risks, Trade-offs, & Open Questions

### Risks & Mitigations
*   **Risk:** `EncryptedFile` performance overhead during high-volume sync writes.
    *   *Mitigation:* Single-threaded coroutine queue buffer on `Dispatchers.IO` handles all operations sequentially, guaranteeing zero main-thread impact.
*   **Risk:** Disk exhaustion due to unhandled log files.
    *   *Mitigation:* Retaining strict 500 KB limits with a maximum of 2 backups (hard limit 1.5 MB).

### Open Questions
1.  **Retention Boundaries:** Should log files be cleaned up automatically in parallel with the database retention policy (resolved by `RetentionBounds` settings), or is the simple file-size based rotation (capped at 1.5MB) sufficient?
    *   *Recommendation:* A simple size-bounded rotation is sufficient and more robust, preventing logs from bloating disk space regardless of user settings.
2.  **Beta Builds:** Should beta builds output regular logs or more detailed logging compared to release builds?
    *   *Recommendation:* Beta builds should use the exact same strict sanitisation policies as release builds to prevent leak vulnerabilities if beta logs are shared publicly.
