package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.DirtyRangeStore
import app.readylytics.health.core.model.domain.sync.DirtyTicket

class FakeDirtyRangeStore(
    private val tickets: List<DirtyTicket> = emptyList(),
) : DirtyRangeStore {
    override suspend fun pending(limit: Int): List<DirtyTicket> = tickets.take(limit)
}
