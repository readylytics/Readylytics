package app.readylytics.health.domain.dashboard

data class DashboardCardSpec(
    val cardId: CardId,
    val legacyDefaultMode: DashboardCardDisplayMode,
    val supportedModes: List<DashboardCardDisplayMode>,
)

object DashboardCardCatalog {
    fun spec(cardId: CardId): DashboardCardSpec? {
        val legacyDefault = legacyDefaults[cardId] ?: return null
        return DashboardCardSpec(
            cardId = cardId,
            legacyDefaultMode = legacyDefault,
            supportedModes = supportedModesMap[cardId] ?: listOf(legacyDefault),
        )
    }

    fun requestedMode(configuration: CardConfiguration): DashboardCardDisplayMode {
        val spec = spec(configuration.cardId) ?: return DashboardCardDisplayMode.VALUE
        val requested = configuration.requestedDisplayMode
        return if (requested != null && spec.supportedModes.contains(requested)) {
            requested
        } else {
            spec.legacyDefaultMode
        }
    }

    fun renderMode(configuration: CardConfiguration): DashboardCardDisplayMode = requestedMode(configuration)

    private val legacyDefaults =
        mapOf(
            CardId.SLEEP_SCORE to DashboardCardDisplayMode.GAUGE,
            CardId.READINESS to DashboardCardDisplayMode.GAUGE,
            CardId.STEPS to DashboardCardDisplayMode.BAR,
            CardId.HRV to DashboardCardDisplayMode.VALUE,
            CardId.SLEEP_RHR to DashboardCardDisplayMode.VALUE,
            CardId.SLEEP_DURATION to DashboardCardDisplayMode.VALUE,
            CardId.STRAIN_RATIO to DashboardCardDisplayMode.VALUE,
            CardId.RAS_DAILY to DashboardCardDisplayMode.VALUE,
            CardId.CIRCADIAN_CONSISTENCY to DashboardCardDisplayMode.VALUE,
            CardId.RESTING_HR to DashboardCardDisplayMode.VALUE,
            CardId.SLEEP_EFFICIENCY to DashboardCardDisplayMode.VALUE,
            CardId.HEART_RATE to DashboardCardDisplayMode.VALUE,
            CardId.WEIGHT to DashboardCardDisplayMode.VALUE,
            CardId.BODY_FAT to DashboardCardDisplayMode.VALUE,
            CardId.BLOOD_PRESSURE to DashboardCardDisplayMode.VALUE,
            CardId.OXYGEN_SATURATION to DashboardCardDisplayMode.VALUE,
        )

    private val supportedModesMap =
        mapOf(
            CardId.SLEEP_SCORE to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.READINESS to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.STEPS to listOf(DashboardCardDisplayMode.BAR),
            CardId.HRV to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.SLEEP_RHR to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.SLEEP_DURATION to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.STRAIN_RATIO to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.RAS_DAILY to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.CIRCADIAN_CONSISTENCY to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.RESTING_HR to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.SLEEP_EFFICIENCY to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.HEART_RATE to listOf(DashboardCardDisplayMode.VALUE),
            CardId.WEIGHT to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.BODY_FAT to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
            CardId.BLOOD_PRESSURE to listOf(DashboardCardDisplayMode.VALUE),
            CardId.OXYGEN_SATURATION to
                listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE),
        )
}
