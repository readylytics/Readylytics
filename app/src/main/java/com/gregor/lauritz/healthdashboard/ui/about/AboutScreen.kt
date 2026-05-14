package com.gregor.lauritz.healthdashboard.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun AboutScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Top Spacer
            item { Spacer(Modifier.height(32.dp)) }

            // Header Section
            item {
                SectionHeader("# About your scores")
                BodyText(
                    "This app turns the data your phone and wearables already collect — sleep, heart rate, and exercise — into three daily numbers that try to answer one question: **how is your body doing today, and what should you do with that information?**",
                )
                BodyText(
                    "We try to be honest about what these numbers can and can't tell you. They are decision aids, not diagnoses. If something feels off in your body, trust your body over the score.",
                )
            }

            // Measurement Note (Highlighted Box)
            item {
                HighlightBox {
                    SubHeader("## A note on measurement")
                    BodyText(
                        "Wearables estimate sleep stages, HRV, and nocturnal physiology indirectly using probabilistic algorithms. These estimates may contain significant measurement error compared to clinical systems like polysomnography or ECG. The scores shown here are wellness-oriented estimates, not clinical measurements.",
                    )
                }
            }

            item { SectionDivider() }

            // At a Glance Section
            item {
                SubHeader("## The three scores at a glance")
                ScoreTable()
                BodyText(
                    "You'll see all three on your dashboard once enough data has been collected. Until then, we'll show you what we have and explain what's missing.",
                )
            }

            item { SectionDivider() }

            // Sleep Score Section
            item {
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
            }

            item { SectionDivider() }

            // Circadian Consistency Section
            item {
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
            }

            item { SectionDivider() }

            // Readiness Section
            item {
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

            item { SectionDivider() }

            // Requirements Section
            item {
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
                BulletItem("show the score with a \"data partial\" badge and explain which component was estimated, or")
                BulletItem("skip the score for that day entirely if too much is missing (especially total sleep time).")
            }

            item { SectionDivider() }

            // Stabilisation Section
            item {
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
            }

            item { SectionDivider() }

            // Glossary Section
            item {
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
            }

            item { SectionDivider() }

            // Limitations Section
            item {
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
            }

            item {
                BodyText(
                    "*Selected primary sources informing the scoring: Buysse 1989 (PSQI); Buysse 2014 (RU-SATED); Ohayon et al. 2004, 2017; Hirshkowitz et al. 2015 (NSF); Boulos et al. 2019 (Lancet Respir Med); Plews et al. 2012, 2013, 2014 (HRV monitoring); Buchheit 2014 (Front Physiol); Le Meur et al. 2013 (parasympathetic hyperactivity); Mishra et al. 2020 (Nat Biomed Eng); Quer et al. 2021 (Nat Med); Phillips et al. 2017 (Sleep Regularity Index); Lunsford-Avery et al. 2018; Windred et al. 2023/2024; Khalsa et al. 2003 (phase-response curve); Banister 1991; Foster 1998; Gabbett 2016; Lolli et al. 2019; Impellizzeri et al. 2020/2021.*",
                    fontStyle = FontStyle.Italic,
                )
            }

            item { SectionDivider() }

            // Disclaimer Section
            item {
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

            // Continue Button
            item {
                Button(
                    onClick = onDismiss,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 16.dp),
                ) {
                    Text("Continue to App")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    val cleanText = text.removePrefix("# ").trim()
    Text(
        text = cleanText,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SubHeader(text: String) {
    val cleanText = text.removePrefix("## ").trim()
    Text(
        text = cleanText,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    fontStyle: FontStyle? = null,
) {
    Text(
        text = parseMarkdown(text),
        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = fontStyle ?: FontStyle.Normal),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun BulletItem(text: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = parseMarkdown(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScoreTable() {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Header
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Score",
                    Modifier.weight(1.5f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "What it answers",
                    Modifier.weight(3f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Range",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            TableRow("**Sleep Score**", "How restorative was last night's sleep?", "0-100")
            TableRow("**Circadian Consistency**", "How regular is your sleep schedule?", "0-100")
            TableRow("**Readiness**", "How prepared are you for today's training load?", "0-100")
        }
    }
}

@Composable
private fun TableRow(
    col1: String,
    col2: String,
    col3: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(parseMarkdown(col1), Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
        Text(col2, Modifier.weight(3f), style = MaterialTheme.typography.bodySmall)
        Text(col3, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HighlightBox(content: @Composable () -> Unit) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) { content() }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * Simple markdown-lite parser for **bold** and *italic* text.
 */
private fun parseMarkdown(text: String): AnnotatedString =
    buildAnnotatedString {
        var currentText = text

        // This is a very basic sequential parser.
        // For a more robust solution, a regex or state machine would be better.
        // We'll handle **bold** and *italic*.

        val parts = currentText.split("(?=\\*\\*)|(?<=\\*\\*)".toRegex())
        var isBold = false

        parts.forEach { part ->
            if (part == "**") {
                isBold = !isBold
            } else {
                val subParts = part.split("(?=\\*)|(?<=\\*)".toRegex())
                var isItalic = false
                subParts.forEach { subPart ->
                    if (subPart == "*") {
                        isItalic = !isItalic
                    } else {
                        val style =
                            when {
                                isBold && isItalic ->
                                    SpanStyle(
                                        fontWeight = FontWeight.Bold,
                                        fontStyle = FontStyle.Italic,
                                    )
                                isBold -> SpanStyle(fontWeight = FontWeight.Bold)
                                isItalic -> SpanStyle(fontStyle = FontStyle.Italic)
                                else -> SpanStyle()
                            }
                        withStyle(style) {
                            append(subPart)
                        }
                    }
                }
            }
        }
    }
