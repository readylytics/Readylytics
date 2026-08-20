package app.readylytics.health.data.preferences

import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutChartId
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryId

object WorkoutsLayoutMapper {
    fun toCardDomain(proto: WorkoutCardConfigurationProto): CardConfiguration? {
        val cardId =
            try {
                CardId.valueOf(proto.cardId)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return CardConfiguration(
            cardId = cardId,
            isVisible = proto.isVisible,
            position = proto.position,
            requestedDisplayMode = parseDisplayMode(proto.requestedDisplayMode),
        )
    }

    fun toCardProto(domain: CardConfiguration): WorkoutCardConfigurationProto =
        WorkoutCardConfigurationProto
            .newBuilder()
            .setCardId(domain.cardId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .setRequestedDisplayMode(domain.requestedDisplayMode?.name.orEmpty())
            .build()

    fun toChartDomain(proto: WorkoutChartConfigurationProto): WorkoutChartConfiguration? {
        val chartId =
            try {
                WorkoutChartId.valueOf(proto.chartId)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return WorkoutChartConfiguration(
            chartId = chartId,
            isVisible = proto.isVisible,
            position = proto.position,
        )
    }

    fun toChartProto(domain: WorkoutChartConfiguration): WorkoutChartConfigurationProto =
        WorkoutChartConfigurationProto
            .newBuilder()
            .setChartId(domain.chartId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .build()

    fun toHistoryDomain(proto: WorkoutHistoryConfigurationProto): WorkoutHistoryConfiguration? {
        val historyId =
            try {
                WorkoutHistoryId.valueOf(proto.historyId)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return WorkoutHistoryConfiguration(
            historyId = historyId,
            isVisible = proto.isVisible,
            position = proto.position,
        )
    }

    fun toHistoryProto(domain: WorkoutHistoryConfiguration): WorkoutHistoryConfigurationProto =
        WorkoutHistoryConfigurationProto
            .newBuilder()
            .setHistoryId(domain.historyId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .build()

    private fun parseDisplayMode(value: String): DashboardCardDisplayMode? =
        value.takeIf(String::isNotBlank)?.let { stored ->
            runCatching { DashboardCardDisplayMode.valueOf(stored) }.getOrNull()
        }
}
