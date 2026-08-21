package app.readylytics.health.core.model.domain.dashboard

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardCardDisplayModeSerializerTest {
    @Test
    fun `unknown serialized mode decodes to null`() {
        val decoded =
            Json.decodeFromString<CardConfiguration>(
                """{"cardId":"HRV","isVisible":true,"position":2,"requestedDisplayMode":"TREND"}""",
            )
        assertNull(decoded.requestedDisplayMode)
    }

    @Test
    fun `missing field decodes to null`() {
        val decoded =
            Json.decodeFromString<CardConfiguration>(
                """{"cardId":"HRV","isVisible":true,"position":2}""",
            )
        assertNull(decoded.requestedDisplayMode)
    }

    @Test
    fun `explicit null decodes to null`() {
        val decoded =
            Json.decodeFromString<CardConfiguration>(
                """{"cardId":"HRV","isVisible":true,"position":2,"requestedDisplayMode":null}""",
            )
        assertNull(decoded.requestedDisplayMode)
    }

    @Test
    fun `known mode round trips through JSON`() {
        val original = CardConfiguration(CardId.HRV, requestedDisplayMode = DashboardCardDisplayMode.BAR)

        val json = Json.encodeToString(original)
        val decoded = Json.decodeFromString<CardConfiguration>(json)

        assertEquals(original, decoded)
        assertEquals(json.contains("\"requestedDisplayMode\":\"BAR\""), true)
    }
}
