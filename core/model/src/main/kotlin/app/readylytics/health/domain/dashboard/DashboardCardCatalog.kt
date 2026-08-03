package app.readylytics.health.domain.dashboard

data class DashboardCardSpec(
    val cardId: CardId,
    val legacyDefaultMode: DashboardCardDisplayMode,
    val supportedModes: List<DashboardCardDisplayMode>,
)

object DashboardCardCatalog {
    fun spec(cardId: CardId): DashboardCardSpec? = specs[cardId]

    fun requestedMode(configuration: CardConfiguration): DashboardCardDisplayMode {
        val spec = spec(configuration.cardId) ?: return DashboardCardDisplayMode.VALUE
        val requested = configuration.requestedDisplayMode
        return if (requested != null && spec.supportedModes.contains(requested)) {
            requested
        } else {
            spec.legacyDefaultMode
        }
    }

    fun applyGlobalDisplayMode(
        configurations: List<CardConfiguration>,
        mode: DashboardCardDisplayMode,
    ): List<CardConfiguration> =
        configurations.map { config ->
            val supported = spec(config.cardId)?.supportedModes.orEmpty()
            if (mode in supported) config.copy(requestedDisplayMode = mode) else config
        }

    private val ALL_MODES =
        listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE)
    private val ONLY_BAR = listOf(DashboardCardDisplayMode.BAR)
    private val ONLY_VALUE = listOf(DashboardCardDisplayMode.VALUE)

    private val specs: Map<CardId, DashboardCardSpec> =
        mapOf(
            CardId.SLEEP_SCORE to DashboardCardSpec(CardId.SLEEP_SCORE, DashboardCardDisplayMode.GAUGE, ALL_MODES),
            CardId.READINESS to DashboardCardSpec(CardId.READINESS, DashboardCardDisplayMode.GAUGE, ALL_MODES),
            CardId.STEPS to DashboardCardSpec(CardId.STEPS, DashboardCardDisplayMode.BAR, ONLY_BAR),
            CardId.HRV to DashboardCardSpec(CardId.HRV, DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.SLEEP_RHR to DashboardCardSpec(CardId.SLEEP_RHR, DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.SLEEP_DURATION to
                DashboardCardSpec(
                    CardId.SLEEP_DURATION,
                    DashboardCardDisplayMode.VALUE,
                    ALL_MODES,
                ),
            CardId.STRAIN_RATIO to
                DashboardCardSpec(
                    CardId.STRAIN_RATIO,
                    DashboardCardDisplayMode.VALUE,
                    ALL_MODES,
                ),
            CardId.RAS_DAILY to DashboardCardSpec(CardId.RAS_DAILY, DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.CIRCADIAN_CONSISTENCY to
                DashboardCardSpec(CardId.CIRCADIAN_CONSISTENCY, DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.RESTING_HR to DashboardCardSpec(CardId.RESTING_HR, DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.SLEEP_EFFICIENCY to
                DashboardCardSpec(CardId.SLEEP_EFFICIENCY, DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.HEART_RATE to DashboardCardSpec(CardId.HEART_RATE, DashboardCardDisplayMode.VALUE, ONLY_VALUE),
            CardId.WEIGHT to DashboardCardSpec(CardId.WEIGHT, DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.BODY_FAT to DashboardCardSpec(CardId.BODY_FAT, DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.BLOOD_PRESSURE to
                DashboardCardSpec(CardId.BLOOD_PRESSURE, DashboardCardDisplayMode.VALUE, ONLY_VALUE),
            CardId.OXYGEN_SATURATION to
                DashboardCardSpec(CardId.OXYGEN_SATURATION, DashboardCardDisplayMode.VALUE, ALL_MODES),
        )
}
