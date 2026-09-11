# Release Diagnostics and Restore Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close release-output and destructive restore/backup failure paths before changing ingestion.

**Architecture:** Sanitize diagnostic events before fan-out, validate archive inventory before replacement, and postpone pruning until verified publication. Keep full operation recovery in the recovery plan.

**Tech Stack:** Existing Kotlin/Compose/Room/SQLCipher/Health Connect/WorkManager/DataStore modules; JUnit, MockK, existing Robolectric adapters, and instrumented Room/benchmark fixtures. No dependency upgrades or new Gradle modules.

**Spec:** `internal-docs/plans/ARCHITECTURE_HEALTH_DATA_SCORING_REMEDIATION_PLAN.md`, WP-02, WP-03; SEC-001, SEC-004, SEC-005. Read it and [the execution index](2026-09-11-remediation-phase-0-1-index.md) before this plan. Finding IDs refer to that spec, not similarly named historical source comments.

## Global Constraints

- "Do not use restricted experimental deduplication internals or require an SDK upgrade for this plan."
- "Transient failures propagate as typed errors/exceptions and cancellation remains cancellation."
- "SDK read/retry/enrichment happens outside Room transactions."
- "Never uninstall `app.readylytics.health` to solve a migration or signing conflict."
- Repository floors: minSdk=26, compile/targetSdk=37; Health Connect 1.1.0; current Room schema 19 at inspected HEAD `93c468b2`.
- Room is the UI source of truth; calculations remain pure Kotlin. Preserve current-day refresh, `RetentionBounds`, full-range session reconciliation, WorkManager `KEEP`, and existing progress channels.
- Preserve formulas, coefficients, profiles and sleep/source-selection policies. Decision-gated proposals below are not permission to change product semantics.
- Follow the index's documentation matrix, mandatory pre-commit commands, migration ledger, file limits (target ≤400, hard ≤800), and indexing rules. No new suppressions or baseline edits.

---

### Task S1: Sanitize diagnostics before any release sink (WP-02)

**Files and responsibilities:**
- Modify: `app/src/main/kotlin/app/readylytics/health/util/SecureFileLogSink.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/crashreport/CrashReportStoreImpl.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/crashreport/CachePrune.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/crashreport/CrashReportFormatter.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/util/SafeDiagnosticFormatter.kt` — Pure reason codes and bounded exception class/frame formatting
- Create: `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/util/SafeDiagnosticFormatterTest.kt` — new behavior test
- Test: `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/crashreport/CrashReportFormatterTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/util/SecureFileLogSinkTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/crashreport/CachePruneTest.kt`

**Interfaces:** Produces `DiagnosticReason { OPERATION_FAILED, PERMISSION_DENIED, BACKUP_FAILED, RESTORE_FAILED, LOG_WRITE_FAILED }` and `safeDiagnostic(reason: DiagnosticReason, failure: Throwable?): String`. Existing `DomainLogSink.log(...)` remains usable: its arbitrary message/tag/session fields are never forwarded by the release adapter. Counts/durations can later use specifically typed approved fields; no arbitrary string map.

- [ ] **Step 1: Add the synthetic payload corpus test.**

```kotlin
@Test
fun `release diagnostics omit nested payloads`() {
    val corpus = listOf("bpm=187", "rmssd=94.7", "52.5200,13.4050",
        "source_private_123", "content://private/tree/secret", "backup-secret")
    val root = IllegalStateException(corpus.joinToString(";"), IllegalArgumentException(corpus[3]))
    root.addSuppressed(java.io.IOException(corpus[4]))
    val text = safeDiagnostic(DiagnosticReason.RESTORE_FAILED, root)
    corpus.forEach { assertFalse(text.contains(it)) }
    assertTrue(text.contains("RESTORE_FAILED"))
    assertTrue(text.contains("java.lang.IllegalStateException"))
    assertTrue(text.contains("SafeDiagnosticFormatterTest"))
}
```

Also test cyclic causes, suppressed cycles and very deep chains so error reporting cannot recurse/OOM. In `SecureFileLogSinkTest`, capture logcat and decrypted file output for the same corpus in message, tag, session ID and Throwable; assert all destinations omit it. In the crash formatter fixture assert the original exception message is absent.

- [ ] **Step 2: Run the targeted tests red.**

```bash
./gradlew :core:model:testDebugUnitTest --tests '*SafeDiagnosticFormatterTest' --tests '*CrashReportFormatterTest'
./gradlew :app:testDebugUnitTest --tests '*SecureFileLogSinkTest' --tests '*CachePruneTest'
```

Expected: missing new formatter initially; after adding its declaration, current logcat/crash paths still expose the corpus. Do not count a compiler failure alone as proof that old sink behavior was characterized.

- [ ] **Step 3: Implement the formatter and make sink fan-out accept only its output.**

```kotlin
enum class DiagnosticReason {
    OPERATION_FAILED, PERMISSION_DENIED, BACKUP_FAILED, RESTORE_FAILED, LOG_WRITE_FAILED,
}

fun safeDiagnostic(reason: DiagnosticReason, failure: Throwable?): String = buildString {
    appendLine(reason.name)
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val pending = java.util.ArrayDeque<Throwable>()
    failure?.let(pending::add)
    var remaining = 16
    while (pending.isNotEmpty() && remaining-- > 0) {
        val next = pending.removeFirst()
        if (!seen.add(next)) continue
        appendLine(next.javaClass.name)
        next.stackTrace.take(32).forEach { frame ->
            appendLine("at ${frame.className}.${frame.methodName}:${frame.lineNumber}")
        }
        next.cause?.let(pending::add)
        next.suppressed.take(8).forEach(pending::add)
    }
}
```

Frame/class names come from actual throwables, never messages or `toString()`. Do not include arbitrary file paths. In `SecureFileLogSink.log`, compute the safe string synchronously before launching file work; call `Log.println(priority, "Readylytics", safeText)` with no Throwable overload. Buffer that same text; remove raw message/session interpolation. The sink's own failure path uses `LOG_WRITE_FAILED` and the same formatter. Keep DEBUG filtering before lambda evaluation.

`formatCrashReport` keeps approved timestamp/app version/SDK metadata and uses this formatter for Throwable output. Audit raw `Log.*`, `printStackTrace`, `stackTraceToString`, audit detail and report export paths with `rg`; replace release payload paths with reason codes. Debug-only developer logging can retain its existing build gate.

- [ ] **Step 4: Retire old unsafe cache content and update disclosures.**

Use a new diagnostic cache version/directory, clean only the exact previous app-owned log/crash/export slots before new release logging/share initialization, and retain no decrypt-and-share fallback for old unsafe logs. Add a fixture with legacy slots plus an unrelated file; legacy slots disappear, unrelated file survives. Reuse `CachePrune` lifecycle integration and preserve explicit user-initiated sharing.

Update `docs/index.md`, `docs/about.md`, `docs/privacy.md` and in-app About strings: release diagnostics exist locally, exclude arbitrary message payloads, and leave the device only when explicitly shared. Add the safe-field policy to `internal-docs/DATA_FLOW.md`; no telemetry/network change.

- [ ] **Step 5: Verify all release destinations and commit.**

Run the targeted suites then shared gates. On an isolated release-like build, inject the synthetic corpus, inspect logcat, decrypt/export diagnostics through the app, and create/share-preview a crash report without sending it. Check class/frame usefulness and absence of every corpus string. `codegraph index`; suggested message: `fix: sanitize release diagnostics before dispatch`.

### Task S2: Validate complete restore inventory and replacement (WP-03)

**Files and responsibilities:**
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalRestoreManager.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/RestoreBatchLoader.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/RestoreVitalsLoader.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupModels.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/backup/BackupInventoryPolicy.kt` — Pure schema inventory/count rules
- Create: `app/src/main/kotlin/app/readylytics/health/data/backup/RestoreInventoryValidator.kt` — Streaming count and duplicate-key validation
- Create: `core/model/src/test/kotlin/app/readylytics/health/core/model/domain/backup/BackupInventoryPolicyTest.kt` — new behavior test
- Test: `app/src/test/kotlin/app/readylytics/health/data/backup/LocalRestoreValidationTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/backup/LocalRestoreApplicationTest.kt`
- Create: `app/src/androidTest/kotlin/app/readylytics/health/data/backup/RestoreRollbackInstrumentedTest.kt` — new behavior test

**Interfaces:** Produces `BackupInventoryPolicy.requiredTables(version: Int): Set<String>` and `validateInventory(required: Set<String>, declared: Map<String, Long>, observed: Map<String, Long>): Unit`. `RestoreInventoryValidator` runs a read-only validation pass before application; application still validates inside a real Room transaction before commit. Existing `RestoreResult` remains the public result.

- [ ] **Step 1: Add malformed archive tests to the existing restore fixture.**

```kotlin
@Test
fun validate_rejectsMissingRequiredTable() = runTest {
    val payload = createValidBackupJson().apply { remove("heartRateRecords") }
    val archive = createBackupZipFile("missing-heart-rate.zip", payload)
    assertTrue(manager.validate(android.net.Uri.fromFile(archive)).isFailure)
}
```

After seeding `session_1` with the existing valid-archive helper, run `applyRestore` with missing core arrays, duplicate top-level keys/source IDs, negative counts, mismatched counts, missing source FK and corrupt/truncated JSON. Assert `RestoreResult.Failure` and the original session/data checksums unchanged. Add legacy omission tests that seed current vital rows first and prove absent legitimate old arrays become empty after successful restore.

- [ ] **Step 2: Run the failure and establish historical format evidence.**

```bash
./gradlew :app:testDebugUnitTest --tests '*LocalRestoreValidationTest' --tests '*LocalRestoreApplicationTest'
git log --all --oneline -- app/src/main/kotlin/app/readylytics/health/data/backup/BackupStreamWriter.kt
```

Use this concrete conservative inventory, based on inspected historical writers: v5 (`3ceabd0d`) and v7 (`596986ad`) exported the five core arrays; the v9-era writer gained vitals in `ade96233` without a version bump; v10 (`01c944f7`) exported source/warm/vitals, v11 (`ac49bf24`) added routes, and current v19 (`28174241` and HEAD) exports VO2. Do not require vitals for every v9 archive merely because a later v9 writer included them. For legacy optional arrays, a declared count makes their actual presence/count mandatory even when the minimum inventory does not.

```kotlin
object BackupInventoryPolicy {
    private val core = setOf("sleepSessions", "heartRateRecords", "hrvRecords", "workouts", "dailySummaries")
    private val vitals = setOf("weightRecords", "bodyFatRecords", "bloodPressureRecords",
        "oxygenSaturationRecords", "bodyTemperatureRecords", "stepRecords")
    fun requiredTables(version: Int): Set<String> = buildSet {
        require(version >= 5)
        addAll(core)
        if (version >= 10) { addAll(vitals); add("healthSourceRecords"); add("hrMinuteBuckets") }
        if (version >= 11) add("workoutRoutePoints")
        if (version >= 19) add("vo2MaxRecords")
    }
}
```

Run existing `BackupSchemaPolicy.requireSupported` first for the maximum version. v18 is accepted conservatively with optional VO2 unless its count declares it; current v19 requires it. P1/T2 extend new-format requirements for their explicit archive metadata/tables. Preserve actual v5/v7/v9-before-fix/v9-after-fix/v10/current fixtures, not merely a current payload with a changed version number.

- [ ] **Step 3: Implement count/presence validation with safe failures.**

```kotlin
fun validateInventory(
    required: Set<String>,
    declared: Map<String, Long>,
    observed: Map<String, Long>,
) {
    require(observed.keys.containsAll(required)) { "BACKUP_REQUIRED_TABLE_MISSING" }
    require(declared.keys.containsAll(required)) { "BACKUP_REQUIRED_COUNT_MISSING" }
    require(declared.values.all { it >= 0L }) { "BACKUP_COUNT_INVALID" }
    require(observed.all { (table, count) -> declared[table] == count }) { "BACKUP_COUNT_MISMATCH" }
    require(declared.keys.all { it in observed }) {
        "BACKUP_DECLARED_TABLE_MISSING"
    }
}
```

Track encountered top-level names separately and reject duplicates even if counts match. Count actual decoded entries with Long counters. Current archives require all writer arrays; legacy optional omission means zero/default, never preserve current rows. Validate source identity uniqueness and child FKs in staging/application using real indexed tables rather than retaining a million-ID heap set. Required v10+ source rows load before HR/HRV; for older formats keep the existing version-specific decoder and exact timestamp-suffix removal.

- [ ] **Step 4: Centralize replacement and rollback.**

Move all table clearing to one child-before-parent replacement block in `LocalRestoreManager`: routes, sleep stages, HR/HRV, warm buckets, vitals/steps/VO2, summaries, sleep/workout parents, then source records. Remove conditional `deleteAll()` calls from `RestoreVitalsLoader`. Keep insight dismissals/audit records under explicitly documented ownership; do not infer they are missing health arrays. Re-run observed-count validation plus `PRAGMA foreign_key_check` before transaction return. Preferences apply only after validated DB commit; full maintenance coordination is R3.

The no-blanket-delete ingestion rule does not prohibit a user-requested, validated, transactional full restore. Failed parsing/count/FK validation must roll back all replacement tables. Stream in stable parent-before-child application order independently of JSON field order, using additional ZIP passes or staging; no full JSON materialization.

- [ ] **Step 5: Verify compatibility, document and commit.**

Run pure policy tests, restore suites and the real Room rollback fixture. Update DATA_FLOW restore boundaries, `docs/index.md`, `docs/about.md`, `docs/privacy.md`, `docs/backup-and-data.md`, and resource-based restore errors. Run shared gates and `codegraph index`. Suggested message: `fix: validate restore inventory before replacement`.

### Task S3: Preserve recovery points when backup creation fails (early SEC-005)

**Files and responsibilities:**
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/crashreport/CachePrune.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/DatabaseReadyStartupInitializer.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/backup/LocalBackupManagerTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/crashreport/CachePruneTest.kt`

**Interfaces:** Consumes `BackupStore.publish/read/prune`, current ZIP creation and startup lifecycle. Produces a verified-publication precondition for pruning; R1/R2 replace plaintext staging and add durable ownership.

- [ ] **Step 1: Add failure assertions to the existing manager fixture.**

Use the existing MockK store/writer setup to throw IOException during JSON write, ZIP creation, publication and publication read-back. Each case must satisfy:

```kotlin
coVerify(exactly = 0) { backupStore.prune(any()) }
```

Use the actual store variable name from the fixture. Seed exactly one valid encrypted archive in file/SAF fixtures and compare bytes/list after each failure; mocked call counts alone do not prove recoverability.

- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests '*LocalBackupManagerTest'` and observe pruning before failure.**

- [ ] **Step 3: Move `pruneOldBackups(customUri)` after successful publication read-back verification.**

Reopen the published archive through `BackupStore.read`, decrypt and parse inventory using S2, then prune. Preserve the newly verified archive and at least one recoverable archive for any prune cutoff; never prune the old location merely because a new backup was written to a different directory. Use the selected store identity captured at operation start.

- [ ] **Step 4: Add startup cleanup restricted to orphan plaintext staging.**

Move temporary JSON into `cacheDir/backup-staging/` with an operation-specific name, and let startup delete only app-created plaintext staging entries with no live operation owner. Until R2 adds durable journals, startup runs before manual/scheduled backup admission. Do not recurse through user backup directories. R1 removes the plaintext staging path by streaming encryption; retain cleanup for legacy leftovers.

- [ ] **Step 5: Run failure/restart fixtures, synchronize backup/privacy documentation, and commit.**

Use shared gates. Suggested message: `fix: retain backups until replacement verifies`. No new API or schema is required for this guard; do not claim recoverable rotation is implemented by it.
