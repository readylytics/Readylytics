package com.gregor.lauritz.healthdashboard.domain.sync

/**
 * Pure, Android-free helper that strictly filters a list of records to a single
 * selected source device.
 *
 * Health Connect cannot filter by physical device at read time, so the app fetches
 * all records and narrows them here by the human-readable device label
 * (see `DeviceLabel`). When [selectedDevice] is null or blank the selection is
 * "All devices" and every record is kept; otherwise only records whose label
 * matches exactly are retained (no silent fallback to other devices).
 */
object DeviceSourceFilter {
    fun <T> filterToDevice(
        records: List<T>,
        selectedDevice: String?,
        getDeviceName: (T) -> String?,
    ): List<T> =
        if (selectedDevice.isNullOrBlank()) {
            records
        } else {
            records.filter { getDeviceName(it) == selectedDevice }
        }
}
