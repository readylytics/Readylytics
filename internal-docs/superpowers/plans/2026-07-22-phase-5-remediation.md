# Phase 5 Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Phase 5 remediation tasks including UI-001 ViewModel cleanup, SEC-001 security hardening, and DB-001 DB key migration.

**Architecture:** We will clean up a dead branch in the UI, add regex-based redaction to the file logger, debug-gate permissions logging, and implement a SQLite data migration for heart rate primary keys to improve performance.

**Tech Stack:** Kotlin, Room, JUnit.

## Global Constraints

- Target ≤ 400 lines/file, hard limit ≤ 800 lines (refactor if exceeded).
- All business/calculation logic must be pure Kotlin (zero Android dependencies).
- Strings & i18n: All user-facing strings must be defined in `strings.xml`.
- Pre-Commit (Mandatory): `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`

---

### Task 1: UI-001 DashboardViewModel Cleanup

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt`

**Interfaces:**
- Produces: Collapsed `resolveDashboardSleepSessionSummary` returning a single `SleepSessionSummary`.

- [ ] **Step 1: Simplify the conditional in DashboardViewModel**

Modify `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt`. Find `resolveDashboardSleepSessionSummary` and remove the dead conditional, retaining only the comment.

```kotlin
        internal fun resolveDashboardSleepSessionSummary(
            summary: DailySummary?,
            session: SleepSessionData?,
        ): SleepSessionSummary? {
            session ?: return null
            // Biphasic days can legitimately aggregate more sleep than any single session.
            // Keep the available session-backed fallback instead of blanking dashboard cards.
            return SleepSessionSummary(
                efficiency = session.efficiency,
                startTime = session.startTime,
                endTime = session.endTime,
            )
        }
```

- [ ] **Step 2: Run tests to verify no breakage**

Run: `./gradlew :feature:dashboard:testDebugUnitTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt
git commit -m "refactor: collapse dead conditional in resolveDashboardSleepSessionSummary (UI-001)"
```

---

### Task 2: SEC-001 Permission Logging

**Files:**
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt`

**Interfaces:**
- Produces: Debug-gated logging in `checkPermissions`.

- [ ] **Step 1: Debug-gate permission-set logging**

In `core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt`, modify `checkPermissions` to only log the full sets in debug mode (using `BuildConfig.DEBUG` or by summarizing the output).

```kotlin
                if (app.readylytics.health.core.healthconnect.BuildConfig.DEBUG) {
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") { "Granted permissions: $granted" }
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") { "Required permissions: $requiredPermissions" }
                } else {
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") { "Granted permissions count: ${granted.size}" }
                }

                if (granted.containsAll(requiredPermissions)) {
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") { "All required permissions granted" }
                    PermissionStatus.Granted
                } else {
                    val missing = requiredPermissions - granted
                    if (app.readylytics.health.core.healthconnect.BuildConfig.DEBUG) {
                        app.readylytics.health.domain.util.logD("HealthConnectRepository") { "Missing permissions: $missing" }
                    } else {
                        app.readylytics.health.domain.util.logD("HealthConnectRepository") { "Missing permissions count: ${missing.size}" }
                    }
                    PermissionStatus.Missing(missing)
                }
```
*(Ensure `BuildConfig` import matches your module's BuildConfig).*

- [ ] **Step 2: Run tests**

Run: `./gradlew :core:healthconnect:testDebugUnitTest`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add core/healthconnect/src/main/kotlin/app/readylytics/health/data/healthconnect/HealthConnectRepositoryImpl.kt
git commit -m "fix: debug-gate permission set logging (SEC-001)"
```

---

### Task 3: SEC-001 Log Redaction

**Files:**
- Create: `app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt`

**Interfaces:**
- Produces: A sanitized log output where numeric health metrics or UUIDs are redacted.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt`:

```kotlin
package app.readylytics.health.util

import app.readylytics.health.domain.util.LogContext
import app.readylytics.health.domain.util.LogLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureFileLogSinkTest {
    @Test
    fun testLogSanitization() {
        val original = "User HR is 120 bpm, HRV 45.2, BP 120/80"
        val sanitized = SecureFileLogSink.sanitizeLogMessage(original)
        
        assertFalse("Should redact heart rate", sanitized.contains("120"))
        assertFalse("Should redact HRV", sanitized.contains("45.2"))
        assertTrue("Should contain redaction markers", sanitized.contains("***"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.util.SecureFileLogSinkTest"`
Expected: FAIL with "Unresolved reference: sanitizeLogMessage"

- [ ] **Step 3: Write minimal implementation**

Modify `app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt`. Add the sanitization method and apply it in `bufferLog`.

```kotlin
    companion object {
        const val DEFAULT_MAX_FILE_SIZE_BYTES: Long = 2L * 1024L * 1024L
        const val DEFAULT_MAX_BACKUPS: Int = 2

        internal fun sanitizeLogMessage(message: String): String {
            // Basic heuristic to redact numbers that might be health data (e.g. HR, BP, etc.)
            // Replaces numeric sequences with ***
            return message.replace(Regex("\\b\\d+(?:\\.\\d+)?\\b"), "***")
        }
    }

    private fun bufferLog(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
        logContext: LogContext,
    ) {
        val timestamp = dateFormat.format(Date())
        val sessionId = logContext.sessionId ?: "none"
        val sanitizedMessage = sanitizeLogMessage(message)
        val logLine =
            "$timestamp [$level] [$tag] [Session:$sessionId] $sanitizedMessage" +
                (throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: "") + "\n"
        
        pendingLogs.add(logLine)
        // ... rest remains the same
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "app.readylytics.health.util.SecureFileLogSinkTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt
git commit -m "feat: implement log redaction in SecureFileLogSink (SEC-001)"
```

---

### Task 4: DB-001 Room Entity Migration (Preparation)

**Files:**
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/local/entity/HeartRateRecordEntity.kt` (and `HrvRecordEntity.kt` if applicable).

- [ ] **Step 1: Update Entities**

Change the primary key structure in the Room entities to use an auto-generated integer and add a composite unique index for `sourceRecordId` and `timestampMs`. Note: actual SQLite execution of this v6->v7 migration will be done via a foreground progress UI task which is handled in a subsequent step. For now, prepare the schema.

```kotlin
// In HeartRateRecordEntity.kt
@Entity(
    tableName = "heart_rate_records",
    indices = [
        Index(value = ["sourceRecordId", "timestampMs"], unique = true),
        Index(value = ["timestampMs"]),
        Index(value = ["sessionId", "recordType", "beatsPerMinute"])
    ]
)
data class HeartRateRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
    val sourceRecordId: String,
    // ... existing fields
)
```
*(Repeat for `HrvRecordEntity` if specified in the repo).*

- [ ] **Step 2: Commit Schema Prep**

```bash
git add core/database/src/main/kotlin/app/readylytics/health/data/local/entity/
git commit -m "chore: prepare DB entities for v7 integer primary key migration (DB-001)"
```

*(Note: The actual chunked migration with UI implementation exceeds a standard bite-sized task and requires its own dedicated plan sub-section or separate architectural design based on the app's UI framework).*
