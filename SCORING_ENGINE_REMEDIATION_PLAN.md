# Scoring Engine Remediation Plan

> **Status:** Plan only. This document proposes changes; it does **not** modify
> production code, formulas, or behavior.
>
> **Authoritative input:** [`SCORING_ENGINE_DISCOVERY_REPORT.md`](./SCORING_ENGINE_DISCOVERY_REPORT.md)
> (the audit). Cross-referenced against [`ABOUT.md`](./ABOUT.md),
> [`docs/DATA_FLOW.md`](./docs/DATA_FLOW.md), [`.claude/CLAUDE.md`](./.claude/CLAUDE.md),
> [`AGENTS.md`](./AGENTS.md), and the in-app About page implementation
> (`app/src/main/kotlin/app/readylytics/health/ui/about/**`,
> `app/src/main/res/values/strings.xml`).
>
> All source paths are rooted at `app/src/main/kotlin/app/readylytics/health/` unless
> stated otherwise. Line numbers reference `feature/mvp` at audit time and should be
> re-confirmed at implementation.
>
> **Traceability rule:** every recommendation below cites the discovery-report finding
> (Audit row, Open Risk, or Clarification Q) it resolves, or the explicit product decision
> that directs it. Nothing here is invented.

---

## 1. Executive Summary

The Readylytics scoring engine is a pure-Kotlin domain layer whose **core mathematics
the audit found sound** — the Readiness weights (0.4/0.3/0.3), Sleep Score weighting
(50/25/25), lnRMSSD HRV z-score, percentile nocturnal-RHR floor, ATL/CTL EMAs, Strain
Ratio Gaussian decay, TRIMP models, recovery caps, and σ-blend baseline maturation are
all classified `SCIENTIFICALLY_SUPPORTED` or `REASONABLE_ENGINEERING_APPROXIMATION`.
This plan therefore **changes no scoring formula**. It fixes the *wiring, determinism,
dead code, profile model, display alignment, and documentation* around that validated math.

**What will be fixed**

| Area | Problem (discovery finding) | Fix |
| :-- | :-- | :-- |
| Circadian | Profile thresholds computed but never consumed; live score uses a flat ±30 for all profiles; a second dead threshold system + dead shift strategy (Audit M1/M2, Risk 1/2, Q1/Q2) | Wire profile thresholds (Athlete ±20 / Active ±30 / Sedentary ±45) into the live score through a **single** resolver; delete the dead strategy/config/dual-pref code |
| Determinism | Day boundaries depend on `ZoneId.systemDefault()` (Determinism §, Risk 4, Q8) | Persist a `scoringZoneId`; thread it into all day-boundary math; recompute on change |
| Profiles | Shift Worker documented but unimplemented; `GENERAL` undocumented and overlaps Active (Audit MISMATCH, Q2; product decision) | Collapse to **Athlete / Active / Sedentary**; migrate `SHIFT_WORKER → ACTIVE` and `GENERAL → ACTIVE` |
| Phase model | 3-phase day-based enum vs 4-phase model in docs (Audit MISMATCH, Risk 2b, Q6) | 4 phases keyed off **baseline-usable** sleep-session count |
| HRV display | Two HRV-baseline statistics coexist; chart baseline can disagree with the score (Risk 6, Q7; product decision) | Display the **scoring** baseline `exp(mean(ln(RMSSD)))` everywhere, in **ms**; never expose `lnRMSSD` |
| Documentation | "Load is primary driver" wrong; hidden modifiers undocumented; in-app About drifted from both ABOUT.md and code (Audit MISMATCH ×6, UNDOCUMENTED ×9, Q3/Q5/Q9) | Correct all surfaces; document modifiers; add automated drift tests + governance |

**What stays unchanged (frozen by product decision and audit validation)**

- Readiness = `0.4·Restoration + 0.3·SleepScore + 0.3·LoadScore` (+ caps). Math frozen.
- Sleep Score weighting, **lnRMSSD methodology and HRV z-score**, RHR percentile
  methodology, ATL/CTL EMA, Strain Ratio + Load Gaussian decay, TRIMP (Banister/Cheng/
  iTRIMP), PAI, recovery caps, σ-blend baseline maturation.
- All "hidden modifiers" — HRV saturation, late-nadir penalty, Banister profile
  multipliers, PAI tiering, PAI readiness adjustment, recovery caps — are **kept**. The
  goal for them is *documentation and validation*, not removal or change.
- The HRV-display change (§6) is **display-alignment only** — it does not touch lnRMSSD
  scoring, z-scores, baseline maturation, or restoration.

**Why the validated formulas should not be altered.** The audit's Scientific Validation
table grades every core formula `SCIENTIFICALLY_SUPPORTED` or
`REASONABLE_ENGINEERING_APPROXIMATION`, with citations the code already makes (Plews 2013 /
Buchheit 2014 for lnRMSSD, Ohayon 2004 for sleep architecture, Banister/Morton for TRIMP,
Gabbett 2016 for load). The discovery report found **zero data-driven determinism
violations** in the math itself — only the timezone configuration caveat. Changing a
formula would invalidate the frozen per-day baselines, break the determinism guarantees
(`WalkForwardDeterminismTest`, `BaselineFreezeBehaviorTest`), and contradict the explicit
product decision that the scoring methodology is unchanged. Any future formula change must
clear a higher evidence bar than this audit provides.

---

## 2. Prioritized Findings

Severity reflects user/product impact, not implementation size. Each row is traceable to
the discovery report.

### Critical

| # | Finding | Source | Rationale |
| :-- | :-- | :-- | :-- |
| C1 | **Cross-timezone non-determinism.** Day boundaries use `ZoneId.systemDefault()`; the resulting midnight ms is the `DailySummaryEntity.dateMidnightMs` primary key. The same SQLite scored under a different device timezone yields different windows/scores. | Determinism Investigation; Risk 4; Q8 | Product decision #1 makes cross-device reproducibility a hard requirement. Until fixed, "identical inputs → identical outputs" is **false** across devices. Foundational — every downstream phase must build on deterministic scores. |

### High

| # | Finding | Source | Rationale |
| :-- | :-- | :-- | :-- |
| H1 | **Profile circadian thresholds not wired into the live score.** `CircadianConsistencyConfig` (Athlete 20 / Active 30 / Sedentary 45 / Shift `Int.MAX_VALUE`) is computed and validated but never consumed; the displayed score uses flat `prefs.consistencyThresholdMinutes` (30) for everyone. | Audit M1/M2; Risk 1; Q1 | Direct user-visible mismatch with ABOUT.md: an Athlete and a Sedentary user are scored on the same ±30 bar despite docs promising ±20 / ±45. This is the single most material behavioral discrepancy in the audit. |

### Medium

| # | Finding | Source | Rationale |
| :-- | :-- | :-- | :-- |
| M1 | **Two parallel circadian threshold systems.** `ScoringConfig.circadianConsistency.thresholdMinutes` (profile-derived, dead) vs `prefs.consistencyThresholdMinutes` (live) vs `circadianThresholdOverride` (stored, also unused by score). | Risk 2 | Diverging sources of truth; the override the Settings UI writes never affects scoring. |
| M2 | **Phase model divergence.** Code: 3 phases by `daysSinceInstall` (7/42). Docs: 4 phases by data maturity (Calib 1–7, Prov 8–21, Maturing 22–60, Mature 60+). | Audit MISMATCH; Risk 2b; Q6 | Product decision #6 fixes the target: session-count-based 4-phase model with confidence indicators. |
| M3 | **Profile model overbuilt vs documented.** Shift Worker per-weekday logic does not exist (strategy returns `Int.MAX_VALUE`); `GENERAL` is the actual default and is selectable in the picker yet is absent from ABOUT.md. | Audit MISMATCH; Q2; product decision | Product decisions #3 + new decision: collapse to **Athlete / Active / Sedentary**; migrate `SHIFT_WORKER → ACTIVE` and `GENERAL → ACTIVE`. |
| M4 | **Readiness "primary driver" wording wrong.** ABOUT.md says Load is the primary driver; weights make Restoration (0.4) the largest. | Audit MISMATCH; Q3 | Internal contradiction in user-facing docs; formula stays, wording is corrected. |
| M5 | **DATA_FLOW overstates Readiness caps.** §2.5 lists "illness, overreaching, workout impact, rest day success" as caps; only OVERREACHING(70)/ILLNESS(50) cap the number. | Audit MISMATCH; Q5 | Doc correction; behavior is already correct. |
| M6 | **HRV baseline display can contradict the score.** `computeHrvBaseline` returns an arithmetic median of nightly means; the score's frozen baseline is geometric `exp(mean(ln))`. A chart can show a night "above baseline" while Restoration treats it as below. | Risk 6; Q7; product decision | New product decision: display the scoring baseline (geometric, in ms) everywhere. Display-only; no math change. |

### Low

| # | Finding | Source | Rationale |
| :-- | :-- | :-- | :-- |
| L1 | **Undocumented modifiers.** HRV-score saturation (knee 1.5 / slope 0.25), late-nadir 0.95 penalty, per-profile Banister multiplier (1.0–1.75), PAI tiered accumulation + readiness adjustment, suspicious-stage reweight, EMA missing-day=0/SMA warmup, Tanaka/Karvonen HRmax. | Audit UNDOCUMENTED_BEHAVIOR ×9; Risk 5; Q9 | Decision #4 keeps them; they must be documented so no scoring behavior is user-invisible. |
| L2 | **Stale DATA_FLOW package root.** Document roots paths at `com/gregor/lauritz/healthdashboard/`; code lives at `app/readylytics/health/`. | Audit MISMATCH; Risk 3 | Pure documentation defect; every path citation is wrong. |
| L3 | **In-app About strings drifted from BOTH ABOUT.md and code.** e.g. flat ±30 threshold copy, Load sweet-spot "0.8–1.3", decay "above 1.5", day-range phases. | New finding (content audit); supports Q1/Q3/Q4/Q6 | The in-app About page is a third, independently-wrong narrative. Must be reconciled and guarded. |

---

## 3. Circadian Remediation Plan

**Goal (product-fixed):** the *displayed* circadian score uses profile-specific deviation
thresholds — **Athlete ±20, Active ±30, Sedentary ±45** — with a single source of truth,
no duplicated threshold systems, and no dead code paths. (Resolves H1, M1; Audit M1/M2;
Risk 1/2; Q1.)

### 3.1 Current state (confirmed)

Three disconnected threshold concepts exist:

1. **Profile-derived (dead for scoring).** `ScoringConfigFactory.createCircadianConsistencyConfig`
   (`ScoringConfigFactory.kt:119-134`) calls `CircadianStrategyFactory.getStrategy(profile)`
   → `RegularUserCircadianStrategy` (20/30/30/45) or `ShiftWorkerCircadianStrategy`
   (`Int.MAX_VALUE`), producing `CircadianConsistencyConfig.thresholdMinutes`. **Consumers:
   only** `ScoringConfigValidator.kt:26` and a debug log (`ComputeSleepMetricsUseCase.kt:74`).
2. **Flat live threshold (used).** `CircadianConsistencyRepository.kt:61` reads
   `prefs.consistencyThresholdMinutes` (default 30, `SettingsDefaults.kt:45`) for all
   profiles. This is what the user actually sees.
3. **User override (stored, unused by score).** `circadianThresholdOverride` (encrypted,
   `DataStoreCircadianThresholdPreferences.kt`), written by
   `ui/settings/CircadianThresholdSettingsSection.kt`, fed only into the dead config (1),
   never into the live score (2).

Dead/duplicated code to remove: `domain/circadian/CircadianConsistencyStrategy.kt`,
`CircadianStrategyFactory.kt`, `RegularUserCircadianStrategy.kt`,
`ShiftWorkerCircadianStrategy.kt`, and the threshold field of
`components/CircadianConsistencyConfig.kt`.

### 3.2 Recommended architecture (single source of truth, significantly simpler)

Collapse all three into one pure resolver and one defaults table:

```
PhysiologyProfile  ──┐
                     ├──►  CircadianThresholdDefaults.forProfile(profile): Int   (20/30/45)
circadianOverride? ──┘            │
                                  ▼
                resolveCircadianThreshold(profile, override): Int   (override ?: default)
                                  │
                                  ▼
            CircadianConsistencyRepository.compute(...)   ← uses THIS value (not prefs flat)
```

- **Keep** `CircadianThresholdDefaults` (`domain/circadian/CircadianThresholdDefaults.kt`)
  as the *only* threshold map: `ATHLETE → 20`, `ACTIVE → 30`, `SEDENTARY → 45`. Remove the
  `GENERAL` and `SHIFT_WORKER` entries (both gone — see §4).
- **Add** a single pure function `resolveCircadianThreshold(profile, override): Int =
  override ?: CircadianThresholdDefaults.forProfile(profile)`. This replaces the entire
  strategy hierarchy.
- **Delete** the four strategy/factory classes and the `thresholdMinutes`/
  `useShiftWorkerMode` fields of `CircadianConsistencyConfig` (keep the window fields if
  still needed, otherwise inline them).
- **Rewire** `CircadianConsistencyRepository`: instead of reading
  `prefs.consistencyThresholdMinutes`, accept the resolved threshold (computed from the
  day's frozen/active `PhysiologyProfile` + `circadianThresholdOverride`). The repository
  is pure Kotlin, so pass the resolved `Int` in from the caller
  (`ScoringRepositoryImpl` / the dashboard flow that builds circadian inputs).
- **Retire** the flat `prefs.consistencyThresholdMinutes` (see migration below). Keep
  `consistencyEvaluationDays` (7) and `consistencyBaselineDays` (14) as-is — they are not
  profile-specific and the audit found them `EXACT_MATCH`.

### 3.3 User override

- **Keep** the existing `circadianThresholdOverride` (encrypted) as the **only** user knob.
  After remediation it actually reaches the score (today it does not). Validation stays in
  `CircadianThresholdValue` (0–90) / `CircadianThresholdValidator`.
- **Settings UI** (`CircadianThresholdSettingsSection.kt`): relabel the slider so its
  baseline reflects the **profile default** (e.g. "Default for Active: ±30 min — override
  below"), and **remove** the `ShiftWorkerModeSelector` block entirely (§4). The slider
  writes `circadianThresholdOverride` exactly as today.
- **Onboarding:** no new step. Selecting the profile now implicitly sets the threshold
  (via the defaults table) — this is the behavior ABOUT.md already promises. No onboarding
  write to a threshold pref is required.

### 3.4 Migration & settings impact

- For existing users whose `consistencyThresholdMinutes` was left at the default (30), do
  nothing — they fall through to the profile default.
- For existing users who **changed** `consistencyThresholdMinutes` away from 30, a one-time
  DataStore migration copies that value into `circadianThresholdOverride` (only if no
  override is already set), preserving their intent. Then the `consistencyThresholdMinutes`
  field is deprecated/removed from the proto-read path.
- This change alters displayed circadian scores for Athlete/Sedentary users who relied on
  the flat default — this is the **intended correction** (ABOUT.md already documents the
  profile thresholds). Document it in release notes (see §8/§12).

### 3.5 Outcome

One threshold value, derived from `profile (+ optional override)`, consumed by exactly one
place (the live repository). Four classes, two dead config fields, and one redundant pref
are deleted. Net: fewer moving parts, score matches docs.

---

## 4. Profile Simplification — Remove Shift Worker and GENERAL (both → Active)

**Goal (product-fixed):** the only supported profiles become **Athlete / Active /
Sedentary**. Remove `SHIFT_WORKER` and `GENERAL`; migrate existing users of both to
**Active**. Do **not** keep `GENERAL` as a hidden or internal profile. (Resolves M3; Audit
MISMATCH; Q2; new product decision.)

### 4.1 These are product decisions, not neutral migrations

- **Shift Worker → Active.** Today `SHIFT_WORKER`'s physiology constants are **identical to
  `SEDENTARY`** (`PhysiologyProfile.kt:38-46`: `lnSigmaPrior 0.20`, `banisterMultiplier 1.75`,
  `defaultChengBeta 0.11`, `defaultItrimB 1.5`, `defaultSleepGoalHours 7.5`). The
  mathematically closest remap is therefore **Sedentary**; the product has chosen **Active**
  (tighter HRV/PAI tuning). State this trade-off plainly and surface a one-time in-app notice
  so migrated users can re-select Sedentary if they prefer.
- **GENERAL → Active.** `GENERAL` is the current default
  (`SettingsDefaults.PHYSIOLOGY_PROFILE = GENERAL`, proto value `PROFILE_GENERAL = 0`) and is
  selectable in the picker, yet **is never documented in ABOUT.md** and overlaps conceptually
  with Active. Keeping it internally while showing "Active" in the UI would recreate exactly
  the code/doc/UI drift this effort removes. A three-profile model is easier for users and to
  maintain. Its constants differ from Active only in `lnSigmaPrior 0.18` (vs `0.15`) and
  `PAI_SCALING_GENERAL 0.20` (vs `PAI_SCALING_ACTIVE 0.18`); it is already grouped with Active
  for emergency-flag and HRV-saturation tiers (`ScoringConfigFactory.kt:98,114`). **Migrated
  GENERAL users may therefore see small score changes** (a slightly narrower HRV σ-prior → a
  touch more HRV sensitivity; marginally lower PAI scaling). This is an **accepted product
  trade-off** and must be documented in release notes.

### 4.2 Inventory of references

| Category | Shift Worker locations | GENERAL locations |
| :-- | :-- | :-- |
| Enum definition | `PhysiologyProfile.kt:38-46` (`SHIFT_WORKER`) | `PhysiologyProfile.kt` (`GENERAL`) |
| Default profile | — | `SettingsDefaults.kt:63` (`PHYSIOLOGY_PROFILE = GENERAL`) |
| Proto / serialization | `proto/user_preferences.proto` (`PROFILE_SHIFT_WORKER = 4`); `PhysiologyPreferences.kt:160`; `UserPreferencesSerializer.kt:148`; `UserPreferences.kt:89`; `DataStoreModule.kt:233` | `proto/user_preferences.proto` (`PROFILE_GENERAL = 0` — the proto3 zero/default value); same serializer/boundary sites |
| Backup / restore | `LocalRestoreManager.kt:459` (`PhysiologyProfileProto.valueOf(it)`) | same site |
| Scoring config | `ScoringConfigFactory.kt:88,103,116,130`; `PaiCalculator.kt:14`; `ScoringConstants.PAI_SCALING_SHIFT_WORKER = 0.20` | `ScoringConfigFactory.kt:86,98,114`; `PaiCalculator.kt:12`; `ScoringConstants.PAI_SCALING_GENERAL = 0.20` |
| Circadian | `CircadianStrategyFactory.kt:8`; `CircadianThresholdDefaults.kt:12-14`; `RegularUserCircadianStrategy.kt:15`; `ShiftWorkerCircadianStrategy.kt` (whole file) | `CircadianThresholdDefaults.kt` (`GENERAL → 30`) |
| UI | `PhysiologyProfilePicker.kt:20`; `CircadianThresholdSettingsSection.kt:38,98-100,151,351-355`; `SettingsScreen.kt:455` | `PhysiologyProfilePicker.kt:18` ("General population" entry) |
| Strings | `profile_title_shift_worker`; `about_circadian_caveat` shift mention | (no dedicated profile string; ensure none introduced) |
| Tests | `domain/circadian/CircadianStrategyTest.kt:9,43-54`; `data/local/CircadianStrategyTest.kt:9`; `PaiCalculatorTest.kt:21` | `PaiCalculatorTest.kt` (GENERAL scaling assertions, if any) |

### 4.3 Removal & migration strategy

1. **Domain enum:** delete both `SHIFT_WORKER` and `GENERAL` from `PhysiologyProfile`.
   Remaining: `ATHLETE, ACTIVE, SEDENTARY`.
2. **Default profile:** change `SettingsDefaults.PHYSIOLOGY_PROFILE` from `GENERAL` to
   **`ACTIVE`**.
3. **Proto compatibility (do not break the wire format):**
   - **Keep both proto enumerators** `PROFILE_GENERAL = 0` and `PROFILE_SHIFT_WORKER = 4`;
     never delete or reuse those ordinals. `PROFILE_GENERAL = 0` is the proto3 zero value, so
     it must remain a valid enumerator (optionally rename to `PROFILE_UNSPECIFIED = 0` with a
     reserved old name) — it cannot simply be removed.
   - **Map at the read boundary:** wherever proto → domain conversion happens
     (`UserPreferences.kt:89`, `DataStoreModule.kt:233`, `LocalRestoreManager.kt:459`),
     translate **`PROFILE_GENERAL` → `ACTIVE`** and **`PROFILE_SHIFT_WORKER` → `ACTIVE`**.
     This makes any stored, defaulted, or restored value resolve to a supported profile even
     before the rewrite migration runs.
4. **One-time DataStore migration:** rewrite persisted `PROFILE_GENERAL` and
   `PROFILE_SHIFT_WORKER` → `PROFILE_ACTIVE`, so the stored value is canonical going forward.
5. **Scoring config / circadian / UI / strings / tests:** delete all `SHIFT_WORKER` and
   `GENERAL` branches; remove `ShiftWorkerCircadianStrategy`, `PAI_SCALING_SHIFT_WORKER`,
   `PAI_SCALING_GENERAL` (GENERAL users now use `PAI_SCALING_ACTIVE`), the picker entries, the
   settings `ShiftWorkerModeSelector`, `profile_title_shift_worker`, and the shift mention in
   `about_circadian_caveat`. Merge the `GENERAL` arms of `ScoringConfigFactory` (lines
   86/98/114) into the `ACTIVE` arms. Update/remove the named tests; add migration tests (§11).
6. **Profile snapshot history:** `DailySummaryEntity.snapshotProfile` may contain
   `SHIFT_WORKER` or `GENERAL` on historical rows. Apply the same read-time mapping → `ACTIVE`
   (or leave historical snapshots intact as an immutable record and only map on read for
   display). Document the choice.
7. **Onboarding / settings / About / docs:** remove both from the picker and all copy;
   ensure onboarding offers exactly Athlete / Active / Sedentary; update ABOUT.md, the in-app
   About page, and DATA_FLOW (§8).

### 4.4 Rollout

Ship behind the normal release; the read-boundary mapping (step 3) guarantees safety even
if the rewrite migration (step 4) is deferred or fails. Validate with migration tests and a
backup-restore round-trip (both `GENERAL` and `SHIFT_WORKER` payloads must restore as Active)
before release. Include a one-time in-app notice and a release note that **existing GENERAL
and Shift Worker users were migrated to Active and may see small score changes.**

---

## 5. Timezone Determinism Plan

**Goal (product-fixed):** identical SQLite + identical preferences ⇒ identical scores,
regardless of device timezone. Remove scoring's dependence on `ZoneId.systemDefault()`.
(Resolves C1; Determinism §; Risk 4; Q8.)

### 5.1 Current state (confirmed)

Day boundaries are computed with `ZoneId.systemDefault()` at:
`ScoringRepositoryImpl.kt:74` (the `dateMidnightMs` primary-key source),
`CircadianConsistencyRepository.kt:68/100/146`, `HrvBaselineProvider.kt:29/42`,
`RhrBaselineProvider.kt:38/56/67` — all pure-Kotlin domain. A `TimezoneProvider`
abstraction exists (`domain/util/TimezoneProvider.kt`, `data/util/TimezoneProviderImpl.kt`)
but still emits `systemDefault()` and is **not** injected into scoring. No timezone is
stored in `UserPreferences` or `DailySummaryEntity`. Determinism tests assume a fixed zone.

### 5.2 Timezone ownership & storage

- Add **`scoringZoneId: String`** (IANA id, e.g. `"Europe/Berlin"`) to `UserPreferences`
  and `proto/user_preferences.proto`. This makes timezone part of "identical preferences" —
  an explicit, stored scoring input rather than an ambient device property.
- **Ownership:** the scoring timezone is a *preference*, set once and stable. The device's
  live zone (`systemDefault()`) may still drive UI navigation ("which date is today"), but
  **score computation for a given `LocalDate` always uses `scoringZoneId`.**

### 5.3 Threading the zone into scoring

- `ScoringRepositoryImpl` resolves `scoringZoneId` from prefs and passes a `ZoneId` (or the
  derived midnight bounds) **down into** every day-boundary computation, replacing
  `ZoneId.systemDefault()` at the five sites in §5.1. Because `domain/scoring/**` must stay
  Android-free, the zone is a **parameter**, not a provider lookup — consistent with the
  existing pure-Kotlin invariant in `.claude/CLAUDE.md`.
- No formula changes; only the source of the day-window boundary changes.

### 5.4 Migration (preserves historical reproducibility)

- **Existing users:** a one-time migration seeds `scoringZoneId = ZoneId.systemDefault()`
  captured at upgrade. Their existing `DailySummaryEntity.dateMidnightMs` keys were computed
  under that same zone, so historical rows remain valid and **no recompute is forced**.
- **New users:** capture the device zone at onboarding into `scoringZoneId`.
- **User-initiated zone change:** if the scoring zone is ever changed (settings option, if
  exposed), trigger the existing full-historical recompute (`HealthResyncWorker` /
  `FullHistoricalResyncUseCase`) so all `dateMidnightMs` boundaries are rebuilt under the new
  zone. Reuse the established recalc path — do not add a parallel one.

### 5.5 How historical scores remain reproducible

Because the zone used to compute each day is now a stored preference (seeded to the exact
zone under which existing summaries were originally produced), replaying the same SQLite +
the same preferences reproduces the same midnight boundaries and therefore the same scores —
on any device, in any ambient timezone. The frozen-baseline + single-code-path guarantees
the audit already verified (`WalkForwardDeterminismTest`, `SyncScopeDeterminismTest`,
`BaselineFreezeBehaviorTest`) continue to hold; we remove the one configuration-level caveat
they could not cover.

### 5.6 Explicit answer after remediation

> **Yes, identical inputs always produce identical outputs.** Timezone is no longer an
> ambient device property; it is part of the stored preferences that define "identical
> inputs."

---

## 6. HRV Baseline Display Alignment

**Goal (product-fixed):** **one** HRV baseline definition, **one** HRV story, **one**
user-facing interpretation — and HRV always shown in **milliseconds**. (Resolves M6; Risk 6;
Q7; new product decision.) **This is a display-alignment change only — no scoring math
changes.**

### 6.1 The problem (confirmed)

Two "HRV baseline" statistics coexist:
- **Geometric (the score's baseline):** `hrvMuMssd = mean(ln(nightly RMSSD means))`, surfaced
  as `exp(mu)` by `HrvBaselineProvider.getPreciseHrvBaseline()`. The nightly HRV z-score that
  drives Restoration is `(ln(today) − mu) / sigma`.
- **Arithmetic (a convenience value):** `BaselineComputer.computeHrvBaseline` returns the
  median of nightly **raw** RMSSD means.

Because RMSSD is right-skewed, the geometric value is systematically lower, so a chart that
labels the arithmetic median as "your HRV baseline" can show a night *above* the line while
Restoration scores it as average/below — the chart and the score tell different stories.

### 6.2 Authoritative displayed baseline

```
Displayed HRV Baseline (ms) = exp( mean( ln(nightly RMSSD means) ) )
```

i.e. the **scoring** baseline (`exp(mu)`), converted back to milliseconds before display.
Everything HRV the user sees is in **ms**; `lnRMSSD` is **never** exposed in the UI:
- nightly HRV as raw RMSSD in `ms`,
- HRV charts and trend lines in `ms`,
- HRV baseline (band/line/card) from `exp(mu)` in `ms`.

### 6.3 Implementation strategy

- **Identify all HRV-baseline display consumers** and point them at the scoring baseline
  (`exp(mu)` → ms): confirmed candidates `ui/vitals/VitalsViewModel.kt` (HRV chart + baseline
  band), the settings HRV-baseline surfaces (`ui/settings/SleepSettingsViewModel.kt`,
  `AdvancedSettings.kt`, `SettingsState.kt`, `SettingsEvent.kt`), and the display projection in
  `data/repository/ScoringRepositoryImpl.kt` (`DailyMetrics`/`DailyMetricsMapper`). Replace any
  arithmetic/raw-median baseline display with the geometric baseline.
- **Retire or rename** `BaselineComputer.computeHrvBaseline` (arithmetic median) if it is no
  longer needed once displays converge on `getPreciseHrvBaseline`. Confirm its remaining call
  sites (`HrvBaselineProvider.kt:30/43`, `ScoringRepositoryImpl.kt:254/304`) and ensure none of
  them reintroduce the arithmetic statistic into a user-facing baseline; keep a single source.
- **Synchronize the methodology description** across charts, cards, tooltips, score
  explanations, `ABOUT.md`, and the in-app About page: the baseline is the geometric mean of
  lnRMSSD shown in ms (the glossary already states lnRMSSD is used internally — make explicit
  that the *displayed* number is `exp(mu)` in ms).

### 6.4 Explicitly not changed

lnRMSSD scoring, HRV z-score calculations, baseline maturation/σ-blend logic, and restoration
calculations are untouched. Only the *displayed* baseline source is unified.

---

## 7. Documentation Synchronization Architecture

**Goal:** prevent future drift between the scoring implementation and every documentation
surface: `ABOUT.md`, `docs/DATA_FLOW.md`, the in-app About page, onboarding, score
explanation screens, tooltips, help dialogs, FAQ/empty-state content.

### 7.1 The drift problem (confirmed)

There are currently **three** independently-maintained narratives that disagree:

1. **Code constants** (`ScoringConstants.kt`, `CircadianThresholdDefaults`, `PhysiologyProfile`).
2. **`ABOUT.md`** (authored markdown).
3. **In-app About** — ~76 `about_*` keys in `res/values/strings.xml`, rendered by a tiny
   `parseMarkdown()` in `ui/about/AboutComponents.kt`. **ABOUT.md is not bundled or rendered
   at runtime.** These strings have drifted from *both* of the above (flat ±30 threshold,
   Load "0.8–1.3" sweet spot, decay "above 1.5", day-range phases).

### 7.2 Options considered

| Option | Pros | Cons |
| :-- | :-- | :-- |
| **A. Canonical ABOUT.md + automated drift tests** *(recommended; chosen)* | Keeps the CLAUDE.md i18n mandate (`strings.xml` stays the runtime/translation source); ABOUT.md stays human-readable; a JVM test fails the build on any numeric divergence between ABOUT.md, English `about_*` defaults, and `ScoringConstants`. Low tooling cost. | Prose can still drift (tests guard numbers, not wording); requires authoring the parser/assertions. |
| **B. Generate `strings.xml` `about_*` from ABOUT.md** | Strongest single-source guarantee for English copy. | Heavy build tooling; fights the translator/i18n workflow; brittle markdown→XML codegen; large change. |
| **C. Bundle ABOUT.md as an asset, render markdown in-app** | One literal source for the About page. | Breaks the `strings.xml`/i18n mandate; loses per-tooltip/per-card granularity; no localization. |
| **D. Centralize numbers only** (shared constants object referenced by code, a generated ABOUT.md fragment, and string tests) | Guarantees numeric consistency with minimal prose coupling. | Prose still manual; partial guarantee; adds an indirection layer. |

### 7.3 Recommendation

**Adopt Option A.** `ABOUT.md` is the canonical authored narrative. `strings.xml` remains
the in-app/i18n delivery vehicle. A new pure-JVM test (zero Android deps, per CLAUDE.md)
extracts the numeric claims from ABOUT.md, the English `about_*` defaults, and
`ScoringConstants`/`CircadianThresholdDefaults`, and **fails on divergence** (the drift
detector in §11). Pair it with the governance rules in §10 so prose updates travel together
in the same PR. This is the lightest change that makes the *numbers* impossible to drift and
makes prose drift a review checklist item.

---

## 8. Documentation Remediation

Concrete edit plan per surface. Content corrections are listed once and applied to every
surface that repeats them.

### Content corrections (apply everywhere the topic appears)

| Topic | Correct statement | Wrong today in |
| :-- | :-- | :-- |
| Readiness driver (M4/Q3) | Restoration (0.4) is the **largest** weight; Load and Sleep are 0.3 each. "If you significantly overload, Readiness drops; sustained poor restoration or sleep also pulls it down." Remove "Load is the primary driver." | `ABOUT.md:121`; `about_readiness_intro` |
| Readiness caps (M5/Q5) | Only **OVERREACHING (cap 70)** and **ILLNESS_ONSET (cap 50)** cap the number, on two consecutive nights. Workout-impact / rest-day flags are **informational only**. | `DATA_FLOW.md §2.5` |
| Circadian thresholds (H1/Q1) | Profile-specific: **Athlete ±20, Active ±30, Sedentary ±45**; full score within threshold, linear decay to threshold+60, 0 beyond. | `about_threshold_*` (flat ±30 / 30–90) |
| Load curve (Q4/L3) | `sr ≤ 1.3 → 100`; `sr > 1.3 → 100·exp(−2.5·(sr−1.3)²)` (Gaussian decay from 1.3, **no** 0.8 floor, **no** 1.3–1.5 linear segment). | `about_ratio_sweet_spot` ("0.8–1.3"), `about_ratio_quadratic` ("above 1.5") |
| Profiles | **Athlete / Active / Sedentary** only. Remove all Shift Worker **and General** copy. | ABOUT.md profile table & "Special handling for shift workers"; `about_circadian_caveat`; `profile_title_shift_worker`; picker "General population" |
| Phase model (M2/Q6) | 4 phases by **baseline-usable** session count: Calibration 0–6, Early Baseline 7–20, Maturing 21–59, Mature 60+; confidence Not Ready / Low / Medium / High. Progress depends on nights with usable HRV/RHR, **not** calendar age. | `about_phase_*` (day ranges); ABOUT.md "How long until your scores stabilise"; `Phase.kt` |
| HRV baseline display (M6/Q7) | The displayed HRV baseline is the **geometric mean of lnRMSSD**, `exp(mean(ln(RMSSD)))`, shown in **ms** — the same baseline the Restoration score uses. HRV is always shown in ms; lnRMSSD is internal only. | Charts/cards that may show an arithmetic-median baseline; glossary should clarify the displayed value |
| Hidden modifiers (L1/Q9) | Document: HRV-score saturation (knee 1.5, slope 0.25); late-nadir 0.95 penalty (after 67% of session); per-profile Banister multiplier (1.0–1.75); PAI tiered accumulation (1.0/0.5/0.25) + readiness adjustment + daily cap 75; suspicious-stage reweight (dur 0.75 / arch 0.0); EMA missing-day=0 + SMA warmup; Tanaka HRmax `208−0.7·age` (Karvonen fallback). | Absent from ABOUT.md / DATA_FLOW |
| Timezone | Scores are computed against a stored scoring timezone, so identical data + preferences reproduce identical scores across devices. | Absent |
| Package root (L2) | `app/readylytics/health/` (not `com/gregor/lauritz/healthdashboard/`). | `DATA_FLOW.md:14` and every path citation |

### Per-artifact plan

- **`ABOUT.md`:** apply all corrections above; rewrite the "Your profile matters" and
  "Circadian Consistency" sections (drop Shift Worker **and** General); replace the "How long
  until your scores stabilise" section with the session-count 4-phase model; add a "Hidden
  modifiers" / "Score adjustments" subsection; clarify in the glossary that the *displayed*
  HRV baseline is `exp(mu)` in ms; add a short "Determinism & timezone" note; reconcile the
  Load §4 "~1.5" narrative with the 1.3 decay onset (Q4).
- **`docs/DATA_FLOW.md`:** fix the package root (L2); correct the §2.5 caps wording (M5);
  add ownership pointers for the new `scoringZoneId`, the resolved circadian threshold, the
  session-count phase calculator, and the unified HRV-display baseline source; note Shift
  Worker + GENERAL removal.
- **In-app About page (`strings.xml` `about_*`):** apply every content correction;
  re-key/rewrite `about_threshold_*`, `about_ratio_*`, `about_phase_*`, and the
  `about_circadian_caveat` shift mention; add modifier/timezone/HRV-baseline strings.
- **Score explanation screens / tooltips (`tooltip_*`, `MetricTooltip.kt` consumers):**
  align readiness, circadian, load, phase, and HRV-baseline tooltips with the corrected numbers.
- **Onboarding (`ui/onboarding/**`, `onboarding_feature_*`):** ensure profile copy lists
  only Athlete/Active/Sedentary; keep feature blurbs accurate (note circadian threshold is
  set by profile).
- **Empty states / calibration banner:** update the calibration/"Not Ready" copy to the
  session-count phase language.
- **FAQ / help dialogs:** none found as separate files; fold any relevant Q&A into the
  About strings. If FAQ content is added later, it inherits the drift test.

**Goal:** no documentation drift — the same numbers and phase/profile/threshold/HRV-baseline
definitions appear identically across all surfaces.

---

## 9. In-App About Page Remediation

Per-surface audit. Almost all content originates in `res/values/strings.xml` and is
rendered by `ui/about/AboutComponents.parseMarkdown()` (bold/italic only); tooltips render
through `ui/components/MetricTooltip.kt` from `tooltip_*` strings.

| Surface | Current content source | Ownership | Update strategy | Test strategy |
| :-- | :-- | :-- | :-- | :-- |
| About page (scoring sections) | `strings.xml` `about_*` (~76 keys) → `AboutScreen.kt` / `AboutComponents.kt` | About page | Apply §8 corrections; remove Shift Worker + General; rewrite threshold/ratio/phase/HRV keys | Drift test (§11) asserts numbers vs ABOUT.md + constants; Compose render test that key sections show |
| Score explanation screens | `strings.xml` (per-metric) | Dashboard/detail VMs | Align readiness/circadian/load/HRV wording | Drift test + screenshot/semantics test |
| Tooltips | `tooltip_*` via `MetricTooltip` | Metric cards | Correct numbers (thresholds, sweet spot, caps, HRV baseline in ms) | Drift test covers tooltip numeric claims |
| Help dialogs / educational cards | `strings.xml` | Respective screens | Same corrections | Drift test |
| Onboarding explanations | `onboarding_feature_*` + profile picker | `ui/onboarding/**` | Athlete/Active/Sedentary only; profile-sets-threshold note | Picker test asserts 3 profiles, no Shift Worker/General |
| Empty states / calibration banner | `strings.xml` | Dashboard/Vitals | Session-count phase + "Not Ready" copy | Phase-UI test |
| HRV chart/card/baseline | `VitalsViewModel` + `strings.xml` | Vitals | Baseline from `exp(mu)` in ms; methodology copy | HRV-display regression test (§11) |
| FAQ content | none separate | — | Fold into About strings | n/a |

**Goal:** a user receives the *same* explanation whether they read ABOUT.md, the in-app
About page, a tooltip, a score-detail screen, or onboarding. After remediation:

- Hidden modifiers (HRV saturation, late-nadir, Banister multipliers, PAI tiering, recovery
  caps) each have user-facing copy — no scoring behavior is undocumented.
- Shift Worker and General are absent from every user-facing surface.
- Circadian threshold behavior is described per profile (±20/±30/±45) and matches the live
  score.
- The 4-phase session-count model is described identically everywhere.
- The HRV baseline is described and displayed consistently as `exp(mu)` in ms.

---

## 10. CLAUDE.md Governance Update Plan

Extend `.claude/CLAUDE.md` (and mirror in `AGENTS.md`, which duplicates it) so the
implementation, ABOUT.md, DATA_FLOW.md, and the in-app About page cannot diverge again.
Today CLAUDE.md only makes `DATA_FLOW.md` load-bearing.

### 10.1 Documentation Synchronization Rule (new)

> Any PR that changes formulas, thresholds, coefficients, scoring behavior, readiness
> logic, sleep logic, circadian logic, recovery logic, the phase model, the profile set, the
> HRV-display baseline, or onboarding scoring explanations **must**, in the same PR, update:
> `ABOUT.md`, the relevant `docs/DATA_FLOW.md` sections, the in-app About page (`about_*` /
> `tooltip_*` strings), and all affected user-facing scoring explanations. Treat a stale
> scoring doc as a broken build.

### 10.2 Documentation Review Checklist (new)

> Reviewers must verify, before approval: implementation matches `ABOUT.md`; implementation
> matches the in-app About page; implementation matches onboarding explanations; the drift
> test passes.

### 10.3 Single-source-of-truth strategy (new)

State that `ABOUT.md` is the canonical scoring narrative and the in-app `about_*` strings
are the i18n delivery copy, kept consistent by the automated **drift-detection test** (§11).
Record the options/pros-cons from §7 and that Option A is adopted. Extend the existing
"`DATA_FLOW.md` is load-bearing" clause to explicitly cover `ABOUT.md` and the `about_*`
strings.

### 10.4 Recommended single-source approach (answer to the task's question)

Prefer **synchronizing** the in-app About page with ABOUT.md via the drift test (Option A)
over **generating** it (Option B) or **bundling/rendering** ABOUT.md (Option C): generation
and bundling both fight the mandatory `strings.xml` i18n workflow, while the drift test
delivers the consistency guarantee at a fraction of the tooling cost and risk.

---

## 11. Testing Strategy

All scoring tests stay pure-JVM (zero Android deps), mirroring the source package, per
CLAUDE.md / `AGENTS.md`.

### Circadian
- Live score uses the **profile** threshold: assert Athlete→20, Active→30, Sedentary→45
  drive the displayed score (not a flat 30). Boundary cases at `dev = threshold`,
  `threshold+60`, `> threshold+60`.
- Override precedence: `circadianThresholdOverride` overrides the profile default end-to-end
  into the score.
- Dead-code guard: a test/inspection ensuring the removed strategy classes are gone and the
  repository no longer reads `prefs.consistencyThresholdMinutes`.

### Migration (profile simplification)
- DataStore migration: stored `PROFILE_SHIFT_WORKER` → `PROFILE_ACTIVE` **and**
  `PROFILE_GENERAL` → `PROFILE_ACTIVE`.
- Read-boundary mapping: proto `PROFILE_SHIFT_WORKER` and `PROFILE_GENERAL` (including the
  proto3 default 0) resolve to `ACTIVE` before rewrite.
- Default-profile test: a fresh install / unset profile resolves to `ACTIVE`.
- Backup/restore round-trip: backups containing Shift Worker or General restore as Active.
- Historical `snapshotProfile = SHIFT_WORKER`/`GENERAL` rows resolve/display as Active per
  chosen policy.

### Determinism (timezone)
- **Cross-timezone equality:** score a fixed dataset with the JVM default zone set to two
  different zones (e.g. `UTC` vs `America/Los_Angeles`) but the **same** stored
  `scoringZoneId`; assert identical sleep/readiness/HRV/RHR/μ/σ and identical
  `dateMidnightMs`. (This is the gap today.)
- Migration-seed test: a pre-existing summary remains byte-identical after seeding
  `scoringZoneId` to the original zone.
- Extend `WalkForwardDeterminismTest` / `SyncScopeDeterminismTest` to pass an explicit zone.

### Readiness (unchanged formula)
- Golden test: `Readiness = 0.4·sRest + 0.3·Sleep + 0.3·Load`, then OVERREACHING cap 70,
  then ILLNESS cap 50, then clamp — exact values, guarding against accidental weight/cap
  edits.

### Modifiers
- HRV saturation: z above knee 1.5 dampened by slope 0.25; below knee unchanged.
- Late-nadir: 0.95 multiplier when HR nadir after 67% of session; none otherwise.
- Recovery caps: two-consecutive-night gating for OVERREACHING/ILLNESS; single night does
  not cap; workout-impact/rest-day flags do **not** cap.

### HRV display alignment
- Displayed HRV baseline originates from the **scoring** source: assert the value shown by
  the HRV chart/card equals `exp(hrvMuMssd)` converted to ms (within rounding), not the
  arithmetic median.
- No-ln guard: assert no user-facing HRV string/format surfaces a log value; HRV is in ms.
- Equivalence: where `computeHrvBaseline` is retired/renamed, assert displays still resolve
  to the geometric baseline.

### Phase logic
- Boundaries on **baseline-usable** session counts: 6→Calibration, 7→Early Baseline,
  20→Early Baseline, 21→Maturing, 59→Maturing, 60→Mature.
- Confidence progression Not Ready → Low → Medium → High tracks the phase.
- **Negative test:** HRV-missing / invalid nights do **not** increment the count or advance
  confidence (uses the `canContributeToBaseline` definition, not raw sleep-session count).

### Documentation consistency (drift detection)
- A pure-JVM test parses numeric claims from `ABOUT.md`, the English `about_*`/`tooltip_*`
  defaults, and `ScoringConstants`/`CircadianThresholdDefaults`/`PhysiologyProfile`, and
  fails on any mismatch for: Sleep weights (50/25/25), Readiness weights (0.4/0.3/0.3) and
  caps (70/50), circadian thresholds (20/30/45), load sweet spot (1.3) and decay-k (2.5),
  age-band deep/REM targets, RHR percentile default (5), phase boundaries (7/21/60), and
  HRV sensitivity tiers (1.2/1.5/2.0).
- A presence test: every profile/threshold/phase value referenced in `ABOUT.md` exists in
  `strings.xml`, and no surface mentions "Shift Worker" or "General population".

---

## 12. Risk Assessment

| Change | Implementation risk | Migration risk | User-facing risk | Data-integrity risk | Mitigation |
| :-- | :-- | :-- | :-- | :-- | :-- |
| Timezone determinism (§5) | Medium — threading a zone through several pure functions | Low — seed = current zone preserves existing keys | Low — scores unchanged for existing users | **Low** — no recompute forced; keys preserved | Seed-migration test; cross-tz determinism test; reuse existing recalc path for any zone change |
| Circadian rewire (§3) | Low–Medium — delete strategies, repoint one input | Low — default-30 users unaffected; non-default → override | **Medium** — Athlete/Sedentary scores shift to ±20/±45 (intended correction) | Low — display value only, no baseline change | Release note; boundary tests; the change makes score match long-standing ABOUT.md copy |
| Profile simplification (§4) | Low–Medium | **Medium** — proto/enum/backups for two values incl. proto3 default 0 | Medium — Shift Worker + General users change profile; small score shift for General | Low — proto values reserved, read-mapped, then rewritten | Reserve ordinals 0 & 4; read-boundary map → Active; rewrite migration; backup round-trip test; in-app notice + release note |
| Phase model 4-state (§ M2) | Medium — new enum + confidence UI | Low — `snapshotCalibrationPhase` is nullable TEXT, no schema change | Low–Medium — new confidence labels | Low — historical snapshot strings retained/mapped on read | Reuse existing `totalValidHrvNights`; boundary + negative tests; recompute optional |
| HRV display alignment (§6) | Low — repoint display consumers | None | Low–Positive — chart now matches the score | None — no math change | Display-source regression test; no-ln guard; converge on one baseline source |
| Documentation + drift tests (§7–§10) | Low | None | Positive — accurate docs | None | Drift test guards numbers; review checklist guards prose |

Cross-cutting: **no formula changes**, so frozen baselines and the audit's determinism
contracts remain valid throughout.

---

## 13. Rollout Order

Sequenced to minimize regressions. **Timezone determinism is first** (foundational
behavior); the **documentation drift tests are last** because they must assert against the
final constants/strings produced by the behavioral phases.

| Phase | Work | Rationale | Exit criteria |
| :-- | :-- | :-- | :-- |
| **1. Timezone determinism** | Stored `scoringZoneId`, zone threading into the 5 day-boundary sites, seed migration, recompute-on-change | Everything downstream must be reproducible first | Cross-tz determinism test green; existing determinism tests pass with explicit zone |
| **2. Circadian single-source** | Resolver + defaults table; delete strategies/dead config/dual pref; repoint live repository; settings relabel | The highest-impact behavioral mismatch (H1) | Profile-threshold tests green; dead-code removed |
| **3. Profile simplification** | Remove Shift Worker + General; enum/proto/config/UI/strings/tests; read-map + rewrite migration; default → Active; in-app notice | Depends on circadian (shares profile/threshold code) | Migration + backup round-trip tests green |
| **4. Phase model** | 4-state enum on baseline-usable count; ConfidenceLevel (Not Ready/Low/Medium/High); dashboard + About UI | Builds on stable profiles/determinism | Boundary + negative phase tests green |
| **5. HRV display alignment** | Repoint HRV-baseline display consumers to `exp(mu)` in ms; retire/rename arithmetic baseline; methodology copy | Display correctness; feeds the doc/drift work | HRV-display regression test green; one baseline source |
| **6. Documentation + governance + drift tests** | Apply §8/§9 corrections across all surfaces; CLAUDE.md/AGENTS.md rules; drift-detection + presence tests | Locks in the now-final constants/strings | Drift test green; no surface mentions Shift Worker/General; all numbers reconciled |

Behavior-independent doc fixes (e.g. the DATA_FLOW package-root path, L2) may ride along in
any earlier phase, but the automated guardrail is established in Phase 6.

---

## Resolved Product Decisions (recorded for traceability)

All clarification questions raised during planning are now settled by explicit product
decisions; **no open product questions remain.**

1. **Q7 — HRV baseline statistic for display → resolved.** Display the **scoring** baseline
   `exp(mean(ln(RMSSD)))`, converted to **ms**, everywhere; never expose `lnRMSSD`. Display-
   only; no math change. (§6.)
2. **`GENERAL` profile identity → resolved.** `GENERAL` is **removed**, not kept internal;
   migrated to **Active** alongside Shift Worker. Final profiles: Athlete / Active /
   Sedentary. Small score changes for former General users are an accepted trade-off. (§4.)
3. **Shift Worker → Active → confirmed** as a deliberate product decision even though today's
   Shift constants match **Sedentary** (documented as a behavior change in §4).
4. **Documentation single-source approach → canonical ABOUT.md + drift tests** (Option A, §7).

---

*This plan is implementation-ready: every recommendation traces to a confirmed finding in
`SCORING_ENGINE_DISCOVERY_REPORT.md` or an explicit product decision, and no change alters
the scientifically validated scoring mathematics.*
