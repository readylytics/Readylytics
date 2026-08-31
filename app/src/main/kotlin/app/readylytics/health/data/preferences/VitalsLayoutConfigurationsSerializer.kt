package app.readylytics.health.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object VitalsLayoutConfigurationsSerializer : Serializer<VitalsLayoutConfigurationsProto> {
    override val defaultValue: VitalsLayoutConfigurationsProto =
        VitalsLayoutConfigurationsProto
            .newBuilder()
            .addAllVitalsCards(SettingsDefaults.DEFAULT_VITALS_CARDS.map { VitalsLayoutMapper.toCardProto(it) })
            .addAllTrendCharts(SettingsDefaults.DEFAULT_VITALS_CHARTS.map { VitalsLayoutMapper.toChartProto(it) })
            .build()

    override suspend fun readFrom(input: InputStream): VitalsLayoutConfigurationsProto {
        try {
            return VitalsLayoutConfigurationsProto.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(
        t: VitalsLayoutConfigurationsProto,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}
