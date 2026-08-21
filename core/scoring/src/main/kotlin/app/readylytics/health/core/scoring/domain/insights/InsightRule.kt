package app.readylytics.health.core.scoring.domain.insights

/**
 * A single deterministic rule evaluated against an [InsightContext]. New
 * rules can be added to [InsightEngine] without modifying existing rules.
 */
interface InsightRule {
    fun evaluate(context: InsightContext): InsightFinding?
}
