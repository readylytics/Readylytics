package app.readylytics.health.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import app.readylytics.health.domain.sync.ResyncCheckpoint
import app.readylytics.health.domain.sync.ResyncCheckpointStore
import app.readylytics.health.domain.sync.ResyncPhase
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

object ResyncCheckpointSerializer : Serializer<ResyncCheckpointProto> {
    override val defaultValue: ResyncCheckpointProto = ResyncCheckpointProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): ResyncCheckpointProto {
        try {
            return ResyncCheckpointProto.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(
        t: ResyncCheckpointProto,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}

@Singleton
class ResyncCheckpointStoreImpl
    @Inject
    constructor(
        private val dataStore: DataStore<ResyncCheckpointProto>,
    ) : ResyncCheckpointStore {
        override val checkpoint: Flow<ResyncCheckpoint?> =
            dataStore.data.map { proto ->
                proto.takeIf { it.phase != ResyncPhaseProto.RESYNC_PHASE_UNSPECIFIED }?.toDomain()
            }

        override suspend fun save(checkpoint: ResyncCheckpoint) {
            dataStore.updateData {
                checkpoint.toProto()
            }
        }

        override suspend fun clear() {
            dataStore.updateData {
                ResyncCheckpointProto.getDefaultInstance()
            }
        }
    }

private fun ResyncCheckpointProto.toDomain(): ResyncCheckpoint =
    ResyncCheckpoint(
        startDate = LocalDate.ofEpochDay(startEpochDay),
        endDate = LocalDate.ofEpochDay(endEpochDay),
        phase =
            when (phase) {
                ResyncPhaseProto.INGEST -> ResyncPhase.INGEST
                ResyncPhaseProto.PRUNE -> ResyncPhase.PRUNE
                ResyncPhaseProto.RECONCILE -> ResyncPhase.RECONCILE
                ResyncPhaseProto.RECOMPUTE -> ResyncPhase.RECOMPUTE
                ResyncPhaseProto.RESYNC_PHASE_UNSPECIFIED,
                ResyncPhaseProto.UNRECOGNIZED,
                -> error("Cannot map checkpoint phase: $phase")
            },
        nextDate = LocalDate.ofEpochDay(nextEpochDay),
        selectionHash = selectionHash,
    )

private fun ResyncCheckpoint.toProto(): ResyncCheckpointProto =
    ResyncCheckpointProto
        .newBuilder()
        .setStartEpochDay(startDate.toEpochDay())
        .setEndEpochDay(endDate.toEpochDay())
        .setPhase(
            when (phase) {
                ResyncPhase.INGEST -> ResyncPhaseProto.INGEST
                ResyncPhase.PRUNE -> ResyncPhaseProto.PRUNE
                ResyncPhase.RECONCILE -> ResyncPhaseProto.RECONCILE
                ResyncPhase.RECOMPUTE -> ResyncPhaseProto.RECOMPUTE
            },
        ).setNextEpochDay(nextDate.toEpochDay())
        .setSelectionHash(selectionHash)
        .build()
