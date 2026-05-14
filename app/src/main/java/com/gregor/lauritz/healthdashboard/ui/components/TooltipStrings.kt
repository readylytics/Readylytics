package com.gregor.lauritz.healthdashboard.ui.components

const val SLEEP_SCORE_TOOLTIP =
    "Total quality of rest based on duration and cycles.\n\n" +
        "• 80–100: Optimal\n" +
        "• 60–79: Fair\n" +
        "• < 60: Poor"

const val PAI_TOOLTIP =
    "Your 7-day rolling heart health score.\n" +
        "Based on how often and how hard you challenge your heart.\n\n" +
        "• 100+: Optimal\n" +
        "• 75–99: Neutral\n" +
        "• 50–74: Warning\n" +
        "• < 50: Poor"

fun circadianTooltipText(thresholdMinutes: Int) =
    buildString {
        append("Measures how regular your sleep schedule is.\n\n")
        append("High consistency stabilizes your internal clock, improving deep sleep and energy levels.\n\n")
        append("• ≥ 80%: Optimal\n")
        append("• 60–79%: Neutral\n")
        append("• 40–59%: Warning\n")
        append("• < 40%: Poor\n\n")
        append("Consistency Window: ±$thresholdMinutes min grace period before score drops.")
    }
