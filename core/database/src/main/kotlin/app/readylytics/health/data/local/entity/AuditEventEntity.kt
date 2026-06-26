package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.readylytics.health.domain.audit.AuditEvent
import java.time.Instant

@Entity(
    tableName = "audit_events",
    indices = [Index(value = ["occurredAtEpochMs"], name = "index_audit_events_occurredAtEpochMs")],
)
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,
    val occurredAtEpochMs: Long,
    val detail: String?,
) {
    fun toDomain(): AuditEvent =
        AuditEvent(
            id = id,
            type = AuditEvent.Type.entries.first { it.storageKey == type },
            occurredAt = Instant.ofEpochMilli(occurredAtEpochMs),
            detail = detail,
        )

    companion object {
        fun fromDomain(event: AuditEvent): AuditEventEntity =
            AuditEventEntity(
                id = event.id,
                type = event.type.storageKey,
                occurredAtEpochMs = event.occurredAt.toEpochMilli(),
                detail = event.detail,
            )
    }
}
