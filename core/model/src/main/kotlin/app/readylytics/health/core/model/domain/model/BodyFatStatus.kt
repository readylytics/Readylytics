package app.readylytics.health.core.model.domain.model

sealed class BodyFatStatus {
    object Optimal : BodyFatStatus()

    object Neutral : BodyFatStatus()

    object Warning : BodyFatStatus()

    object Poor : BodyFatStatus()
}
