package app.readylytics.health.data.preferences

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResyncCheckpointStoreImplTest {
    @Test
    fun `round trips hrPageToken and hrvPageToken`() {
        val checkpoint =
            ResyncCheckpoint(
                startDate = LocalDate.of(2024, 1, 1),
                endDate = LocalDate.of(2024, 1, 31),
                phase = ResyncPhase.INGEST,
                nextDate = LocalDate.of(2024, 1, 1),
                selectionHash = "hash-123",
                baselineChangeTokens = mapOf(HealthDataType.HEART_RATE to "token-hr"),
                chunkDaysOverride = 15,
                hrPageToken = "next-page-hr-token-42",
                hrvPageToken = "next-page-hrv-token-99",
            )

        val proto = checkpoint.toProto()
        assertEquals("next-page-hr-token-42", proto.hrPageToken)
        assertEquals("next-page-hrv-token-99", proto.hrvPageToken)

        val restored = proto.toDomain()
        assertEquals(checkpoint, restored)
    }

    @Test
    fun `maps empty or missing page tokens to null in domain`() {
        val proto =
            ResyncCheckpointProto
                .newBuilder()
                .setStartEpochDay(LocalDate.of(2024, 1, 1).toEpochDay())
                .setEndEpochDay(LocalDate.of(2024, 1, 31).toEpochDay())
                .setPhase(ResyncPhaseProto.INGEST)
                .setNextEpochDay(LocalDate.of(2024, 1, 1).toEpochDay())
                .setSelectionHash("hash-123")
                .build()

        val domain = proto.toDomain()
        assertNull(domain.hrPageToken)
        assertNull(domain.hrvPageToken)
    }

    @Test
    fun `maps blank page tokens to null in domain`() {
        val proto =
            ResyncCheckpointProto
                .newBuilder()
                .setStartEpochDay(LocalDate.of(2024, 1, 1).toEpochDay())
                .setEndEpochDay(LocalDate.of(2024, 1, 31).toEpochDay())
                .setPhase(ResyncPhaseProto.INGEST)
                .setNextEpochDay(LocalDate.of(2024, 1, 1).toEpochDay())
                .setSelectionHash("hash-123")
                .setHrPageToken("   ")
                .setHrvPageToken("")
                .build()

        val domain = proto.toDomain()
        assertNull(domain.hrPageToken)
        assertNull(domain.hrvPageToken)
    }
}
