package app.readylytics.health.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import app.readylytics.health.domain.preferences.SleepScoreRecalcBaseline
import app.readylytics.health.domain.preferences.SleepScoreRecalcBaselineStore
import app.readylytics.health.domain.scoring.SleepScoreWeightProfile
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

object SleepScoreRecalcBaselineSerializer : Serializer<SleepScoreRecalcBaselineProto> {
    override val defaultValue: SleepScoreRecalcBaselineProto = SleepScoreRecalcBaselineProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): SleepScoreRecalcBaselineProto {
        try {
            return SleepScoreRecalcBaselineProto.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(
        t: SleepScoreRecalcBaselineProto,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}

@Singleton
class SleepScoreRecalcBaselineStoreImpl
    @Inject
    constructor(
        private val dataStore: DataStore<SleepScoreRecalcBaselineProto>,
    ) : SleepScoreRecalcBaselineStore {
        override val baseline: Flow<SleepScoreRecalcBaseline?> =
            dataStore.data.map { proto ->
                if (proto.isSet()) proto.toDomain() else null
            }

        override suspend fun markRecalced(
            weightProfile: SleepScoreWeightProfile,
            goalSleepHours: Float,
            hypersomniaOnsetPercent: Int,
        ) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setSleepScoreWeightProfile(weightProfile.toProto())
                    .setGoalSleepHours(goalSleepHours)
                    .setHypersomniaOnsetPercent(hypersomniaOnsetPercent)
                    .build()
            }
        }
    }

private fun SleepScoreRecalcBaselineProto.isSet(): Boolean =
    hasSleepScoreWeightProfile() ||
        hasGoalSleepHours() ||
        hasHypersomniaOnsetPercent()

private fun SleepScoreRecalcBaselineProto.toDomain(): SleepScoreRecalcBaseline =
    SleepScoreRecalcBaseline(
        weightProfile = sleepScoreWeightProfile.toDomain(),
        goalSleepHours = goalSleepHours,
        hypersomniaOnsetPercent = hypersomniaOnsetPercent,
    )

private fun SleepScoreWeightProfile.toProto(): SleepScoreWeightProfileProto =
    when (this) {
        SleepScoreWeightProfile.BALANCED ->
            SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_BALANCED
        SleepScoreWeightProfile.DURATION_FOCUSED ->
            SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_DURATION_FOCUSED
        SleepScoreWeightProfile.RECOVERY_FOCUSED ->
            SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_RECOVERY_FOCUSED
        SleepScoreWeightProfile.ARCHITECTURE_FOCUSED ->
            SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_ARCHITECTURE_FOCUSED
        SleepScoreWeightProfile.CONTINUITY_FOCUSED ->
            SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_CONTINUITY_FOCUSED
    }

private fun SleepScoreWeightProfileProto.toDomain(): SleepScoreWeightProfile =
    when (this) {
        SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_DURATION_FOCUSED ->
            SleepScoreWeightProfile.DURATION_FOCUSED
        SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_RECOVERY_FOCUSED ->
            SleepScoreWeightProfile.RECOVERY_FOCUSED
        SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_ARCHITECTURE_FOCUSED ->
            SleepScoreWeightProfile.ARCHITECTURE_FOCUSED
        SleepScoreWeightProfileProto.SLEEP_WEIGHT_PROFILE_CONTINUITY_FOCUSED ->
            SleepScoreWeightProfile.CONTINUITY_FOCUSED
        else -> SleepScoreWeightProfile.BALANCED
    }
