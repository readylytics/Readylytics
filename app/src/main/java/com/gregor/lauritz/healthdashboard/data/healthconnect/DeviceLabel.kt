package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.metadata.Device

/**
 * Utility for converting a Health Connect [Device] into a human-readable label
 * that can be used for identifying primary devices.
 *
 * Health Connect's [Device] exposes `manufacturer` and `model` plus a numeric
 * `type`. We prefer the combination "manufacturer model" when both are present,
 * falling back to whichever single value is available. Returns null when the
 * device metadata carries no useful identification (e.g. manually-entered data).
 */
internal object DeviceLabel {
    fun from(device: Device?): String? {
        if (device == null) return null
        val manufacturer = device.manufacturer?.trim().orEmpty()
        val model = device.model?.trim().orEmpty()
        return when {
            manufacturer.isNotEmpty() && model.isNotEmpty() -> "$manufacturer $model"
            model.isNotEmpty() -> model
            manufacturer.isNotEmpty() -> manufacturer
            else -> null
        }
    }
}
