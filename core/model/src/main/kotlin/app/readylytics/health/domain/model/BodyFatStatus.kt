package app.readylytics.health.domain.model

sealed class BodyFatStatus {
    object Optimal : BodyFatStatus()

    object Neutral : BodyFatStatus()

    object Warning : BodyFatStatus()

    object Poor : BodyFatStatus()

    object Calibrating : BodyFatStatus()
}
