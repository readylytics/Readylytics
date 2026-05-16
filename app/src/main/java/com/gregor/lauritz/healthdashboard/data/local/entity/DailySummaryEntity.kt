package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gregor.lauritz.healthdashboard.domain.model.ReadinessResult

@Entity(
    tableName = "daily_summaries",
    indices = [Index(value = ["dateMidnightMs"])],
)
data class DailySummaryEntity(
    @PrimaryKey val dateMidnightMs: Long,
    val sleepScore: Float? = null,
    val loadScore: Float? = null,
    val readinessScore: Float? = null,
    val strainRatio: Float? = null,
    val nocturnalRhr: Int? = null,
    val nocturnalHrv: Int? = null,
    val sleepDurationMinutes: Int? = null,
    val deepSleepPercent: Float? = null,
    val remSleepPercent: Float? = null,
    val totalTrimp: Float? = null,
    val rhrRatio: Float? = null,
    val hrvBaseline: Int? = null,
    val restingHeartRate: Int? = null,
    val restingHrRatio: Float? = null,
    val restingHrBaseline: Int? = null,
    val paiScore: Float? = null,
    val totalPai: Float? = null,
    val stepCount: Int? = null,
    val zLnHrv: Float? = null,
    val zRhr: Float? = null,
    val recoveryFlags: String? = null,
    val hrvSigma: Float? = null,
    @Embedded(prefix = "diag_")
    val diagnostics: ReadinessResult.Diagnostics = ReadinessResult.Diagnostics(),
    @Embedded(prefix = "contrib_")
    val contributors: ReadinessResult.Contributors = ReadinessResult.Contributors(),
    // Legacy/supporting fields not bundled into ReadinessResult
    val rollingMu: Float? = null,
    val rhrDeltaBpm: Float? = null,
    val lateNadir: Boolean? = null,
    val stagesSuspicious: Boolean? = null,
    val isCalibrating: Boolean? = null,
    val hrvScoreContribution: Float? = null,
    val rhrScoreContribution: Float? = null,
    val durationScoreContribution: Float? = null,
    val architectureScoreContribution: Float? = null,
    val loadContribution: Float? = null,
    val sRest: Float? = null,
) {
    companion object {
        fun fromJson(json: org.json.JSONObject): DailySummaryEntity =
            DailySummaryEntity(
                dateMidnightMs = json.getLong("dateMidnightMs"),
                sleepScore =
                    if (json.has("sleepScore") &&
                        !json.isNull("sleepScore")
                    ) {
                        json.getDouble("sleepScore").toFloat()
                    } else {
                        null
                    },
                loadScore =
                    if (json.has("loadScore") &&
                        !json.isNull("loadScore")
                    ) {
                        json.getDouble("loadScore").toFloat()
                    } else {
                        null
                    },
                readinessScore =
                    if (json.has("readinessScore") &&
                        !json.isNull("readinessScore")
                    ) {
                        json.getDouble("readinessScore").toFloat()
                    } else {
                        null
                    },
                strainRatio =
                    if (json.has("strainRatio") &&
                        !json.isNull("strainRatio")
                    ) {
                        json.getDouble("strainRatio").toFloat()
                    } else {
                        null
                    },
                nocturnalRhr =
                    if (json.has("nocturnalRhr") &&
                        !json.isNull("nocturnalRhr")
                    ) {
                        json.getInt("nocturnalRhr")
                    } else {
                        null
                    },
                nocturnalHrv =
                    if (json.has("nocturnalHrv") &&
                        !json.isNull("nocturnalHrv")
                    ) {
                        json.getInt("nocturnalHrv")
                    } else {
                        null
                    },
                sleepDurationMinutes =
                    if (json.has("sleepDurationMinutes") &&
                        !json.isNull("sleepDurationMinutes")
                    ) {
                        json.getInt("sleepDurationMinutes")
                    } else {
                        null
                    },
                deepSleepPercent =
                    if (json.has("deepSleepPercent") &&
                        !json.isNull("deepSleepPercent")
                    ) {
                        json.getDouble("deepSleepPercent").toFloat()
                    } else {
                        null
                    },
                remSleepPercent =
                    if (json.has("remSleepPercent") &&
                        !json.isNull("remSleepPercent")
                    ) {
                        json.getDouble("remSleepPercent").toFloat()
                    } else {
                        null
                    },
                totalTrimp =
                    if (json.has("totalTrimp") &&
                        !json.isNull("totalTrimp")
                    ) {
                        json.getDouble("totalTrimp").toFloat()
                    } else {
                        null
                    },
                rhrRatio =
                    if (json.has("rhrRatio") &&
                        !json.isNull("rhrRatio")
                    ) {
                        json.getDouble("rhrRatio").toFloat()
                    } else {
                        null
                    },
                hrvBaseline =
                    if (json.has("hrvBaseline") &&
                        !json.isNull("hrvBaseline")
                    ) {
                        json.getInt("hrvBaseline")
                    } else {
                        null
                    },
                restingHeartRate =
                    if (json.has("restingHeartRate") &&
                        !json.isNull("restingHeartRate")
                    ) {
                        json.getInt("restingHeartRate")
                    } else {
                        null
                    },
                restingHrRatio =
                    if (json.has("restingHrRatio") &&
                        !json.isNull("restingHrRatio")
                    ) {
                        json.getDouble("restingHrRatio").toFloat()
                    } else {
                        null
                    },
                restingHrBaseline =
                    if (json.has("restingHrBaseline") &&
                        !json.isNull("restingHrBaseline")
                    ) {
                        json.getInt("restingHrBaseline")
                    } else {
                        null
                    },
                paiScore =
                    if (json.has("paiScore") &&
                        !json.isNull("paiScore")
                    ) {
                        json.getDouble("paiScore").toFloat()
                    } else {
                        null
                    },
                totalPai =
                    if (json.has("totalPai") &&
                        !json.isNull("totalPai")
                    ) {
                        json.getDouble("totalPai").toFloat()
                    } else {
                        null
                    },
                stepCount =
                    if (json.has("stepCount") &&
                        !json.isNull("stepCount")
                    ) {
                        json.getInt("stepCount")
                    } else {
                        null
                    },
                zLnHrv =
                    if (json.has("zLnHrv") &&
                        !json.isNull("zLnHrv")
                    ) {
                        json.getDouble("zLnHrv").toFloat()
                    } else {
                        null
                    },
                zRhr = if (json.has("zRhr") && !json.isNull("zRhr")) json.getDouble("zRhr").toFloat() else null,
                recoveryFlags =
                    if (json.has("recoveryFlags") &&
                        !json.isNull("recoveryFlags")
                    ) {
                        json.getString("recoveryFlags")
                    } else {
                        null
                    },
                hrvSigma =
                    if (json.has("hrvSigma") &&
                        !json.isNull("hrvSigma")
                    ) {
                        json.getDouble("hrvSigma").toFloat()
                    } else {
                        null
                    },
                diagnostics =
                    if (json.has("diagnostics")) {
                        val d = json.getJSONObject("diagnostics")
                        ReadinessResult.Diagnostics(
                            zLnHrv = if (d.isNull("zLnHrv")) null else d.getDouble("zLnHrv").toFloat(),
                            zRhr = if (d.isNull("zRhr")) null else d.getDouble("zRhr").toFloat(),
                            lnSigma = if (d.isNull("lnSigma")) null else d.getDouble("lnSigma").toFloat(),
                            rollingMu = if (d.isNull("rollingMu")) null else d.getDouble("rollingMu").toFloat(),
                            rhrDeltaBpm = if (d.isNull("rhrDeltaBpm")) null else d.getDouble("rhrDeltaBpm").toFloat(),
                            isCalibrating = d.optBoolean("isCalibrating", false),
                            stagesSuspicious = d.optBoolean("stagesSuspicious", false),
                            lateNadir = d.optBoolean("lateNadir", false),
                            hrvMissing = d.optBoolean("hrvMissing", false),
                            timezoneJump = d.optBoolean("timezoneJump", false),
                            configHashCode = if (d.isNull("configHashCode")) null else d.getInt("configHashCode"),
                            phaseName = if (d.isNull("phaseName")) null else d.getString("phaseName"),
                        )
                    } else {
                        ReadinessResult.Diagnostics()
                    },
                contributors =
                    if (json.has("contributors")) {
                        val c = json.getJSONObject("contributors")
                        ReadinessResult.Contributors(
                            hrvScore = if (c.isNull("hrvScore")) null else c.getDouble("hrvScore").toFloat(),
                            rhrScore = if (c.isNull("rhrScore")) null else c.getDouble("rhrScore").toFloat(),
                            durationScore = if (c.isNull("durationScore")) null else c.getDouble("durationScore").toFloat(),
                            architectureScore = if (c.isNull("architectureScore")) null else c.getDouble("architectureScore").toFloat(),
                            loadContribution = if (c.isNull("loadContribution")) null else c.getDouble("loadContribution").toFloat(),
                        )
                    } else {
                        ReadinessResult.Contributors()
                    },
                rollingMu =
                    if (json.has("rollingMu") &&
                        !json.isNull("rollingMu")
                    ) {
                        json.getDouble("rollingMu").toFloat()
                    } else {
                        null
                    },
                rhrDeltaBpm =
                    if (json.has("rhrDeltaBpm") &&
                        !json.isNull("rhrDeltaBpm")
                    ) {
                        json.getDouble("rhrDeltaBpm").toFloat()
                    } else {
                        null
                    },
                lateNadir =
                    if (json.has("lateNadir") &&
                        !json.isNull("lateNadir")
                    ) {
                        json.getBoolean("lateNadir")
                    } else {
                        null
                    },
                stagesSuspicious =
                    if (json.has("stagesSuspicious") &&
                        !json.isNull("stagesSuspicious")
                    ) {
                        json.getBoolean("stagesSuspicious")
                    } else {
                        null
                    },
                isCalibrating =
                    if (json.has("isCalibrating") &&
                        !json.isNull("isCalibrating")
                    ) {
                        json.getBoolean("isCalibrating")
                    } else {
                        null
                    },
                hrvScoreContribution =
                    if (json.has("hrvScoreContribution") &&
                        !json.isNull("hrvScoreContribution")
                    ) {
                        json.getDouble("hrvScoreContribution").toFloat()
                    } else {
                        null
                    },
                rhrScoreContribution =
                    if (json.has("rhrScoreContribution") &&
                        !json.isNull("rhrScoreContribution")
                    ) {
                        json.getDouble("rhrScoreContribution").toFloat()
                    } else {
                        null
                    },
                durationScoreContribution =
                    if (json.has("durationScoreContribution") &&
                        !json.isNull("durationScoreContribution")
                    ) {
                        json.getDouble("durationScoreContribution").toFloat()
                    } else {
                        null
                    },
                architectureScoreContribution =
                    if (json.has("architectureScoreContribution") &&
                        !json.isNull("architectureScoreContribution")
                    ) {
                        json.getDouble("architectureScoreContribution").toFloat()
                    } else {
                        null
                    },
                loadContribution =
                    if (json.has("loadContribution") &&
                        !json.isNull("loadContribution")
                    ) {
                        json.getDouble("loadContribution").toFloat()
                    } else {
                        null
                    },
                sRest =
                    if (json.has(
                            "sRest",
                        ) &&
                        !json.isNull(
                            "sRest",
                        )
                    ) {
                        json.getDouble("sRest").toFloat()
                    } else {
                        null
                    },
            )
    }
}
