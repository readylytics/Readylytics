package app.readylytics.health.benchmark

import app.readylytics.health.core.database.data.local.HealthDatabase

/**
 * Captures `EXPLAIN QUERY PLAN` output for a live SQLCipher-backed [HealthDatabase].
 *
 * §7.3 criterion 7 of the remediation plan requires the hot heart-rate queries to be index-supported
 * with no table scan and no temp B-tree sort. Recording the plan as text in `benchmark/BASELINE.md`
 * is what lets a later phase show the plan did not regress — a wall-clock number alone cannot.
 *
 * Uses the same raw-SQL route `CurrentSchemaBenchmarkFixture.checkpointWal` already uses against a
 * SQLCipher helper, so it needs no extra plumbing.
 */
object QueryPlanRecorder {
    fun plan(
        database: HealthDatabase,
        sql: String,
    ): String {
        val rows = mutableListOf<String>()
        database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            val detailIndex = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) {
                rows += cursor.getString(detailIndex)
            }
        }
        return rows.joinToString("\n")
    }

    /** Single-line form for one metric line per plan in logcat. */
    fun planOneLine(
        database: HealthDatabase,
        sql: String,
    ): String = plan(database, sql).replace("\n", " | ")
}
