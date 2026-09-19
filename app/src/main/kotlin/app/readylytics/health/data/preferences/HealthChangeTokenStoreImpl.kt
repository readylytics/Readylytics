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
        override suspend fun get(dataType: HealthDataType): String? = getToken(dataType.name)

        override suspend fun put(
            dataType: HealthDataType,
            token: String,
            syncedAtMs: Long,
        ) = putToken(dataType.name, token, syncedAtMs)

        override suspend fun putAll(
            tokens: Map<HealthDataType, String>,
            syncedAtMs: Long,
        ) {
            dataStore.updateTokens(tokens.mapKeys { it.key.name }, syncedAtMs)
        }

        override suspend fun suspendType(dataType: HealthDataType) = suspendToken(dataType.name)

        override suspend fun isSuspended(dataType: HealthDataType): Boolean = isTokenSuspended(dataType.name)

        override suspend fun getToken(tokenKey: String): String? {
            val proto = dataStore.data.first()
            return proto.tokensMap[tokenKey]?.takeIf { it.isNotEmpty() }
        }

        override suspend fun isTokenSuspended(tokenKey: String): Boolean {
            val proto = dataStore.data.first()
            return proto.suspendedTypesList.contains(tokenKey)
        }

        override suspend fun suspendToken(tokenKey: String) {
            dataStore.updateData { current ->
                val builder =
                    current
                        .toBuilder()
                        .removeTokens(tokenKey)
                        .removeLastSuccessTimestampsMs(tokenKey)
                if (!current.suspendedTypesList.contains(tokenKey)) {
                    builder.addSuspendedTypes(tokenKey)
                }
                builder.build()
            }
        }

        override suspend fun putToken(
            tokenKey: String,
            token: String,
            syncedAtMs: Long,
        ) {
            dataStore.updateData { current ->
                val builder =
                    current
                        .toBuilder()
                        .putTokens(tokenKey, token)
                        .putLastSuccessTimestampsMs(tokenKey, syncedAtMs)
                val remainingSuspended = current.suspendedTypesList.filter { it != tokenKey }
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

private suspend fun DataStore<HealthChangeTokensProto>.updateTokens(
    tokens: Map<String, String>,
    syncedAtMs: Long,
) {
    updateData { current ->
        val builder = current.toBuilder()
        for (suspended in current.suspendedTypesList) {
            builder.removeTokens(suspended)
            builder.removeLastSuccessTimestampsMs(suspended)
        }
        val tokenNames = tokens.keys.toSet()
        builder
            .putAllTokens(tokens)
            .putAllLastSuccessTimestampsMs(
                tokens.keys.associateWith { syncedAtMs },
            )
        val remainingSuspended = current.suspendedTypesList.filter { it !in tokenNames }
        builder.clearSuspendedTypes().addAllSuspendedTypes(remainingSuspended)
        builder.build()
    }
}
