# Known issue: SQLCipher key/DB race on fresh install

**Status:** Fixed — see `docs/superpowers/plans/2026-07-28-sqlcipher-multiprocess-key-race.md`
for the full implementation plan and root-cause trace. `SqlCipherKeyManager` now holds a
cross-process `FileLock` (plus an in-process `ReentrantLock`) around the entire key
check/generate/decrypt critical section (`getOrCreateDbKey()` and `validateKeyDecryption()`),
and the key write is now a synchronous `commit()` rather than an async `apply()`, so every
process racing `Application.onCreate()` on a fresh install converges on one key before any
of them touches the DB file. See "Resolution" below for verification details. Originally
out of scope for M2 (Macrobenchmark work, `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`)
— found while trying to run the new `ScrollBenchmark` suite on a physical device (Samsung
SM-A576B, API 36-ish) during that work, but the bug is unrelated to anything M2 changed and
reproduces on a completely fresh install of the `benchmark` build type.

## Symptom

On a fresh install, `HealthDashboardApplication.onCreate()` fails to open the SQLCipher
database:

```
sqlcipher: ERROR CORE sqlcipher_page_cipher: hmac check failed for pgno=1
sqlcipher: ERROR CORE sqlite3Codec: error decrypting page 1 data: 1
SQLiteLog: (26) file is not a database in "SELECT COUNT(*) FROM sqlite_schema;"
SQLiteDatabase: Failed to open database '.../databases/health_dashboard.db'.
net.zetetic.database.sqlcipher.SQLiteNotADatabaseException: file is not a database (code 26)
	at ... SQLiteDatabase.openDatabase
	at app.readylytics.health.HealthDashboardApplication.<init-path>
	at app.readylytics.health.HealthDashboardApplication.onCreate
```

The app's own key-corruption recovery path (`DatabaseRecoveryScreen`, gated by
`dbKeyOk` in `MainActivity.kt` per the performance plan's F13 item) correctly detects
this and routes there instead of crashing — this is why the screen looked like a
"rebuilding/updating database" screen rather than a crash, and is why it is easy to
mistake for a real migration in progress.

## Reproduction

1. Fully uninstall any existing install of the target package.
2. Fresh install (`./gradlew :app:installBenchmark` or via
   `:benchmark:connectedBenchmarkAndroidTest`, which installs before running tests).
3. Launch the app (either manually or via the instrumented test's
   `startActivityAndWait()`).
4. Observe `logcat` for the `sqlcipher`/`SQLiteNotADatabaseException` sequence above,
   almost always attributed to a PID that is NOT the main activity's PID.

Reproduced 3 times in a row on the device above, deterministically, immediately after
install — not a one-off or leftover-state artifact (confirmed by fully uninstalling and
reinstalling between attempts).

## Suspected root cause

On install, Android launches **multiple separate short-lived processes** in quick
succession, each of which runs `Application.onCreate()` independently:

- One or more `androidx.profileinstaller.ProfileInstallReceiver` broadcast processes
  (observed twice, ~0.4s apart, via `ActivityManager: Start proc ... for broadcast
  {.../androidx.profileinstaller.ProfileInstallReceiver}`)
- The main activity process (`... for next-top-activity {.../MainActivity}`)

`logcat` shows `keystore2` service activity from multiple PIDs within the same
millisecond window as the failure, consistent with two or more of these processes
concurrently racing to generate/retrieve the app's Android-Keystore-backed SQLCipher
key and initialize the encrypted DB file — i.e. a **cross-process race** in whatever
`SqlCipherKeyManager` does on first run (key generation, DB file creation, or both),
not a single-process concurrency issue (a single process's own coroutines/threads
would ordinarily be protected by an in-process lock, which does not help across
separate OS processes).

This has not been root-caused precisely — the above is an informed hypothesis from the
available logcat evidence, not a confirmed fix target. Whoever picks this up should:

1. Read `SqlCipherKeyManager` (`app/src/main/kotlin/app/readylytics/health/data/security/`)
   and confirm whether its key-generation/DB-open path has any cross-process guard
   (a `FileLock`, a `ContentProvider`-based single-process election, or similar) —
   it very likely does not, since Kotlin-level `synchronized`/`Mutex` only protects
   within one process.
2. Confirm which specific process "wins" and which "loses" the race, and what state
   each leaves behind (does the loser's DB write clobber the winner's, or does it
   read while the winner is mid-write?).
2. Decide the fix shape: making DB/key initialization idempotent across processes
   (e.g. gate it behind a single `ContentProvider` init, a cross-process file lock, or
   simply not touching the DB from broadcast-receiver-only process launches at all,
   if `Application.onCreate()` can cheaply detect it's running in a receiver-only
   process and skip DB initialization there).

## Why this matters for M2 and beyond

- It blocks `benchmark/BASELINE.md` from ever being filled in via
  `./gradlew :benchmark:connectedBenchmarkAndroidTest` on an affected device, since the
  app never reaches the tab UI `ScrollBenchmark` needs.
- It is a real first-launch data-integrity risk for actual end users too, not just an
  artifact of the benchmark environment — any real user's very first launch after
  install is subject to the same multi-process race.

## Resolution

Root cause narrowed to the unguarded critical section in
`SqlCipherKeyManager.getOrCreateDbKey()` (`app/src/main/kotlin/app/readylytics/health/data/security/SqlCipherKeyManager.kt`),
confirmed by the code trace above; the fix covers both an in-process race (e.g. the main-thread
`DatabaseMigrationControllerImpl` constructor racing an `appScope` coroutine within one process)
and a genuine cross-process race (via the `FileLock`). This doc's original open question of
exactly which process wins/loses in the field was not isolated further — the "Start proc" lines
in the original logcat are equally consistent with the process starting, dying and restarting in
sequence as with two genuinely concurrent processes — since the fix closes the race either way.
Notably `androidx.profileinstaller.ProfileInstallReceiver` runs in the app's default process
(confirmed via the merged manifest — it declares no `android:process`), so the "detect and
skip DB init in a receiver-only process" idea speculated above turned out not to be viable:
there is no process-identity signal available in `Application.onCreate()` to distinguish it
from the activity-launch process. Fixed instead with a real cross-process mutex (`FileLock`
+ `ReentrantLock`) around the key-generation critical section, plus a durable
(`commit = true`) key write so a losing process's post-lock read is guaranteed to see the
winner's persisted key rather than a stale per-process `SharedPreferences` cache.

`validateKeyDecryption()` (called from `MainActivity.onCreate()`) is also covered by the
same lock — an earlier draft of the fix left it unlocked on the reasoning that it only
reads, but code review surfaced a real gap: a process's first read of `SharedPreferences`
can be cached before another process's key write lands, causing that process to later
regenerate a *different* key even after correctly acquiring the lock elsewhere. Locking
`validateKeyDecryption()` too closes that gap unconditionally, without depending on
version-specific Android `SharedPreferences` reload timing.

**Verification performed** (device: Samsung SM-A576B, the same model this issue was
originally found on):
- Unit tests (`SqlCipherKeyManagerTest`, Robolectric): a concurrency regression test spins
  up 8 threads calling the locked critical section simultaneously and asserts they all
  converge on a byte-identical key; sanity-checked to fail without the lock and pass with
  it restored.
- A genuine two-OS-process instrumented test
  (`SqlCipherKeyManagerCrossProcessRaceTest`, `app/src/androidTest/kotlin/app/readylytics/health/data/security/`)
  drives two real Android `Service` processes (`app/src/debug/kotlin/app/readylytics/health/data/security/racetest/KeyRaceTestService.kt`)
  racing to open the same fresh SQLCipher DB file, using the app's real Hilt-singleton
  `SqlCipherKeyManager` instance. Passed 4/4 runs on fresh app data on the physical device.
- Manual fresh-install repro per this doc's own steps (uninstall, fresh `installBenchmark`,
  launch, watch `logcat` for the `sqlcipher`/`SQLiteNotADatabaseException` signature),
  repeated 3 times as originally documented above: **zero occurrences** across all 3 runs.
- `./gradlew :benchmark:connectedBenchmarkAndroidTest`: the app now reaches the tab UI
  `ScrollBenchmark` needs (the specific thing this doc said was blocked). 4/6 benchmark
  tests passed; the 2 failures (`ScrollBenchmark.dashboardVitalsTabSwitch` — "Vitals nav
  item not found"; `StartupBenchmark.hotStart` — "Unable to read any metrics during
  benchmark") are unrelated pre-existing issues (a UI test-selector mismatch and a startup
  metrics-capture gap), not the SQLCipher race — no `sqlcipher`/HMAC/`SQLiteNotADatabaseException`
  signature appears anywhere in that run's logcat. `benchmark/BASELINE.md` can now be filled
  in via this command; the selector/metrics failures are a separate, unrelated follow-up.

**Testing gaps (known, deliberate):** Task 1's Robolectric test proves in-process *thread*
convergence with a fake `KeyProvider`; Task 2's instrumented test proves real two-*process*
convergence with the real Keystore. Neither covers (a) multiple `SqlCipherKeyManager` instances
coexisting in one process — a real gap found in final review, since test/benchmark code
hand-constructs extra instances; now closed structurally by moving the in-process `ReentrantLock`
into the companion object (JVM-wide) so correctness no longer depends on `@Singleton` DI scoping;
(b) `resetKeyAndDatabase()` under concurrency — now also inside the same lock with a durable
`commit = true` removal, but not exercised concurrently by a test; or (c) the actual production
trigger path (`Application.onCreate()` on a genuinely fresh install), which remains
manual-repro-only because it requires a truly fresh install rather than a test fixture. Also note
no CI job in this repo runs `connectedAndroidTest`, so Task 2's instrumented test is currently a
manual regression guard a developer must run locally, not an automated gate — a worthwhile future
improvement.
