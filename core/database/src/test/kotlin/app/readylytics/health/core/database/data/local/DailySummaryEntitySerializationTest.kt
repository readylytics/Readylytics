package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
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

    @Test
    fun testSerializationRoundTripWithNullResidualFatigue() {
        val original = DailySummaryEntity(dateMidnightMs = 123456789L, residualFatigue = null)
        val json = Json { encodeDefaults = true }
        val serialized = json.encodeToString(original)

        val deserialized = json.decodeFromString<DailySummaryEntity>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSerializationRoundTripWithResidualFatigueValue() {
        val original = DailySummaryEntity(dateMidnightMs = 123456789L, residualFatigue = 42.5f)
        val json = Json { encodeDefaults = true }
        val serialized = json.encodeToString(original)

        val deserialized = json.decodeFromString<DailySummaryEntity>(serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun testDecodesBackupWithoutResidualFatigueKeyToNull() {
        val legacyBackup = """{"dateMidnightMs":123456789,"sleepScore":87.5,"napCount":2}"""

        val testCodec = Json { encodeDefaults = true }
        val fromTestCodec = testCodec.decodeFromString<DailySummaryEntity>(legacyBackup)
        assertEquals(123456789L, fromTestCodec.dateMidnightMs)
        assertEquals(87.5f, fromTestCodec.sleepScore)
        assertEquals(2, fromTestCodec.napCount)
        assertEquals(null, fromTestCodec.residualFatigue)

        val restoreCodec = Json { ignoreUnknownKeys = true }
        val fromRestoreCodec = restoreCodec.decodeFromString<DailySummaryEntity>(legacyBackup)
        assertEquals(123456789L, fromRestoreCodec.dateMidnightMs)
        assertEquals(null, fromRestoreCodec.residualFatigue)
    }
}
