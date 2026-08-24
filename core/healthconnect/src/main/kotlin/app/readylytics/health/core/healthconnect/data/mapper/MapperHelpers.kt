package app.readylytics.health.core.healthconnect.data.mapper

import java.time.Instant

/**
 * Common extraction and mapping helpers for Health Connect data mappers.
 * These helpers reduce duplication and support scaling as mappers grow.
 */
object MapperHelpers {

    /**
     * Extracts a composite ID from a record's ID and timestamp.
     * Used consistently across all mappers to create unique entity identifiers.
     */
    fun extractRecordId(id: String, timeMs: Long): String =
        "${id}_${timeMs}"

    /**
     * Extracts a composite ID from a record's ID and Instant timestamp.
     * Convenience wrapper that converts Instant to milliseconds.
     */
    fun extractRecordIdFromInstant(id: String, time: Instant): String =
        extractRecordId(id, time.toEpochMilli())

    /**
     * Converts an Instant to epoch milliseconds.
     * Centralizes timestamp conversion logic used across all mappers.
     */
    fun extractTimestampMs(time: Instant): Long =
        time.toEpochMilli()

    /**
     * Safely extracts a device name field, providing empty string as default.
     * Use when device name must never be null.
     */
    fun extractDeviceName(deviceName: String?): String =
        deviceName?.takeIf { it.isNotBlank() } ?: ""

    /**
     * Generic list mapper wrapper that applies a mapper function to each element.
     * Reduces boilerplate across toEntities functions.
     */
    inline fun <reified Input, reified Output> mapRecordList(
        records: List<Input>,
        mapper: (Input) -> Output,
    ): List<Output> =
        records.map { mapper(it) }

    /**
     * Safely extracts a long value with a default fallback.
     * Useful for count/quantity fields.
     */
    fun extractLongWithDefault(value: Long?, default: Long = 0L): Long =
        value ?: default
}
