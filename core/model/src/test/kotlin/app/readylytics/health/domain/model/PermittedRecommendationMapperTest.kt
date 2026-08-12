package app.readylytics.health.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PermittedRecommendationMapperTest {

    @Test
    fun `resolves REST when ILLNESS_ONSET flag is present regardless of status`() {
        val result = PermittedRecommendationMapper.resolve(
            status = MetricStatus.OPTIMAL,
            flags = listOf(RecoveryFlag.ILLNESS_ONSET)
        )
        assertEquals(PermittedRecommendation.REST, result)
    }

    @Test
    fun `resolves ACTIVE_RECOVERY when OVERREACHING flag is present and no illness`() {
        val result = PermittedRecommendationMapper.resolve(
            status = MetricStatus.OPTIMAL,
            flags = listOf(RecoveryFlag.OVERREACHING)
        )
        assertEquals(PermittedRecommendation.ACTIVE_RECOVERY, result)
    }

    @Test
    fun `resolves based on status when no overriding flags are present`() {
        assertEquals(
            PermittedRecommendation.REST,
            PermittedRecommendationMapper.resolve(MetricStatus.POOR, emptyList())
        )
        assertEquals(
            PermittedRecommendation.ACTIVE_RECOVERY,
            PermittedRecommendationMapper.resolve(MetricStatus.WARNING, emptyList())
        )
        assertEquals(
            PermittedRecommendation.TRAIN,
            PermittedRecommendationMapper.resolve(MetricStatus.NEUTRAL, emptyList())
        )
        assertEquals(
            PermittedRecommendation.TRAIN,
            PermittedRecommendationMapper.resolve(MetricStatus.OPTIMAL, emptyList())
        )
        assertEquals(
            PermittedRecommendation.TRAIN,
            PermittedRecommendationMapper.resolve(MetricStatus.CALIBRATING, emptyList())
        )
        assertEquals(
            PermittedRecommendation.UNKNOWN,
            PermittedRecommendationMapper.resolve(MetricStatus.NO_DATA, emptyList())
        )
    }
}
