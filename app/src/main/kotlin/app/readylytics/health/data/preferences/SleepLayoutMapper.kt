package app.readylytics.health.data.preferences

import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId

object SleepLayoutMapper {
    fun toTopCardDomain(proto: SleepTopCardConfigurationProto): SleepTopCardConfiguration? {
        val cardId =
            try {
                SleepTopCardId.valueOf(proto.cardId)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return SleepTopCardConfiguration(
            cardId = cardId,
            isVisible = proto.isVisible,
            position = proto.position,
            requestedDisplayMode = parseDisplayMode(proto.requestedDisplayMode),
        )
    }

    fun toTopCardProto(domain: SleepTopCardConfiguration): SleepTopCardConfigurationProto =
        SleepTopCardConfigurationProto
            .newBuilder()
            .setCardId(domain.cardId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .setRequestedDisplayMode(domain.requestedDisplayMode?.name.orEmpty())
            .build()

    fun toChartDomain(proto: SleepChartConfigurationProto): SleepChartConfiguration? {
        val chartId =
            try {
                SleepChartId.valueOf(proto.chartId)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return SleepChartConfiguration(
            chartId = chartId,
            isVisible = proto.isVisible,
            position = proto.position,
        )
    }

    fun toChartProto(domain: SleepChartConfiguration): SleepChartConfigurationProto =
        SleepChartConfigurationProto
            .newBuilder()
            .setChartId(domain.chartId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .build()

    fun toMetricCardDomain(proto: SleepMetricCardConfigurationProto): SleepMetricCardConfiguration? {
        val cardId =
            try {
                SleepMetricCardId.valueOf(proto.cardId)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return SleepMetricCardConfiguration(
            cardId = cardId,
            isVisible = proto.isVisible,
            position = proto.position,
            requestedDisplayMode = parseDisplayMode(proto.requestedDisplayMode),
        )
    }

    fun toMetricCardProto(domain: SleepMetricCardConfiguration): SleepMetricCardConfigurationProto =
        SleepMetricCardConfigurationProto
            .newBuilder()
            .setCardId(domain.cardId.name)
            .setIsVisible(domain.isVisible)
            .setPosition(domain.position)
            .setRequestedDisplayMode(domain.requestedDisplayMode?.name.orEmpty())
            .build()

    private fun parseDisplayMode(value: String): DashboardCardDisplayMode? =
        value.takeIf(String::isNotBlank)?.let { stored ->
            runCatching { DashboardCardDisplayMode.valueOf(stored) }.getOrNull()
        }
}
