# About your scores

This app turns the data your phone and wearables already collect — sleep, heart rate, and exercise — into three daily numbers that try to answer one question: **how is your body doing today, and what should you do with that information?**

We try to be honest about what these numbers can and can't tell you. They are decision aids, not diagnoses. If something feels off in your body, trust your body over the score.

## Your profile matters

When you first open the app, you select a **Physiological Profile** — Athlete, Active, or Sedentary — that tunes how we interpret your data. Different activity profiles show different recovery patterns, so the thresholds we use to score you are customized.

- **Athlete** — you train regularly (3+ days/week structured exercise). Your metrics will be interpreted with tighter circadian consistency targets (±20 min) and more nuanced HRV sensitivity.
- **Active** — you exercise 1–3 times per week or have an active job. Moderate thresholds (±30 min circadian, balanced HRV sensitivity).
- **Sedentary** — you don't exercise regularly but may have daily movement. More relaxed thresholds (±45 min circadian) so normal life variation doesn't penalise you.

**Why profiles exist.** An athlete's HRV is normally more stable than a sedentary person's, so a genuine departure means more for an athlete — we don't compare them to the same bar. Likewise, someone training to peak benefits from tighter circadian consistency targets than someone with a relaxed schedule. Profiles let us keep scores fair across different lifestyles.

---

## A note on measurement

Wearables estimate sleep stages, HRV, and nocturnal physiology indirectly using probabilistic algorithms. These estimates may contain significant measurement error compared to clinical systems like polysomnography or ECG. The scores shown here are wellness-oriented estimates, not clinical measurements. Profile settings optimize _estimated_ recovery signals, which are influenced by many factors (stress, sleep environment, caffeine, hydration, illness, etc.). Scores are wellness indicators, not diagnoses.

---

## The three scores at a glance

| Score                     | What it answers                                 | Range |
| ------------------------- | ----------------------------------------------- | ----- |
| **Sleep Score**           | How restorative was last night's sleep?         | 0–100 |
| **Circadian Consistency** | How regular is your sleep schedule?             | 0–100 |
| **Readiness**             | How prepared are you for today's training load? | 0–100 |

You'll see all three on your dashboard once enough data has been collected. Until then, we'll show you what we have and explain what's missing.

---

## Sleep Score

A 100-point summary of last night's sleep, made of three parts:

- **Duration (50%)** — how much sleep you got, compared to your goal (default 8 hours, configurable). Includes a small adjustment for how efficient your time in bed was.
- **Architecture (25%)** — how much of your sleep was deep (slow-wave) sleep and REM. Both matter. Deep sleep is when most physical recovery happens; REM is when your brain processes memory and emotion. Targets are age-specific to account for the natural biological decline in deep sleep across the lifespan (Ohayon 2004).
- **Restoration (25%)** — how rested your estimated recovery-related physiology looks. We use the natural log of RMSSD (**lnRMSSD**) and overnight resting heart rate (**RHR**) to compute Z-scores. The log transformation is the scientific gold standard (Plews 2013, Buchheit 2014) for monitoring recovery, as it normalizes the skewed distribution of raw HRV data.

**Reading the score**

- **85–100** Excellent. You slept enough, your sleep stages looked balanced, and your autonomic recovery markers were strong.
- **70–84** Good. Most components are healthy; one is slightly below your norm.
- **50–69** Fair. Likely a duration shortfall, fragmented sleep, or an off night for HRV/RHR.
- **Below 50** Poor. Multiple components are below typical. One bad night is rarely meaningful; a streak deserves attention.

**Deep and REM sleep targets — age matters**

As you age, the amount of deep sleep naturally declines. We adjust your targets based on your age so you're never unfairly compared to a younger person.

| Age range | Deep sleep target | REM sleep target |
| --------- | ----------------- | ---------------- |
| 18–29     | 20%               | 22%              |
| 30–49     | 18%               | 21%              |
| 50–59     | 15%               | 20%              |
| 60+       | 12%               | 19%              |

These ranges come from polysomnography studies in healthy populations. They represent the healthy mid-range; your personal healthy normal may sit anywhere within your age band. Note that the age-related decline in deep (slow-wave) sleep is much steeper than the decline in REM, which falls only modestly across adulthood (Ohayon 2004). We do not penalise you if your wearable reports unusual numbers — wearable stage detection is imperfect.

**HRV sensitivity by profile**

Heart rate variability (HRV) is noisy on any single night. To avoid false positives, we only flag HRV as notably "high" or "low" once it crosses a Z-score threshold. While your personal baseline is still being learned (the Early Baseline phase), we estimate your day-to-day variability from a population value tiered by profile, so the threshold shifts with it.

- **Athlete** — HRV is normally stable, so a genuine departure is more likely to mean something (a high reading can even signal parasympathetic hyperactivity during functional overreaching; Le Meur 2013). We flag sooner: Z beyond ±1.2 is notable.
- **Active** — moderate sensitivity: Z beyond ±1.5 is notable.
- **Sedentary** — more natural night-to-night noise, so we require a larger departure before flagging to avoid false alarms: Z beyond ±2.0 is notable.

This tuning means a single noisy night isn't treated as a signal as readily for a Sedentary person as the same Z-score would be for an Athlete, because higher baseline noise produces more large deviations by chance. Once your personal baseline matures (60+ nights), the Z-score is computed against _your own_ standard deviation, which already accounts for your individual variability.

_Implemented in: `SleepScoringStrategy.kt`, `SleepArchitectureTargets.kt`, `ScoringConstants.kt`_

_Restoration/HRV Z-scores implemented in: `LoadScoringStrategy.kt`, `BaselineComputer.kt`, `HrvBaselineProvider.kt`, `RhrBaselineProvider.kt`_

---

## Circadian Consistency

This score asks: **do you go to bed and wake up at roughly the same times each day?** Schedule regularity is independently linked to better metabolic, cognitive, and cardiovascular outcomes — sometimes more strongly than sleep duration itself (Windred et al. 2023, UK Biobank).

We compare each night's bedtime and wake time to your _typical_ (median) bedtime and wake time over the last 14 days. The bigger the deviation, the lower the daily score. The number you see is a 7-day rolling average so a single late night doesn't tank it.

**Profile-specific thresholds**

How strict we are about "consistent" depends on your profile:

| Profile       | Deviation threshold | Interpretation                    |
| ------------- | -------------------- | --------------------------------- |
| **Athlete**   | ±20 minutes          | Tight control for performance     |
| **Active**    | ±30 minutes          | Standard regularity               |
| **Sedentary** | ±45 minutes          | Relaxed; normal life variation OK |

Within each threshold band:

- Within the threshold → full score.
- Threshold to threshold+60 min → score decays linearly.
- Beyond threshold+60 min → score is 0 for that day.

You can override the resolved threshold with your own value in Settings if the profile
default doesn't fit your schedule.

**A caveat for biphasic sleepers.** This metric is calibrated for people with one main sleep period per day. If you sleep in two segments by choice (e.g., 2:00–4:00 AM and then again at 6:00–7:00 AM), the score may misclassify your schedule. We exclude any single sleep period under 3 hours from the median calculation so naps don't pull your "typical" times around — but this rule has imperfect coverage of every sleep pattern.

_Implemented in: `CircadianConsistencyRepository.kt`, `CircadianThresholdDefaults.kt`_

---

## Readiness

A daily 0–100 composite number summarising three signals:

**Readiness = 0.4 × Restoration (sRest) + 0.3 × Sleep Score + 0.3 × Load Score**

Each component is described in its own section above. Restoration carries the largest single weight (0.4), so your overnight recovery markers are the biggest lever on Readiness — but a significant load spike or poor sleep will also pull the score down.

The Load Score (one of the three components) itself is based on your training load ratio:

We compute two rolling averages of your training load:

- **Acute load (ATL)** — roughly the last week
- **Chronic load (CTL)** — roughly the last 6 weeks

The ratio (ATL ÷ CTL) tells us whether you've recently spiked above your recent norm. Around 1.0 means you're training in line with your fitness; substantially above 1.0 means a relative spike.

**How we score the ratio**

- sr ≤ 1.3 → 100 (in your normal range — "sweet spot")
- sr > 1.3 → 100 × exp(−2.5 × (sr − 1.3)²) — a smooth Gaussian decay that starts gently and accelerates as the spike grows (approximating Gabbett 2016's elevated-risk zone)

**Tooltips**

- _Peak (85–100)_ — your recent load is consistent with your fitness.
- _Maintain (60–84)_ — manageable load increase.
- _Caution (30–59)_ — meaningful load spike; consider an easier day.
- _High Fatigue (<30)_ — large spike vs. your norm.

**Emergency signals**

If your HRV and RHR patterns suggest possible physiological stress, possible illness onset caps it at 50. A two-night pattern of unusually favorable HRV and resting heart rate is shown as a strong recovery signal instead. Strong recovery signals do not cap Readiness or automatically mean you should train harder than planned. To ensure accuracy and filter out acute noise (e.g., alcohol or minor stress), the algorithm requires the thresholds to be breached on **two consecutive nights** (Mishra 2020, Le Meur 2013). This is informational only, not medical advice. Workout-impact and rest-day flags shown elsewhere are informational only and do not cap your Readiness number.

**What we don't do.** We don't penalise you for resting. A week of light activity will _not_ drop Readiness; the score is designed for load _spikes_, not undertraining.

_Implemented in: `LoadScoringStrategy.kt`, `PaiScoringStrategy.kt`, `ComputeSleepMetricsUseCase.kt`, `LoadMetricsProvider.kt`, `PaiProvider.kt`_

---

## What the app needs from you

We read from Android Health Connect:

- **Sleep sessions** (with stages if your device records them)
- **Heart rate** during sleep (for restoration metrics)
- **Heart rate variability (RMSSD)** during sleep
- **Heart rate** during exercise sessions (for training load)

The app reads only — it never writes. You can revoke access at any time in Health Connect settings.

If a particular metric is missing on a given day, we'll either:

- show the score with a "data partial" badge and explain which component was estimated, or
- skip the score for that day entirely if too much is missing (especially total sleep time).

---

## How long until your scores stabilise

Biological baselines take time to learn. We are explicit about the phases:

- **Calibration (0–6 nights, confidence: Not Ready).** We collect data. Sleep Score and Circadian Consistency are visible from the first night you log enough sleep, but **HRV-based Restoration is hidden** because a single night of HRV is not interpretable on its own. Readiness uses a placeholder fitness value based on the activity level you tell us during onboarding.
- **Early Baseline (7–20 nights, confidence: Low).** We start showing all three scores, but Restoration uses a population-typical estimate of how much your HRV varies night to night (tiered by your profile). Expect more variability than the mature score.
- **Maturing (21–59 nights, confidence: Medium).** Your personal HRV mean is settled, and we begin blending your personal night-to-night variability with the population estimate. A confidence indicator becomes visible.
- **Mature (60+ nights, confidence: High).** All scores use your own personal baselines for both the average and the variability. This is when small day-to-day differences become trustworthy.

Progress through these phases depends on the number of nights with usable HRV/RHR data, not calendar age. If you wear your tracker only 3–5 nights a week, the timeline lengthens proportionally.

_Implemented in: `Phase.kt`, `PhaseCalculator.kt`_

---

## A short glossary

- **HRV (Heart Rate Variability)** — the millisecond-level variation in time between heartbeats. Higher generally indicates better autonomic recovery, _up to a point_.
- **RMSSD** — the specific HRV measure most apps use. We work with the natural log of RMSSD (**lnRMSSD**) internally because it linearizes the naturally skewed distribution of heart rate variability, making statistical comparison (Z-scores) valid (Plews 2013, Buchheit 2014). The **HRV Baseline (ms)** shown on your dashboard is the geometric mean of your recent nightly RMSSD — computed as `exp(mean(lnRMSSD))` — not a simple average, since a plain average would be skewed upward by occasional high outlier nights.
- **Deep sleep / Slow-Wave Sleep / N3** — the deepest stage of NREM sleep; growth-hormone release is concentrated here.
- **REM** — the dreaming stage, important for memory and emotional processing.
- **RHR (Resting Heart Rate)** — your true resting heart rate, calculated as a user-defined low percentile (default 5%) of your heart rate samples across the detected sleep period. This sits below your _average_ overnight heart rate, which is pushed up by REM and brief awakenings — using the low percentile gives a more stable night-to-night baseline. _Note: This nightly-frozen nocturnal floor is the foundational baseline used directly in all downstream recovery, Heart Rate Reserve (HRR), and TRIMP calculations to ensure training load metrics are highly stable and unaffected by wake-time noise or systemic average inflation._

  _Implemented in: `SleepPercentileRhrCalculator.kt`, `BaselineComputer.kt`, `RhrBaselineProvider.kt`_

- **TRIMP (Training Impulse)** — a single number summarising the intensity-weighted duration of an exercise session. Advanced models (such as LT-TRIMP) rely on the specific Heart Rate Zones configured in your app settings. These zones are always active; it is your responsibility to ensure they accurately reflect your current fitness level.

  _Implemented in: `PaiCalculator.kt`, `ComputeWorkoutTrimpUseCase.kt`, `PaiScoringStrategy.kt`, `HrMaxProvider.kt`_

- **ATL / CTL** — short-term and long-term rolling averages of TRIMP. Borrowed from Banister's training-load model and used by most cycling and running apps.
- **Z-score** — a standardized number telling you how many standard deviations above or below your average a metric is. Z=0 is your average; Z=+2 is very high; Z=−2 is very low.

---

## Score adjustments you may not see

A few smaller modifiers shape the numbers behind the scenes. We list them here for transparency:

- **HRV-score saturation.** Above a Z-score of 1.5, additional HRV improvement contributes less to your Restoration score (a 0.25 slope beyond that point) — so an extraordinarily high reading doesn't dominate the score the way a moderate one does.
- **Late-nadir penalty.** If your lowest overnight heart rate occurs in the final third of your sleep period (after 67% of total sleep time has elapsed), we apply a small 0.95 multiplier to the restoration component. A very late RHR nadir often reflects a shortened or fragmented night rather than genuine recovery.
- **Per-profile training-load multiplier.** Your Banister training-load model uses a profile-specific multiplier when converting heart-rate-reserve intensity into TRIMP: Athlete ×1.0, Active ×1.35, Sedentary ×1.75. This reflects that the same relative effort represents a larger physiological load for someone who trains less.
- **PAI tiered accumulation.** Your daily Personal Activity Intelligence (PAI) points accumulate at full rate (×1.0) up to 50 points, at half rate (×0.5) from 50–100, and at a quarter rate (×0.25) beyond 100 — with a 75-point daily cap. This keeps one very long or intense session from disproportionately inflating your Load Score, and feeds into the Readiness load component.
- **Suspicious sleep-stage reweight.** If your wearable's sleep-stage data for a night looks implausible (e.g., no deep or REM sleep detected at all), we reweight the Sleep Score: Duration rises to 75% and Architecture drops to 0%, while Restoration stays at 25%. This avoids penalising you for a wearable data glitch rather than your actual sleep.
- **Missing-day handling in load averages.** Acute and chronic training-load averages (ATL/CTL) are exponential moving averages where a day with no logged exercise counts as zero TRIMP, not "no data". When you only have one day of history, that single day's value is used directly as the starting average.
- **Estimated max heart rate.** If you haven't entered your own max heart rate, we estimate it from your age using the Tanaka formula (`208 − 0.7 × age`), which is more accurate across adult age ranges than the older "220 − age" rule of thumb.

## Determinism & timezone

Your scores are computed against a stored scoring timezone, so the same underlying health data and settings always produce the same scores — recomputing your history (e.g., after a resync) reproduces identical numbers, and scores remain consistent if you travel or change your device's timezone.

---

## Honest limitations

1. **Wearable stage detection is imperfect.** Even premium devices misclassify deep and REM sleep on individual nights. We use age-adjusted targets and treat architecture scores as approximate wellness indicators. Architecture differences on individual nights should not be over-interpreted.

2. **Population norms are not destiny.** The age-banded deep sleep ranges come from polysomnography studies of healthy adults. Your healthy normal may sit anywhere in your age band. Our scoring uses age-banded targets so the ceiling shifts with you.

3. **Profiles are engineering heuristics, not physics.** The cutoffs (Athlete ±20 min, Active ±30 min, Sedentary ±45 min circadian threshold) are chosen for practical usability, not derived from prospective studies. We monitor whether these cutoffs are working well and will adjust if needed.

4. **The ACWR (Readiness load ratio) is descriptive, not predictive.** The methodological literature (Lolli et al. 2019; Impellizzeri et al. 2020, 2021) has demonstrated that the acute-to-chronic ratio is often a mathematical artifact and is not a validated _causal_ injury predictor. We approximate Gabbett's (2016) elevated-risk zone above the 1.3 sweet-spot ceiling with a Gaussian decay penalty — the curve shape is our own modelling choice — and present it strictly as a **load-change indicator** to help you visualise spikes in training intensity, not as a diagnostic injury-risk score.

5. **One night is noise; trends are signal.** Treat any single day's score as a data point, not a verdict. Look at the 7-day trend.

6. **This app does not diagnose anything.** If you suspect sleep apnea, a heart condition, an infection, an injury, or any other health concern, see a clinician. Physiological metrics such as HRV, sleep staging, and resting heart rate are non-specific and can be influenced by numerous behavioral, environmental, pharmacological, and measurement-related factors.

---

_Selected primary sources informing the scoring: Buysse 1989 (PSQI); Buysse 2014 (RU-SATED); Ohayon et al. 2004, 2017; Hirshkowitz et al. 2015 (NSF); Boulos et al. 2019 (Lancet Respir Med); Lauer et al. 1991; SIESTA database; Plews et al. 2012, 2013, 2014 (HRV monitoring); Buchheit 2014 (Front Physiol); Le Meur et al. 2013 (parasympathetic hyperactivity); Mishra et al. 2020 (Nat Biomed Eng); Quer et al. 2021 (Nat Med); Phillips et al. 2017 (Sleep Regularity Index); Lunsford-Avery et al. 2018; Windred et al. 2023/2024; Khalsa et al. 2003 (phase-response curve); Banister 1991; Foster 1998; Gabbett 2016; Lolli et al. 2019; Impellizzeri et al. 2020/2021._

---

## Scientific and medical disclaimer

This app describes a wellness-oriented monitoring framework derived from consumer wearable signals and sports-science literature. The framework is **not validated for medical diagnosis, disease screening, treatment guidance, or injury prediction**. Profiles and their associated thresholds optimize _estimated_ recovery signals and are engineering heuristics chosen for practical usability, not clinical validation.

If you have concerns about your health, sleep, or recovery, consult a qualified healthcare provider.
