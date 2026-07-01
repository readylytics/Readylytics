package app.readylytics.health.domain.validation

class HrrToleranceRule : IntRangeRule(15, 60, "Recovery match window: 15–60 s")
