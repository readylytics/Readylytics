# Insight Details Matrix

This document defines the user-facing details, signal meanings, possible causes, recommendations, caveats, and safety notes for all 20 emitted insights.

---

## BP_ELEVATED_HIGH_STRAIN

- **Type**: `PHYSIOLOGY`
- **Trigger**: Systolic blood pressure is more than 10 mmHg above baseline and training strain ratio is above 1.3.
- **Card title**: Blood Pressure Elevated During High Strain
- **Card description**: Your systolic blood pressure is above your recent baseline while training strain is high.
- **Observed signal**: Your systolic blood pressure is more than 10 mmHg above your recent baseline, and your strain ratio is high.
- **Meaning**: Your cardiovascular system may be under additional stress today.
- **Confidence**: `MEDIUM`
- **Possible causes**:
  - High recent training load: Recent training may be adding cardiovascular demand.
  - Poor sleep: Short or disrupted sleep can make recovery signals less favorable.
  - Stress: Mental or physical stress can affect blood pressure and recovery markers.
  - Caffeine or stimulants: Recent stimulant intake can raise blood pressure in some people.
  - Dehydration: Fluid balance can influence heart rate and blood pressure.
  - Alcohol: Alcohol can disrupt sleep and overnight recovery.
  - Measurement timing or device variation: Blood pressure readings vary with posture, timing, cuff fit, and device noise.
- **Recommendations**:
  - Avoid very intense training if blood pressure remains elevated or you feel unwell.
  - Retake the measurement under consistent resting conditions.
  - Prioritize hydration, sleep, and recovery.
- **Caveats**:
  - One elevated reading does not diagnose hypertension.
- **Safety note**: If blood pressure is repeatedly high, very high, or occurs with chest pain, shortness of breath, severe headache, dizziness, or neurological symptoms, seek medical advice.
- **Source notes / rationale**: Cautious cardiovascular strain warning, strictly non-diagnostic.

---

## CIRCADIAN_SHIFT_RECOVERY_MISS

- **Type**: `PHYSIOLOGY`
- **Trigger**: Bedtime was more than 90 minutes later than median bedtime and no clear recovery rebound was detected after a rest day.
- **Card title**: Late Bedtime May Have Affected Recovery
- **Card description**: Your bedtime was much later than usual, which may have made overnight recovery less consistent.
- **Observed signal**: You went to bed more than 90 minutes later than your usual bedtime, and no clear recovery rebound was detected after a rest day.
- **Meaning**: A large bedtime shift can make sleep and recovery signals less consistent.
- **Confidence**: `MEDIUM`
- **Possible causes**:
  - Later bedtime: Your sleep timing moved well outside your usual pattern.
  - Shorter sleep opportunity: Less time in bed can reduce recovery opportunity.
  - Social schedule or travel: Schedule changes can shift sleep timing.
  - Stress: Stress can delay sleep onset and affect overnight physiology.
  - Late meal or screen exposure: Evening routines can delay sleep and recovery signals.
  - Normal HRV variability: One night can move around because of normal measurement variation.
- **Recommendations**:
  - Try to return to your usual sleep schedule tonight.
  - Keep training flexible if you also feel tired.
- **Caveats**:
  - This does not prove your circadian rhythm shifted.
- **Safety note**: None.
- **Source notes / rationale**: Chronobiology recovery alignment.

---

## HIGH_STRAIN_SLEEP_DEFICIT

- **Type**: `PHYSIOLOGY`
- **Trigger**: Strain ratio is above 1.3 and sleep duration was less than 85% of the goal.
- **Card title**: High Strain and Short Sleep
- **Card description**: High strain combined with short sleep may be limiting your recovery today.
- **Observed signal**: Your strain ratio is high and you slept less than 85% of your goal.
- **Meaning**: Your body may have had less overnight recovery time after a high-load period.
- **Confidence**: `MEDIUM`
- **Possible causes**:
  - High training load: Recent training load is above your usual range.
  - Short sleep duration: You slept well below your configured sleep goal.
  - Late workout: Training close to bedtime can delay overnight settling.
  - Stress: Stress can reduce sleep quality and recovery consistency.
  - Poor sleep timing: A shifted sleep window can make recovery less predictable.
  - Under-fueling: Not eating enough can make high-load periods harder to recover from.
- **Recommendations**:
  - Avoid adding another hard session if you feel tired.
  - Prioritize an earlier bedtime tonight.
  - Consider easy training or recovery work.
- **Caveats**:
  - This does not prove your recovery is impaired.
- **Safety note**: None.
- **Source notes / rationale**: Classic training-sleep restriction interaction.

---

## HRV_DECLINE_STREAK

- **Type**: `PHYSIOLOGY`
- **Trigger**: HRV z-score below baseline (`zLnHrv < 0`) for three consecutive nights, and the rounded nocturnal HRV is also below the rounded HRV baseline for each of those nights (matching what the dashboard tooltip shows) — this second check prevents a day from counting when it only misses on float-noise-level z-score precision while displaying as "on baseline".
- **Card title**: HRV Below Baseline Multiple Nights
- **Card description**: Your HRV has been below your baseline for several consecutive nights, which may suggest accumulating strain.
- **Observed signal**: Your HRV has been below your baseline for three consecutive nights.
- **Meaning**: A multi-night HRV decline may reflect increased recovery demand, stress, illness, or poor sleep.
- **Confidence**: `MEDIUM`
- **Possible causes**:
  - Accumulated training load: Repeated training stress can increase recovery demand.
  - Poor sleep: Sleep loss can lower HRV relative to baseline.
  - Stress: Mental or physical stress can lower HRV.
  - Illness onset: Early illness can appear as lower HRV and higher resting heart rate.
  - Alcohol: Alcohol often disrupts sleep and autonomic recovery.
  - Dehydration: Fluid loss can affect HRV and heart rate.
  - Travel: Travel can disrupt timing, sleep, hydration, and recovery.
- **Recommendations**:
  - Look at how you feel and whether resting heart rate, sleep, or readiness also look worse.
  - If several markers are poor, consider reducing intensity.
- **Caveats**:
  - HRV below baseline does not identify the exact cause.
- **Safety note**: None.
- **Source notes / rationale**: Multi-day autonomic decline monitoring.

---

## HRV_DROP_LOW_SPO2

- **Type**: `PHYSIOLOGY`
- **Trigger**: HRV is much lower than usual and average sleeping oxygen was below 94%.
- **Card title**: Low HRV and Lower Oxygen Overnight
- **Card description**: Your HRV dropped sharply and your sleeping oxygen was lower than usual, which may suggest illness, poor recovery, or a breathing-related disruption.
- **Observed signal**: Your HRV was much lower than usual and your average sleeping oxygen was below 94%.
- **Meaning**: This combination can occur with illness, poor recovery, altitude, congestion, device fit issues, or breathing-related sleep disruption.
- **Confidence**: `MEDIUM`
- **Possible causes**:
  - Early illness or congestion: Congestion or early illness can affect breathing and HRV.
  - Poor sleep: Disrupted sleep can lower HRV and affect oxygen readings.
  - Breathing disruption: Overnight breathing disruption can appear as lower oxygen.
  - Sleeping position: Position can affect breathing and sensor contact.
  - Altitude: Higher altitude can lower overnight oxygen saturation.
  - Device fit or sensor noise: Wearable oxygen readings can be sensitive to fit and motion.
- **Recommendations**:
  - Check whether your wearable recorded HRV and whether Health Connect synced overnight data.
  - Prioritize rest if you also feel sick or unusually fatigued.
- **Caveats**:
  - This does not diagnose sleep apnea or any medical condition.
- **Safety note**: Repeated low oxygen readings, loud snoring, pauses in breathing, or strong daytime sleepiness are worth discussing with a clinician.
- **Source notes / rationale**: Oxygen saturation drop warning, non-diagnostic.

---

## LATE_NADIR

- **Type**: `PHYSIOLOGY`
- **Trigger**: Lowest heart rate occurred in the final third of the sleep period.
- **Card title**: Late Heart Rate Nadir
- **Card description**: Your lowest heart rate occurred later than usual last night, which may suggest delayed overnight recovery.
- **Observed signal**: Your lowest heart rate happened in the final third of your sleep period.
- **Meaning**: Your body may have taken longer than usual to settle into a more recovered overnight state.
- **Confidence**: `MEDIUM`
- **Possible causes**:
  - Late workout: Training close to bedtime can delay overnight settling.
  - High training load: High load can extend overnight recovery demand.
  - Late meal: Eating late can keep heart rate elevated.
  - Alcohol: Alcohol can delay overnight heart-rate recovery.
  - Stress: Stress can keep heart rate elevated during sleep.
  - Travel: Travel can disrupt timing and recovery.
  - Poor sleep: Fragmented sleep can alter heart-rate patterns.
  - Early illness: Early illness can raise overnight heart rate.
- **Recommendations**:
  - Keep today flexible. If you feel tired, prefer easy training, mobility, or recovery work.
- **Caveats**:
  - A late heart rate nadir does not prove poor recovery or identify a single cause.
- **Safety note**: None.
- **Source notes / rationale**: Delayed overnight recovery marker.

---

## LATE_NADIR_ELEVATED_RHR

- **Type**: `PHYSIOLOGY`
- **Trigger**: Nadir was delayed and resting heart rate was more than 5 bpm above baseline.
- **Card title**: Delayed Recovery with Elevated Resting Heart Rate
- **Card description**: Your heart rate nadir was delayed and your resting heart rate was above baseline, which may suggest increased overnight recovery demand.
- **Observed signal**: Your lowest heart rate occurred late, and your resting heart rate was more than 5 bpm above baseline.
- **Meaning**: Your body may have remained under physiological strain during the night.
- **Confidence**: `MEDIUM_HIGH`
- **Possible causes**:
  - Late or intense workout: Hard training or late timing can keep heart rate elevated overnight.
  - Illness onset: Early illness can raise resting heart rate.
  - Poor sleep: Short or disrupted sleep can reduce overnight recovery.
  - Alcohol: Alcohol can raise overnight heart rate.
  - Dehydration: Fluid loss can raise heart rate.
  - Stress: Stress can increase overnight physiological strain.
  - Heat: Heat exposure can raise heart rate and recovery demand.
- **Recommendations**:
  - Choose an easier training option if you also feel tired or unwell.
  - Prioritize hydration and sleep tonight.
- **Caveats**:
  - This does not diagnose illness or prove overtraining.
- **Safety note**: None.
- **Source notes / rationale**: Autonomic strain warning.

---

## LATE_NADIR_SHORT_SLEEP

- **Type**: `PHYSIOLOGY`
- **Trigger**: Nadir was delayed and sleep was less than 85% of goal.
- **Card title**: Late Recovery and Short Sleep
- **Card description**: Your lowest heart rate occurred late, and your sleep was shorter than your goal, which may have reduced overnight recovery time.
- **Observed signal**: Your heart rate nadir occurred late and you slept less than 85% of your sleep goal.
- **Meaning**: Your recovery window may have been shortened before your body fully settled overnight.
- **Confidence**: `MEDIUM`
- **Possible causes**:
  - Short sleep duration: You slept well below your configured sleep goal.
  - Late bedtime: A late bedtime can reduce sleep opportunity.
  - Early wake-up: An early wake-up can end sleep before full recovery opportunity.
  - Late workout: Training close to bedtime can delay overnight settling.
  - Stress: Stress can fragment sleep and keep heart rate higher.
  - Poor sleep continuity: Interrupted sleep can reduce recovery opportunity.
- **Recommendations**:
  - Keep intensity flexible today and aim for more sleep opportunity tonight.
- **Caveats**:
  - This does not prove your recovery was poor, but it suggests less recovery opportunity.
- **Safety note**: None.
- **Source notes / rationale**: Sleep duration restricted recovery.

---

## LOAD_SPIKE_RECOVERY_STRAIN

- **Type**: `PHYSIOLOGY`
- **Trigger**: Strain ratio > 1.3, yesterday TRIMP >= 120, or ACWR >= 1.5, AND at least one recovery marker shows strain.
- **Card title**: Training Load May Be Affecting Recovery
- **Card description**: Your recent training load is high, and your recovery markers show signs of strain.
- **Observed signal**: Your recent training load is above your usual range, and at least one recovery marker is less favorable than normal.
- **Meaning**: Your body may need more recovery before another hard session.
- **Confidence**: `MEDIUM`
- **Possible causes**:
  - High strain ratio: Your acute training strain is high compared with your longer-term baseline.
  - High TRIMP yesterday: Yesterday's training load was high.
  - Short sleep: You slept well below your configured sleep goal.
  - Low HRV: Your HRV is below your recent baseline.
  - Elevated resting heart rate: Your resting heart rate is above your recent baseline.
  - Dehydration: Fluid loss can make recovery markers less favorable.
  - Under-fueling: Not eating enough can make high-load periods harder to recover from.
  - Heat stress: Heat can raise cardiovascular strain and recovery demand.
  - General life stress: Life stress can affect HRV, resting heart rate, and sleep.
- **Recommendations**:
  - Avoid stacking another hard session on top of this signal.
  - Prefer easy aerobic work, mobility, or rest if you also feel tired.
  - Prioritize sleep, hydration, and enough food before your next hard session.
- **Caveats**:
  - This does not indicate overtraining.
  - This does not predict injury by itself.
- **Safety note**: If fatigue persists despite several easy days, or if performance drops for a longer period, consider adjusting your training plan or seeking professional guidance.
- **Source notes / rationale**: Non-diagnostic replacement for overreaching.

---

## RAS_DEPLETION_HIGH_STRAIN

- **Type**: `TRAINING_BEHAVIOR`
- **Trigger**: RAS score is below target while strain ratio indicates recent training load.
- **Card title**: Low RAS Despite Training Load
- **Card description**: Your RAS is low even though recent training strain is elevated.
- **Observed signal**: Your RAS score is below target while your strain ratio indicates recent training load.
- **Meaning**: Some workouts may add training strain without raising RAS much, depending on heart-rate intensity and duration.
- **Confidence**: None.
- **Possible causes**:
  - Strength training with lower sustained heart rate: RAS rewards sustained heart-rate activity more than brief spikes.
  - Short workouts: Short sessions may not add much RAS even when they feel hard.
  - Incomplete heart-rate data: Missing heart-rate samples can reduce RAS credit.
  - Training intensity below RAS zones: Some work may be below zones that contribute strongly to RAS.
  - Device measurement gaps: Wearable gaps can affect RAS calculation.
- **Recommendations**:
  - If RAS is a goal, include some sustained aerobic work in appropriate heart-rate zones.
- **Caveats**:
  - Low RAS does not mean your training was useless.
- **Safety note**: None.
- **Source notes / rationale**: Heart-rate intensity vs workout duration monitoring.

---

## RAS_WEEKLY_UNDERPERFORMANCE

- **Type**: `TRAINING_BEHAVIOR`
- **Trigger**: Weekly rolling RAS total is below target.
- **Card title**: Weekly RAS Below Target
- **Card description**: Your rolling weekly RAS total is below your target.
- **Observed signal**: Your 7-day RAS total is below the configured weekly target.
- **Meaning**: RAS is designed to reward sustained heart-rate activity over the week.
- **Confidence**: None.
- **Possible causes**:
  - Too few aerobic sessions: Fewer sustained sessions can lower weekly RAS.
  - Low heart-rate intensity: Lower-intensity sessions may add less RAS.
  - Missing heart-rate data: Missing heart-rate samples can reduce RAS credit.
  - Recovery week: A planned easier week can reduce weekly RAS.
  - Strength-focused training: Strength work may add less RAS than sustained aerobic work.
- **Recommendations**:
  - Add easy or moderate aerobic sessions if this fits your training plan.
- **Caveats**:
  - A low RAS week is not automatically bad if you are intentionally recovering or focusing on strength.
- **Safety note**: None.
- **Source notes / rationale**: Cardiovascular health metric tracking.

---

## RECOVERY_HRV_MISSING

- **Type**: `DATA_QUALITY`
- **Trigger**: No sleep HRV reading was recorded last night.
- **Card title**: HRV Data Missing
- **Card description**: Today's readiness score is based on resting heart rate and sleep only because no HRV reading was recorded last night.
- **Observed signal**: No sleep HRV reading was recorded last night.
- **Meaning**: Readiness may be less personalized today because HRV could not be included.
- **Confidence**: None.
- **Possible causes**:
  - Watch not worn overnight: No overnight wear can prevent HRV capture.
  - Loose fit: Poor sensor contact can prevent HRV capture.
  - Low battery: Low battery can stop overnight recording.
  - Sync delay: Health Connect data may not have arrived yet.
  - Device did not record HRV: Some devices or nights may not include HRV.
  - Health Connect did not provide HRV data: The source app may not have shared HRV data.
- **Recommendations**:
  - Check whether your wearable recorded HRV and whether Health Connect synced overnight data.
- **Caveats**: None.
- **Safety note**: None.
- **Source notes / rationale**: Data completeness notification.

---

## RECOVERY_SUSPICIOUS_STAGE_RATIO

- **Type**: `DATA_QUALITY`
- **Trigger**: Last night's deep+REM sleep-stage proportion (of aggregate sleep duration) looked statistically implausible. Formerly named `RECOVERY_STAGES_MISSING`; the underlying `RecoveryFlag.STAGES_MISSING` constant is kept only for backward-compatible parsing of historical persisted rows.
- **Card title**: Unusual Sleep Stage Proportions
- **Card description**: Last night's deep/REM sleep proportions looked implausible, so your sleep score reflects duration and restoration only, not architecture.
- **Observed signal**: Last night's deep and/or REM sleep proportion was higher than the expected range.
- **Meaning**: Sleep score may be less complete because sleep architecture could not be evaluated.
- **Confidence**: None.
- **Possible causes**:
  - Watch not worn consistently: Inconsistent wear can lead to skewed stage proportions.
  - Loose fit: Poor sensor contact can cause sleep stage misclassification.
  - Device did not classify sleep stages: Some nights may not receive reliable stage labels.
  - Short or interrupted sleep: Short or fragmented sleep can skew stage proportions.
  - Sync delay: Health Connect data may not have arrived yet.
  - Health Connect data gap: The source app may not have shared complete stage data.
- **Recommendations**:
  - Check your wearable sleep report and Health Connect sync status.
- **Caveats**: None.
- **Safety note**: None.
- **Source notes / rationale**: Sleep scoring adaptation info.

---

## REST_DAY_NO_IMPACT

- **Type**: `PHYSIOLOGY`
- **Trigger**: Yesterday was a low-load day, today's HRV did not clearly improve, and today's HRV is not already in the optimal range.
- **Card title**: No Clear Recovery Rebound Yet
- **Card description**: After a light day, your HRV did not clearly rebound yet.
- **Observed signal**: Yesterday was a low-load day, but today's HRV did not clearly improve.
- **Meaning**: One light day may not have been enough to shift your recovery markers, or this may simply reflect normal HRV variability.
- **Confidence**: `LOW_MEDIUM`
- **Possible causes**:
  - Accumulated fatigue: Recovery markers can take more than one day to rebound.
  - Poor sleep: Poor sleep can blunt a recovery rebound.
  - Stress: Stress can keep HRV lower despite low training load.
  - Dehydration: Fluid loss can affect HRV.
  - Early illness: Early illness can lower HRV.
  - Normal HRV variation: One morning can vary because of normal measurement noise.
- **Recommendations**:
  - Watch the next 2-3 days rather than reacting to one morning.
- **Caveats**:
  - This does not mean the rest day failed.
- **Safety note**: None.
- **Source notes / rationale**: Rest day outcome evaluation.

---

## REST_DAY_SUCCESS

- **Type**: `PHYSIOLOGY`
- **Trigger**: Yesterday was a low-load day, and either today's HRV rose significantly versus yesterday, or today's HRV newly reached the optimal range after not being optimal yesterday. If HRV was already optimal on both days with no significant further rise, no insight is shown.
- **Card title**: Recovery Rebound
- **Card description**: After a light day, your HRV improved, which may suggest a positive recovery response.
- **Observed signal**: Yesterday was a low-load day and today's HRV improved.
- **Meaning**: Your body may have responded positively to lower training stress.
- **Confidence**: `LOW_MEDIUM`
- **Possible causes**:
  - Reduced training load: Lower load can support recovery.
  - Good sleep: Good sleep can improve recovery markers.
  - Lower stress: Lower stress can support HRV.
  - Hydration: Better fluid balance can support recovery signals.
  - Normal HRV variability: One strong morning can reflect normal variation.
- **Recommendations**:
  - Treat this as encouraging, not a guarantee.
- **Caveats**:
  - This does not guarantee peak performance today.
  - It does not mean you should automatically train harder than planned.
- **Safety note**: None.
- **Source notes / rationale**: Favorable rest day response.

---

## SICK_INDICATOR

- **Type**: `PHYSIOLOGY`
- **Trigger**: Resting heart rate is elevated and HRV is lower than usual for two consecutive days.
- **Card title**: Possible Illness Signal
- **Card description**: Your resting heart rate is elevated and HRV is suppressed, which may suggest your body is under strain.
- **Observed signal**: Your resting heart rate is higher than usual and your HRV is lower than usual for more than one day.
- **Meaning**: This pattern can occur with illness, inflammation, poor sleep, high stress, or heavy recovery demand.
- **Confidence**: `MEDIUM_HIGH`
- **Possible causes**:
  - Early illness: Early illness can lower HRV and raise resting heart rate.
  - Poor sleep: Short or disrupted sleep can make recovery markers less favorable.
  - High stress: Stress can lower HRV and raise resting heart rate.
  - Heavy training: Heavy training can increase recovery demand.
  - Dehydration: Fluid loss can raise heart rate.
  - Alcohol: Alcohol can disrupt sleep and recovery markers.
  - Heat: Heat can raise heart rate and strain.
  - Travel: Travel can disrupt sleep, hydration, and timing.
- **Recommendations**:
  - Reduce intensity today, prioritize sleep and hydration, and check how you feel.
- **Caveats**:
  - This does not diagnose an infection or identify the cause of the signal.
- **Safety note**: If you have fever, chest pain, shortness of breath, fainting, severe symptoms, or persistent abnormal readings, seek medical advice.
- **Source notes / rationale**: Readiness indicator for physical stress, non-diagnostic.

---

## STEP_SHORTFALL

- **Type**: `TRAINING_BEHAVIOR`
- **Trigger**: Step count is below 70% of goal.
- **Card title**: Low Daily Activity
- **Card description**: You are well below your step goal today.
- **Observed signal**: Your step count is below 70% of your daily goal.
- **Meaning**: Daily movement supports general activity balance, but a low-step day may be intentional during recovery.
- **Confidence**: None.
- **Possible causes**:
  - Recovery day: Lower movement may be intentional during recovery.
  - Desk-heavy day: Long seated work can reduce step count.
  - Travel: Travel can reduce normal movement.
  - Missed device wear: Steps can be undercounted if the device was not worn.
  - Low outdoor activity: Less outdoor time often reduces steps.
  - Intentional taper: A taper can intentionally reduce daily movement.
- **Recommendations**:
  - If it fits your day, a short walk can help close the gap.
- **Caveats**:
  - A low step count is not automatically bad, especially on rest or recovery days.
- **Safety note**: None.
- **Source notes / rationale**: Activity tracking comparison.

---

## STRONG_RECOVERY_SIGNAL

- **Type**: `PHYSIOLOGY`
- **Trigger**: HRV is much higher than baseline and resting heart rate is much lower than baseline for two consecutive days.
- **Card title**: Strong Recovery Signal
- **Card description**: Your HRV and resting heart rate were unusually favorable compared with your baseline.
- **Observed signal**: Your HRV was much higher than usual and your resting heart rate was much lower than usual.
- **Meaning**: This pattern is consistent with a favorable overnight recovery signal.
- **Confidence**: `MEDIUM`
- **Possible causes**:
  - Good sleep: Good sleep can support favorable recovery markers.
  - Lower stress: Lower stress can support HRV and resting heart rate.
  - Low recent training load: Lower load can improve recovery markers.
  - Good hydration: Hydration can support cardiovascular recovery.
  - Normal HRV variability: A strong single signal can reflect normal HRV variation.
- **Recommendations**:
  - This is an encouraging signal. If you also feel good, normal training is reasonable.
  - Avoid using one strong recovery signal as a reason to suddenly increase training load.
- **Caveats**:
  - This does not guarantee peak performance today.
  - It does not mean you should automatically train harder than planned.
- **Safety note**: None.
- **Source notes / rationale**: Information recovery signal, non-diagnostic.

---

## WEIGHT_DRIFT_TRAINING_LOAD

- **Type**: `PHYSIOLOGY`
- **Trigger**: Weight changed by more than 2% over the past week during high strain.
- **Card title**: Weight Change Under High Training Load
- **Card description**: Your weight changed during a high-load period, which may reflect fluid shifts, glycogen changes, under-fueling, or normal variation.
- **Observed signal**: Your weight changed by more than 2% over the past week while training strain is high.
- **Meaning**: During high training load, short-term weight changes often reflect fluid balance, glycogen, food volume, or recovery stress rather than true tissue change.
- **Confidence**: `MEDIUM`
- **Possible causes**:
  - Fluid loss: Sweat and hydration changes can move weight quickly.
  - Glycogen changes: Stored carbohydrate changes also shift water weight.
  - Under-fueling: Eating too little during high load can affect weight and recovery.
  - High sweat loss: Heat or long sessions can cause fluid shifts.
  - Salt intake changes: Sodium changes can affect water retention.
  - Meal timing: Food volume and timing can move scale weight.
  - Scale timing variation: Different weigh-in timing can change readings.
- **Recommendations**:
  - Look at the trend, not one weigh-in.
  - Make sure you are eating and hydrating enough during high-load periods.
- **Caveats**:
  - A one-week weight change does not prove fat gain or fat loss.
- **Safety note**: None.
- **Source notes / rationale**: Short-term weight variation context, strictly non-diagnostic.

---

## WORKOUT_IMPACT

- **Type**: `PHYSIOLOGY`
- **Trigger**: Training load yesterday was high (TRIMP >= 120), current HRV and RHR are both outside their optimal ranges, and HRV dropped from yesterday by more than the configured HRV optimal-threshold percentage.
- **Card title**: Training Load Carryover
- **Card description**: Yesterday's training load was high and today's HRV/RHR signals suggest recovery is still carrying over.
- **Observed signal**: Yesterday's training load was high, HRV is no longer optimal, RHR is no longer optimal, and HRV dropped meaningfully from yesterday.
- **Meaning**: Your body may still be repairing, refueling, and returning to baseline after a demanding session.
- **Confidence**: `MEDIUM`
- **Possible causes**:
  - Long workout: Long duration can raise training load.
  - High-intensity workout: High intensity can raise TRIMP.
  - Late workout: Late training can affect overnight recovery.
  - Poor sleep after training: Poor sleep can extend recovery demand.
  - Dehydration: Fluid loss can make recovery feel harder.
  - Under-fueling: Not eating enough can slow recovery from hard sessions.
  - Heat: Heat can increase cardiovascular strain.
- **Recommendations**:
  - Use today as a flexible day. If you feel good, normal training may still be fine; if you feel flat, choose easy training.
- **Caveats**:
  - A high TRIMP day does not automatically mean your recovery is poor.
- **Safety note**: None.
- **Source notes / rationale**: Post-training recovery carryover.
