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
    fun `round trips completedTypes and completedTypesRecorded`() {
        val checkpoint =
            ResyncCheckpoint(
                startDate = LocalDate.of(2024, 1, 1),
                endDate = LocalDate.of(2024, 1, 31),
                phase = ResyncPhase.INGEST,
                nextDate = LocalDate.of(2024, 1, 1),
                selectionHash = "hash-123",
                baselineChangeTokens = mapOf(HealthDataType.HEART_RATE to "token-hr"),
                completedTypes = setOf(HealthDataType.HEART_RATE, HealthDataType.SLEEP),
                completedTypesRecorded = true,
            )

        val proto = checkpoint.toProto()
        assertEquals(setOf("HEART_RATE", "SLEEP"), proto.completedTypesList.toSet())
        assertEquals(true, proto.completedTypesRecorded)

        val restored = proto.toDomain()
        assertEquals(checkpoint, restored)
    }

    @Test
    fun `round trips a genuinely-narrowed-to-empty completedTypes distinctly from a legacy checkpoint`() {
        // A real (post-H1) checkpoint that narrowed completedTypes down to empty -- e.g. every
        // tracked type was denied in an already-committed chunk -- must round-trip as
        // completedTypesRecorded=true with an empty set, NOT be indistinguishable from a checkpoint
        // that never had this field at all (see the next test).
        val checkpoint =
            ResyncCheckpoint(
                startDate = LocalDate.of(2024, 1, 1),
                endDate = LocalDate.of(2024, 1, 31),
                phase = ResyncPhase.INGEST,
                nextDate = LocalDate.of(2024, 1, 1),
                selectionHash = "hash-123",
                baselineChangeTokens = mapOf(HealthDataType.HRV to "token-hrv"),
                completedTypes = emptySet(),
                completedTypesRecorded = true,
            )

        val proto = checkpoint.toProto()
        assertEquals(true, proto.completedTypesRecorded)
        assertEquals(emptyList<String>(), proto.completedTypesList)

        val restored = proto.toDomain()
        assertEquals(emptySet<HealthDataType>(), restored.completedTypes)
        assertEquals(true, restored.completedTypesRecorded)
    }

    @Test
    fun `maps missing completedTypes and completedTypesRecorded to conservative legacy defaults in domain`() {
        // A proto serialized before completed_types / completed_types_recorded existed (or before
        // this review fix added completed_types_recorded) never wrote either field, so both decode
        // to their proto3 defaults -- completedTypesRecorded MUST be false here so callers can tell
        // this apart from a real checkpoint that legitimately narrowed completedTypes to empty.
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
        assertEquals(emptySet<HealthDataType>(), domain.completedTypes)
        assertEquals(false, domain.completedTypesRecorded)
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
