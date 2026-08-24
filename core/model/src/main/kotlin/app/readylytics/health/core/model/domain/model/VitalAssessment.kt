package app.readylytics.health.core.model.domain.model

import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.util.UnitConverter
import java.math.BigDecimal
import kotlin.math.abs

interface VitalAssessment {
    val status: MetricStatus
    val zoneBands: List<ZoneBand>?
}

data class PersonalBaselineAssessment(
    val value: Int?,
    val baseline: Int?,
    override val status: MetricStatus,
    val ratio: Float?,
    val delta: Int?,
    override val zoneBands: List<ZoneBand>?,
) : VitalAssessment

data class Spo2Assessment(
    val value: Float?,
    override val status: MetricStatus,
    override val zoneBands: List<ZoneBand>,
) : VitalAssessment

data class BodyTemperatureAssessment(
    val value: Float?,
    val baseline: Float?,
    override val status: MetricStatus,
    override val zoneBands: List<ZoneBand>? = null,
) : VitalAssessment

fun assessHrv(
    value: Int?,
    baseline: Int?,
    optimalRatio: Float,
    warningRatio: Float,
): PersonalBaselineAssessment =
    assessPersonalBaseline(
        value = value,
        baseline = baseline,
        statusForRatio = { ratio -> hrvStatusFromRatio(ratio, optimalRatio, warningRatio) },
        zoneBandsForBaseline = { roundedBaseline ->
            hrvZoneBandsForBaseline(roundedBaseline, optimalRatio, warningRatio)
        },
    )

fun hrvZoneBandsForBaseline(
    baseline: Int,
    optimalRatio: Float,
    warningRatio: Float,
): List<ZoneBand> =
    hrvZoneBandsForThresholds(
        optimalMin = scaledThreshold(baseline, optimalRatio),
        warningMin = scaledThreshold(baseline, warningRatio),
        poorMin = baseline * hrvPoorRatio(warningRatio),
    )

fun assessRhr(
    value: Int?,
    baseline: Int?,
    optimalRatio: Float,
    warningRatio: Float,
): PersonalBaselineAssessment =
    assessPersonalBaseline(
        value = value,
        baseline = baseline,
        statusForRatio = { ratio -> rhrStatusFromRatio(ratio, optimalRatio, warningRatio) },
        zoneBandsForBaseline = { roundedBaseline ->
            rhrZoneBandsForBaseline(roundedBaseline, optimalRatio, warningRatio)
        },
    )

fun rhrZoneBandsForBaseline(
    baseline: Int,
    optimalRatio: Float,
    warningRatio: Float,
): List<ZoneBand> =
    rhrZoneBandsForThresholds(
        optimalMax = scaledThreshold(baseline, optimalRatio),
        warningMax = scaledThreshold(baseline, warningRatio),
        poorMax = baseline * rhrPoorRatio(warningRatio),
    )

fun assessSpo2(value: Float?): Spo2Assessment =
    Spo2Assessment(
        value = value,
        status = spo2StatusFromValue(value),
        zoneBands = spo2ReferenceZoneBands(),
    )

fun assessBodyTemperature(
    valueCelsius: Float?,
    baselineCelsius: Float?,
    thresholdCelsius: Float,
    unitSystem: UnitSystem,
): BodyTemperatureAssessment =
    BodyTemperatureAssessment(
        value = valueCelsius?.let { UnitConverter.celsiusToDisplayTemperature(it, unitSystem) },
        baseline = baselineCelsius?.let { UnitConverter.celsiusToDisplayTemperature(it, unitSystem) },
        status = bodyTemperatureStatus(valueCelsius, baselineCelsius, thresholdCelsius),
    )

fun bodyTemperatureStatus(
    value: Float?,
    baseline: Float?,
    thresholdCelsius: Float,
): MetricStatus =
    when {
        value == null -> MetricStatus.CALIBRATING
        baseline == null -> MetricStatus.NEUTRAL
        abs(value - baseline) >= thresholdCelsius -> MetricStatus.WARNING
        else -> MetricStatus.NEUTRAL
    }

private fun assessPersonalBaseline(
    value: Int?,
    baseline: Int?,
    statusForRatio: (Float) -> MetricStatus,
    zoneBandsForBaseline: (Int) -> List<ZoneBand>,
): PersonalBaselineAssessment {
    val positiveBaseline = baseline?.takeIf { it > 0 }
    val ratio =
        if (value != null && positiveBaseline != null) {
            value / positiveBaseline.toFloat()
        } else {
            null
        }

    return PersonalBaselineAssessment(
        value = value,
        baseline = baseline,
        status = ratio?.let(statusForRatio) ?: MetricStatus.CALIBRATING,
        ratio = ratio,
        delta =
            if (value != null && positiveBaseline != null) {
                value - positiveBaseline
            } else {
                null
            },
        zoneBands = positiveBaseline?.let(zoneBandsForBaseline),
    )
}

internal fun rhrStatusFromRatio(
    ratio: Float,
    optimalRatio: Float,
    warningRatio: Float,
): MetricStatus {
    val ratioValue = ratio.asCanonicalDouble()
    val optimal = optimalRatio.asCanonicalDouble()
    val warning = warningRatio.asCanonicalDouble()
    val poorRatio = rhrPoorRatio(warningRatio)
    return when {
        ratioValue <= optimal -> MetricStatus.OPTIMAL
        ratioValue < warning -> MetricStatus.NEUTRAL
        ratioValue < poorRatio -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

internal fun hrvStatusFromRatio(
    ratio: Float,
    optimalRatio: Float,
    warningRatio: Float,
): MetricStatus {
    val ratioValue = ratio.asCanonicalDouble()
    val optimal = optimalRatio.asCanonicalDouble()
    val warning = warningRatio.asCanonicalDouble()
    val poorRatio = hrvPoorRatio(warningRatio)
    return when {
        ratioValue >= optimal -> MetricStatus.OPTIMAL
        ratioValue > warning -> MetricStatus.NEUTRAL
        ratioValue >= poorRatio -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

internal fun spo2StatusFromValue(value: Float?): MetricStatus =
    when {
        value == null -> MetricStatus.CALIBRATING
        value >= 98f -> MetricStatus.OPTIMAL
        value >= 95f -> MetricStatus.NEUTRAL
        value >= 90f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

internal fun rhrZoneBandsForThresholds(
    optimalMax: Double,
    warningMax: Double,
    poorMax: Double,
): List<ZoneBand> =
    listOf(
        createRhrOptimalBand(optimalMax),
        createRhrNeutralBand(optimalMax, warningMax),
        createRhrWarningBand(warningMax, poorMax),
        createRhrCriticalBand(poorMax),
    )

private fun createRhrOptimalBand(optimalMax: Double): ZoneBand =
    ZoneBand(
        lowerBound = Double.NEGATIVE_INFINITY,
        upperBound = optimalMax,
        zone = HealthZone.OPTIMAL,
        includesMaximum = true,
    )

private fun createRhrNeutralBand(optimalMax: Double, warningMax: Double): ZoneBand =
    ZoneBand(
        lowerBound = optimalMax,
        upperBound = warningMax,
        zone = HealthZone.NEUTRAL,
        includesMinimum = false,
    )

private fun createRhrWarningBand(warningMax: Double, poorMax: Double): ZoneBand =
    ZoneBand(
        lowerBound = warningMax,
        upperBound = poorMax,
        zone = HealthZone.WARNING,
    )

private fun createRhrCriticalBand(poorMax: Double): ZoneBand =
    ZoneBand(
        lowerBound = poorMax,
        upperBound = Double.POSITIVE_INFINITY,
        zone = HealthZone.CRITICAL,
    )

internal fun hrvZoneBandsForThresholds(
    optimalMin: Double,
    warningMin: Double,
    poorMin: Double,
): List<ZoneBand> =
    listOf(
        createHrvCriticalBand(poorMin),
        createHrvWarningBand(poorMin, warningMin),
        createHrvNeutralBand(warningMin, optimalMin),
        createHrvOptimalBand(optimalMin),
    )

private fun createHrvCriticalBand(poorMin: Double): ZoneBand =
    ZoneBand(
        lowerBound = Double.NEGATIVE_INFINITY,
        upperBound = poorMin,
        zone = HealthZone.CRITICAL,
    )

private fun createHrvWarningBand(poorMin: Double, warningMin: Double): ZoneBand =
    ZoneBand(
        lowerBound = poorMin,
        upperBound = warningMin,
        zone = HealthZone.WARNING,
        includesMaximum = true,
    )

private fun createHrvNeutralBand(warningMin: Double, optimalMin: Double): ZoneBand =
    ZoneBand(
        lowerBound = warningMin,
        upperBound = optimalMin,
        zone = HealthZone.NEUTRAL,
        includesMinimum = false,
    )

private fun createHrvOptimalBand(optimalMin: Double): ZoneBand =
    ZoneBand(
        lowerBound = optimalMin,
        upperBound = Double.POSITIVE_INFINITY,
        zone = HealthZone.OPTIMAL,
    )

internal fun spo2ReferenceZoneBands(): List<ZoneBand> =
    listOf(
        ZoneBand(Double.NEGATIVE_INFINITY, 90.0, HealthZone.CRITICAL),
        ZoneBand(90.0, 95.0, HealthZone.WARNING),
        ZoneBand(95.0, 98.0, HealthZone.NEUTRAL),
        ZoneBand(98.0, Double.POSITIVE_INFINITY, HealthZone.OPTIMAL),
    )

private fun scaledThreshold(
    baseline: Int,
    ratio: Float,
): Double = BigDecimal.valueOf(baseline.toLong()).multiply(ratio.asCanonicalBigDecimal()).toDouble()

private fun rhrPoorRatio(warningRatio: Float): Double {
    val warning = warningRatio.asCanonicalBigDecimal()
    return warning.add(warning.subtract(BigDecimal.ONE)).toDouble()
}

private fun hrvPoorRatio(warningRatio: Float): Double {
    val warning = warningRatio.asCanonicalBigDecimal()
    return warning.subtract(BigDecimal.ONE.subtract(warning)).toDouble()
}

private fun Float.asCanonicalDouble(): Double = toString().toDouble()

private fun Float.asCanonicalBigDecimal(): BigDecimal = BigDecimal(toString())
