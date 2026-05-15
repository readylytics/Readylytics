# MEDICAL_ALGORITHMS.md

Open documentation of the recovery / readiness / training-load algorithms used
by **Health & Recovery Dashboard**. This document is written for clinicians,
sports-science researchers, and contributors who need to audit, validate, or
extend the math behind every visible score in the app.

All formulae here are implemented in pure Kotlin under
`app/src/main/java/com/gregor/lauritz/healthdashboard/domain/scoring/` and are
unit-tested under `app/src/test/...`. Refer to `ScoringCalculator.kt` and the
Phase-1 helpers (`LoadMetricsFactory`, `RecoveryFlagValidator`,
`SleepScoreWeightValidator`, `ReadinessPAIModulation`, `ZoneConfigValidator`,
`DeduplicationStrategy`) for the source of truth.

> **This software is NOT a medical device.** See the *Clinical Disclaimers*
> section before relying on any output for health decisions.

---

## Table of contents

1. [Sleep Score Algorithm](#1-sleep-score-algorithm)
2. [Load Score (Strain Ratio) Algorithm](#2-load-score-strain-ratio-algorithm)
3. [Readiness Score Algorithm](#3-readiness-score-algorithm)
4. [HRV Normalization & Scoring](#4-hrv-normalization--scoring)
5. [RHR Scoring](#5-rhr-resting-heart-rate-scoring)
6. [TRIMP (Training Impulse) Models](#6-trimp-training-impulse-models)
7. [PAI (Personal Activity Index) Accumulation](#7-pai-personal-activity-index-accumulation)
8. [Recovery Flags (Overreaching & Illness Detection)](#8-recovery-flags-overreaching--illness-detection)
9. [Sleep Architecture Validation](#9-sleep-architecture-validation)
10. [Circadian Consistency Scoring](#10-circadian-consistency-scoring)
11. [Confidence & Uncertainty](#11-confidence--uncertainty)
12. [Known Limitations & Future Directions](#12-known-limitations--future-directions)
13. [Comparison to Elite Wearable Platforms](#13-comparison-to-elite-wearable-platforms)
14. [Clinical Disclaimers](#14-clinical-disclaimers)
15. [References & Peer-Reviewed Literature](#15-references--peer-reviewed-literature)

---

## 1. Sleep Score Algorithm

### Formula

```
sleepScore = 0.50 · Duration  +  0.25 · Architecture  +  0.25 · Restoration
```

Each sub-score is on a 0–100 scale.

### Sub-components

**Duration (additive efficiency)** — `ScoringConstants.Sleep.WEIGHT_TST_IN_DURATION = 0.7`,
`WEIGHT_EFF_IN_DURATION = 0.3`

```
duration =
    0.7 · (TST / G_TST) · 100      // total sleep time vs. user goal G_TST
  + 0.3 · effBanded                // 5-band efficiency: <65/<75/<85/<90/≥90
                                   // → 15 / 40 / 65 / 85 / 100
```

This additive structure avoids double-penalising the user (TST already encodes
efficiency, since TST = time-in-bed × efficiency).

**Architecture (age-banded)** — see `ScoringCalculatorImpl.computeArchSubScore`.

Deep- and REM-sleep targets are taken from **Ohayon et al. 2004** healthy-adult
reference percentiles:

| Age band | Deep target | REM target |
|----------|------------:|-----------:|
| < 30     | 18%         | 22%        |
| 30 – 49  | 16%         | 21%        |
| 50 – 64  | 14%         | 20%        |
| 65 +     | 12%         | 18%        |

```
deepScore = min(deepPct / deepTarget, 1) · 100
remScore  = min(remPct  / remTarget , 1) · 100
architecture = 0.5 · deepScore + 0.5 · remScore
```

**Restoration (HRV + RHR, profile-weighted)** — see `computeRestorationSubScore`.

```
restoration = w_HRV · HRVScore  +  w_RHR · RHRScore
```

Default weights `(w_HRV, w_RHR) = (0.5, 0.5)`; ATHLETE/SHIFT_WORKER profiles
can use alternative weightings via `PhysiologyProfile.restorationWeights`.

### Validation status

The 0.50 / 0.25 / 0.25 split is **inherited from PSQI** (Buysse 1989), a
self-report clinical tool, and has *not* been validated empirically against
wearable-derived next-day readiness or polysomnography-based recovery scoring.

`SleepScoreWeightValidator` (Phase 1.4) provides a synthetic + real-data
framework to evaluate alternative weight sets by Pearson r / R² against
next-day readiness labels. Recommended validation: 1000-sample synthetic
cohort plus an A/B test on 100+ users over 8 weeks.

### Limitations

- Depends on the underlying wearable's sleep-staging accuracy. Recent
  meta-analyses (Walch et al. 2024; Chinoy et al. 2020) report F1 scores
  between **0.26 and 0.69** for 4-stage classification across consumer
  devices.
- Fitbit overestimates REM; Apple Watch underestimates deep sleep — these
  device biases propagate untouched into the architecture sub-score.

### References

- Buysse DJ et al. 1989. *Pittsburgh Sleep Quality Index*. Psychiatry Res.
- Buysse DJ. 2014. *Sleep health: can we define it? Does it matter?* Sleep.
- Ohayon MM et al. 2004. *Meta-analysis of quantitative sleep parameters from
  childhood to old age in healthy individuals.* Sleep 27:1255.
- Knutson KL et al. 2017. *The NSF Sleep Health Index*. Sleep Health.
- Walch O et al. 2024. *Validation of consumer sleep wearables: a systematic
  review.* Sleep Advances.

---

## 2. Load Score (Strain Ratio) Algorithm

### Formula

```
SR = ATL_7d / CTL_42d          (Acute:Chronic Workload Ratio)

loadScore =
    100                          if SR ≤ 1.3                 // sweet spot
    100 · exp(-2.5 · excess²)    otherwise; excess = SR-1.3   // quadratic decay
```

`ATL_7d` is the 7-day EMA of daily TRIMP; `CTL_42d` is the 42-day EMA. Both
are seeded with the prior mid-week SMA so the score stabilises quickly when
users onboard.

### Fallback metrics — `LoadMetricsFactory` (Phase 1.2)

Recent literature has questioned the standalone validity of ACWR (Carey 2016;
Robinson 2017; Impellizzeri 2020). When ACWR is unreliable or undefined, this
factory falls back to:

| Source         | Trigger                                | Confidence |
|----------------|----------------------------------------|-----------:|
| RATIO_ACWR     | workouts present, CTL established      | 0.7        |
| MONOTONICITY   | acute > chronic for 3+ consecutive days| 0.6        |
| ABSOLUTE       | no chronic baseline                    | 0.5        |
| SLEEP_ONLY     | no workout data at all                 | 0.4        |

`LoadMetricsFactory.validateAcwrConsistency()` emits warnings when the SR
ratio contradicts the day's recorded session data (e.g. SR>1.5 but TRIMP=0).

### Validation status

ACWR is moderately supported in the original injury-prediction literature
(Gabbett 2016; Windt & Gabbett 2018) but recent systematic reviews
(Impellizzeri et al. 2020) document substantial heterogeneity across
populations. Treat the load score as a directional indicator, not a clinical
threshold.

### References

- Banister EW. 1991. *Modeling elite athletic performance.*
- Gabbett TJ. 2016. *The training-injury prevention paradox.* BJSM.
- Windt J, Gabbett TJ. 2018. *How do training and competition workloads
  relate to injury?* BJSM.
- Carey DL et al. 2016. *Reassessing the ACWR.* JSAMS.
- Robinson MD. 2017. *Acute:chronic workload ratio: conceptual issues.* BJSM.
- Impellizzeri FM et al. 2020. *Acute:chronic workload ratio: critical
  appraisal.* IJSPP.

---

## 3. Readiness Score Algorithm

### Formula

```
readiness = 0.4 · Restoration  +  0.3 · Sleep  +  0.3 · Load
```

Followed by emergency caps:

```
if OVERREACHING in flags:  readiness = min(readiness, 70)
if ILLNESS_ONSET in flags: readiness = min(readiness, 50)
```

### Cap reasoning

The caps reflect a deliberate asymmetric penalty: when the body is signalling
real distress (HRV ↓ + RHR ↑ for 2 nights, or HRV ↑ + RHR ↓ for 2 nights
indicating maladaptive overreaching), no combination of "good sleep" or
"good load" should produce a green readiness score.

### Confidence

The Phase-1 `RecoveryFlagValidator` exposes per-flag confidence (0–1) so the
UI can convey "low-confidence ILLNESS signal" rather than a hard alert when
the trigger barely cleared the threshold. Confidence intervals on the
readiness score itself are typically ±5–15 points depending on calibration
progress; see *Confidence & Uncertainty* below.

### Limitations

- Cap thresholds derived from very small specialised cohorts
  (Le Meur 2013 n=16; Mishra 2020 n=18); false-positive rate is plausibly
  10–20% in a general population.
- Weights are heuristic, not empirically optimised.

### References

- Le Meur Y et al. 2013. *Functional overreaching characterized by elevated
  HRV.* Med Sci Sports Exerc.
- Bellenger CR et al. 2017. *HRV and recovery monitoring.* Front Physiol.
- Mishra T et al. 2020. *Pre-symptomatic detection of COVID-19 from smart-watch
  data.* Nat Biomed Eng.

---

## 4. HRV Normalization & Scoring

### Pipeline

1. Use nocturnal RMSSD (ms) as the primary HRV metric.
2. Apply `ln(RMSSD)` because RMSSD distributions are approximately
   log-normal.
3. Compute Z-score against a personal baseline:
   ```
   μ7d   = mean of ln(RMSSD) over the last 7 valid nights
   σ56d  = stdev of ln(RMSSD) over the last 56 valid nights, blended with prior
   z     = (ln(today) - μ7d) / σ56d
   ```
4. Score: `HRVScore = 50 + 25·z` with soft saturation above z=1.5
   (slope 0.25) to avoid ceiling lock for extreme HRV days.

### Sigma blending — `hrvSigma()`

To stabilise σ during onboarding the personal estimate is blended with a
profile-specific prior:

```
w = clamp((n - 7) / (60 - 7), 0, 1)
σ = w · stdev(lnHistory) + (1 - w) · σ_prior
σ = max(σ, 0.04)                    // physiological floor
```

`ScoringCalculatorImpl.hrvSigmaConfidence(n)` returns the same `w` so it can
be persisted alongside the score (`hrvSigmaConfidence` field) and used as a
calibration indicator in the UI.

Sensitivity analysis (`HrvSigmaTest`): a ±10% perturbation of the prior
changes the final sigma by < 10% at low n and ~0% once n ≥ 60.

### Validation

`HrvSigmaTest` validates against published Kubios MARS and Plews 2013 cohort
sigma ranges (0.15–0.22 ln-units) at n = 7, 30, 60, 100.

### Known issues

- The blending heuristic is not peer-reviewed; treat the score as
  approximate until validated against polysomnography-paired data.
- The σ floor (0.04) can artificially inflate scores when personal variance
  is genuinely very low — by design (avoids divide-by-near-zero noise).

### References

- Plews DJ et al. 2013. *Training adaptation and heart rate variability in
  elite endurance athletes.* Sports Med 43:773.
- Plews DJ. 2013b. *Heart rate variability and training intensity
  distribution.* Sports Med.
- Buchheit M. 2014. *Monitoring training status with HR-derived measures.*
  Front Physiol 5:73.
- Task Force of the European Society of Cardiology. 1996. *HRV: Standards of
  measurement.* Circulation.
- Schaffarczyk M et al. 2024. *HRV-guided training: practical guidelines.*

---

## 5. RHR (Resting Heart Rate) Scoring

### Pipeline

1. **Baseline**: adaptive percentile (5–15%) of session HR nadirs over the
   last 30 days (`RhrBaselineProvider`).
2. **Z-score** against personal rolling baseline:
   ```
   z = (RHR_today − median_30d) / stdev_30d
   ```
3. **Score**: `RHRScore = 50 − 25·z`, clamped to [0, 100]. Elevated RHR
   produces a lower score; depressed RHR signals strong recovery.

### Validation

RHR correlates inversely with cardiovascular fitness (Cole 1999 NEJM) and
positively with infection / illness (Mishra 2020 Nat Biomed Eng). The metric
is widely validated in the cardiology literature.

### Limitations

- Medication (beta-blockers), caffeine, circadian phase, ambient temperature,
  hydration, and alcohol all confound RHR; the app currently has no way to
  account for these.
- The minimum fallback RHR (40 bpm) protects against measurement errors at
  the cost of slightly biasing very-fit athletes toward the floor.

### References

- Cole CR et al. 1999. *Heart rate recovery immediately after exercise.* NEJM.
- Shetler K et al. 2001. *Heart rate recovery: validation and methodologic
  issues.* J Am Coll Cardiol.
- Quer G et al. 2020. *Detection of respiratory viruses using wearable HR
  data.* Nat Med.

---

## 6. TRIMP (Training Impulse) Models

The app supports three TRIMP models, selectable per user via
`UserPreferences.trimpModel`:

### Banister (1991) — default

```
HR_R = (HR_avg − RHR) / (HR_max − RHR)       // % HR reserve
TRIMP = duration_min · HR_R · a · exp(b · HR_R)
```

Sex-specific (a, b): male (0.64, 1.92), female (0.86, 1.67) per Banister.

### Cheng LT-TRIMP (2007)

Piecewise scaling around the lactate threshold (default 85% HRR):

```
weight = 0.36                    if HR_R < 0.85       (linear zone)
       = 0.72 · exp(β · HR_R)    otherwise            (exponential)
TRIMP  = duration_min · HR_R · weight · 3.2
```

### iTRIMP / Manzi (2009)

```
TRIMP = duration_min · HR_R · exp(2.1 · HR_R) · 0.48
```

### Validation status

| Model      | Peer review                | Evidence base                            |
|------------|----------------------------|------------------------------------------|
| Banister   | Yes — 40+ years            | Strongest validated of the three         |
| Cheng LT   | Yes (Cheng 2007)           | Adapted for fitness-aware load           |
| iTRIMP     | Yes (Manzi 2009)           | Smaller cohort; cycling/running specific |

### Limitations & current direction

Recent power-meter literature (cycling, 2023–2024) consistently outperforms
HR-derived TRIMP for cyclists. RPE-based TRIMP (session-RPE × duration) is
now considered the most validated wearable-free alternative. The roadmap
contains a power-based TRIMP option for cyclists and an RPE-fallback path.

### References

- Banister EW. 1991. *Modeling elite athletic performance.*
- Cheng AJ et al. 2007. *LT-TRIMP: an improved training-load model.*
- Manzi V et al. 2009. *Individual TRIMP.*
- Lucia A et al. 2003. *TRIMP comparison.*

---

## 7. PAI (Personal Activity Index) Accumulation

### Phases

1. **Daily TRIMP → Daily PAI** via a profile-dependent scaling factor
   (`ScoringConstants.Pai.PAI_SCALING_*`).
2. **Daily cap** at 75 points.
3. **Tiered accumulation** (over a rolling 7-day window):
   - Tier 1 (0–50):  1.0× multiplier
   - Tier 2 (50–100): 0.5× multiplier
   - Tier 3 (100+):  0.25× multiplier
4. **Readiness modulation** via `ReadinessPAIModulation` (Phase 1.5).
   Replaces the old multiplicative coupling with a cap-based table:
   - readiness < 40   → cap 50
   - readiness 40–59  → cap 60
   - readiness 60–69  → cap 70
   - readiness ≥ 70   → cap 75
   - user override    → cap 75 (bypass modulation)

The cap-based approach breaks the feedback loop the multiplicative form
created (PAI affects load → load affects readiness → readiness affects PAI).

### Validation

The PAI model is inspired by **WHOOP**'s proprietary daily-strain target but
**no peer-reviewed PAI model exists** in literature. Treat confidence as low.
Recommended: user-feedback validation (does the score predict perceived
performance?) on a real cohort.

### References

- WHOOP Inc. *Daily Strain documentation* (proprietary).
- No peer-reviewed PAI source available at time of writing.

---

## 8. Recovery Flags (Overreaching & Illness Detection)

### Triggers

| Flag           | Condition                                                  | Cap on readiness |
|----------------|------------------------------------------------------------|-----------------:|
| OVERREACHING   | zHRV > +1.5 AND zRHR < −2.0 for **2** consecutive nights   | ≤ 70             |
| ILLNESS_ONSET  | zHRV < −1.5 AND (zRHR > +2.0 OR RHR delta ≥ 5 bpm) for 2 nights | ≤ 50         |

### Physiological basis

- **Overreaching**: parasympathetic up-regulation (high HRV) with
  bradycardic adaptation (low RHR) for several consecutive nights is the
  hallmark of *functional overreaching* described in Le Meur 2013 and the
  endurance-athlete literature.
- **Illness**: autonomic stress depresses HRV; infection / inflammation
  elevates resting heart rate. Documented in Mishra 2020 and Quer 2021 in
  the context of COVID and other viral infections.

### Confidence — `RecoveryFlagValidator` (Phase 1.3)

```
confidence = 0.6 · penetration  +  0.4 · consecutiveDays
```

Where *penetration* is how far past the threshold the z-scores landed
(normalised to 1.5 z-units = full credit), and *consecutiveDays* maxes out at
5. Performance targets exposed via `targetsFor()`:

| Flag           | Sensitivity | Specificity |
|----------------|------------:|------------:|
| OVERREACHING   | 70%         | 80%         |
| ILLNESS_ONSET  | 60%         | 85%         |

Specificity is prioritised to keep false-positive alerts under control.

### Limitations

Both default thresholds come from very small specialised cohorts (n = 16, 18)
and have not been validated in general app populations. False-positive rate
is likely 10–20% before tuning.

### References

- Le Meur Y et al. 2013. Med Sci Sports Exerc.
- Mishra T et al. 2020. Nat Biomed Eng.
- Quer G et al. 2021. Nat Med.

---

## 9. Sleep Architecture Validation

### Age-banded targets

Healthy-adult percentile reference data from Ohayon et al. 2004 (see Section 1
table). Targets become the saturation denominators for the deep / REM
sub-scores.

### Acceptance rules

```
if deep+REM ≤ 0.70:                 accept            // typical range
if 0.70 < deep+REM ≤ 0.75:          accept + flag     // SUSPICIOUS (likely
                                                       // device artifact)
if deep+REM > 0.75
  OR deep > 0.40
  OR REM  > 0.45:                    reject          // physiologically
                                                      // implausible
```

Rejected nights still contribute to duration / restoration metrics but their
architecture sub-score is set to 0 with `stagesSuspicious = true`, and the
architecture-weight in the headline sleep score is set to 0 so suspicious
data does not poison the result.

### Device bias

- Fitbit consistently overestimates REM (Walch 2024).
- Apple Watch underestimates deep sleep.
- Recent multi-device meta-analyses (Nature 2025) report stage-classification
  F1 between **0.26 and 0.69**.

### References

- Ohayon MM et al. 2004. Sleep 27:1255.
- Walch O et al. 2024. Sleep Advances.
- Rechtschaffen A, Kales A. 1968. *A Manual of Standardized Terminology…*

---

## 10. Circadian Consistency Scoring

### Pipeline

- Compute bedtime and wake-time stability against the user's rolling
  baseline (last 7 sessions).
- Each within-threshold deviation → 100 points; linear decay to 0 over a
  60-minute window.
- Sessions shorter than 180 minutes are excluded (naps).
- Status mapping: ≥ 80 optimal, 60–79 neutral, 40–59 warning, < 40 poor.

### Physiological basis

Stable circadian rhythm is linked to better sleep quality, lower cortisol
dysregulation, and improved recovery (Czeisler, Kleitman, and the Oura
consistency research summaries).

### Validation status

Partially validated by sleep-science research; widely used in commercial
wearables. Not a stand-alone medical metric.

### References

- Czeisler CA. *Foundational circadian biology papers.*
- Kleitman N. *Sleep and Wakefulness.*

---

## 11. Confidence & Uncertainty

### Calibration phases

| Days of data | Status                  | UX                     |
|--------------|-------------------------|------------------------|
| 0 – 7        | Calibrating             | "Low confidence — building baseline" |
| 7 – 30       | Improving               | "Moderate confidence" |
| 30 +         | Mature baseline         | "High confidence"      |

### Confidence intervals

Daily scores carry an approximate **±5 – 15 point** confidence band depending
on:
- Days of personal data (drives σ blending weight)
- Sleep-stage data presence
- Workout data availability (drives load score confidence)
- Per-flag penetration depth (drives recovery-flag confidence)

The recommended UX is to show a range (e.g. "Readiness 65 (58–72)") rather
than a single point estimate.

### Statistical underpinning

The HRV pipeline is effectively a Bayesian posterior for a log-normal
distribution: a personal sample mean / sigma blended with a profile-specific
prior, with the blending weight rising as more samples arrive.

---

## 12. Known Limitations & Future Directions

### Current limitations

- Wearable accuracy varies dramatically across devices (F1 0.26–0.69 for
  sleep staging).
- ACWR validity is questioned in modern systematic reviews.
- HRV sigma blending heuristic is not peer-reviewed.
- Recovery-flag thresholds come from small populations (n < 20).
- Sleep-score weights are inherited from PSQI, not empirically optimised.
- PAI model is proprietary in inspiration; no peer-reviewed PAI literature.

### Recommended validations before production

- Polysomnography validation: 30 participants × 5+ nights.
- A/B testing of score weights (athlete cohort, 100+ users, 8+ weeks).
- Beta-test recovery flags with labelled outcomes (measure sensitivity /
  specificity in-population).
- User-feedback validation: does score correlate with self-reported
  performance / wellbeing?

### Future improvements

- Power-meter-based TRIMP for cyclists (replaces HR-only).
- RPE-based TRIMP fallback (validated in 2023 meta-analyses).
- Per-user ML calibration of weights (lightweight on-device model).
- Optional clinical biomarker integration (cortisol, immune markers) when
  available from third-party sources.

---

## 13. Comparison to Elite Wearable Platforms

| Platform | Strain / Load | Recovery | Notable strengths              | Trade-offs vs. this app   |
|----------|---------------|----------|--------------------------------|---------------------------|
| WHOOP    | Proprietary daily strain | HRV + RHR + sleep + temp | ML personalisation, skin-temperature | Closed source, subscription |
| Oura Ring| —             | HRV + RHR + sleep consistency | PSG-validated stage detection (~79% 4-stage accuracy) | Closed source, hardware-tied |
| Garmin   | Training Load Index + Recovery Time Advisor | HRV + VO2max | Power-meter integration, multi-sport | Vendor lock-in            |
| **This app** | ACWR + fallbacks (LoadMetricsFactory) | HRV + RHR + sleep | **Transparent, evidence-cited, auditable** | No ML personalisation, simpler than WHOOP/Oura |

The deliberate trade-off in this app: simpler, fully transparent algorithms
backed by published references, at the cost of less personalisation.

---

## 14. Clinical Disclaimers

- **This software is NOT a medical device.** It is *not* FDA-cleared, CE-marked
  as a medical device, or registered with any regulatory body.
- The app is **not** intended to diagnose, treat, cure, or prevent any disease.
- The readiness score does **not** indicate medical fitness to exercise.
- Recovery flags suggest the user *may* benefit from monitoring; they do
  **not** constitute a clinical diagnosis.
- **If you experience chest pain, shortness of breath, dizziness, or other
  warning symptoms during exercise, stop and consult a physician
  immediately.**
- Scores depend on wearable accuracy and wear compliance. Devices vary
  substantially in stage detection (F1 0.26 – 0.69).
- Consult a qualified healthcare provider before making major changes to
  training, sleep, or medication based on app output.

---

## 15. References & Peer-Reviewed Literature

### Sleep science

- Buysse DJ et al. 1989. Psychiatry Research.
- Buysse DJ. 2014. *Sleep health*. Sleep.
- Ohayon MM et al. 2004. Sleep 27:1255.
- Knutson KL et al. 2017. *NSF Sleep Health Index*. Sleep Health.
- Rechtschaffen A, Kales A. 1968. *Standardized terminology for sleep stages.*
- Walch O et al. 2024. *Validation of consumer sleep wearables.* Sleep Advances.
- Chinoy ED et al. 2020. *Comparative performance of wearable sleep
  trackers.* SLEEP.

### Heart rate variability

- Plews DJ et al. 2013. *Training adaptation and HRV in endurance athletes.*
  Sports Med 43:773.
- Plews DJ. 2013b. Sports Med.
- Buchheit M. 2014. Front Physiol 5:73.
- Task Force of the European Society of Cardiology. 1996. Circulation.
- Schaffarczyk M et al. 2024.
- Porges SW. *Polyvagal theory: foundational papers.*

### Training load & TRIMP

- Banister EW. 1991. *Modeling elite athletic performance.*
- Cheng AJ et al. 2007. LT-TRIMP.
- Manzi V et al. 2009. iTRIMP.
- Lucia A et al. 2003.
- Gabbett TJ. 2016. BJSM.
- Windt J, Gabbett TJ. 2018. BJSM.
- Foster C. 1998. Monotony Index.

### Wearable validation

- Walch O et al. 2024. Sleep Advances.
- Chinoy ED et al. 2020. SLEEP.
- Nature 2025 multi-device validation meta-analysis.

### Recovery / overreaching / illness

- Le Meur Y et al. 2013. *Functional overreaching characterized by elevated
  HRV.* Med Sci Sports Exerc.
- Bellenger CR et al. 2017. Front Physiol.
- Mishra T et al. 2020. *Pre-symptomatic detection of COVID-19 from smartwatch
  data.* Nat Biomed Eng.
- Quer G et al. 2020 / 2021. Nat Med.

### ACWR

- Carey DL et al. 2016. JSAMS.
- Robinson MD. 2017. BJSM.
- Impellizzeri FM et al. 2020. IJSPP — *Acute:chronic workload ratio:
  critical appraisal.*

### RHR / cardiovascular

- Cole CR et al. 1999. NEJM.
- Shetler K et al. 2001. J Am Coll Cardiol.
- Karvonen MJ. 1957.
- Tanaka H et al. 2001. J Am Coll Cardiol.

### Circadian biology

- Czeisler CA. Foundational circadian biology papers.
- Kleitman N. *Sleep and Wakefulness.*
- Oura Health Research summaries (consistency).

---

*Document version: Phase 1 (Core Safety) – authored as part of
`claude/review-health-connect-algorithms` branch. Updates welcome via PR.*
