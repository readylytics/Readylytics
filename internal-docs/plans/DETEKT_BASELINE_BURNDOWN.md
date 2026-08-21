# Detekt Baseline Burn-Down

**Status:** not started · **Created:** 2026-08-18 · **Owner:** unassigned

Self-contained. A coding agent picking this up needs no other document. It explains what the
baselines are, why they exist, how to run detekt, how to regenerate a baseline safely, and how
to work through the 658 suppressed findings without breaking the build or the other guard rails
this repo relies on.

---

## 1. What you are looking at

`detekt` runs on every module. Findings that already existed when the integration landed were
frozen into a **per-module** `detekt-baseline.xml` so the build could go green without a
1,600-line cleanup. Every entry in those files is a real, still-present finding that detekt is
being told to ignore. This document is about removing them, one at a time, by fixing the code.

**As of 2026-08-21 there are 647 baselined findings across 16 files:**

| Entries | File |
|--------:|------|
| 96 | `core/database/detekt-baseline.xml` |
| 94 | `app/detekt-baseline.xml` |
| 87 | `core/scoring/detekt-baseline.xml` |
| 55 | `core/model/detekt-baseline.xml` |
| 45 | `feature/settings/detekt-baseline.xml` |
| 44 | `core/ui/detekt-baseline.xml` |
| 39 | `core/healthconnect/detekt-baseline.xml` |
| 39 | `feature/vitals/detekt-baseline.xml` |
| 39 | `feature/workouts/detekt-baseline.xml` |
| 32 | `feature/dashboard/detekt-baseline.xml` |
| 29 | `feature/sleep/detekt-baseline.xml` |
| 25 | `core/database-schema/detekt-baseline.xml` |
| 8 | `core/designsystem/detekt-baseline.xml` |
| 7 | `feature/onboarding/detekt-baseline.xml` |
| 5 | `feature/about/detekt-baseline.xml` |
| 3 | `feature/insights/detekt-baseline.xml` |

By rule:

| Count | Rule | Notes |
|------:|------|-------|
| 185 | `LongMethod` | threshold 60 lines |
| 81 | `MaxLineLength` | |
| 61 | `ReturnCount` | |
| 59 | `TooManyFunctions` | |
| 50 | `CyclomaticComplexMethod` | threshold 15 |
| 42 | `LongParameterList` | function 8 / constructor 10, defaults + data classes ignored |
| 23 | `SwallowedException` | |
| 19 | `UnusedPrivateProperty` | |
| 15 | `UnusedPrivateMember` | |
| 14 | `UnusedParameter` | |
| 12 | `ExplicitItLambdaParameter` | |
| 11 | `ComplexCondition` | |
| 8 each | `UseCheckOrError`, `MatchingDeclarationName`, `LargeClass` | |
| 7 | `DestructuringDeclarationWithTooManyEntries` | |
| 5 each | `TooGenericExceptionThrown`, `NestedBlockDepth`, `InvalidPackageDeclaration`, `InstanceOfCheckForException`, `ImplicitDefaultLocale` | |
| 4 each | `NewLineAtEndOfFile`, `EmptyFunctionBlock` | |
| 3 | `LoopWithTooManyJumpStatements` | |
| 2 each | `ThrowsCount`, `SpreadOperator`, `MayBeConst` | |
| 1 each | `MemberNameEqualsClassName`, `FunctionOnlyReturningConstant` | |

Files carrying the most entries (these are where the work concentrates):

```
18  LocalRestoreManager.kt              11  DashboardMetricCardPreviews.kt
14  ScoringSyncScopeOutputsDeterminismTest.kt   10  DashboardCardsSettingsViewModelTest.kt
13  ScoringRepositoryImpl.kt             9  WorkoutDetailViewModelTest.kt / ResyncRangeUseCase.kt
11  ReadinessSummaryCoordinator.kt       7  SleepDayAggregatorTest.kt / BaselineComputer.kt
11  HealthConnectRepositoryImpl.kt       6  SqlCipherKeyManager.kt / SleepTrendChart.kt / LocalBackupManager.kt / InsightCauseRanker.kt
```

Detekt runs over **test sources too** — several of the heaviest files above are tests.

---

## 2. Setup facts you need before touching anything

- **detekt 1.23.8** (`gradle/libs.versions.toml:3`), plugin applied in
  `build-logic/src/main/kotlin/readylytics.kotlin-android-conventions.gradle.kts`.
- **Shared config, per-module baseline.** Config is one file for the whole repo
  (`config/detekt/detekt.yml`, 64 lines); the baseline is deliberately **not**:

  ```kotlin
  config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
  baseline = layout.projectDirectory.file("detekt-baseline.xml").asFile
  ```

  **Do not try to consolidate the baselines into one root file.** That was tried and it cannot
  work: `detektBaseline` is a *per-project* task, every module would write the same path, and
  the last writer wins — the file ends up holding one module's findings and cannot be
  reproduced. The comment at lines 22-26 of that file records this; leave it in place.
- **`build.maxIssues: 0`** (`config/detekt/detekt.yml:2`). Any finding not in the baseline
  fails the build. There is no slack.
- **Five rules are deliberately disabled** and must stay that way unless you are explicitly
  asked to revisit them. Each has a recorded reason in `config/detekt/detekt.yml`:
  - `TooGenericExceptionCaught` — off.
  - `RethrowCaughtException` — off because it directly contradicts the Konsist rule
    `suspend functions do not swallow CancellationException` in
    `app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt`, which *requires*
    `catch (e: CancellationException) { throw e }`. **Konsist owns this policy.** Re-enabling
    the detekt rule makes the two guards fight.
  - `FunctionNaming` — off; `.editorconfig` disables `ktlint_standard_function-naming` for
    backtick test names and `@Composable` PascalCase. Was 280 baseline entries.
  - `WildcardImport` — off; `.editorconfig` sets `ktlint_standard_no-wildcard-imports = disabled`.
    Was 22 entries.
  - `MagicNumber` — off; 694 entries, overwhelmingly scoring coefficients in `domain/scoring`
    where the constants *are* the domain and sit beside their derivations.

---

## 3. Commands

```bash
# Run detekt everywhere (this is what CI runs — .github/workflows/ci.yml)
./gradlew detekt

# One module only — far faster while iterating
./gradlew :core:scoring:detekt

# Where the human-readable reports land
#   <module>/build/reports/detekt/detekt.html   (open this; it is much easier than the XML)
#   <module>/build/reports/detekt/detekt.xml
```

Detekt is **not** incremental in a helpful way here — always confirm with a module-scoped run
before the full one.

The mandatory pre-commit gate for this repo is unchanged and detekt is only part of it:

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
# and, once all coding tasks are done:
./gradlew lintRelease
```

Run `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` before handing work back.
Current known-good state: **3,082 unit tests, 0 failures, 0 lint warnings.**

---

## 4. Regenerating a baseline — read this before you do it

```bash
# Regenerate every module's baseline
./gradlew detektBaseline

# Regenerate one module
./gradlew :core:scoring:detektBaseline
```

`detektBaseline` **ignores the existing baseline and writes a fresh full list of every current
finding.** That has two consequences:

1. It is the correct way to find **stale entries** — findings already fixed by earlier work
   whose baseline rows are now dead weight. Regenerate, diff, and the disappeared IDs are the
   stale ones.
2. It is a *terrible* way to "fix" a failing build, because it silently re-freezes any genuine
   new finding you just introduced. **Never run `detektBaseline` to make a red build green.**
   If detekt fails on code you wrote, fix the code.

Safe procedure for a staleness sweep (**step 0** of this work — do it first, it is free
progress):

```bash
git status --porcelain          # must be clean, or at least commit the baselines first
./gradlew detektBaseline
git diff --stat -- '**/detekt-baseline.xml'
git diff -- '**/detekt-baseline.xml'   # removed <ID> lines == findings already fixed
```

Then read the diff carefully:
- **Lines removed only** → those findings are gone. Keep the regenerated file, commit it as a
  standalone "drop stale detekt baseline entries" change. Nothing else needed.
- **Any line added** → a finding is being newly suppressed. Do **not** commit that. Work out
  where it came from (`git stash` your changes and re-run to confirm it predates you), and
  either fix the code or, if it is genuinely pre-existing and merely re-worded, see §5.
- Verify afterwards with `./gradlew detekt` — it must pass.

---

## 5. Baseline entries key on the full signature, not the line number

An entry looks like:

```xml
<ID>LongMethod:HealthChangeSynchronizerImpl.kt$HealthChangeSynchronizerImpl$private suspend fun upsertRecord( dataType: HealthDataType, record: Record, prefs: UserPreferences, )</ID>
```

Format: `RuleName:FileName.kt$EnclosingDeclaration$signature`.

**Line numbers are not part of it, so adding imports or moving code up and down a file will not
invalidate an entry — but changing a signature will.** This bites in a non-obvious way: on
2026-08-18 replacing a fully-qualified parameter type
(`prefs: app.readylytics.health.data.preferences.UserPreferences` → `prefs: UserPreferences`)
broke `:core:healthconnect:detekt`, because the baseline still held the old spelling. The fix
is a one-line edit of the `<ID>` to match the new signature — not a regeneration.

So: **if detekt fails on a file you only refactored cosmetically, diff the signature against the
baseline entry before reaching for `detektBaseline`.**

---

## 6. How to work through the findings

Do this **module by module**, smallest first, one rule at a time, one commit per coherent
group. Never mix a baseline burn-down with behavioural work — a reviewer must be able to read
the diff as "no behaviour changed".

Suggested order (cheapest and safest first):

**Tier 1 — mechanical, near-zero risk (~50 entries)**
`NewLineAtEndOfFile` (4), `MayBeConst` (2), `ExplicitItLambdaParameter` (12),
`UnusedPrivateProperty` (19), `UnusedPrivateMember` (15), `UnusedParameter` (14).
Delete or rename; the compiler and tests catch any mistake immediately. Note `ktlintFormat`
fixes `NewLineAtEndOfFile` for you. For unused members, check for reflection/DI/Room usage
before deleting — Hilt and Room reference things the compiler cannot see.

**Tier 2 — local, mechanical-with-judgement (~110 entries)**
`MaxLineLength` (81), `ImplicitDefaultLocale` (5), `UseCheckOrError` (8),
`InstanceOfCheckForException` (5), `TooGenericExceptionThrown` (5),
`DestructuringDeclarationWithTooManyEntries` (7).
`MaxLineLength` is mostly wrapping; be careful not to fight ktlint, which also has an opinion —
run `./gradlew ktlintFormat` after and re-check detekt.

**Tier 3 — real refactors (~400 entries)**
`LongMethod` (185), `TooManyFunctions` (59), `CyclomaticComplexMethod` (50),
`LongParameterList` (42), `ComplexCondition` (11), `LargeClass` (8), `NestedBlockDepth` (5).
These are the actual architecture debt. Two decompositions already landed in this repo and are
worth reading as worked examples before starting:

- **`core/database/.../repository/ScoringRepositoryImpl.kt`** went from 863 lines to 440, and
  its constructor from 21 dependencies to 10, by extracting collaborators
  (`ScoringDayDataLoader`, `ReadinessSummaryCoordinator`, and a set of
  `Compute*UseCase`/`Resolve*UseCase` types now in `core/database` and `core/scoring`). The
  seam that mattered was **data access, not method length** — ten of the 21 constructor
  parameters were DAOs, and the class was simultaneously a data-gathering layer and a scoring
  orchestrator. Slicing more methods would not have moved either number.
- **`feature/workouts/.../WorkoutsStateFactory.kt`**: `buildWorkoutsState` went from 173 lines
  and complexity 22 to 59 lines, and from 14 parameters to 1, by introducing the
  `WorkoutsStateInputs` parameter object and extracting five verbatim helpers. **Parameter-object
  extraction is the standard fix for `LongParameterList`.**

Both followed one rule, which you should follow too: **no expression is rewritten, only
relocated.** Move code into a helper unchanged; do not "clean it up" on the way. For anything
touching scoring, `core/database/src/test/resources/golden/` holds golden-snapshot fixtures that
exist precisely to prove the output did not move — see the hard constraints in §7.

**Tier 4 — needs a decision, not a fix (~30 entries)**
`SwallowedException` (23), `ThrowsCount` (2), `InvalidPackageDeclaration` (5).
`SwallowedException` overlaps a policy area that has already been litigated in this repo: some
broad catches are correct by design (e.g. `SecureFileLogSink` — a log sink inside a detached
`scope.launch` must not propagate), and the `CancellationException` rethrow rule is owned by
Konsist. **Read the surrounding comments before "fixing" one of these**; several carry an
explicit rationale explaining why they are the way they are. `InvalidPackageDeclaration` (5 entries) survived the package alignment refactor — these are
files whose package does not match their directory path, scattered across `core/ui`, `core/scoring`,
and `feature/settings`. Each needs its package fixed or the file moved.

---

## 7. Hard constraints (violating any of these is a failed task)

- **Scoring math is off-limits.** `domain/scoring/**` formulas, coefficients, operator order
  and constants must not change. `LongMethod`/`CyclomaticComplexMethod` findings inside scoring
  are fixed by *relocating* expressions into helpers, never by rewriting them.
- **Never regenerate the golden fixtures** in `core/database/src/test/resources/golden/` to make
  a test pass. A changed golden value means the refactor changed the math — revert it.
- **Do not lower a threshold in `config/detekt/detekt.yml`** to clear findings, and do not
  disable a rule to empty its baseline section. That inverts the point of the exercise. If a
  rule genuinely does not fit this codebase, say so and get it agreed before changing config —
  and record the reason in the config file the way the existing five disabled rules do.
- **Do not consolidate the per-module baselines** (see §2).
- **File size targets** from the project rules still apply: aim ≤ 400 lines/file, hard limit
  800 — refactor if exceeded.
- If you touch the ingestion pipeline, Room schema, scoring use-cases, or scoring formulas,
  `internal-docs/DATA_FLOW.md` must be updated in the same change. A stale `DATA_FLOW.md` is
  treated as a broken build. Pure detekt cleanups normally do not trigger this — but a Tier 3
  refactor of `ScoringRepositoryImpl` or `HealthConnectRepositoryImpl` does.
- New files require `codegraph index` afterwards; structural moves require `codegraph sync`.

---

## 8. Definition of done for a burn-down commit

1. Baseline entries removed (not re-suppressed), and the count in this document updated.
2. `./gradlew ktlintCheck detekt testDebugUnitTest lintRelease` passes.
3. Unit test count has not dropped (3,082 as of 2026-08-21) and failures are 0.
4. No behavioural diff — if the change is not provably behaviour-preserving, it needs a test
   that proves it.
5. `git diff -- '**/detekt-baseline.xml'` shows **removals only**.
