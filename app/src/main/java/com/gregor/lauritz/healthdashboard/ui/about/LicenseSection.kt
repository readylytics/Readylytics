package com.gregor.lauritz.healthdashboard.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

@Composable
fun LicenseSection() {
    Column {
        SectionDivider()

        SubHeader("## What the app needs from you")
        BodyText("We read from Android Health Connect:")
        BulletItem("**Sleep sessions** (with stages if your device records them)")
        BulletItem("**Heart rate** during sleep (for restoration metrics)")
        BulletItem("**Heart rate variability (RMSSD)** during sleep")
        BulletItem("**Heart rate** during exercise sessions (for training load)")

        Spacer(Modifier.height(8.dp))
        BodyText(
            "The app reads only — it never writes. You can revoke access at any time in Health Connect settings.",
        )
        BodyText("If a particular metric is missing on a given day, we'll either:")
        BulletItem(
            "show the score with a \"data partial\" badge and explain which component was estimated, or",
        )
        BulletItem("skip the score for that day entirely if too much is missing (especially total sleep time).")

        SectionDivider()

        SubHeader("## How long until your scores stabilise")
        BodyText("Biological baselines take time to learn. We are explicit about the phases:")
        BulletItem(
            "**Days 1-7 (Calibration).** We collect data. Sleep Score and Circadian Consistency are visible from the first night you log enough sleep, but **HRV-based Restoration is hidden** because a single night of HRV is not interpretable on its own. Readiness uses a placeholder fitness value based on the activity level you tell us during onboarding.",
        )
        BulletItem(
            "**Days 8-21 (Provisional).** We start showing all three scores, but Restoration uses a population-typical estimate of how much your HRV varies day to day. Expect more variability than the mature score.",
        )
        BulletItem(
            "**Days 22-60 (Maturing).** Your personal HRV mean is settled, and we begin blending your personal day-to-day variability with the population estimate. Confidence indicator visible.",
        )
        BulletItem(
            "**Day 60+ (Mature).** All scores use your own personal baselines for both the average and the variability. This is when small day-to-day differences become trustworthy.",
        )

        Spacer(Modifier.height(8.dp))
        BodyText("If you wear your tracker only 3-5 nights a week, the timeline lengthens proportionally.")

        SectionDivider()

        SubHeader("## A short glossary")
        BulletItem(
            "**HRV (Heart Rate Variability)** — the millisecond-level variation in time between heartbeats. Higher generally indicates better autonomic recovery, *up to a point*.",
        )
        BulletItem(
            "**RMSSD** — the specific HRV measure most apps use. We work with the natural log of RMSSD (**lnRMSSD**) internally because it linearizes the naturally skewed distribution of heart rate variability, making statistical comparison (Z-scores) valid (Plews 2013, Buchheit 2014).",
        )
        BulletItem(
            "**RHR (Resting Heart Rate)** — your nocturnal heart rate, averaged across deep portions of sleep.",
        )
        BulletItem(
            "**Deep sleep / Slow-Wave Sleep / N3** — the deepest stage of NREM sleep; growth-hormone release is concentrated here.",
        )
        BulletItem("**REM** — the dreaming stage, important for memory and emotional processing.")
        BulletItem(
            "**TRIMP (Training Impulse)** — a single number summarising the intensity-weighted duration of an exercise session.",
        )
        BulletItem(
            "**ATL / CTL** — short-term and long-term rolling averages of TRIMP. Borrowed from Banister's training-load model and used by most cycling and running apps.",
        )

        SectionDivider()

        SubHeader("## Honest limitations")
        BulletItem(
            "**1. Wearable stage detection is imperfect.** Even premium devices misclassify deep and REM sleep on individual nights. We use age-adjusted targets and don't penalise you for differences within ~5% of the target. Architecture differences within that margin are not meaningful.",
        )
        BulletItem(
            "**2. Population norms are not destiny.** The age-banded deep sleep ranges come from polysomnography studies of healthy adults. Your healthy normal may sit anywhere in your age band. Our scoring uses age-banded targets so the ceiling shifts with you.",
        )
        BulletItem(
            "**3. The ACWR (Readiness load ratio) is descriptive, not predictive.** The methodological literature (Lolli et al. 2019; Impellizzeri et al. 2020, 2021) has demonstrated that the acute-to-chronic ratio is often a mathematical artifact and is not a validated *causal* injury predictor. We follow the Gabbett (2016) quadratic penalty model but present it strictly as a **load change indicator** to help you visualize spikes in training intensity, not as a diagnostic injury risk score.",
        )
        BulletItem(
            "**4. Recovery flag.** When your HRV and RHR patterns suggest possible physiological stress (such as early illness or functional overreaching), we surface a soft notification. To ensure accuracy and filter out acute noise, the algorithm requires the thresholds to be breached on **two consecutive nights** (Mishra 2020, Le Meur 2013). This is informational only, not medical advice.",
        )
        BulletItem(
            "**5. One night is noise; trends are signal.** Treat any single day's score as a data point, not a verdict. Look at the 7-day trend.",
        )
        BulletItem(
            "**6. This app does not diagnose anything.** If you suspect sleep apnea, a heart condition, an infection, or an injury, see a clinician.",
        )

        BodyText(
            "*Selected primary sources informing the scoring: Buysse 1989 (PSQI); Buysse 2014 (RU-SATED); Ohayon et al. 2004, 2017; Hirshkowitz et al. 2015 (NSF); Boulos et al. 2019 (Lancet Respir Med); Plews et al. 2012, 2013, 2014 (HRV monitoring); Buchheit 2014 (Front Physiol); Le Meur et al. 2013 (parasympathetic hyperactivity); Mishra et al. 2020 (Nat Biomed Eng); Quer et al. 2021 (Nat Med); Phillips et al. 2017 (Sleep Regularity Index); Lunsford-Avery et al. 2018; Windred et al. 2023/2024; Khalsa et al. 2003 (phase-response curve); Banister 1991; Foster 1998; Gabbett 2016; Lolli et al. 2019; Impellizzeri et al. 2020/2021.*",
            fontStyle = FontStyle.Italic,
        )

        SectionDivider()

        HighlightBox {
            SubHeader("## Scientific and medical disclaimer")
            BodyText(
                "This app describes a wellness-oriented monitoring framework derived from consumer wearable signals and sports-science literature. The framework is **not validated for medical diagnosis, disease screening, treatment guidance, or injury prediction**. Physiological metrics such as HRV, sleep staging, and resting heart rate are non-specific and can be influenced by numerous behavioral, environmental, pharmacological, and measurement-related factors.",
            )
            BodyText(
                "If you have concerns about your health, sleep, or recovery, consult a qualified healthcare provider.",
            )
        }
    }
}
