# Insight Research Summary

This document summarizes the rationale and guardrails behind Readylytics' non-diagnostic wording, baseline-driven framing, and physiological boundaries.

## Wording Guardrails

Wearable-derived HRV, resting heart rate, sleep, oxygen saturation, blood pressure, weight, steps, PAI, and training-load data are treated as contextual signals. Details use cautious phrases such as "may", "might", "can", "is consistent with", and "could be related to". 

Diagnostic and certainty language is strictly avoided:
- No use of words like "proves", "diagnosed", "caused by", or similar direct causal/diagnostic linkages.
- Instead of "you are overtrained", the app uses "training load carryover" or "training load may be affecting recovery."
- Instead of "you are sick", the app uses "possible illness signal" or "body under physical strain."
- Instead of "you have sleep apnea", the app flags "lower oxygen overnight" with appropriate fit/position/congestion caveats.

## Baseline Framing

Insight copy compares metrics against the user's personal baseline or usual range whenever possible rather than absolute population-level statistics.
- Blood pressure is evaluated as a drift from personal baseline systolic BP.
- HRV and resting heart rate are assessed relative to rolling baseline standard deviations (Z-scores).
- Weight shifts are evaluated relative to rolling average weight.

This approach avoids implying that standard clinical cutoffs are diagnostic of pathology for a specific individual.

## Daily Signal Limits

One-day fluctuations in physiological markers (such as a drop in HRV or a late heart rate nadir) can reflect normal variation, sensor noise, device fit, travel, evening alcohol consumption, temporary stress, or a shift in sleep schedule. 
Detail copy includes caveats stating that a single night's reading should not drive strong conclusions, encouraging users to observe the 7-day trend.

## Medical Safety

Readylytics does not diagnose hypertension, sleep apnea, cardiac abnormalities, infections, or clinical overtraining syndrome. 
For potentially significant biometrics deviations (such as a large oxygen saturation drop, persistent high blood pressure, or a sustained illness signal), a dedicated safety note is displayed. This safety note advises the user on clinical symptoms to watch for and recommends consulting a qualified healthcare professional.

## Training Load

High acute training load combined with recovery strain is framed as a training-planning signal rather than proof of clinical overtraining. It suggests modifying intensity or duration of workouts to balance strain.
Conversely, exceptionally favorable HRV and resting heart rate (Strong Recovery Signal) are presented as an encouraging indicator, but with the caveat that one strong morning is not a guarantee of peak performance or a recommendation to sharply exceed the planned training volume.
