package app.readylytics.health.data.local

import app.readylytics.health.data.local.entity.DailySummaryEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DailySummaryEntitySerializationTest {
    @Test
    fun testSerializationAndDeserializationWithLocalDate() {
        val original =
            DailySummaryEntity(
                dateMidnightMs = 123456789L,
                baselineCalculatedAtDate = LocalDate.of(2026, 6, 14),
            )
        val json = Json { encodeDefaults = true }
        val serialized = json.encodeToString(original)

        // Assert that the serialized string contains the date in correct ISO format
        assert(serialized.contains("\"baselineCalculatedAtDate\":\"2026-06-14\"")) {
            "Expected serialized string to contain date, but got: $serialized"
        }

        val deserialized = json.decodeFromString<DailySummaryEntity>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSerializationAndDeserializationWithNullLocalDate() {
        val original =
            DailySummaryEntity(
                dateMidnightMs = 123456789L,
                baselineCalculatedAtDate = null,
            )
        val json = Json { encodeDefaults = true }
        val serialized = json.encodeToString(original)

        val deserialized = json.decodeFromString<DailySummaryEntity>(serialized)
        assertEquals(original, deserialized)
    }
}
