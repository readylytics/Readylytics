package app.readylytics.health.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.sync.HealthChangeTokenStore
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

object HealthChangeTokensSerializer : Serializer<HealthChangeTokensProto> {
    override val defaultValue: HealthChangeTokensProto = HealthChangeTokensProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): HealthChangeTokensProto {
        try {
            return HealthChangeTokensProto.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(
        t: HealthChangeTokensProto,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}

@Singleton
class HealthChangeTokenStoreImpl
    @Inject
    constructor(
        private val dataStore: DataStore<HealthChangeTokensProto>,
    ) : HealthChangeTokenStore {
        override suspend fun get(dataType: HealthDataType): String? {
            val proto = dataStore.data.first()
            return proto.tokensMap[dataType.name]?.takeIf { it.isNotEmpty() }
        }

        override suspend fun put(
            dataType: HealthDataType,
            token: String,
            syncedAtMs: Long,
        ) {
            dataStore.updateData { current ->
                current
                    .toBuilder()
                    .putTokens(dataType.name, token)
                    .putLastSuccessTimestampsMs(dataType.name, syncedAtMs)
                    .build()
            }
        }

        override suspend fun clear(dataType: HealthDataType) {
            dataStore.updateData { current ->
                current
                    .toBuilder()
                    .removeTokens(dataType.name)
                    .removeLastSuccessTimestampsMs(dataType.name)
                    .build()
            }
        }

        override suspend fun clearAll() {
            dataStore.updateData {
                HealthChangeTokensProto.getDefaultInstance()
            }
        }
    }
