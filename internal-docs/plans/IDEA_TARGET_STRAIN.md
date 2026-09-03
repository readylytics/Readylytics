# Idea: Dynamic Target Strain & Daily TRIMP Guidance

> **Status:** CONCEPT / BACKLOG — Captured 2026-09-03.

## 1. Problem Statement & Motivation

Today, Readylytics gives the user:
1. **Readiness Score (0–100):** A composite of nocturnal restoration (HRV lnRMSSD Z-score, RHR nadir floor), sleep score, and chronic training load ratio (ACWR).
2. **Training Readiness & Residual Fatigue:** Workout-only exponential fatigue decay.
3. **Daily Accumulated TRIMP & RAS:** Load accrued throughout the day.

**The Gap:** While the user knows *how ready* they are, they are left to guess *how much work* they should perform today. A user with an 88 Readiness doesn't know whether 80 TRIMP is enough or if they have capacity for 160 TRIMP. Conversely, a user with a 38 Readiness might accidentally overreach because there is no explicit target or ceiling guiding their day.

---

## 2. Scientific Basis & Modeling

### 2.1 The Target Strain Formula

The daily target should adapt to two factors:
1. **The User's Fitness Baseline (Capacity):** Daily Chronic Training Load ($CTL / 7$ or baseline average daily TRIMP). A daily target for an athlete whose CTL is 700/week (100/day) is very different from a sedentary/active user whose CTL is 140/week (20/day).
2. **Today's Readiness & Residual Fatigue (Recovery State):**
   - **High Readiness (80–100):** Overreaching/Stimulus window. Recommended daily load = $1.1 \times \text{Daily CTL}$ to $1.4 \times \text{Daily CTL}$.
   - **Optimal / Moderate Readiness (60–79):** Maintenance window. Recommended daily load = $0.8 \times \text{Daily CTL}$ to $1.1 \times \text{Daily CTL}$.
   - **Low Readiness (40–59):** Active recovery / Deload. Recommended daily load = $0.4 \times \text{Daily CTL}$ to $0.7 \times \text{Daily CTL}$.
   - **Very Low / Illness Flag (<40 or `possible-illness`):** Complete rest / Light movement. Recommended daily load = $0$ to $0.3 \times \text{Daily CTL}$ (or capped at a light Zone 1 ceiling).

### 2.2 Dynamic Adjustment with Residual Fatigue
If `ResidualFatigue` from recent workouts is high, the upper bound of the target window is scaled down:
$$\text{Target}_{\text{effective}} = \text{Target}_{\text{raw}} \times \exp\left(-\frac{\text{ResidualFatigue}}{2 \cdot S}\right)$$

---

## 3. UI & User Experience

### 3.1 Dashboard & Workouts Integration
- **Target Strain Progress Card:**
  - Shows a dual-indicator bar or ring:
    - **Target Zone:** A highlighted bracket (e.g. `85 – 120 TRIMP`).
    - **Current Progress:** Filled bar showing today's accumulated TRIMP (Workout only or Everyday HR depending on settings).
  - Status indicators:
    - *Under Target* (e.g., "65 / 85–120 TRIMP — Stimulating effort still recommended")
    - *In Target Zone* (e.g., "Optimal strain achieved for today's recovery")
    - *Exceeded Target* (e.g., "145 / 120 TRIMP — High strain day, prioritize sleep tonight")

### 3.2 Live Day Progression
- As the user logs workouts or accumulates everyday HR minutes, the card updates in real-time.
- If morning Readiness was in the Calibration phase (<7 days), show "Calibrating" instead of numeric target bounds.

---

## 4. Pure-Kotlin Architecture

- **Domain Model:** `TargetStrainWindow(minTrimp: Float, maxTrimp: Float, currentTrimp: Float, status: TargetStrainStatus)`
- **Use Case:** `ComputeTargetStrainUseCase(readiness, residualFatigue, ctl, profile)` in `:core:scoring`.
- **Zero Android dependencies:** Calculation logic lives in pure Kotlin with 100% unit test coverage.
