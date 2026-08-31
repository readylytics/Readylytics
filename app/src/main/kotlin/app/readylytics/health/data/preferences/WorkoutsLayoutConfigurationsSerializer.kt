package app.readylytics.health.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object WorkoutsLayoutConfigurationsSerializer : Serializer<WorkoutsLayoutConfigurationsProto> {
    override val defaultValue: WorkoutsLayoutConfigurationsProto =
        WorkoutsLayoutConfigurationsProto
            .newBuilder()
            .addAllWorkoutCards(SettingsDefaults.DEFAULT_WORKOUT_CARDS.map { WorkoutsLayoutMapper.toCardProto(it) })
            .addAllWorkoutCharts(SettingsDefaults.DEFAULT_WORKOUT_CHARTS.map { WorkoutsLayoutMapper.toChartProto(it) })
            .addAllWorkoutHistory(
                SettingsDefaults.DEFAULT_WORKOUT_HISTORY.map { WorkoutsLayoutMapper.toHistoryProto(it) },
            ).build()

    override suspend fun readFrom(input: InputStream): WorkoutsLayoutConfigurationsProto {
        try {
            return WorkoutsLayoutConfigurationsProto.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(
        t: WorkoutsLayoutConfigurationsProto,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}
