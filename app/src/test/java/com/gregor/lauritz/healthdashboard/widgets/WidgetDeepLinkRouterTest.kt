package com.gregor.lauritz.healthdashboard.widgets

import android.net.Uri
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WidgetDeepLinkRouterTest {
    private lateinit var router: WidgetDeepLinkRouter

    @Before
    fun setup() {
        router = WidgetDeepLinkRouter()
    }

    @Test
    fun parseDeepLink_dashboard_returns_dashboard_target() {
        val uri = Uri.parse("app://dashboard")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Dashboard)
    }

    @Test
    fun parseDeepLink_metric_hrv_returns_hrv_metric() {
        val uri = Uri.parse("app://metric/hrv")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Metric)
        assertEquals(MetricType.HRV, (result as DeepLinkTarget.Metric).type)
    }

    @Test
    fun parseDeepLink_metric_rhr_returns_rhr_metric() {
        val uri = Uri.parse("app://metric/rhr")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Metric)
        assertEquals(MetricType.RHR, (result as DeepLinkTarget.Metric).type)
    }

    @Test
    fun parseDeepLink_metric_sleep_score_returns_sleep_score() {
        val uri = Uri.parse("app://metric/sleep_score")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Metric)
        assertEquals(MetricType.SLEEP_SCORE, (result as DeepLinkTarget.Metric).type)
    }

    @Test
    fun parseDeepLink_metric_steps_returns_steps_metric() {
        val uri = Uri.parse("app://metric/steps")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Metric)
        assertEquals(MetricType.STEPS, (result as DeepLinkTarget.Metric).type)
    }

    @Test
    fun parseDeepLink_metric_readiness_returns_readiness() {
        val uri = Uri.parse("app://metric/readiness")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Metric)
        assertEquals(MetricType.READINESS, (result as DeepLinkTarget.Metric).type)
    }

    @Test
    fun parseDeepLink_metric_pai_returns_pai() {
        val uri = Uri.parse("app://metric/pai")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Metric)
        assertEquals(MetricType.PAI, (result as DeepLinkTarget.Metric).type)
    }

    @Test
    fun parseDeepLink_metric_recovery_returns_recovery() {
        val uri = Uri.parse("app://metric/recovery")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Metric)
        assertEquals(MetricType.RECOVERY, (result as DeepLinkTarget.Metric).type)
    }

    @Test
    fun parseDeepLink_metric_case_insensitive() {
        val uri = Uri.parse("app://metric/HRV")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Metric)
        assertEquals(MetricType.HRV, (result as DeepLinkTarget.Metric).type)
    }

    @Test
    fun parseDeepLink_invalid_metric_returns_null() {
        val uri = Uri.parse("app://metric/invalid_metric")
        val result = router.parseDeepLink(uri)

        assertNull(result)
    }

    @Test
    fun parseDeepLink_invalid_host_returns_null() {
        val uri = Uri.parse("app://invalid_host")
        val result = router.parseDeepLink(uri)

        assertNull(result)
    }

    @Test
    fun parseDeepLink_invalid_scheme_returns_null() {
        val uri = Uri.parse("http://dashboard")
        val result = router.parseDeepLink(uri)

        assertNull(result)
    }

    @Test
    fun parseDeepLink_null_uri_returns_null() {
        val result = router.parseDeepLink(null)

        assertNull(result)
    }

    @Test
    fun parseDeepLink_metric_sleep_duration_returns_sleep_duration() {
        val uri = Uri.parse("app://metric/sleep_duration")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Metric)
        assertEquals(MetricType.SLEEP_DURATION, (result as DeepLinkTarget.Metric).type)
    }

    @Test
    fun parseDeepLink_metric_stress_returns_stress() {
        val uri = Uri.parse("app://metric/stress")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Metric)
        assertEquals(MetricType.STRESS, (result as DeepLinkTarget.Metric).type)
    }

    @Test
    fun parseDeepLink_metric_strain_ratio_returns_strain_ratio() {
        val uri = Uri.parse("app://metric/strain_ratio")
        val result = router.parseDeepLink(uri)

        assertTrue(result is DeepLinkTarget.Metric)
        assertEquals(MetricType.STRAIN_RATIO, (result as DeepLinkTarget.Metric).type)
    }
}
