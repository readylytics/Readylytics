package app.readylytics.health.core.model.domain.util

import app.readylytics.health.domain.model.DomainIntervalTotal
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionTotalsResolverTest {
    private val sessionStart: Instant = Instant.parse("2026-08-16T07:42:46Z")
    private val sessionEnd: Instant = Instant.parse("2026-08-16T08:49:31Z")
    private val watch = "com.strava"
    private val phone = "com.google.android.apps.fitness"

    @Test
    fun `sums every record written by the session's own package`() {
        val totals =
            listOf(
                total(watch, minutes = 0, toMinutes = 20, value = 4_500.0),
                total(watch, minutes = 20, toMinutes = 45, value = 5_600.0),
                total(watch, minutes = 45, toMinutes = 66, value = 3_800.0),
            )

        assertEquals(13_900.0, resolve(totals))
    }

    @Test
    fun `ignores records from other packages so a phone stream cannot double count`() {
        val totals =
            listOf(
                total(watch, minutes = 0, toMinutes = 66, value = 13_900.0),
                total(phone, minutes = 0, toMinutes = 66, value = 11_200.0),
            )

        assertEquals(13_900.0, resolve(totals))
    }

    @Test
    fun `returns null when the session's writer stored nothing so the caller falls back to the route`() {
        assertNull(resolve(emptyList()))
        assertNull(resolve(listOf(total(phone, minutes = 0, toMinutes = 66, value = 11_200.0))))
    }

    @Test
    fun `attributes a boundary-straddling record by midpoint so it lands in exactly one session`() {
        // Starts 20 min before the session, ends 4 min into it -- midpoint is 8 min before it.
        assertNull(resolve(listOf(total(watch, minutes = -20, toMinutes = 4, value = 900.0))))
        // Starts 5 min into the session, ends 5 min after -- midpoint is inside.
        assertEquals(900.0, resolve(listOf(total(watch, minutes = 61, toMinutes = 71, value = 900.0))))
    }

    @Test
    fun `skips negative and non-finite values`() {
        val totals =
            listOf(
                total(watch, minutes = 0, toMinutes = 30, value = -50.0),
                total(watch, minutes = 30, toMinutes = 60, value = Double.NaN),
                total(watch, minutes = 10, toMinutes = 20, value = 1_000.0),
            )

        assertEquals(1_000.0, resolve(totals))
    }

    private fun resolve(totals: List<DomainIntervalTotal>): Double? =
        SessionTotalsResolver.totalFor(sessionStart, sessionEnd, watch, totals)

    private fun total(
        origin: String,
        minutes: Long,
        toMinutes: Long,
        value: Double,
    ) = DomainIntervalTotal(
        startTime = sessionStart.plusSeconds(minutes * 60),
        endTime = sessionStart.plusSeconds(toMinutes * 60),
        value = value,
        originPackage = origin,
    )
}
