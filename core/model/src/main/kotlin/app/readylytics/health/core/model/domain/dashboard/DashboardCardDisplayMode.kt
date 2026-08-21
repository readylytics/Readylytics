package app.readylytics.health.core.model.domain.dashboard

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The visualization mode a user has requested for a specific dashboard card.
 *
 * This is a tolerant, additive persistence field: unknown/future mode names
 * (e.g. from a newer app version) and missing values both decode to `null`
 * via [NullableDashboardCardDisplayModeSerializer] rather than crashing or
 * rejecting the surrounding backup/preferences payload.
 */
@Serializable
enum class DashboardCardDisplayMode {
    GAUGE,
    BAR,
    VALUE,
}

/**
 * Tolerant nullable serializer for [DashboardCardDisplayMode].
 *
 * Any string that does not match a known enum constant (including future
 * modes introduced by a newer app version) decodes to `null` instead of
 * throwing, so old and new persisted data both remain loadable.
 */
object NullableDashboardCardDisplayModeSerializer : KSerializer<DashboardCardDisplayMode?> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(
            "NullableDashboardCardDisplayMode",
            PrimitiveKind.STRING,
        ).nullable

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(
        encoder: Encoder,
        value: DashboardCardDisplayMode?,
    ) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.name)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): DashboardCardDisplayMode? {
        if (!decoder.decodeNotNullMark()) {
            decoder.decodeNull()
            return null
        }
        return runCatching {
            DashboardCardDisplayMode.valueOf(decoder.decodeString())
        }.getOrNull()
    }
}
