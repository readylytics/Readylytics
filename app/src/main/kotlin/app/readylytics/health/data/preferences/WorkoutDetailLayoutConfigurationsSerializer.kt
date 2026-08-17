package app.readylytics.health.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/**
 * Default value is an empty map, not a pre-seeded default layout: storage is sparse and a
 * type without an entry resolves to [SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS] on read.
 */
object WorkoutDetailLayoutConfigurationsSerializer : Serializer<WorkoutDetailLayoutConfigurationsProto> {
    override val defaultValue: WorkoutDetailLayoutConfigurationsProto =
        WorkoutDetailLayoutConfigurationsProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): WorkoutDetailLayoutConfigurationsProto {
        try {
            return WorkoutDetailLayoutConfigurationsProto.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(
        t: WorkoutDetailLayoutConfigurationsProto,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}
