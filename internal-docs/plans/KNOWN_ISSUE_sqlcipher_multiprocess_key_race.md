# Known issue: SQLCipher key/DB race on fresh install

**Status:** Confirmed, reproducible, not yet fixed. Out of scope for M2 (Macrobenchmark
work, `internal-docs/plans/PERFORMANCE_OPTIMIZATION_PLAN.md`) — found while trying to run
the new `ScrollBenchmark` suite on a physical device (Samsung SM-A576B, API 36-ish) during
that work, but the bug is unrelated to anything M2 changed and reproduces on a completely
fresh install of the `benchmark` build type.

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
