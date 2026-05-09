# About your scores

This app turns the data your phone and wearables already collect — sleep, heart rate, and exercise — into three daily numbers that try to answer one question: **how is your body doing today, and what should you do with that information?**

We try to be honest about what these numbers can and can't tell you. They are decision aids, not diagnoses. If something feels off in your body, trust your body over the score.

## Your profile matters

When you first open the app, you select a **Physiological Profile** — Athlete, Active, Sedentary, or Shift Worker — that tunes how we interpret your data. Different activity profiles show different recovery patterns, so the thresholds we use to score you are customized.

- **Athlete** — you train regularly (3+ days/week structured exercise). Your metrics will be interpreted with tighter circadian consistency targets (±20 min) and more nuanced HRV sensitivity.
- **Active** — you exercise 1–3 times per week or have an active job. Moderate thresholds (±30 min circadian, balanced HRV sensitivity).
- **Sedentary** — you don't exercise regularly but may have daily movement. More relaxed thresholds (±45 min circadian) so normal life variation doesn't penalise you.
- **Shift Worker** — you have a rotating or irregular schedule. We measure circadian _consistency within a week_ rather than across weeks, and ignore absolute bedtimes.

**Why profiles exist.** A 2:00 AM bedtime is a red flag for an athlete aiming to peak; it's normal for a shift worker. Similarly, an athlete's HRV is more stable than a sedentary person's, so we don't compare them to the same bar. Profiles let us keep scores fair across different lifestyles.

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
- **Architecture (25%)** — how much of your sleep was deep (slow-wave) sleep and REM. Both matter. Deep sleep is when most physical recovery happens; REM is when your brain processes memory and emotion. Targets are age-specific (see below).
- **Restoration (25%)** — how rested your estimated recovery-related physiology looks, based on overnight heart rate variability (HRV) and resting heart rate (RHR). How we interpret HRV varies by profile (see "HRV sensitivity by profile" below).

**Reading the score**

- **85–100** Excellent. You slept enough, your sleep stages looked balanced, and your autonomic recovery markers were strong.
- **70–84** Good. Most components are healthy; one is slightly below your norm.
- **50–69** Fair. Likely a duration shortfall, fragmented sleep, or an off night for HRV/RHR.
- **Below 50** Poor. Multiple components are below typical. One bad night is rarely meaningful; a streak deserves attention.

**Deep and REM sleep targets — age matters**

As you age, the amount of deep sleep naturally declines. We adjust your targets based on your age so you're never unfairly compared to a younger person.

| Age range | Deep sleep target | REM sleep target |
| --------- | ----------------- | ---------------- |
| 18–29     | 18–20%            | 22%              |
| 30–49     | 16–18%            | 21%              |
| 50–59     | 14–16%            | 20%              |
| 60+       | 10–13%            | 18%              |

These ranges come from polysomnography studies in healthy populations. They represent the healthy mid-range; your personal healthy normal may sit anywhere within your age band. We do not penalise you if your wearable reports unusual numbers — wearable stage detection is imperfect.

**HRV sensitivity by profile**

Heart rate variability (HRV) is noisy on any single night. To avoid false positives, we only consider HRV "high" or "low" relative to a Z-score threshold. This threshold shifts based on your profile.

- **Athlete** — we expect stable HRV. High variability stands out more, so we flag it sooner (Z > +2.0 is "very high").
- **Active** — moderate sensitivity (Z > +1.5 is "very high").
- **Sedentary** — more natural variability, so we use a tighter threshold (Z > +1.2 is "very high").

This tuning means a Sedentary person's high-variability night doesn't get the same weight as an Athlete's, because natural day-to-day noise is higher for less-regular lifestyles.

---

## Circadian Consistency

This score asks: **do you go to bed and wake up at roughly the same times each day?** Schedule regularity is independently linked to better metabolic, cognitive, and cardiovascular outcomes — sometimes more strongly than sleep duration itself (Windred et al. 2023, UK Biobank).

We compare each night's bedtime and wake time to your _typical_ (median) bedtime and wake time over the last 14 days (or 4 weeks for shift workers; see below). The bigger the deviation, the lower the daily score. The number you see is a 7-day rolling average so a single late night doesn't tank it.

**Profile-specific thresholds**

How strict we are about "consistent" depends on your profile:

| Profile          | Deviation threshold | Interpretation                    |
| ---------------- | ------------------- | --------------------------------- |
| **Athlete**      | ±20 minutes         | Tight control for performance     |
| **Active**       | ±30 minutes         | Standard regularity               |
| **Sedentary**    | ±45 minutes         | Relaxed; normal life variation OK |
| **Shift Worker** | Within-week only    | See below                         |

Within each threshold band:

- Within the threshold → full score.
- Threshold to threshold+60 min → score decays linearly.
- Beyond threshold+60 min → score is 0 for that day.

**Special handling for shift workers**

If you work rotating shifts or have an irregular schedule, the score measures **within-week consistency** rather than absolute times. We compare each night to the median bedtime/wake time for that same day-of-week over the past 4 weeks. This way, a Monday night is compared to other Mondays, not to your overall average. The threshold is tighter (±30 min from your Monday typical time) because the focus is on predictability within each shift cycle, not absolute timing.

**A caveat for biphasic sleepers.** This metric is calibrated for people with one main sleep period per day. If you sleep in two segments by choice (e.g., 2:00–4:00 AM and then again at 6:00–7:00 AM), the score may misclassify your schedule. We exclude any single sleep period under 3 hours from the median calculation so naps don't pull your "typical" times around — but this rule has imperfect coverage of every sleep pattern.

---

## Readiness

A daily 0–100 number reflecting whether your recent training load is in a healthy range.

We compute two rolling averages of your training load:

- **Acute load (ATL)** — roughly the last week
- **Chronic load (CTL)** — roughly the last 6 weeks

The ratio (ATL ÷ CTL) tells us whether you've recently spiked above your recent norm. Around 1.0 means you're training in line with your fitness; substantially above 1.0 means a relative spike.

**How we score the ratio**

- 0.8–1.3 → 100 (in your normal range — "sweet spot")
- 1.3–1.5 → linearly decays
- Above 1.5 → smooth quadratic decay

**Tooltips**

- _Peak (85–100)_ — your recent load is consistent with your fitness.
- _Maintain (60–84)_ — manageable load increase.
- _Caution (30–59)_ — meaningful load spike; consider an easier day.
- _High Fatigue (<30)_ — large spike vs. your norm.

**Emergency signals**

If your HRV and RHR patterns suggest possible physiological stress (such as early illness or functional overreaching), we apply a soft cap to Readiness to give you a heads-up. This requires two consecutive nights matching the pattern and is informational only, not medical advice.

**What we don't do.** We don't penalise you for resting. A week of light activity will _not_ drop Readiness; the score is designed for load _spikes_, not undertraining.

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

- **Days 1–7 (Calibration).** We collect data. Sleep Score and Circadian Consistency are visible from the first night you log enough sleep, but **HRV-based Restoration is hidden** because a single night of HRV is not interpretable on its own. Readiness uses a placeholder fitness value based on the activity level you tell us during onboarding.
- **Days 8–21 (Provisional).** We start showing all three scores, but Restoration uses a population-typical estimate of how much your HRV varies day to day (tiered by your profile). Expect more variability than the mature score.
- **Days 22–60 (Maturing).** Your personal HRV mean is settling, and we begin blending your personal day-to-day variability with the population estimate. Confidence indicator visible.
- **Day 60+ (Mature).** All scores use your own personal baselines for both the average and the variability. This is when small day-to-day differences become trustworthy.

If you wear your tracker only 3–5 nights a week, the timeline lengthens proportionally.

---

## A short glossary

- **HRV (Heart Rate Variability)** — the millisecond-level variation in time between heartbeats. Higher generally indicates better autonomic recovery, _up to a point_.
- **RMSSD** — the specific HRV measure most apps use. We work with the natural log of RMSSD internally because it behaves better statistically.
- **RHR (Resting Heart Rate)** — your nocturnal heart rate, averaged across deep portions of sleep.
- **Deep sleep / Slow-Wave Sleep / N3** — the deepest stage of NREM sleep; growth-hormone release is concentrated here.
- **REM** — the dreaming stage, important for memory and emotional processing.
- **TRIMP (Training Impulse)** — a single number summarising the intensity-weighted duration of an exercise session.
- **ATL / CTL** — short-term and long-term rolling averages of TRIMP. Borrowed from Banister's training-load model and used by most cycling and running apps.
- **Z-score** — a standardized number telling you how many standard deviations above or below your average a metric is. Z=0 is your average; Z=+2 is very high; Z=−2 is very low.

---

## Honest limitations

1. **Wearable stage detection is imperfect.** Even premium devices misclassify deep and REM sleep on individual nights. We use age-adjusted targets and don't penalise you for differences within ~5% of the target. Architecture differences within that margin are not meaningful.

2. **Population norms are not destiny.** The age-banded deep sleep ranges come from polysomnography studies of healthy adults. Your healthy normal may sit anywhere in your age band. Our scoring uses age-banded targets so the ceiling shifts with you.

3. **Profiles are engineering heuristics, not physics.** The cutoffs (Athlete ±20 min, Active ±30 min, Sedentary ±45 min circadian threshold) are chosen for practical usability, not derived from prospective studies. We monitor whether these cutoffs are working well and will adjust if needed.

4. **The ACWR (Readiness load ratio) is contested.** The methodological literature (Lolli et al. 2019; Impellizzeri et al. 2020, 2021) has shown that the acute-to-chronic ratio is partially a mathematical artifact and is not a validated _causal_ injury predictor. We present it as a _load change indicator_, not an injury risk score, and we are evaluating an uncoupled-window version (chronic = days 8–28 prior; acute = last 7) for the next release.

5. **One night is noise; trends are signal.** Treat any single day's score as a data point, not a verdict. Look at the 7-day trend.

6. **This app does not diagnose anything.** If you suspect sleep apnea, a heart condition, an infection, an injury, or any other health concern, see a clinician. Physiological metrics such as HRV, sleep staging, and resting heart rate are non-specific and can be influenced by numerous behavioral, environmental, pharmacological, and measurement-related factors.

---

_Selected primary sources informing the scoring: Buysse 1989 (PSQI); Buysse 2014 (RU-SATED); Ohayon et al. 2004, 2017; Hirshkowitz et al. 2015 (NSF); Boulos et al. 2019 (Lancet Respir Med); Lauer et al. 1991; SIESTA database; Plews et al. 2012, 2013, 2014 (HRV monitoring); Buchheit 2014 (Front Physiol); Le Meur et al. 2013 (parasympathetic hyperactivity); Mishra et al. 2020 (Nat Biomed Eng); Quer et al. 2021 (Nat Med); Phillips et al. 2017 (Sleep Regularity Index); Lunsford-Avery et al. 2018; Windred et al. 2023/2024; Khalsa et al. 2003 (phase-response curve); Banister 1991; Foster 1998; Gabbett 2016; Lolli et al. 2019; Impellizzeri et al. 2020/2021._

---

## Scientific and medical disclaimer

This app describes a wellness-oriented monitoring framework derived from consumer wearable signals and sports-science literature. The framework is **not validated for medical diagnosis, disease screening, treatment guidance, or injury prediction**. Profiles and their associated thresholds optimize _estimated_ recovery signals and are engineering heuristics chosen for practical usability, not clinical validation.

If you have concerns about your health, sleep, or recovery, consult a qualified healthcare provider.
