package app.readylytics.health.data.audit

import app.readylytics.health.data.local.dao.AuditEventDao
import app.readylytics.health.data.local.entity.AuditEventEntity
import app.readylytics.health.domain.audit.AuditEvent
import app.readylytics.health.domain.audit.AuditTrailRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomAuditTrailRepository
    @Inject
    constructor(
        private val dao: AuditEventDao,
    ) : AuditTrailRepository {
        override suspend fun append(event: AuditEvent) {
            dao.insert(AuditEventEntity.fromDomain(event))
        }

        override fun observeRecent(limit: Int): Flow<List<AuditEvent>> =
            dao
                .observeRecent(limit)
                .map { events -> events.map { it.toDomain() } }
                .distinctUntilChanged()
    }
