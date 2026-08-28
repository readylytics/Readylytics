---
layout: default
title: About Your Scores
permalink: /about/
---
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

Wearables estimate sleep stages, HRV, and nocturnal physiology indirectly using probabilistic algorithms. These estimates may contain significant measurement error compared to clinical systems like polysomnography or ECG. The scores shown here are fitness-focused estimates, not clinical measurements. Profile settings optimize _estimated_ recovery signals, which are influenced by many factors (stress, sleep environment, caffeine, hydration, illness, etc.). Scores are readiness indicators, not diagnoses.

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

A 100-point summary of last night's sleep, made of four parts (under the default **Balanced** profile):

- **Duration (40%)** — how much sleep you got, compared to your goal (default 8 hours, configurable). Scoring follows a smooth continuous logistic curve below your goal. A configurable oversleep dead zone (default 125% of goal) prevents penalizing modest sleep-ins while gently decaying scores for excessive hypersomnia. If you sleep in multiple segments (biphasic sleep or naps), all sleep periods count toward your total duration (naps add duration without altering overnight metrics). Includes a continuous adjustment for how efficient your time in bed was.
- **Architecture (20%)** — how much of your sleep was deep (slow-wave) sleep and REM. Both matter. Deep sleep is when most physical recovery happens; REM is when your brain processes memory and emotion. Targets are age-continuous to account for the natural biological decline in deep sleep across the lifespan (Ohayon 2004).
- **Restoration (25%)** — how rested your estimated recovery-related physiology looks. We use the natural log of RMSSD (**lnRMSSD**) and overnight resting heart rate (**RHR**) to compute Z-scores. The log transformation is the scientific gold standard (Plews 2013, Buchheit 2014) for monitoring recovery, as it normalizes the skewed distribution of raw HRV data.
- **Fragmentation (15%)** — evaluates sleep continuity using Wake After Sleep Onset (**WASO**) between sleep onset and final awakening, alongside discrete awakenings of at least 1.5 minutes. Because normal adult sleep includes brief wake periods, a grace allowance of 20 minutes of WASO and 2 awakenings is permitted before continuous exponential penalties apply.

**Weight emphasis profiles**

You can customize the relative emphasis of each sleep component in Settings to suit your recovery focus (Balanced, Duration Focused, Recovery Focused, Architecture Focused, or Continuity Focused). Changing your weight profile requires running the **Recalculate scores** action in Settings to apply the new weighting across your history.

**Sleep Regularity multiplier**

Your sleep score incorporates **Sleep Regularity** as a penalty-only multiplier between 0.92 and 1.00 derived from your circadian consistency score. Consistent sleep timing preserves 100% of your score (multiplier 1.00), while irregular schedules incur a mild proportional deduction. During baseline calibration or when circadian data is missing, the multiplier remains neutral (1.00).

If a source provides a sleep session with no stage records, Readylytics uses the raw session span as total sleep duration. Because Architecture and Fragmentation are unavailable, the Sleep Score renormalizes the remaining sub-scores (Duration and Restoration) according to your active weight profile. This differs from suspicious but non-empty stage data: the source supplied stages, but their distribution failed plausibility checks.

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

**Biphasic sleep and naps**

We fully support segmented or biphasic sleep. If you sleep in multiple blocks (like a main overnight sleep and an afternoon nap), your "core" sleep drives your recovery metrics (Restoration/HRV/RHR), while all supplemental sleep adds to your total Duration. This means naps will improve your Sleep Score by adding duration, but they won't rewrite your overnight Readiness signal or baseline HRV.

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

When HRV is much lower than usual and resting heart rate is elevated for more than one day, readiness may be capped as a cautious possible-illness signal (which caps it at 50). When HRV is much higher than usual and resting heart rate is much lower than usual, the app treats this as an encouraging recovery signal rather than overreaching. Strong recovery signals do not cap Readiness. To ensure accuracy and filter out acute noise (e.g., alcohol or minor stress), the algorithm requires the thresholds to be breached on **two consecutive nights** (Mishra 2020, Le Meur 2013). This is informational only, not medical advice. Workout-impact and rest-day flags shown elsewhere are informational only and do not cap your Readiness number.

**What we don't do.** We don't penalise you for resting. A week of light activity will _not_ drop Readiness; the score is designed for load _spikes_, not undertraining.

_Implemented in: `LoadScoringStrategy.kt`, `RasScoringStrategy.kt`, `ComputeSleepMetricsUseCase.kt`, `LoadMetricsProvider.kt`, `RasProvider.kt`_

---

## Load Sources

Two independent settings control which heart-rate data feeds your strain/training-load
metrics versus your Readylytics Activity Score (RAS):

- **Strain / Training Load source** (default: **Workout only**) — controls TRIMP,
  acute/chronic load (ATL/CTL), Strain Ratio, Load Score, and **Readiness**. Readiness
  always uses this source; the RAS source never affects Readiness.
- **RAS source** (default: **Everyday heart-rate load**) — controls your daily and
  7-day total RAS only, independent of the Strain / Training Load source above.

**Workout only** counts heart-rate load from your logged exercise sessions only — the
original behaviour.

**Everyday heart-rate load** also counts elevated heart rate outside workouts (e.g. from
stress, illness, or heat) on top of your workout TRIMP. Your workout TRIMP is folded into
this total **exactly once** — it is never double-counted. Sleep is always excluded from
the everyday calculation.

For the everyday calculation, every waking, non-sleep, non-workout minute with at least
one heart-rate sample is classified into a heart-rate zone using your configured
zones/TRIMP settings — the same model used for workouts. **Zone 0** minutes (below your
Zone 1 threshold) are excluded from TRIMP but still counted toward coverage. **Zone 1
and above** minutes contribute TRIMP using the standard per-minute formula.

- **coverageMinutes** — waking, non-sleep, non-workout minutes with ≥1 heart-rate sample
  (Zone 0 included).
- **validBucketCount** — the subset of those minutes in Zone 1+ that actually
  contributed TRIMP.
- **Confidence** is derived from `coverageMinutes`: 0 → **None**, 1–179 → **Low**,
  180–479 → **Medium**, 480+ → **High**. A day needs at least 180 coverage minutes to be
  a valid everyday-load estimate; below that, Readiness shows a low-confidence indicator
  whenever the Strain / Training Load source is set to Everyday heart-rate load. For the AI
  Advisor with that source selected, **Low** coverage caps a high confidence at **Medium**;
  **None** lowers the base confidence by one level, never below **Low**. The AI Advisor's base
  confidence starts at **Low** during Calibration/Early Baseline, **Medium** once your baselines
  are Maturing (or **Low** if HRV or sleep-stage data is missing today), and **High** once Mature
  (or **Medium** if HRV or sleep-stage data is missing today). Everyday-load coverage can then
  only lower this, never raise it, per the coverage rule above.

Both source variants are calculated and stored for every day, so switching either
setting is instant — no recalculation or history rewrite is needed.

**New installs** default to Strain / Training Load = Workout only and RAS = Everyday
heart-rate load. **Existing users upgrading** keep their prior behaviour automatically: the
first time the app runs after upgrade, the RAS source is set to Workout only as a
one-time default if you already have workout history — you can change it in Settings at
any time.

_Implemented in: `EverydayHeartRateLoadCalculator.kt`, `LoadSourceSelector.kt`, `LoadSourceMode.kt`_

---

## BMI and Body Fat

**BMI (Body Mass Index)** classifies your weight relative to height using WHO-aligned bands:

- **Underweight** — BMI below 18.5
- **Healthy weight** — 18.5 to 24.9
- **Overweight** — 25 to 29.9
- **Obesity** — 30 and above

The Weight card’s BMI reference gauge uses visual anchors of 15, 21.7, and 35 so its midpoint is shown at 21.7. These anchors position the gauge only; they do not change the BMI status bands above.

**Body Fat Percentage** uses continuous, gender-specific bands when your profile records a
biological sex:

- **Male** — Below essential <2%, Essential 2–5.9%, Athletic 6–13.9%, Fitness 14–17.9%,
  Acceptable 18–24.9%, Obese 25%+
- **Female** — Below essential <10%, Essential 10–13.9%, Athletic 14–20.9%, Fitness
  21–24.9%, Acceptable 25–31.9%, Obese 32%+

If gender is set to **Other**, **Prefer not to say**, or is unset, we show a fixed
10–30% reference band centered on 20% instead of a gendered scale — values inside the
band are Optimal, at or below 10% is Neutral, and above 30% is Poor.

The Body Fat card also shows a **reference midpoint** — a target value for your
physiology profile, used only to position the marker on the gauge, not to change your
status:

- **Male** — Athlete 9.5%, Active 15.5%, Sedentary 19.5%
- **Female** — Athlete 17%, Active 22.5%, Sedentary 26.5%
- **Other / Prefer not to say / unset** — fixed at 20%, independent of profile

**Reading the status.** Optimal (green) and Neutral (informational) describe healthy or
expected ranges; Warning flags dangerously low essential-fat levels, or a BMI that is either
underweight or overweight — the same status covers both directions, so check the category
label (not just the color) to see which one applies; Poor flags the obesity range for BMI or
body fat.

The status colors on Weight and Body Fat trend charts use these same canonical bands. Visual
reference anchors and profile markers never redefine a reading’s status.

_Implemented in: `BodyCompositionAssessment.kt`, `BmiService.kt`, `HealthMetricsService.kt`,
`HealthMetricsCalculator.kt`_

---

## Blood Pressure

The dashboard classifies each blood-pressure reading with an inclusive, component-wise ladder:

- **Optimal** — systolic ≤120 and diastolic ≤80 mmHg
- **Neutral** — otherwise, systolic ≤129 and diastolic ≤89 mmHg
- **Warning** — otherwise, systolic ≤139 and diastolic ≤99 mmHg
- **Poor** — all other readings

For example, 121/80, 120/81, and 129/89 are Neutral, while 130/90 is Warning. This is a
dashboard status, not a diagnosis.

Blood-pressure trend charts obtain their component reference bands from this same ladder.

_Implemented in: `HealthMetricsService.kt`_

---

## Overnight Oxygen Saturation

The dashboard card uses your overnight average oxygen saturation. Overnight oxygen
saturation: below 90% Poor; 90–94% Warning; 95–97% Neutral; 98% and above Optimal.

This means an overnight average of 96% is Neutral. The status describes the displayed
overnight average rather than a scoring-engine calculation.

---

## Body Temperature

The dashboard card and Vitals trend chart show your overnight average body temperature
(nightly average of Health Connect readings within your sleep-session window). This is an
**optional metric** — it requires the separate `READ_BODY_TEMPERATURE` Health Connect
permission, and the card stays hidden until you grant it.

Once you have 14 nights of readings, we show a rolling **14-day baseline** — a plain
trailing average of your own recent nights. Before that, the card shows "Calibrating"
instead of a deviation badge. We compare today's reading against that baseline and flag a
day as elevated when it deviates by at least your configured **elevated-deviation
threshold** (Settings, default **1.0°C**, adjustable from 0.25°C to 1.5°C) in either
direction — a wider swing than typical night-to-night noise.

This baseline is intentionally a simple average, not the log-normal statistical model used
for HRV/RHR — it exists purely to flag a possibly-illness-related change for you to notice.
**Body temperature does not affect Sleep Score, Load Score, or Readiness in any way.** It is
a display-only insight, entirely outside the scoring engine.

---

## Workout GPS and Route Details

When outdoor workouts include GPS location tracks, Readylytics visualizes route contours, pace, and elevation profiles directly inside workout details.

- **Privacy-preserving offline Canvas rendering** — routes are drawn entirely on-device using native Android Canvas vector graphics. The app never embeds or initializes third-party mapping SDKs (such as Google Maps or Mapbox), downloads no raster or vector map tiles, and executes zero network calls. Location coordinates remain strictly private on the device.
- **Douglas-Peucker line simplification** — GPS tracks containing hundreds or thousands of raw coordinates are simplified on-device via the Douglas-Peucker algorithm using an adaptive tolerance. This preserves sharp turns, curves, and route shape while keeping rendering lightweight and responsive.
- **Pace and elevation performance charts** — route waypoints provide distance, altitude, and timestamp metrics used to generate elevation profiles and pace or speed progression charts across the activity.
- **Local storage and cascade lifecycle** — route coordinates are stored in the local encrypted Room database (`workout_route_points` table). When a workout session is deleted or purged during historical retention cleanup, all associated route points are immediately cascade-deleted.
- **Optional permission & graceful fallback** — reading route data requires the optional Health Connect exercise route permission. If route permissions are not granted or route data is unavailable for a session, the app continues to display heart-rate metrics, zone distribution, TRIMP, and recovery analysis normally without route contours.

_Implemented in: `RouteSimplifier.kt`, `RouteDistanceCalculator.kt`, `RouteContourCard.kt`, `WorkoutPerformanceCharts.kt`, `WorkoutDetailScreen.kt`_

---

## What the app needs from you

We read from Android Health Connect:

- **Sleep sessions** (with stages if your device records them)
- **Heart rate** during sleep (for restoration metrics)
- **Heart rate variability (RMSSD)** during sleep
- **Heart rate** during exercise sessions (for training load)
- **Exercise routes & GPS data** (optional, for offline route visualization, pace, and elevation profiles)
- **Distance & elevation gained** (optional, so a workout shows the same figures as the app that recorded it — re-measuring the GPS track instead reads about 1–3% short)

The app reads only — it never writes. You can revoke access at any time in Health Connect settings.

If a particular metric is missing on a given day, we'll either:

- show the score with a "data partial" badge and explain which component was estimated, or
- skip the score for that day entirely if too much is missing (especially total sleep time).

---

## How long until your scores stabilise

Biological baselines take time to learn. We are explicit about the phases:

- **Calibration (0–6 valid nights, confidence: Not Ready).** We collect baseline data and show raw sleep/recovery measurements where available, but Sleep Score, Load Score, and Readiness stay hidden until there are at least 7 valid nights. This avoids unstable early scores while HRV and RHR baselines are still forming.
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

- **TRIMP (Training Impulse)** — a single number summarising the intensity-weighted duration of an exercise session. Advanced models (such as LT-TRIMP) rely on the specific Heart Rate Zones configured in your app settings. These zones are always active; it is your responsibility to ensure they accurately reflect your current fitness level. On workout screens, **overall load** is derived primarily from total TRIMP. **Intensity** is derived from TRIMP/min (load density) and may promote a Moderate workout to Hard when `TRIMP >= 90` and `TRIMP/min >= 1.75`, or a Hard workout to Very Hard when `TRIMP >= 140` and `TRIMP/min >= 2.25`. These categorical labels do **not** replace numeric TRIMP in ATL, CTL, Strain Ratio, Load Score, RAS, or Readiness calculations.

  _Implemented in: `RasCalculator.kt`, `ComputeWorkoutTrimpUseCase.kt`, `RasScoringStrategy.kt`, `HrMaxProvider.kt`_

- **ATL / CTL** — short-term and long-term rolling averages of TRIMP. Borrowed from Banister's training-load model and used by most cycling and running apps.
- **Z-score** — a standardized number telling you how many standard deviations above or below your average a metric is. Z=0 is your average; Z=+2 is very high; Z=−2 is very low.

---

## Score adjustments you may not see

A few smaller modifiers shape the numbers behind the scenes. We list them here for transparency:

- **HRV-score saturation.** Above a Z-score of 1.5, additional HRV improvement contributes less to your Restoration score (a 0.25 slope beyond that point) — so an extraordinarily high reading doesn't dominate the score the way a moderate one does.
- **Late-nadir penalty.** If your lowest overnight heart rate occurs in the final third of your sleep period (after 67% of total sleep time has elapsed), we apply a small 0.95 multiplier to the restoration component. A very late RHR nadir often reflects a shortened or fragmented night rather than genuine recovery.
- **Banister training-load multiplier.** Your Banister training-load model converts heart-rate-reserve intensity into TRIMP using a multiplier of **1.0 for every physiology profile**, so TRIMP is a consistent, profile-independent measure of training load — the same effort produces the same load regardless of profile. The multiplier remains adjustable in Advanced Settings if you want to scale your personal TRIMP magnitude.
- **Readylytics Activity Score (RAS).** RAS is a PAI-style motivational activity metric with a daily cap and rolling 7-day accumulation. It is separate from the physiological Load Score: RAS never feeds Readiness, and Readiness/load continue to use TRIMP → ATL → CTL → Strain Ratio → Load Score.
- **Suspicious sleep-stage reweight.** If your wearable's sleep-stage data for a night looks implausible (e.g., no deep or REM sleep detected at all), we reweight the Sleep Score: Architecture and Fragmentation drop out and the score renormalizes Duration and Restoration according to your active weight profile. This avoids penalising you for a wearable data glitch rather than your actual sleep.
- **Missing-day handling in load averages.** Acute and chronic training-load averages (ATL/CTL) are exponential moving averages where a day with no logged exercise counts as zero TRIMP, not "no data". When you only have one day of history, that single day's value is used directly as the starting average.
- **Estimated max heart rate.** If you haven't entered your own max heart rate, we estimate it from your age using the Tanaka formula (`208 − 0.7 × age`), which is more accurate across adult age ranges than the older "220 − age" rule of thumb.

## Determinism & timezone

Your scores are computed against a stored scoring timezone, so the same underlying health data and settings always produce the same scores — recomputing your history (e.g., after a resync) reproduces identical numbers, and scores remain consistent if you travel or change your device's timezone.

---

## Honest limitations

1. **Wearable stage detection is imperfect.** Even premium devices misclassify deep and REM sleep on individual nights. We use age-adjusted targets and treat architecture scores as approximate readiness indicators. Architecture differences on individual nights should not be over-interpreted.

2. **Population norms are not destiny.** The age-banded deep sleep ranges come from polysomnography studies of healthy adults. Your healthy normal may sit anywhere in your age band. Our scoring uses age-banded targets so the ceiling shifts with you.

3. **Profiles are engineering heuristics, not physics.** The cutoffs (Athlete ±20 min, Active ±30 min, Sedentary ±45 min circadian threshold) are chosen for practical usability, not derived from prospective studies. We monitor whether these cutoffs are working well and will adjust if needed.

4. **The ACWR (Readiness load ratio) is descriptive, not predictive.** The methodological literature (Lolli et al. 2019; Impellizzeri et al. 2020, 2021) has demonstrated that the acute-to-chronic ratio is often a mathematical artifact and is not a validated _causal_ injury predictor. We approximate Gabbett's (2016) elevated-risk zone above the 1.3 sweet-spot ceiling with a Gaussian decay penalty — the curve shape is our own modelling choice — and present it strictly as a **load-change indicator** to help you visualise spikes in training intensity, not as a diagnostic injury-risk score.

5. **One night is noise; trends are signal.** Treat any single day's score as a data point, not a verdict. Look at the 7-day trend.

6. **This app does not diagnose anything.** If you suspect sleep apnea, a heart condition, an infection, an injury, or any other health concern, see a clinician. Physiological metrics such as HRV, sleep staging, and resting heart rate are non-specific and can be influenced by numerous behavioral, environmental, pharmacological, and measurement-related factors.

---

_Selected primary sources informing the scoring: Buysse 1989 (PSQI); Buysse 2014 (RU-SATED); Ohayon et al. 2004, 2017; Hirshkowitz et al. 2015 (NSF); Boulos et al. 2019 (Lancet Respir Med); Lauer et al. 1991; SIESTA database; Plews et al. 2012, 2013, 2014 (HRV monitoring); Buchheit 2014 (Front Physiol); Le Meur et al. 2013 (parasympathetic hyperactivity); Mishra et al. 2020 (Nat Biomed Eng); Quer et al. 2021 (Nat Med); Phillips et al. 2017 (Sleep Regularity Index); Lunsford-Avery et al. 2018; Windred et al. 2023/2024; Khalsa et al. 2003 (phase-response curve); Banister 1991; Foster 1998; Gabbett 2016; Lolli et al. 2019; Impellizzeri et al. 2020/2021._

---

## Scientific and medical disclaimer

This app describes a fitness and training-readiness monitoring framework derived from consumer wearable signals and sports-science literature. The framework is **not validated for medical diagnosis, disease screening, treatment guidance, or injury prediction**. Profiles and their associated thresholds optimize _estimated_ recovery signals and are engineering heuristics chosen for practical usability, not clinical validation.

If you have concerns about your health, sleep, or recovery, consult a qualified healthcare provider.
