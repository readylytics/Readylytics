---
layout: default
title: Customization & Thresholds
permalink: /customization/
---

# Customize Readylytics to your physiology

Readylytics comes with sensible defaults based on your profile (Athlete, Active, or Sedentary), but the app lets you fine-tune nearly every aspect of how your scores are calculated. Whether you want to adjust your heart rate zones, override your HRV baseline, or customize your sleep goal, you're in control.

---

## Heart rate zones

Your heart rate zones determine how the app interprets exercise intensity and calculates training load (TRIMP). The app ships with preset zones based on your max heart rate, but you can customize all five zones to match your fitness level and training philosophy.

**Find it:** Settings → Baselines & Thresholds → Heart Rate Zones

**What you can adjust:**
- **Auto-calculate from age** — The default. We estimate your max HR using age (Tanaka formula: `208 − 0.7 × age`). You can override this with your own measured max.
- **Custom zones** — Toggle "Manual Zone Editing" to fine-tune each zone. You can set zones as percentages of max HR or as absolute BPM thresholds.
  - Zone 1 (easy): 50–60% of max HR
  - Zone 2 (moderate): 60–70%
  - Zone 3 (tempo): 70–80%
  - Zone 4 (threshold): 80–90%
  - Zone 5 (max): 90–100%

If you've recently had a fitness breakthrough or returned from injury, it's worth revisiting your zones to ensure they still reflect your current capacity.

---

## Sleep and step goals

These anchor several calculations: sleep score, readiness, and activity tracking.

**Find it:** Settings → Baselines & Thresholds

**Sleep goal** (4–12 hours, default depends on profile):
- Affects the Sleep Score's duration component — the app compares your actual sleep to this target.
- If you're a naturally short sleeper or recovering from overtraining, lowering this may reduce unnecessary score penalties.
- If you're optimizing for performance, raising it emphasizes sleep recovery.

**Step goal** (1,000–30,000 steps per day):
- Used for tracking daily activity and showing progress toward personal targets.
- Does not directly affect readiness scores, but valuable for holistic health monitoring.

---

## HRV and RHR thresholds

These thresholds determine when the app flags elevated or depressed heart rate variability and resting heart rate as significant signals.

**Find it:** Settings → Baselines & Thresholds → Recovery Thresholds

**HRV thresholds** (multipliers: 0.8–1.2):
- **Optimal threshold** (default 1.10) — When your HRV rises above this multiple of your baseline, it's flagged as a strong recovery signal.
- **Warning threshold** (default 0.90) — When your HRV falls below this multiple, it may signal fatigue or illness.

**RHR thresholds** (multipliers: 0.8–1.2):
- **Optimal threshold** (default 0.90) — Lower RHR suggests cardiovascular fitness and recovery.
- **Warning threshold** (default 1.10) — Elevated RHR (above this multiple of your baseline) can indicate illness, stress, or overtraining.

These multipliers are relative, not absolute values, so they adapt as your fitness changes. If you're consistently seeing false alarms, you can widen the bands; if you want tighter sensitivity, narrow them.

---

## Circadian consistency threshold

This is how strictly the app enforces regular bedtimes and wake times.

**Find it:** Settings → Baselines & Thresholds → Circadian Consistency

**What it does:**
- Your profile sets a default threshold:
  - **Athlete:** ±20 minutes from your typical bedtime/wake time
  - **Active:** ±30 minutes
  - **Sedentary:** ±45 minutes
- If you fall within the threshold, you get a perfect circadian consistency score. Outside it, the score decays, hitting zero beyond threshold + 60 minutes.

**Override it:**
- Use the slider (0–90 minutes) to set a custom tolerance that matches your real schedule.
- For shift workers or those with variable schedules, raising this threshold prevents daily penalties.
- For competitive athletes, keeping it tight enforces the performance benefits of schedule regularity.

---

## Baseline overrides (HRV and RHR)

The app learns your personal HRV and RHR baselines from your data over time (60+ nights for high confidence). If you want to override these calculations—perhaps you've just returned from illness or vacation and your baseline feels out of date—you can set custom values.

**Find it:** Settings → Advanced → Baselines

**HRV baseline override** (1–500 ms):
- Your personal average heart rate variability, displayed as milliseconds.
- If you reset this, the app will recalculate based on your most recent nights.

**RHR baseline override** (30–100 bpm):
- Your personal resting heart rate, measured as a low percentile of overnight samples.

**Resting HR percentile** (1–15, default 5):
- The app takes the lowest 5% of your nighttime heart rate samples to compute RHR (avoiding temporary arousals).
- Adjust this if you want a more sensitive (lower percentile) or conservative (higher percentile) resting baseline.

---

## Advanced load models (TRIMP)

The app uses Training Impulse (TRIMP) to quantify workout intensity and duration into a single number. By default, it uses the Banister model, but you can switch to alternatives.

**Find it:** Settings → Advanced → Load Models

**Banister model** (default):
- Classic exponential decay model. The multiplier (default 1.0–1.75 depending on profile) scales how much load an athlete experiences.
- Athletes use 1.0×; sedentary users get 1.75× to reflect the same workout feeling "heavier."

**Cheng model:**
- Beta-based variant. Adjust the beta parameter (0.04–0.12) to tune sensitivity.

**I-TRIMP model:**
- Intensity-adjusted variant. The B-factor (1.0–4.5) controls decay rate.

Most users stay with Banister. Switch models only if you're comparing Readylytics to another training app using a different standard.

---

## RAS scaling factor

Readiness Absorption Score (RAS) is a motivational daily activity metric (PAI-style). It has its own scaling factor independent of readiness scoring.

**Find it:** Settings → Advanced → RAS Scaling Factor

- **Range:** 0.1–0.3 (default 0.20)
- Higher values make RAS accumulate faster, increasing daily motivation.
- Lower values require more activity to reach the same daily cap.
- Profiles have different defaults; you can reset to yours.

---

## Load source modes

Two independent settings let you choose *where* the app pulls heart rate data for different calculations.

**Find it:** Settings → Advanced or Data Sources

**Strain / Training Load source** (affects Readiness):
- **Workout only** (default) — Load comes only from your logged exercise sessions.
- **Everyday heart rate load** — Load includes elevated HR outside workouts (stress, heat, illness) on top of logged workouts.

**RAS source** (affects RAS only, not Readiness):
- **Workout only** — RAS from exercise only.
- **Everyday heart rate load** — RAS from total daily elevated HR.

Switching either setting is instant; no recalculation needed. Both are calculated in the background, so you can experiment without data loss.

---

## Device filtering

If you have multiple devices syncing to Health Connect, you can choose which device(s) feed data into each metric category.

**Find it:** Settings → Data Sources → Device Selection

**Categories:**
- Activity (exercise, steps)
- Body measurements (weight, body fat)
- Sleep (sleep sessions, stages)
- Vitals (heart rate, HRV, blood pressure, O₂)

By default, all devices contribute. Filter if one device is giving noisy or incorrect data.

---

## When to adjust

**Customization isn't necessary** — the defaults work well for most users. But consider adjusting if:

- Your zones no longer match your fitness level (after a major training block or injury recovery).
- Your schedule is irregular and the circadian threshold is constantly penalizing you.
- You've noticed HRV or RHR thresholds trigger false alarms (too sensitive).
- You're switching training philosophies (e.g., moving from aerobic base-building to high-intensity intervals).
- Your baseline feels stale after a long break or significant life change.

**Avoid** overthinking. One or two tweaks go a long way; resist constant micro-adjustments. Your body and your app's baselines need time to settle.

---

<a href="{{ '/' | relative_url }}" class="back-link">← Back to home</a>
