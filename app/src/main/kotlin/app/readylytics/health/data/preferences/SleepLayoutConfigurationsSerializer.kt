package app.readylytics.health.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object SleepLayoutConfigurationsSerializer : Serializer<SleepLayoutConfigurationsProto> {
    override val defaultValue: SleepLayoutConfigurationsProto =
        SleepLayoutConfigurationsProto
            .newBuilder()
            .addAllTopCards(SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS.map { SleepLayoutMapper.toTopCardProto(it) })
            .addAllTrendCharts(SettingsDefaults.DEFAULT_SLEEP_CHARTS.map { SleepLayoutMapper.toChartProto(it) })
            .addAllMetricCards(
                SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS.map { SleepLayoutMapper.toMetricCardProto(it) },
            ).build()

    override suspend fun readFrom(input: InputStream): SleepLayoutConfigurationsProto {
        try {
            return SleepLayoutConfigurationsProto.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(
        t: SleepLayoutConfigurationsProto,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}
