package app.readylytics.health.data.healthconnect

import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device

/**
 * Utility for converting a Health Connect [Device] into a human-readable label
 * that can be used for identifying primary devices.
 *
 * Health Connect's [Device] exposes `manufacturer` and `model` plus a numeric
 * `type`. We prefer the combination "manufacturer model" when both are present,
 * falling back to whichever single value is available. If device info is absent,
 * we fall back to a hardcoded mapping of known Health Connect package names.
 */
internal object DeviceLabel {
    fun from(
        device: Device?,
        dataOrigin: DataOrigin,
    ): String {
        val manufacturer = device?.manufacturer?.trim()
        val model = device?.model?.trim()

        if (!manufacturer.isNullOrEmpty() && !model.isNullOrEmpty()) {
            return "$manufacturer $model"
        }
        if (!model.isNullOrEmpty()) {
            return model
        }
        if (!manufacturer.isNullOrEmpty()) {
            return manufacturer
        }

        // The phone often records data (e.g. Steps) with no manufacturer/model set.
        // Surface it explicitly so it can be picked as a source device.
        if (device?.type == Device.TYPE_PHONE) {
            return "This Phone"
        }

        // Fallback to package name if device info is not useful
        return mapPackageName(dataOrigin.packageName)
    }

    private fun mapPackageName(packageName: String): String =
        when (packageName) {
            "com.google.android.apps.fitness" -> "Google Fit"
            "com.samsung.android.wear.shealth" -> "Samsung Health (Watch)"
            "com.samsung.android.app.shealth" -> "Samsung Health (Phone)"
            "com.garmin.android.apps.connect" -> "Garmin Connect"
            "com.whoop.android" -> "Whoop"
            "com.ouraring.ouraring" -> "Oura"
            "com.strava" -> "Strava"
            "com.withings.wiscale2" -> "Withings"
            else -> {
                // Best-effort attempt to create a readable name from the package
                // e.g. com.example.health.app -> "Health App"
                packageName
                    .split('.')
                    .lastOrNull()
                    ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    ?.replace("_", " ")
                    ?: packageName
            }
        }
}
