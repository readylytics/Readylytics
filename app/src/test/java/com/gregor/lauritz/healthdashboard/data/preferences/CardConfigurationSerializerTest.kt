package com.gregor.lauritz.healthdashboard.data.preferences

import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardConfigurationSerializerTest {

    @Test
    fun serialize_validConfigurations_returnsJsonString() {
        val configs = listOf(
            CardConfiguration(CardId.SLEEP_SCORE, isVisible = true, position = 0),
            CardConfiguration(CardId.READINESS, isVisible = false, position = 1),
        )

        val json = CardConfigurationSerializer.serialize(configs)

        assertTrue(json.isNotEmpty())
        assertTrue(json.contains("SLEEP_SCORE"))
        assertTrue(json.contains("READINESS"))
    }

    @Test
    fun serialize_emptyList_returnsEmptyJson() {
        val json = CardConfigurationSerializer.serialize(emptyList())
        assertEquals("[]", json)
    }

    @Test
    fun deserialize_validJson_returnsCardConfigurations() {
        val json = """[{"cardId":"SLEEP_SCORE","isVisible":true,"position":0},{"cardId":"READINESS","isVisible":false,"position":1}]"""

        val result = CardConfigurationSerializer.deserialize(json)

        assertEquals(2, result.size)
        assertEquals(CardId.SLEEP_SCORE, result[0].cardId)
        assertTrue(result[0].isVisible)
        assertEquals(0, result[0].position)
        assertEquals(CardId.READINESS, result[1].cardId)
        assertEquals(false, result[1].isVisible)
        assertEquals(1, result[1].position)
    }

    @Test
    fun deserialize_emptyString_returnsEmptyList() {
        val result = CardConfigurationSerializer.deserialize("")
        assertEquals(emptyList(), result)
    }

    @Test
    fun deserialize_invalidJson_returnsEmptyList() {
        val result = CardConfigurationSerializer.deserialize("{invalid json")
        assertEquals(emptyList(), result)
    }

    @Test
    fun deserialize_withUnknownFields_ignoresAndParses() {
        val json = """[{"cardId":"SLEEP_SCORE","isVisible":true,"position":0,"unknownField":"value"}]"""

        val result = CardConfigurationSerializer.deserialize(json)

        assertEquals(1, result.size)
        assertEquals(CardId.SLEEP_SCORE, result[0].cardId)
    }

    @Test
    fun roundTrip_serializeAndDeserialize_preservesData() {
        val original = listOf(
            CardConfiguration(CardId.SLEEP_SCORE, isVisible = true, position = 0),
            CardConfiguration(CardId.HRV, isVisible = false, position = 1),
            CardConfiguration(CardId.RHR, isVisible = true, position = 2),
        )

        val json = CardConfigurationSerializer.serialize(original)
        val deserialized = CardConfigurationSerializer.deserialize(json)

        assertEquals(original, deserialized)
    }
}
