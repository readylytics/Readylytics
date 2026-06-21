# Readylytics Production Readiness Review

**Review date:** 2026-06-19
**Reviewed commit:** `54bfac632e3a8dfab4f11a7f9c5aab2a2f10aa35`
**Branch:** `feature/release-review-3`
**Scope:** `:app`, release configuration, tests, public/internal privacy documentation
**Method:** Static source tracing, Gradle release gates, connected-device tests, startup smoke test, and current official Android documentation

# 1. Architectural Executive Summary

**Overall release readiness verdict:** `Critical Rework Required`

- Historical ATL/CTL grouping applies one UTC offset to an entire multi-month window. Records cross date boundaries around daylight-saving transitions, changing load and readiness outputs.
- Retention cleanup omits weight, body-fat, blood-pressure, and oxygen-saturation tables. Sensitive records remain after the configured retention period.
- Android 12+ device-transfer exclusions exist as XML but are not referenced from the manifest. `allowBackup="false"` alone does not reliably disable device-to-device transfer on all manufacturers.
- Release APK and AAB build successfully with R8, but both generated release artifacts are unsigned.
- Connected verification is not green: six failures occurred before the run was stopped at test 137 of 158.
- Health Connect synchronization upserts records but never reconciles source deletions. Changing the selected source device can leave previously ingested records contributing to scores.
- Full historical resync is one monolithic foreground `dataSync` operation without a durable checkpoint. Android 15+ limits this foreground-service type to six hours per 24 hours.
- Privacy-first posture is weakened by unused Retrofit/OkHttp dependencies, an unnecessary `INTERNET` permission, and 16 unguarded production log calls.
- Core calculation coverage is broad by test-file count, but enforced instruction coverage is only 6.27%, with a 4% release gate and no DST regression coverage.
- Compose generally uses lifecycle-aware collection, stable lazy-list keys, Material theme tokens, and string resources. Custom interactive charts remain largely inaccessible to TalkBack and keyboard input.

**Highest-risk area:** Health-data correctness and retention. DST grouping and incomplete cleanup affect persisted health outputs and privacy guarantees.

**Confidence:** High for source-level and local build findings; medium for device behavior because connected verification did not finish; low for Play Console, signing infrastructure, OEM backup transfer, and real Health Connect datasets unavailable in repository.

**Positive release evidence:**

- Room is application source of truth; UI does not query Health Connect directly.
- SQLCipher factory is installed before Room opens, WAL is enabled, and plaintext migration uses atomic replacement.
- Raw Health Connect entities use stable record IDs as primary keys; ingestion uses `@Upsert`.
- Full resync performs full-range session-link reconciliation before walk-forward scoring.
- Sync cancellation is explicitly rethrown in critical worker/repository paths.
- Release minification and resource shrinking are enabled.
- `allowBackup` is explicitly false, cleartext traffic is disabled, and no app source call sites for network, analytics, or telemetry were found.
- Compose Flow collection uses `collectAsStateWithLifecycle`; no plain `collectAsState` production call was found.

# 2. P0 - Release Blockers

## [P0] DST Corrupts Historical ATL/CTL Date Buckets

**Evidence**

- File: `app/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt`
- Class/function: `ScoringRepositoryImpl.computeDailySummary`, lines 343-365
- File: `app/src/main/kotlin/app/readylytics/health/data/local/dao/WorkoutDao.kt`
- Class/function: `WorkoutDao.getDailyTrmpByEpochDay`, lines 61-75
- File: `app/src/main/kotlin/app/readylytics/health/data/local/dao/DailySummaryDao.kt`
- Class/function: `DailySummaryDao.getEverydayTrimpByEpochDay`, lines 165-179
- Observed pattern: One `tzOffsetMs`, calculated for the target day, is added to every timestamp in an 84-day historical query before integer epoch-day grouping.

**Problem**

Zone offsets vary across DST transitions. When target day uses winter offset but historical records use summer offset, summer local-midnight records can be grouped into the previous date. Workout-only and everyday-HR series both use this conversion.

**Production Risk**

ATL, CTL, strain ratio, load score, and readiness can change around fall-back transitions without source-data changes. This violates deterministic daily-score requirements.

**Recommended Fix**

Remove fixed-offset SQL grouping. Persist an explicit local scoring date/epoch day with each derived record, or fetch timestamp/value rows and convert each timestamp through the persisted scoring `ZoneId`. Define behavior for scoring-zone changes and migrate/recompute affected rows.

**Verification**

- DAO/integration tests spanning spring-forward and fall-back in at least `Europe/Berlin` and `America/New_York`.
- Property test comparing SQL/repository series against per-record `Instant.atZone(zone).toLocalDate()` conversion.
- Determinism test: same source rows produce identical summaries before and after resync across DST boundaries.

**Implementation Scope:** Large

## [P0] Retention Cleanup Leaves Four Sensitive Tables Unbounded

**Evidence**

- File: `app/src/main/kotlin/app/readylytics/health/workers/DataCleanupWorker.kt`
- Class/function: `DataCleanupWorker.doWork`, lines 20-49
- Observed pattern: Worker deletes only sleep sessions, heart rate, HRV, workouts, and daily summaries.
- Related stored entities: `WeightRecordEntity`, `BodyFatRecordEntity`, `BloodPressureRecordEntity`, and `OxygenSaturationRecordEntity` are ingested by `HealthSyncUseCase`, but their DAOs are absent from worker constructor and cleanup transaction.
- Test evidence: No `DataCleanupWorker` behavioral test exists.

**Problem**

Enabled retention does not apply to all locally stored health data.

**Production Risk**

App retains highly sensitive measurements beyond user-selected retention period. UI/history, encrypted backup, and database size can diverge from stated policy.

**Recommended Fix**

Inject all health-record DAOs and delete every retention-governed table using `RetentionBounds` cutoff in one Room transaction. Document whether insight dismissals and configuration records are exempt.

**Verification**

- Worker integration test seeds every table immediately before, at, and after cutoff.
- Assert only pre-cutoff rows are removed and foreign-key sleep stages cascade correctly.
- Restore/backup test proves expired records cannot reappear.

**Implementation Scope:** Medium

## [P0] Device-Transfer Exclusion Rules Are Unused

**Evidence**

- File: `app/src/main/AndroidManifest.xml`
- Element: `<application>`, lines 44-52
- Observed pattern: Manifest sets `android:allowBackup="false"` but does not set `android:dataExtractionRules` or `android:fullBackupContent`.
- File: `app/src/main/res/xml/data_extraction_rules.xml`, lines 5-14
- File: `app/src/main/res/xml/full_backup_content.xml`, lines 6-8
- Observed pattern: Both files exclude all roots, but release lint reports `DataExtractionRules`; `backup_rules.xml` is also reported unused.
- Platform rule: Android documentation states some Android 12+ manufacturers may still perform device-to-device transfer when only `allowBackup="false"` is set: <https://developer.android.com/guide/topics/data/autobackup>.

**Problem**

Intended backup protections are not connected to application manifest.

**Production Risk**

Encrypted database, DataStore, keyset preferences, and backup files can enter OEM device transfer. Android Keystore keys do not transfer with those files, creating both sensitive-data handling and startup/data-loss risks.

**Recommended Fix**

Reference `@xml/data_extraction_rules` and `@xml/full_backup_content` from `<application>`. Consolidate/remove unused sample `backup_rules.xml`. Verify exclusions cover credential-protected and device-protected domains used by app.

**Verification**

- Release lint contains no `DataExtractionRules` or unused backup-rule warning.
- Manifest merger test asserts both attributes and `allowBackup=false`.
- Device backup/restore test with `bmgr` plus OEM device-transfer validation confirms no database, DataStore, keyset, or local backup file transfers.

**Implementation Scope:** Small

## [P0] Release Artifacts Are Unsigned

**Evidence**

- File: `app/build.gradle.kts`
- Block: `android.buildTypes.release`, lines 98-106
- Observed pattern: No release `signingConfig` is declared.
- Generated artifact: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Generated artifact: `app/build/outputs/bundle/release/app-release.aab`
- Verification: `jarsigner -verify app-release.aab` returned `jar is unsigned`.

**Problem**

Repository release build does not produce uploadable Play artifact.

**Production Risk**

Unsigned AAB cannot enter production. External CI signing may exist, but no evidence is available in repository.

**Recommended Fix**

Define secret-backed upload signing in CI or documented local release pipeline. Never commit credentials. Add artifact-signature verification before publication.

**Verification**

- CI release job builds from clean checkout and verifies AAB certificate fingerprint against approved upload key.
- Play internal-track upload succeeds with Play App Signing enabled.

**Implementation Scope:** Medium

## [P0] Connected Release Verification Is Red

**Evidence**

- Command: `./gradlew connectedAndroidTest`
- Device: `SM-A576B`, Android API 36
- Result before termination: 158 tests declared; run reached 137; six failures were recorded.
- `SleepSessionRepositoryImplTest.deleteBeforeTimestamp_removesOldSessions`: expected one row, observed two. Fixture uses `t1Start=1_000_000` and default eight-hour end time, so session does not end before `t2Start=2_000_000`.
- `BatteryTest.idleDrainRate`, line 30: reported extrapolated drain `208.39%`; test blocks suite for five minutes and extrapolates one noisy sample.
- `StartupBenchmark.coldStart`, `warmStart`, `hotStart`: Macrobenchmark configuration error because benchmark is in target app instrumentation instead of separate non-debuggable/self-instrumenting benchmark module.
- `StartupDataStoreTest.dataStoreReadTime`, lines 18-41: JUnit initialization error, `Method dataStoreReadTime() should be void`.

**Problem**

Device gate cannot provide release evidence. Failures combine invalid fixtures, invalid test signatures, wrong benchmark architecture, and nondeterministic battery methodology.

**Production Risk**

Migration, repository, Compose, performance, and device-specific regressions cannot be trusted before release.

**Recommended Fix**

Repair deterministic instrumented tests; move Macrobenchmark into dedicated module with proper runner/build type; replace five-minute battery assertion with controlled benchmark/Power Profiler or repeatable long-run measurement; keep performance suites separate from functional connected gate.

**Verification**

- `connectedAndroidTest` completes with zero failures on supported API matrix.
- Dedicated Macrobenchmark task reports cold/warm/hot startup metrics from non-debuggable target.
- Functional CI gate completes without sleeps measured in minutes.

**Implementation Scope:** Medium

# 3. P1 - High Risk

## [P1] Source Deletions and Device Changes Leave Stale Records

**Evidence:** `HealthSyncUseCase.ingestWindow` lines 523-545 only upserts fetched/filtered rows; only stages belonging to fetched sleep sessions are deleted. No `ChangesToken`, deleted-record log, range reconciliation, or local deletion of absent IDs exists. `UIPreferences.updateDeviceForDataType` lines 75-87 only changes DataStore selection.

**Problem:** Records deleted in Health Connect remain locally. Switching a data-type source filters new ingestion but does not remove rows from previous source, while scoring queries read local tables without device predicate.

**Production Risk:** Deleted data and deselected-device data continue affecting summaries, baselines, charts, and backups.

**Recommended Fix:** Implement Health Connect change-token deletion handling for incremental sync. For full resync/device changes, reconcile stable IDs per completed type/range transactionally; never delete until all pages for that scope succeed. Recompute affected dates afterward.

**Verification:** Integration tests for source deletion, modified record, device A to B switch, all-devices switch, partial-page failure, killed worker, and idempotent retry.

**Implementation Scope:** Large

## [P1] Historical Resync Has No Durable Checkpoint Against FGS Timeout

**Evidence:** `HealthResyncWorker.doWork` invokes one `FullHistoricalResyncUseCase.execute`; `HealthSyncUseCase.resyncRange` may process up to 3650 days and retry restarts whole range. Chunks are fetch units, not persisted work checkpoints. Android 15+ gives `dataSync` foreground services six total hours per 24 hours: <https://developer.android.com/develop/background-work/services/fgs/timeout>.

**Problem:** Large datasets can reach platform timeout; completed chunks are re-read/recomputed from range start.

**Production Risk:** Resync can loop, consume quota/battery, or never complete for long histories.

**Recommended Fix:** Persist resumable phase/chunk/day checkpoint keyed by immutable resync request parameters. Resume safely after worker stop while retaining full-range session reconciliation semantics.

**Verification:** Forced-stop/time-budget tests resume from checkpoint, preserve deterministic output, and complete without duplicate data.

**Implementation Scope:** Large

## [P1] Runtime Network Capability Conflicts With Local-Only Claim

**Evidence:** Manifest line 5 declares `INTERNET`; `app/build.gradle.kts` lines 290-292 packages Retrofit, converter, and OkHttp. No production import or network-call site was found. Network security config disables cleartext only; HTTPS remains allowed.

**Problem:** App ships network stack and permission without verified runtime need.

**Production Risk:** Enlarged attack surface and ambiguity in Play Data Safety/privacy review undermine on-device-only claim.

**Recommended Fix:** Remove permission and unused libraries. If external browser launch is sole need, it does not require app `INTERNET` permission. Add static guard preventing network dependencies/capability from returning without explicit privacy review.

**Verification:** Merged release manifest lacks `INTERNET`; dependency tree lacks Retrofit/OkHttp; external privacy-policy intent still opens browser.

**Implementation Scope:** Small

## [P1] Release Logging Is Not Centrally Sanitized

**Evidence:** Static scan found 16 unguarded `Log.*` calls in production, including `DashboardViewModel`, `DataStoreModule`, `SqlCipherKeyManager`, `LocalBackupViewModel`, `ThresholdSettingsViewModel`, preference serializers, and restore parsing. `SecureLogger.error` logs throwable unconditionally. `DashboardViewModel` also exposes `e.message` as `UiText.RawString`.

**Problem:** Throwable messages/stacks can contain file paths, provider details, corrupt values, or platform internals. Raw exception text bypasses i18n and controlled disclosure.

**Production Risk:** Sensitive operational context can remain in logcat or reach user screenshots/support reports.

**Recommended Fix:** Route all logging through release-aware sanitizer; use stable error codes and generic localized UI messages. Preserve detailed causes only in debug builds or explicitly redacted on-device diagnostics.

**Verification:** Static test forbids direct `Log.*`; release test proves sink is no-op/redacted; exception fixtures containing health values never reach logs/UI.

**Implementation Scope:** Medium

## [P1] Domain Boundary Depends on Data and Room Implementations

**Evidence:** `ScoringRepository` imports and returns `DailySummaryEntity`; `HealthSyncUseCase` imports concrete mappers and ten Room DAOs; `SessionLinkReconciler`, scoring components, and dashboard domain classes also import `data.*`. `HealthSyncUseCase` is 590 lines. `CleanArchTest` forbids Android/Compose/Health Connect imports but does not forbid domain-to-data imports.

**Problem:** Domain contract is not independent from persistence. Sync orchestration owns fetching, filtering, mapping, transactions, retry, reconciliation, and scoring.

**Production Risk:** Persistence/schema changes have broad scoring blast radius; unit tests require large mock graphs; correctness changes are difficult to isolate.

**Recommended Fix:** Introduce domain models and narrow ingestion/reconciliation ports. Move DAO/mapping transaction details behind data-layer repository. Split sync orchestration by fetch, persist, reconcile, and recompute responsibilities without changing formulas.

**Verification:** Architecture test rejects `domain..` imports from `data..`; focused contract tests cover each port; existing determinism suite remains byte-for-byte equivalent.

**Implementation Scope:** Large

## [P1] Interactive Charts Are Pointer-Only

**Evidence:** `HrTimelineChart` and `SleepStagesChart` implement tap selection through `pointerInput`/Canvas without semantics. `CanvasChartTooltip` and `TrimpBreakdownChart` also expose pointer handling without accessible actions. Static inventory found 11 custom Canvas/pointer files lacking chart semantics; only `M3ScoreGaugeCard` supplies semantics.

**Problem:** TalkBack and keyboard users cannot discover chart purpose, traverse values, select points, or dismiss tooltip state.

**Production Risk:** Core health insights are unavailable to accessibility users and Play accessibility quality is not met.

**Recommended Fix:** Add summarized chart descriptions, accessible value lists/actions, selected-point announcements, keyboard/D-pad traversal, and non-chart textual equivalents.

**Verification:** Compose semantics tests plus manual TalkBack, Switch Access, keyboard, 200% font, and high-contrast review.

**Implementation Scope:** Medium

## [P1] Coverage Gate Does Not Protect Critical Behavior

**Evidence:** `jacocoCoverageVerification` reports 6.27% instruction coverage (`355/5665`) and passes against 4% threshold in `app/build.gradle.kts` lines 24-68. No tests reference DST zones. No DataCleanupWorker behavioral test exists.

**Problem:** Aggregate gate can pass while critical retention, timezone, deletion, permissions, and release paths remain uncovered.

**Production Risk:** High-impact regressions merge despite green unit CI.

**Recommended Fix:** Validate JaCoCo class-set completeness; establish package/risk-specific gates for pure scoring, sync, retention, security, and ViewModels. Raise threshold gradually after measuring correct denominator.

**Verification:** CI prints included class count and package coverage; mutation/property tests demonstrate critical assertions fail when logic changes.

**Implementation Scope:** Medium

# 4. P2 - Should Fix Before Release

## [P2] Chart Time and Selection State Become Stale

**Evidence:** `HrTimelineChart` hardcodes a 1440-minute day and clamps samples to `0..1439`; 23/25-hour DST days render incorrectly. `HrTimelineChart.selectedSample` and `SleepStagesChart.selectedSegment` use unkeyed `remember` and are not cleared when input date/data changes. `DashboardScreen` and `HeartRateDetailScreen` remember `LocalDate.now()` indefinitely.

**Risk:** Wrong x-position, stale tooltip from previous date/session, and stale “today” navigation after midnight.

**Fix:** Derive actual day duration from zone boundaries; key/reset selection to dataset/date/session; drive current date from injected clock/date flow.

## [P2] Sleep Chart Recomputes During Every Scroll Pixel

**Evidence:** Release lint `FrequentlyChangingValue` at `SleepStagesChart.kt:299`; `scrollState.value` is read inside composition and included in tooltip `remember` keys.

**Risk:** Continuous recomposition during horizontal scroll on large sleep-stage datasets.

**Fix:** Move scroll-dependent positioning into draw/layout phase or `snapshotFlow`/derived state with controlled emissions. Benchmark recompositions and frame time.

## [P2] Android 17 Large-Screen Readiness Is Unverified

**Evidence:** Target/compile SDK is 37. No `WindowSizeClass`, adaptive navigation scaffold, pane scaffold, or large-screen test exists. Android 17 removes orientation/resizability opt-out for `sw>=600dp`: <https://developer.android.com/about/versions/17/behavior-changes-17>.

**Risk:** Phone layout stretches onto tablets/foldables without tested navigation, chart sizing, or settings ergonomics.

**Fix:** Add adaptive navigation/layout breakpoints and screenshots/UI tests for compact, medium, expanded, portrait, landscape, and fold posture.

## [P2] User-Facing Labels Remain Hardcoded

**Evidence:** `VitalsScreen.kt:183` uses `"RHR"`; line 220 uses `"HRV"`; `StepsBar.kt:98` uses `"Steps"`; `DeviceLabel` hardcodes provider/device names including `"This Phone"`.

**Risk:** Incomplete localization and inconsistent accessibility text.

**Fix:** Move display labels to resources; keep stable internal identifiers separate.

## [P2] Privacy Rationale UI Reads Data Repository Directly

**Evidence:** `PrivacyRationaleActivity` injects `SettingsRepository` and collects `userPreferences` directly inside `setContent`, lines 29-38.

**Risk:** UI boundary bypasses ViewModel/UDF and is harder to test for loading/failure/process recreation.

**Fix:** Add small Activity-scoped ViewModel exposing theme/rationale UI state; keep composable stateless.

## [P2] R8 Rules Defeat Much of Release Shrinking

**Evidence:** `proguard-rules.pro` keeps every Room entity/DAO/database, every ViewModel, all Health Connect records, all Vico classes, all SQLCipher classes, and full scoring/settings repository classes.

**Risk:** Larger artifact, less obfuscation, and hidden reflection assumptions.

**Fix:** Remove rules already supplied by libraries/generated metadata; retain only proven reflection entry points. Verify release smoke tests and compare `mapping.txt`/APK size.

## [P2] Release Lint Warnings Are Allowed to Accumulate

**Evidence:** `warningsAsErrors=false`; release lint reports 110 issues: 105 warnings and 5 hints, including 42 unused resources, 19 modifier-parameter issues, 9 plural candidates, and backup-rule warning.

**Risk:** Important warnings mix with cosmetic debt; new release regressions remain non-blocking.

**Fix:** Resolve/security-baseline current findings, then enable warnings-as-errors or a reviewed lint baseline with no P0/P1 categories suppressed.

# 5. P3 - Cleanup / Nice to Have

- **Oversized files:** 20+ Kotlin files exceed project 400-line target. Largest production files include `TrendCharts.kt` (784), `SettingsScreen.kt` (690), `WorkoutStatsSection.kt` (689), `Theme.kt` (622), `BaselineComputer.kt` (608), and `HealthSyncUseCase.kt` (590). Split only along clear responsibility boundaries while touching affected areas.
- **Unused resources:** Release lint reports 42 unused resources, including unused sample `backup_rules.xml` and legacy color resources. Remove after manifest backup fix.
- **Naming:** `WorkoutDao.getDailyTrmpByEpochDay` misspells TRIMP. Rename with callers/tests during DST fix.
- **Single module:** `:app` contains UI, domain, data, benchmarks, and tests. Modularization would improve enforcement, but should follow correctness/security remediation rather than precede it.

# 6. Health Data Correctness and Determinism Assessment

| Area | Evidence | Verdict |
|---|---|---|
| Stable raw IDs/upsert | Raw entities use HC IDs as primary keys; DAOs use `@Upsert` | Good |
| Sleep-stage idempotency | Fetched session stages are deleted then reinserted transactionally | Good for fetched sessions |
| Session-link determinism | Full-range reconciliation and explicit tie-breaking have tests | Good |
| Frozen baselines | Point-in-time baseline fields persisted in `DailySummaryEntity`; freeze tests exist | Good |
| Resync scope determinism | Dedicated determinism tests exist | Good, but no DST/deletion coverage |
| Load date grouping | One target-day offset applied across historical window | Release blocker |
| HC deletions | No change-token/deleted-ID reconciliation | High risk |
| Device filtering | Applied only to incoming records; stale old-device rows remain | High risk |
| Retention | Four sensitive tables omitted from cleanup | Release blocker |
| Day charts | HR chart assumes 1440 minutes | Should fix |

# 7. Security and Privacy Assessment

| Control | Evidence | Verdict |
|---|---|---|
| Database encryption | SQLCipher `SupportFactory`, random key protected by Android Keystore | Good |
| Plaintext migration | Detects SQLite header, exports encrypted DB, atomically replaces file | Good |
| Cleartext network | `cleartextTrafficPermitted=false`, system trust anchors only | Good |
| App network behavior | No production network/analytics call site found | Good, but permission/dependencies remain |
| Auto backup | `allowBackup=false` | Partial |
| Device transfer | Exclusion XML not referenced | Release blocker |
| Local backup | Zip4j AES-256 encryption with required password; restore uses Room transaction | Good, subject to password UX/device testing |
| Secrets | Static candidate scan found no app credentials/private keys | Good within checked tree |
| Logging | 16 unguarded production log calls | High risk |
| Privacy policy | In-app settings URL and public `docs/privacy.md` exist | Good; Play Console link unverifiable |

# 8. Compose, Material 3, Performance, Charts, and Widgets

- Lifecycle-aware state collection is consistently used.
- Stable keys were present in inspected lazy-list `items` calls.
- Hardcoded colors are confined primarily to theme/token definitions.
- M3 components and semantic color roles are used broadly.
- Custom charts lack accessible alternatives/actions; HR and sleep chart selections persist across dataset changes.
- Release lint confirms frequently changing scroll state is read in composition.
- No widget receiver, Glance/AppWidget implementation, widget XML, or widget test exists. Widget consistency is therefore not applicable to current source and must be treated as unverifiable if store materials promise widgets.

# 9. Persistence, Coroutines, and Background Work

- Room version is 1 and schema JSON is exported. This is acceptable only for first public release; no historical production schema can be verified.
- No destructive migration fallback is configured.
- DAO queries use indices broadly; `QueryOptimizationTest` exists.
- Room query coroutine context uses IO; scoring switches to Default.
- Daily and historical sync share mutex; workers rethrow cancellation in critical paths.
- Full resync is idempotent for repeated upserts but not deletion-complete and not durably resumable.
- `DataCleanupWorker` performs multiple DAO deletions without shared Room transaction and omits four tables.

# 10. Test Coverage and Missing Verification

**Available tests:** 148 unit Kotlin files and 18 instrumented Kotlin files.

| Missing/insufficient test | Type | Target/scenario | Why |
|---|---|---|---|
| DST load grouping | Room integration + property | 84-day window across both DST transitions in multiple zones | Prevent score drift |
| Complete retention | Worker integration | Every entity before/at/after cutoff | Enforce privacy promise |
| HC deletions | Repository/sync integration | Deleted log, modified record, partial page, retry | Remove stale health data safely |
| Device switch | Sync integration | Device A ingest, select B, resync | Prevent mixed-source scoring |
| FGS timeout/resume | WorkManager integration | Stop after checkpoints in ingest/reconcile/recompute | Ensure 10-year resync completes |
| Backup exclusions | Manifest + device | Cloud backup and D2D transfer | Prevent health-data transfer |
| Permission lifecycle | UI/integration | Denied, revoked mid-sync, history/background denied, don't-ask-again | Verify recovery flow |
| Accessibility | Compose UI + manual | TalkBack traversal/actions for every chart; 200% font | Core metrics must be usable |
| Chart state | Compose UI | Change date/session with tooltip/zoom active | Prevent stale UI |
| Large screens | Screenshot/UI | Tablet/foldable, portrait/landscape | API 37 resizing |
| Release signing | CI integration | Certificate fingerprint and internal-track upload | Produce publishable artifact |
| Widgets | N/A | No implementation found | Blindspot if externally promised |

# 11. Verification Results

| Command/check | Result |
|---|---|
| `./gradlew testDebugUnitTest ktlintCheck` | PASS, `BUILD SUCCESSFUL`, 56 tasks |
| `./gradlew jacocoCoverageVerification` | PASS gate; actual coverage 6.27% (`355/5665`) |
| `./gradlew lintRelease` | PASS; 0 errors, 105 warnings, 5 hints |
| `./gradlew assembleRelease bundleRelease` | PASS; R8 executed |
| `jarsigner -verify app-release.aab` | FAIL release requirement: unsigned |
| `./gradlew connectedAndroidTest` | FAIL/incomplete: six failures; stopped at 137/158 after long-running suite exceeded tool window |
| `./gradlew installDebug` | PASS on one connected API 36 device |
| `adb shell am start -W -n app.readylytics.health/.MainActivity` | PASS; process remained alive, no fatal/ANR match in sampled logcat |

# 12. Release Configuration and Play Store Readiness

- `compileSdk=37`, `targetSdk=37`, `minSdk=26`, Java 17, AGP 9.2.1.
- Current Play minimum target requirement is satisfied; Android's published page still states API 35 minimum for new apps/updates from 2025 and was last updated 2026-06-02: <https://developer.android.com/google/play/requirements/target-sdk>.
- Release is minified and resource-shrunk; lint aborts on errors.
- App icon includes adaptive, round, and monochrome resources.
- Manifest exported components are limited to launcher/rationale entry points and protected permission-usage alias; startup provider is not exported.
- Health Connect read, history, and background permissions are declared. Rationale activity/alias match official structure: <https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started>.
- Signing, Play App Signing, Data Safety form, Health Apps declaration, internal testing track, store listing, privacy-policy URL acceptance, support contact, and country availability cannot be verified from source.
- Only base English resources were found; translation completeness is not established.

# 13. Codebase Blindspots / Unverifiable Context

- Play Console Data Safety and Health Apps declarations.
- Upload key custody, Play App Signing configuration, and CI secret controls.
- Whether an external release pipeline signs the repository-produced AAB.
- OEM-specific Android 12+ device-transfer behavior.
- Real Health Connect providers, record deletion/change logs, rate limits, and 10-year dataset volume.
- Runtime behavior on Android 17 device/emulator; connected device ran API 36.
- Tablet, foldable, keyboard, TalkBack, Switch Access, and large-font manual QA.
- Store listing claims about widgets or supported languages.
- Dependency vulnerability/SBOM scan; repository exposes no dependency-check task.
- Prior public Room schema. Database version 1 indicates first-release assumption only.

# 14. Ordered Remediation Plan

- [x] Fix DST date bucketing and add zone-transition regression/property tests before changing other scoring paths.
- [ ] Complete retention cleanup for every sensitive table in one transaction; add worker integration coverage.
- [ ] Wire backup/device-transfer exclusions into manifest and verify on device/OEM path.
- [ ] Establish secret-backed release signing and signed-artifact CI verification.
- [ ] Repair/split connected functional, Macrobenchmark, battery, and performance gates until full device suite is green.
- [ ] Add HC deletion and selected-device reconciliation without weakening killed-worker idempotency.
- [x] Add durable resync checkpoints compatible with full-range reconciliation and Android FGS time limits.
- [ ] Remove runtime network permission/dependencies and centralize sanitized release logging.
- [ ] Add chart accessibility, DST-aware axes, state reset, and scroll-performance fixes.
- [ ] Enforce domain/data dependency direction and split sync orchestration after correctness is protected by tests.
- [ ] Raise verified coverage gates, resolve lint backlog, and add Android 17 large-screen matrix.

**Release gate:** Do not submit to Play until all P0 findings are closed, connected tests complete green, signed AAB is verified, and P1 health-data deletion/device-source behavior has explicit product acceptance or remediation.

# 15. 2026-06-20 Closure Status Snapshot

## P0 Status

| Finding | Status | Fixing Commit(s) | Evidence |
|---|---|---|---|
| DST corrupts historical ATL/CTL date buckets | Closed | `8c79e034` | `.\gradlew testDebugUnitTest --tests "*TrimpDateBucketerTest" --tests "*ScoringPointInTimeRegressionTest" --tests "*ScoringSyncScopeOutputsDeterminismTest"` |
| Retention cleanup leaves four sensitive tables unbounded | Closed | `17a04263` | `.\gradlew testDebugUnitTest --tests "*RetentionCleanupTest" --tests "*DeleteByTimestampTest" --tests "*ProductionReadinessStaticTest"` |
| Device-transfer exclusion rules are unused | Closed | `3c75c660` | `.\gradlew testDebugUnitTest --tests "*ProductionReadinessStaticTest"` and `.\gradlew lintRelease` |
| Release artifacts are unsigned | Closed locally; external workflow/Play upload still unverified | `805b6f14` | `.\gradlew ktlintCheck jacocoCoverageVerification lint lintRelease assembleRelease bundleRelease` with release signing env vars, `jarsigner -verify app\build\outputs\bundle\release\app-release.aab`, artifact `app\build\outputs\bundle\release\app-release.aab` |
| Connected release verification is red | Open | None | `adb devices -l` returned no connected devices on 2026-06-20; Task 16 device matrix and macrobenchmark run not executed in this environment |

## P1 Status

| Finding | Status | Fixing Commit(s) | Evidence |
|---|---|---|---|
| Source deletions and device changes leave stale records | Closed | `46b488da`, `6dc69a48` | `.\gradlew testDebugUnitTest --tests "*HealthChangeSynchronizerImplTest" --tests "*HealthSyncUseCaseTest" --tests "*SyncScopeDeterminismTest"` |
| Historical resync has no durable checkpoint against FGS timeout | Closed | `5e317e5b` | `.\gradlew testDebugUnitTest` plus durable-checkpoint implementation under `domain/sync` and `data/preferences` |
| Runtime network capability conflicts with local-only claim | Closed | `eed70258` | `.\gradlew lint lintRelease assembleRelease bundleRelease`; no `INTERNET` permission in merged release build, signed local release artifacts generated |
| Release logging is not centrally sanitized | Closed locally | `eed70258` | `.\gradlew testDebugUnitTest` and signed local release gate on 2026-06-20; no new release-log regression surfaced in local acceptance run |
| Domain boundary depends on data and Room implementations | Closed | `08304180` | `.\gradlew testDebugUnitTest --tests "*CleanArchTest" --tests "*Scoring*Determinism*" --tests "*HealthSyncUseCaseTest"` |
| Interactive charts are pointer-only | Implementation landed; device/manual verification still pending | `bb230e8f` | Connected accessibility verification from Task 11 not rerun on 2026-06-20 because no device matrix available |
| Coverage gate does not protect critical behavior | Closed | `d33cee11` | `.\gradlew jacocoCoverageVerification` and full signed local gate on 2026-06-20 |

## P2 Status

| Finding | Status | Fixing Commit(s) | Evidence |
|---|---|---|---|
| Chart time and selection state become stale | Closed | `c1af1eb0` | `.\gradlew testDebugUnitTest --tests "*DayTimelineScaleTest" --tests "*SleepStagesChartTest" --tests "*HrTimelineChartStateTest"` |
| Sleep chart recomputes during every scroll pixel | Closed | `c1af1eb0` | `.\gradlew lint lintRelease` passed on 2026-06-20 |
| Android 17 large-screen readiness is unverified | Implementation landed; device/manual verification still pending | `7ccc0194` | No API 37 device/emulator attached on 2026-06-20; adaptive navigation code present but matrix not executed |
| User-facing labels remain hardcoded | Closed | `bb230e8f` | `.\gradlew testDebugUnitTest` and current lint gate passed on 2026-06-20 |
| Privacy rationale UI reads data repository directly | Closed | `7ccc0194` | `PrivacyRationaleViewModel` added; `.\gradlew testDebugUnitTest` passed on 2026-06-20 |
| R8 rules defeat much of release shrinking | Closed | `d33cee11` | `.\gradlew assembleRelease bundleRelease` passed on 2026-06-20 |
| Release lint warnings are allowed to accumulate | Closed | `d33cee11` | `.\gradlew lint lintRelease` passed on 2026-06-20 |

## Release Acceptance Verdict

Local source/build acceptance is now green for signed release gates. Final release acceptance remains blocked by missing external evidence:

- No connected-device functional matrix was available on 2026-06-20.
- No macrobenchmark run was executed on a benchmark-capable device/emulator.
- Protected GitHub release workflow was not run in this environment.
- Play internal-track upload, upgrade retention check, OEM device-transfer validation, and manual accessibility/privacy QA remain unverified.
