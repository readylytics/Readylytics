package com.gregor.lauritz.healthdashboard.domain.calculation

import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import com.gregor.lauritz.healthdashboard.domain.model.BloodPressureStatus
import com.gregor.lauritz.healthdashboard.domain.model.BodyFatStatus
import com.gregor.lauritz.healthdashboard.domain.model.BmiStatus

object HealthMetricsCalculator {
    fun calculateBmi(weightKg: Float, heightCm: Float): Float {
        val heightM = heightCm / 100f
        return weightKg / (heightM * heightM)
    }

    fun assessBmi(bmi: Float): BmiStatus =
        when {
            bmi < 25f -> BmiStatus.Optimal
            bmi < 30f -> BmiStatus.Neutral
            bmi < 35f -> BmiStatus.Warning
            else -> BmiStatus.Poor
        }

    fun assessBloodPressure(systolic: Int, diastolic: Int): BloodPressureStatus =
        when {
            systolic < 120 && diastolic < 80 -> BloodPressureStatus.Optimal
            systolic <= 129 && diastolic < 80 -> BloodPressureStatus.Neutral
            systolic in 130..139 || diastolic in 80..89 -> BloodPressureStatus.HypertensionStage1
            else -> BloodPressureStatus.HypertensionStage2
        }

    fun assessBodyFatPercent(
        bodyFatPercent: Float,
        ageYears: Int,
        gender: Gender?,
    ): BodyFatStatus {
        if (gender == null) return BodyFatStatus.Calibrating

        val age = ageYears.coerceIn(1, 120)
        val (optimalMax, neutralMax) =
            when (gender) {
                Gender.MALE ->
                    when {
                        age in 20..40 -> Pair(19f, 24f)
                        age in 41..60 -> Pair(22f, 28f)
                        else -> Pair(24f, 30f)
                    }
                Gender.FEMALE ->
                    when {
                        age in 20..40 -> Pair(32f, 38f)
                        age in 41..60 -> Pair(34f, 40f)
                        else -> Pair(36f, 42f)
                    }
                Gender.OTHER, Gender.PREFER_NOT_TO_SAY -> Pair(25f, 35f)
            }

        return when {
            bodyFatPercent <= optimalMax -> BodyFatStatus.Optimal
            bodyFatPercent <= neutralMax -> BodyFatStatus.Neutral
            else -> BodyFatStatus.Poor
        }
    }

    fun calculateDailyBpAverage(systolics: List<Int>, diastolics: List<Int>): Pair<Int, Int> {
        if (systolics.isEmpty() || diastolics.isEmpty()) return Pair(0, 0)
        return Pair(
            systolics.average().toInt(),
            diastolics.average().toInt(),
        )
    }
}
