package app.readylytics.health.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.HealthChangeTokenStore
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

        override suspend fun isSuspended(dataType: HealthDataType): Boolean {
            val proto = dataStore.data.first()
            return proto.suspendedTypesList.contains(dataType.name)
        }

        override suspend fun suspendType(dataType: HealthDataType) {
            dataStore.updateData { current ->
                val builder =
                    current
                        .toBuilder()
                        .removeTokens(dataType.name)
                        .removeLastSuccessTimestampsMs(dataType.name)
                if (!current.suspendedTypesList.contains(dataType.name)) {
                    builder.addSuspendedTypes(dataType.name)
                }
                builder.build()
            }
        }

        override suspend fun put(
            dataType: HealthDataType,
            token: String,
            syncedAtMs: Long,
        ) {
            dataStore.updateData { current ->
                val builder =
                    current
                        .toBuilder()
                        .putTokens(dataType.name, token)
                        .putLastSuccessTimestampsMs(dataType.name, syncedAtMs)
                val remainingSuspended = current.suspendedTypesList.filter { it != dataType.name }
                builder
                    .clearSuspendedTypes()
                    .addAllSuspendedTypes(remainingSuspended)
                    .build()
            }
        }

        override suspend fun putAll(
            tokens: Map<HealthDataType, String>,
            syncedAtMs: Long,
        ) {
            dataStore.updateData { current ->
                val builder = current.toBuilder()
                for (suspended in current.suspendedTypesList) {
                    builder.removeTokens(suspended)
                    builder.removeLastSuccessTimestampsMs(suspended)
                }
                val tokenNames = tokens.keys.map { it.name }.toSet()
                builder
                    .putAllTokens(tokens.mapKeys { (dataType, _) -> dataType.name })
                    .putAllLastSuccessTimestampsMs(
                        tokens.keys.associate { dataType -> dataType.name to syncedAtMs },
                    )
                val remainingSuspended = current.suspendedTypesList.filter { it !in tokenNames }
                builder.clearSuspendedTypes().addAllSuspendedTypes(remainingSuspended)
                builder.build()
            }
        }

        override suspend fun clear(dataType: HealthDataType) {
            dataStore.updateData { current ->
                val builder =
                    current
                        .toBuilder()
                        .removeTokens(dataType.name)
                        .removeLastSuccessTimestampsMs(dataType.name)
                val remainingSuspended = current.suspendedTypesList.filter { it != dataType.name }
                builder
                    .clearSuspendedTypes()
                    .addAllSuspendedTypes(remainingSuspended)
                    .build()
            }
        }

        override suspend fun clearAll() {
            dataStore.updateData {
                HealthChangeTokensProto.getDefaultInstance()
            }
        }
    }
