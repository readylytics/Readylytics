package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.SourceRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.model.domain.sync.SourceMetadata

internal data class ResolvedSource(
    val ref: Long,
    val existing: HealthSourceRecordEntity?,
)

/**
 * PERF-001: resolves every parent identity of one Health Connect page in two statements per 250-id
 * chunk (one `IN` lookup, one `INSERT OR IGNORE` of the missing ones) instead of the previous
 * `getOrCreateSourceRef` round trip per parent — which cost roughly one million transactions for a
 * million-parent history. Chunk width keeps the bind-variable count an order of magnitude under the
 * 999 floor.
 */
internal object SourceRefResolver {
    const val LOOKUP_CHUNK = 250

    suspend fun resolveAll(
        dao: SourceRecordDao,
        sources: List<SourceMetadata>,
    ): Map<String, ResolvedSource> {
        if (sources.isEmpty()) return emptyMap()
        val bySourceId = sources.associateBy { it.sourceId }
        val existing = LinkedHashMap<String, HealthSourceRecordEntity>(bySourceId.size)
        bySourceId.keys.chunked(LOOKUP_CHUNK).forEach { chunk ->
            dao.getSourcesByRecordIds(chunk).forEach { existing[it.sourceRecordId] = it }
        }

        val missing = bySourceId.values.filter { it.sourceId !in existing }
        if (missing.isNotEmpty()) {
            missing.chunked(LOOKUP_CHUNK).forEach { chunk ->
                dao.insertIgnoreAll(chunk.map { it.toNewEntity() })
            }
            missing.map { it.sourceId }.chunked(LOOKUP_CHUNK).forEach { chunk ->
                dao.getSourcesByRecordIds(chunk).forEach { inserted ->
                    // Newly created rows have no prior revision/bounds, so `existing` stays null for
                    // them: the caller must treat them as changed and write authoritative metadata.
                    existing[inserted.sourceRecordId] = inserted
                }
            }
        }

        // Hashed once rather than re-scanned per source: a fresh-import page of one-sample parents
        // is ~5_000 payloads that are virtually all missing, so a linear `missing.any { ... }` here
        // would cost ~25M comparisons inside the writer transaction -- exactly the O(n*m) shape
        // this class exists to remove.
        val missingIds = missing.mapTo(HashSet(missing.size)) { it.sourceId }
        return bySourceId.mapValues { (sourceId, _) ->
            val row = existing[sourceId] ?: error("Failed to resolve source ref for $sourceId")
            ResolvedSource(ref = row.id, existing = if (sourceId in missingIds) null else row)
        }
    }

    private fun SourceMetadata.toNewEntity() =
        HealthSourceRecordEntity(
            sourceRecordId = sourceId,
            recordType = recordType,
            createdAtMs = startMs,
            originPackage = originPackage,
            recordStartMs = startMs,
            recordEndExclusiveMs = endExclusiveMs,
            lastModifiedMs = lastModifiedMs,
            metadataState = "AUTHORITATIVE",
            sourceRevision = 0L,
        )
}
