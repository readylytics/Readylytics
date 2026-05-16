package com.gregor.lauritz.healthdashboard.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ContributorsSection() {
    Column {
        SectionDivider()

        SubHeader("## Sleep Score")
        BodyText("A 100-point summary of last night's sleep, made of three parts:")
        BulletItem(
            "**Duration (50%)** — how much sleep you got, compared to your goal (default 8 hours, configurable). Includes a small adjustment for how efficient your time in bed was.",
        )
        BulletItem(
            "**Architecture (25%)** — how much of your sleep was deep (slow-wave) sleep and REM. Both matter. Deep sleep is when most physical recovery happens; REM is when your brain processes memory and emotion. Targets are age-specific to account for the natural biological decline in deep sleep across the lifespan (Ohayon 2004).",
        )
        BulletItem(
            "**Restoration (25%)** — how rested your estimated recovery-related physiology looks. We use the natural log of RMSSD (**lnRMSSD**) and overnight resting heart rate (**RHR**) to compute Z-scores. The log transformation is the scientific gold standard (Plews 2013, Buchheit 2014) for monitoring recovery, as it normalizes the skewed distribution of raw HRV data.",
        )

        Spacer(Modifier.height(8.dp))
        BodyText("**Reading the score**")
        BulletItem(
            "**85-100** Excellent. You slept enough, your sleep stages looked balanced, and your autonomic recovery markers were strong.",
        )
        BulletItem("**70-84** Good. Most components are healthy; one is slightly below your norm.")
        BulletItem(
            "**50-69** Fair. Likely a duration shortfall, fragmented sleep, or an off night for HRV/RHR.",
        )
        BulletItem(
            "**Below 50** Poor. Multiple components are below typical. One bad night is rarely meaningful; a streak deserves attention.",
        )

        Spacer(Modifier.height(8.dp))
        BodyText("**Tooltips you'll see**")
        BulletItem(
            "*Deep sleep target 15-25%* of total sleep, with the healthy range narrowing as you age. We do not penalise you if your wearable reports unusual numbers — wearable stage detection is imperfect.",
        )
        BulletItem("*REM target 20-25%* of total sleep, also drifting slightly downward with age.")
        BulletItem(
            "*Sleep efficiency above 85%* is considered good (American Academy of Sleep Medicine consensus).",
        )

        SectionDivider()

        SubHeader("## Circadian Consistency")
        BodyText(
            "This score asks: **do you go to bed and wake up at roughly the same times each day?** Schedule regularity is independently linked to better metabolic, cognitive, and cardiovascular outcomes — sometimes more strongly than sleep duration itself (Windred et al. 2023, UK Biobank).",
        )
        BodyText(
            "We compare each night's bedtime and wake time to your *typical* (median) bedtime and wake time over the last 14 days. The bigger the deviation, the lower the daily score. The number you see is a 7-day rolling average so a single late night doesn't tank it.",
        )

        Spacer(Modifier.height(8.dp))
        BodyText("**Tunable threshold (default 30 min)**")
        BulletItem("Within ±30 minutes of your typical times → full score.")
        BulletItem("30-90 minutes off → score decays linearly.")
        BulletItem("More than 90 minutes off → score is 0 for that day.")

        BodyText(
            "You can tighten the threshold to 15 minutes if you're aiming for athlete-grade regularity, or relax it up to 90 minutes.",
        )

        Spacer(Modifier.height(8.dp))
        BodyText(
            "**A caveat for shift workers and biphasic sleepers.** This metric is calibrated for people with one main sleep period per day. If you work rotating shifts or sleep in two segments by choice, the score may misclassify your schedule. We exclude any single sleep period under 3 hours from the median calculation so naps don't pull your \"typical\" times around — but this rule has imperfect coverage of every sleep pattern.",
        )

        SectionDivider()

        SubHeader("## Readiness")
        BodyText("A daily 0-100 number reflecting whether your recent training load is in a healthy range.")
        BodyText("We compute two rolling averages of your training load:")
        BulletItem("**Acute load (ATL)** — roughly the last week")
        BulletItem("**Chronic load (CTL)** — roughly the last 6 weeks")
        BodyText(
            "The ratio (ATL ÷ CTL) tells us whether you've recently spiked above your recent norm. Around 1.0 means you're training in line with your fitness; substantially above 1.0 means a relative spike.",
        )

        Spacer(Modifier.height(8.dp))
        BodyText("**How we score the ratio**")
        BulletItem("0.8-1.3 → 100 (in your normal range — \"sweet spot\")")
        BulletItem("1.3-1.5 → linearly decays")
        BulletItem(
            "Above 1.5 → smooth quadratic decay (Gabbett 2016)",
        )

        Spacer(Modifier.height(8.dp))
        BodyText("**Tooltips**")
        BulletItem("*Peak (85-100)* — your recent load is consistent with your fitness.")
        BulletItem("*Maintain (60-84)* — manageable load increase.")
        BulletItem("*Caution (30-59)* — meaningful load spike; consider an easier day.")
        BulletItem("*High Fatigue (<30)* — large spike vs. your norm.")

        Spacer(Modifier.height(8.dp))
        BodyText(
            "**What we don't do.** We don't penalise you for resting. A week of light activity will *not* drop Readiness; the score is designed for load *spikes*, not undertraining.",
        )
    }
}
