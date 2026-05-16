package com.gregor.lauritz.healthdashboard.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AppInfoSection() {
    Column {
        SectionHeader("# About your scores")
        BodyText(
            "This app turns the data your phone and wearables already collect — sleep, heart rate, and exercise — into three daily numbers that try to answer one question: **how is your body doing today, and what should you do with that information?**",
        )
        BodyText(
            "We try to be honest about what these numbers can and can't tell you. They are decision aids, not diagnoses. If something feels off in your body, trust your body over the score.",
        )

        HighlightBox {
            SubHeader("## A note on measurement")
            BodyText(
                "Wearables estimate sleep stages, HRV, and nocturnal physiology indirectly using probabilistic algorithms. These estimates may contain significant measurement error compared to clinical systems like polysomnography or ECG. The scores shown here are wellness-oriented estimates, not clinical measurements.",
            )
        }

        SectionDivider()

        SubHeader("## The three scores at a glance")
        ScoreTable()
        BodyText(
            "You'll see all three on your dashboard once enough data has been collected. Until then, we'll show you what we have and explain what's missing.",
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

            ScoreTableRow("**Sleep Score**", "How restorative was last night's sleep?", "0-100")
            ScoreTableRow("**Circadian Consistency**", "How regular is your sleep schedule?", "0-100")
            ScoreTableRow("**Readiness**", "How prepared are you for today's training load?", "0-100")
        }
    }
}

@Composable
private fun ScoreTableRow(
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
