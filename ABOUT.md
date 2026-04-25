Welcome to **Readylytics**.

Our guiding principle is simple: **Train with insight, not guesswork.** To do that, we’ve built our scoring engines on the bedrock of peer-reviewed sports science and circadian biology. Below is the breakdown of the mathematical models and clinical research powering your data.

---

## 1. Circadian Consistency (Sleep Regularity)

While many apps focus only on how long you sleep, **Readylytics** prioritizes how _regularly_ you sleep.

- **The "Anchor" Logic:** We calculate your score against your **Rolling Median** bedtime and wake time from the last 14 days.
- **Why it matters:** Research indicates that sleep regularity is often a better predictor of metabolic health and cognitive performance than duration alone.
- **The Penalty Zone:** We allow a configurable "grace period" (default +/- 30 mins). If you deviate beyond this, your score decays linearly:
  - $Score = 100 \cdot \left( 1 - \frac{D - T}{60} \right)$ where $D$ is deviation and $T$ is threshold.

---

## 2. Cardiovascular Load (PAI-Inspired)

We use a transparent version of the **Personal Activity Intelligence (PAI)** model to ensure your heart stays resilient.

- **Intensity Weighting:** We use the **Banister TRIMP (Training Impulse)** model to weight high-intensity efforts exponentially.
- **The Formula:** $$T_d = \sum (D \cdot HR_r \cdot a \cdot e^{b \cdot HR_r})$$
- **Clinical Targets:** Your goal is a 7-day rolling sum of **100 points**. Maintaining this "100 PAI" threshold has been clinically shown to reduce cardiovascular mortality risk by up to 25%.
- **The Multiplier:** To prevent "point chasing," points become harder to earn as you approach 100, and daily gains are capped at 75 points.

---

## 3. Readiness & The "Sweet Spot" (ACWR)

Readylytics calculates your daily training capacity by balancing short-term fatigue against long-term fitness.

- **Strain Ratio ($SR$):** This is your **Acute:Chronic Workload Ratio**.
  - $SR = \frac{ATL (7\text{-day load})}{CTL (42\text{-day load})}$
- **The Sweet Spot:** A ratio between **0.8 and 1.2** is the scientifically validated "Sweet Spot" for performance gains.
- **Injury Prevention:** If your ratio exceeds **1.5**, you are in the "Danger Zone" for overtraining and injury.

---

## 4. Sleep Architecture & Restoration

Your Sleep Score ($SS$) is a 100-point composite weighted by duration (50%), architecture (25%), and restoration (25%).

- **Deep & REM Sleep:** We target **15–25% Deep** and **20–25% REM** based on clinical benchmarks for physical and mental repair.
- **Autonomic Recovery:** We track your **HRV Z-Score** (variation compared to your 30-day normal) and your **RHR Ratio**.
- **The Late Nadir Penalty:** If your heart rate doesn't reach its lowest point (nadir) until just before you wake up, Readylytics applies a 10% penalty to your restoration score, as this suggests your body was busy processing metabolic stress (like a late meal or alcohol) instead of recovering.

---

## Technical Appendix: Scientific Sources

| Metric            | Scientific Foundation                 | Reference              |
| :---------------- | :------------------------------------ | :--------------------- |
| **Consistency**   | Sleep Regularity Index (SRI)          | Phillips et al. (2017) |
| **Activity Load** | The HUNT Fitness Study                | Nes et al. (2017)      |
| **Strain Ratio**  | Training—Injury Prevention Paradox    | Gabbett (2016)         |
| **HRV Analysis**  | 30-day Standard Deviation ($Z_{hrv}$) | Plews et al. (2013)    |

> **Readylytics Note:** Professional-grade accuracy requires data. If your scores seem volatile during your first 7 days, don't worry—the engine is currently in its "Calibration" phase to learn your unique biometrics.
