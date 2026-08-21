package app.readylytics.health.core.model.domain.layout

/**
 * Merges a persisted configuration list with the current default set, appending any
 * defaults missing from [stored] (e.g. a card/chart/history entry added in a newer app
 * version) at renumbered positions after the highest stored position. Returns [stored]
 * by reference, unchanged, when nothing is missing.
 */
object LayoutDefaultsMerger {
    fun <T : ReorderableItem<Id>, Id> mergeWithDefaults(
        stored: List<T>,
        defaults: List<T>,
        withPosition: (T, Int) -> T,
    ): List<T> {
        val storedIds = stored.map { it.id }.toSet()
        val missingDefaults = defaults.filter { it.id !in storedIds }
        if (missingDefaults.isEmpty()) return stored

        val maxPos = stored.maxOfOrNull { it.position } ?: -1
        val appended =
            missingDefaults.mapIndexed { index, config -> withPosition(config, maxPos + 1 + index) }
        return stored + appended
    }
}
