package app.readylytics.health.core.model.domain.dashboard

object DashboardCardCatalog {
    fun spec(cardId: CardId): ModeSpec? = specs[cardId]

    fun requestedMode(configuration: CardConfiguration): DashboardCardDisplayMode =
        spec(configuration.cardId)?.resolveRequestedMode(configuration.requestedDisplayMode)
            ?: DashboardCardDisplayMode.VALUE

    fun applyGlobalDisplayMode(
        configurations: List<CardConfiguration>,
        mode: DashboardCardDisplayMode,
    ): List<CardConfiguration> =
        configurations.map { config ->
            val supported = spec(config.cardId)?.supportedModes.orEmpty()
            if (mode in supported) config.copy(requestedDisplayMode = mode) else config
        }

    fun resetAllDisplayModes(configurations: List<CardConfiguration>): List<CardConfiguration> =
        configurations.map { it.copy(requestedDisplayMode = null) }

    private val ALL_MODES =
        listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE)
    private val ONLY_BAR = listOf(DashboardCardDisplayMode.BAR)
    private val ONLY_VALUE = listOf(DashboardCardDisplayMode.VALUE)

    private val specs: Map<CardId, ModeSpec> =
        mapOf(
            CardId.SLEEP_SCORE to ModeSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
            CardId.READINESS to ModeSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
            CardId.STEPS to ModeSpec(DashboardCardDisplayMode.BAR, ONLY_BAR),
            CardId.HRV to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.SLEEP_RHR to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.SLEEP_DURATION to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.STRAIN_RATIO to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.RAS_DAILY to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.CIRCADIAN_CONSISTENCY to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.RESTING_HR to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.SLEEP_EFFICIENCY to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.HEART_RATE to ModeSpec(DashboardCardDisplayMode.VALUE, ONLY_VALUE),
            CardId.WEIGHT to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.BODY_FAT to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.BLOOD_PRESSURE to ModeSpec(DashboardCardDisplayMode.VALUE, ONLY_VALUE),
            CardId.OXYGEN_SATURATION to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.BODY_TEMPERATURE to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.RESIDUAL_FATIGUE to ModeSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
        )
}
