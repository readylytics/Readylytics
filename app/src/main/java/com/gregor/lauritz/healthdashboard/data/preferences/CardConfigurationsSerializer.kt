package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object CardConfigurationsSerializer : Serializer<CardConfigurationsProto> {
    override val defaultValue: CardConfigurationsProto = CardConfigurationsProto.newBuilder()
        .addAllDashboardCards(SettingsDefaults.DEFAULT_DASHBOARD_CARDS.map { CardConfigurationMapper.toProto(it) })
        .build()

    override suspend fun readFrom(input: InputStream): CardConfigurationsProto {
        try {
            return CardConfigurationsProto.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: CardConfigurationsProto, output: OutputStream) {
        t.writeTo(output)
    }
}
