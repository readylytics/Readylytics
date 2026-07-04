package app.readylytics.health.domain.model

data class SleepTypicalRanges(
    val deepSleepRange: IntRange = 10..30,
    val remSleepRange: IntRange = 15..25,
    val lightSleepRange: IntRange = 40..50,
    val awakeRange: IntRange = 5..10,
) {
    companion object {
        val DEFAULT = SleepTypicalRanges()
    }
}
