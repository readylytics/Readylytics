package app.readylytics.health.data.local

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.security.AndroidKeystoreKeyProvider
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.model.domain.model.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Phase 3 Step 7 (Option C) conflict-strategy research on a real device.
 *
 * Prototypes the current `@Insert(REPLACE)` baseline, the Room `@Upsert` candidate, and the
 * proposed conflict-targeted `INSERT ... ON CONFLICT(sourceRecordRef, timestampMs) DO UPDATE`
 * strategy against a fresh SQLCipher-encrypted Room database (production `DatabaseModule` setup,
 * real SQLCipher native engine -- NOT the platform SQLite). Verifies on-device:
 *
 *  - `rowId` stability across idempotent re-ingest (the whole point of Option C);
 *  - `rowId = 0` ingestion (the entity secondary constructor / mapper path);
 *  - the column-comparison predicate in the `WHERE` clause (near-no-op vs. write);
 *  - the SQLite engine actually supports UPSERT syntax (bundled SQLCipher, independent of the
 *    platform SQLite on minSdk 26 devices);
 *  - the generated SQL Room produces for `@Upsert`, so the "does @Upsert target the primary key
 *    instead of the secondary unique key?" question is answered from the compiled impl, not
 *    guessed.
 *
 * Not a fixture of the production schema; uses [UpsertPrototypeDatabase], whose tables/indices are
 * generated from the same production entities (schema-identical to `HealthDatabase` v9).
 */
@RunWith(AndroidJUnit4::class)
class UpsertConflictStrategyInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dbFile: File
    private lateinit var db: UpsertPrototypeDatabase
    private lateinit var dao: UpsertPrototypeDao

    // conflict-targeted strategy exactly as Option C specifies: compare mutable columns
    // (recordType, sessionId, deviceName), only write when one differs.
    private val conflictTargetedHrSql =
        "INSERT INTO heart_rate_records " +
            "(sourceRecordRef, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
            "VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(sourceRecordRef, timestampMs) DO UPDATE SET " +
            "recordType = excluded.recordType, " +
            "sessionId = excluded.sessionId, " +
            "deviceName = excluded.deviceName " +
            "WHERE (recordType IS NOT excluded.recordType OR " +
            "sessionId IS NOT excluded.sessionId OR deviceName IS NOT excluded.deviceName)"

    private val conflictTargetedHrvSql =
        "INSERT INTO hrv_records " +
            "(sourceRecordRef, timestampMs, rmssdMs, recordType, sessionId, deviceName) " +
            "VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(sourceRecordRef, timestampMs) DO UPDATE SET " +
            "recordType = excluded.recordType, " +
            "sessionId = excluded.sessionId, " +
            "deviceName = excluded.deviceName " +
            "WHERE (recordType IS NOT excluded.recordType OR " +
            "sessionId IS NOT excluded.sessionId OR deviceName IS NOT excluded.deviceName)"

    @Before
    fun setUp() {
        dbFile = File(context.getDatabasePath("upsert_prototype_${UUID.randomUUID()}.db").absolutePath)
        val keyManager = SqlCipherKeyManager(context, AndroidKeystoreKeyProvider())
        // Production DatabaseModule wraps Room with the SQLCipher factory (getOrCreateFactory).
        // A fresh test file never existed, so migrateIfNeeded is a no-op; the factory derives the
        // same app-global key the production DB uses (SQLCipher keys are app-scoped, not file-scoped).
        db =
            Room
                .databaseBuilder<UpsertPrototypeDatabase>(
                    context,
                    dbFile.absolutePath,
                ).openHelperFactory(keyManager.getOrCreateFactory(dbFile))
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(kotlinx.coroutines.Dispatchers.IO)
                .build()
        dao = db.upsertPrototypeDao()

        // Phase 5 (v10): heart_rate_records / hrv_records now carry a FK to health_source_records.
        // Provision synthetic parent rows (explicit ids) so the prototype's conflict-strategy inserts
        // satisfy the FK; the parent rows are inert w.r.t. the rowId/changes() behavior under test.
        writable().execSQL("PRAGMA foreign_keys = ON")
        for (ref in listOf(1L, 2L, 3L)) {
            writable().execSQL(
                "INSERT OR IGNORE INTO health_source_records (id, sourceRecordId, recordType, createdAtMs) " +
                    "VALUES (?, ?, 'HEART_RATE', 0)",
                arrayOf<Any?>(ref, "proto-src-$ref"),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    // ---- engine capability ----

    @Test
    fun sqlcipherEngine_supportsUpsertSyntax() {
        val version = queryString("SELECT sqlite_version()")
        Log.i(TAG, "SQLCipher bundled SQLite version: $version")
        // UPSERT (INSERT ... ON CONFLICT ... DO UPDATE) requires SQLite >= 3.24.0. The app always
        // runs SQLCipher's bundled engine, so platform SQLite on minSdk 26 (3.18/3.19) is irrelevant.
        assertTrue(
            "Bundled SQLCipher SQLite ($version) must be >= 3.24.0 for UPSERT syntax",
            versionAtLeast(version, 3, 24),
        )
    }

    // ---- baseline (current production strategy) ----

    @Test
    fun baselineReplace_rotatesRowId_onReingest() =
        runBlocking {
            val ref = 1L
            val ts = 1_000_000L
            dao.replaceAll(listOf(hrEntity(ref, ts, sessionId = null)))

            val firstRowId = dao.getHeartRate(ref, ts)!!.rowId

            // Re-ingest the same natural key with a changed mutable column (reconciler re-tag).
            dao.replaceAll(listOf(hrEntity(ref, ts, sessionId = "sleep-1")))

            val after = dao.getHeartRate(ref, ts)!!
            // REPLACE deletes+reinserts -> rowId rotates. This is the churn Option C removes.
            assertNotEquals("REPLACE must rotate rowId (baseline churn)", firstRowId, after.rowId)
            assertEquals("sessionId must propagate under REPLACE", "sleep-1", after.sessionId)
            assertEquals("re-ingest must not create duplicates", 1, dao.countHeartRate())
        }

    // ---- Room @Upsert candidate ----

    @Test
    fun roomUpsert_reingestWithRowIdZero_doesNotCleanlyUpdateInPlace() =
        runBlocking {
            val ref = 1L
            val ts = 1_000_000L
            dao.upsertAll(listOf(hrEntity(ref, ts, sessionId = null)))
            val firstRowId = dao.getHeartRate(ref, ts)!!.rowId

            val outcome =
                runCatching {
                    dao.upsertAll(listOf(hrEntity(ref, ts, sessionId = "sleep-1")))
                }

            val count = dao.countHeartRate()
            val now = dao.getHeartRate(ref, ts)
            Log.i(
                TAG,
                "Room @Upsert re-ingest (rowId=0): " +
                    "outcome=${outcome.exceptionOrNull()?.javaClass?.simpleName ?: "success"}, " +
                    "count=$count, rowIdBefore=$firstRowId, rowIdAfter=${now?.rowId}, sessionId=${now?.sessionId}",
            )

            // Documented expectation (plan §2.4, Option C): Room @Upsert generated SQL conflicts on
            // the PRIMARY KEY rowId (autoGenerate), so a re-upsert carrying rowId=0 never matches an
            // existing row and either violates the unique (sourceRecordRef, timestampMs) index
            // (SQLiteConstraintException) or duplicates. Either way it is NOT a clean in-place update
            // of a single stable row. Accept both failure shapes here; assert the invariant that
            // matters: no single stable row with the propagated mutable column.
            if (outcome.isFailure) {
                assertTrue(
                    "row must not have been updated in place when @Upsert failed",
                    now?.sessionId == null,
                )
            } else {
                assertTrue(
                    "@Upsert with rowId=0 must not cleanly update a single stable row (got count=$count, " +
                        "sessionId=${now?.sessionId}, rowId=$firstRowId->${now?.rowId})",
                    count > 1 || now?.sessionId == null || now?.rowId != firstRowId,
                )
            }
        }

    // ---- proposed conflict-targeted strategy ----

    @Test
    fun conflictTargeted_newRow_rowIdZeroIngestion_assignsRowId() =
        runBlocking {
            val ref = 1L
            val ts = 1_000_000L
            writable().execSQL(
                conflictTargetedHrSql,
                arrayOf<Any?>(ref, ts, 62, RecordType.RESTING.name, null, null),
            )

            val row = dao.getHeartRate(ref, ts)
            // rowId omitted from the INSERT -> SQLite AUTOINCREMENT assigns it. rowId=0 ingestion works.
            assertTrue("rowId must be auto-assigned > 0", row != null && row.rowId > 0L)
            assertEquals(62, row!!.beatsPerMinute)
            assertEquals(1, dao.countHeartRate())
        }

    @Test
    fun conflictTargeted_identicalReingest_isNoop_preservesRowId() =
        runBlocking {
            val ref = 1L
            val ts = 1_000_000L
            writable().execSQL(conflictTargetedHrSql, arrayOf<Any?>(ref, ts, 62, RecordType.RESTING.name, null, null))
            val firstRowId = dao.getHeartRate(ref, ts)!!.rowId

            // Identical re-ingest: the WHERE predicate evaluates false -> SQLite skips the write.
            writable().execSQL(conflictTargetedHrSql, arrayOf<Any?>(ref, ts, 62, RecordType.RESTING.name, null, null))

            assertEquals("near no-op: changes() must be 0 after identical re-ingest", 0L, changes())
            val after = dao.getHeartRate(ref, ts)!!
            assertEquals("rowId must be stable across identical re-ingest", firstRowId, after.rowId)
            assertEquals(1, dao.countHeartRate())
        }

    @Test
    fun conflictTargeted_changedColumns_updateInPlace_preservingRowId() =
        runBlocking {
            val ref = 1L
            val ts = 1_000_000L
            writable().execSQL(conflictTargetedHrSql, arrayOf<Any?>(ref, ts, 62, RecordType.RESTING.name, null, null))
            val firstRowId = dao.getHeartRate(ref, ts)!!.rowId

            // Reconciler-style re-tag: sessionId + recordType + deviceName all change.
            writable().execSQL(
                conflictTargetedHrSql,
                arrayOf<Any?>(ref, ts, 62, RecordType.SLEEP.name, "sleep-9", "Ring"),
            )

            assertEquals("write must happen when mutable columns differ", 1L, changes())
            val after = dao.getHeartRate(ref, ts)!!
            assertEquals("rowId must be preserved on in-place update", firstRowId, after.rowId)
            assertEquals(RecordType.SLEEP.name, after.recordType)
            assertEquals("sleep-9", after.sessionId)
            assertEquals("Ring", after.deviceName)
            assertEquals(1, dao.countHeartRate())
        }

    @Test
    fun conflictTargeted_hrv_behavesLikeHeartRate() =
        runBlocking {
            val ref = 1L
            val ts = 1_000_000L
            writable().execSQL(
                conflictTargetedHrvSql,
                arrayOf<Any?>(ref, ts, 41.5f, RecordType.SLEEP.name, "sleep-1", null),
            )
            val firstRowId = dao.getHrv(ref, ts)!!.rowId

            // identical re-ingest -> no-op
            writable().execSQL(
                conflictTargetedHrvSql,
                arrayOf<Any?>(ref, ts, 41.5f, RecordType.SLEEP.name, "sleep-1", null),
            )
            assertEquals(0L, changes())
            assertEquals(firstRowId, dao.getHrv(ref, ts)!!.rowId)

            // changed deviceName -> in-place update, stable rowId
            writable().execSQL(
                conflictTargetedHrvSql,
                arrayOf<Any?>(ref, ts, 41.5f, RecordType.SLEEP.name, "sleep-1", "Ring"),
            )
            assertEquals(1L, changes())
            val after = dao.getHrv(ref, ts)!!
            assertEquals(firstRowId, after.rowId)
            assertEquals("Ring", after.deviceName)
            assertEquals(1, dao.countHrv())
        }

    // ---- generated SQL capture ----

    @Test
    fun roomQuery_acceptsUpsertSyntax_andBehavesLikeExecSql() =
        runBlocking {
            val ref = 1L
            val ts = 1_000_000L

            // Room 2.8's @Query parser accepts the UPSERT statement (KSP generated a prepared
            // statement, not a compile error) -- see UpsertPrototypeDao.conflictTargetedUpsert.
            // Prove the generated statement path behaves identically to raw execSQL.
            dao.conflictTargetedUpsert(ref, ts, 62, RecordType.RESTING.name, null, null)
            val firstRowId = dao.getHeartRate(ref, ts)!!.rowId

            // identical re-ingest -> near no-op (WHERE predicate false), rowId stable.
            // Row state is the load-bearing assertion (same content, same rowId, no duplicate).
            // `changes()` is connection-scoped and can read a stale counter on a different pooled
            // connection, so assert the observable rows rather than the raw counter.
            dao.conflictTargetedUpsert(ref, ts, 62, RecordType.RESTING.name, null, null)
            val afterIdentical = dao.getHeartRate(ref, ts)!!
            assertEquals(firstRowId, afterIdentical.rowId)
            assertEquals(RecordType.RESTING.name, afterIdentical.recordType)
            assertEquals(1, dao.countHeartRate())

            // reconciler re-tag -> in-place update, stable rowId
            dao.conflictTargetedUpsert(ref, ts, 62, RecordType.SLEEP.name, "sleep-7", "Ring")
            val after = dao.getHeartRate(ref, ts)!!
            assertEquals(firstRowId, after.rowId)
            assertEquals(RecordType.SLEEP.name, after.recordType)
            assertEquals("sleep-7", after.sessionId)
            assertEquals("Ring", after.deviceName)
            assertEquals(1, dao.countHeartRate())
        }

    @Test
    fun capturesRoomUpsertGeneratedSql() {
        val generated = roomUpsertGeneratedSql(dao)
        Log.i(TAG, "Room @Upsert generated SQL: ${generated ?: "NOT FOUND (impl shape changed)"}")
        assertTrue(
            "must capture the generated @Upsert SQL from UpsertPrototypeDao_Impl",
            generated != null && generated.contains("INSERT INTO") && generated.contains("UPDATE"),
        )
        Log.i(
            TAG,
            "generated SQL conflict target analysis: " +
                if (generated!!.contains("WHERE `rowId` = ?")) {
                    "Room 2.8 @Upsert = INSERT(nulllif(rowId,0)) + UPDATE WHERE rowId -> " +
                        "conflict target is PRIMARY KEY rowId, NOT the secondary unique " +
                        "(sourceRecordRef, timestampMs) index; rowId=0 re-ingest cannot match existing rows"
                } else {
                    "conflict target is NOT rowId -> inspect: $generated"
                },
        )
    }

    // ---- helpers ----

    private fun writable(): SupportSQLiteDatabase = db.openHelper.writableDatabase

    private fun changes(): Long = queryLong("SELECT changes()")

    private fun queryLong(statement: String): Long =
        writable().query(statement).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun queryString(statement: String): String =
        writable().query(statement).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun versionAtLeast(
        version: String,
        major: Int,
        minor: Int,
    ): Boolean {
        val parts = version.split(".").mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
        if (parts.size < 2) return false
        return parts[0] > major || (parts[0] == major && parts[1] >= minor)
    }

    private fun hrEntity(
        ref: Long,
        timestampMs: Long,
        sessionId: String?,
    ) = HeartRateRecordEntity(
        sourceRecordRef = ref,
        timestampMs = timestampMs,
        beatsPerMinute = 62,
        recordType = RecordType.RESTING.name,
        sessionId = sessionId,
        deviceName = null,
    )

    /**
     * Reflects the Room-generated DAO implementation to read the compiled `@Upsert` statements.
     * Room 2.8 models `@Upsert` as an [EntityUpsertAdapter] holding two adapters whose protected
     * `createQuery()` methods return the INSERT and the UPDATE (conflict-target) SQL. Reading them
     * answers "which conflict target does @Upsert use" from the compiled artifact:
     * the UPDATE is keyed on the PRIMARY KEY `rowId` (`WHERE `rowId` = ?`), not the secondary
     * unique (sourceRecordRef, timestampMs) index.
     */
    private fun roomUpsertGeneratedSql(daoInstance: UpsertPrototypeDao): String? =
        try {
            val adapterField =
                daoInstance.javaClass.declaredFields
                    .firstOrNull { it.name.contains("upsertAdapterOfHeartRate") }
                    ?: return null
            adapterField.isAccessible = true
            val adapter = adapterField.get(daoInstance) ?: return null

            val insertAdapterField = adapter.javaClass.getDeclaredField("entityInsertAdapter")
            insertAdapterField.isAccessible = true
            val insertSql = invokeCreateQuery(insertAdapterField.get(adapter)) ?: return null

            val updateAdapterField = adapter.javaClass.getDeclaredField("updateAdapter")
            updateAdapterField.isAccessible = true
            val updateSql = invokeCreateQuery(updateAdapterField.get(adapter)) ?: return null

            // Pair explicitly: protects against reordering and documents both halves @Upsert emits.
            "$insertSql || $updateSql"
        } catch (e: Throwable) {
            Log.w(TAG, "could not reflect Room generated upsert SQL", e)
            null
        }

    private fun invokeCreateQuery(adapter: Any): String? =
        try {
            val createQuery =
                adapter.javaClass.getDeclaredMethod("createQuery").apply { isAccessible = true }
            createQuery.invoke(adapter) as? String
        } catch (e: Throwable) {
            Log.w(TAG, "could not invoke createQuery on ${adapter.javaClass.simpleName}", e)
            null
        }

    private companion object {
        const val TAG = "UpsertConflictStrategy"
    }
}
