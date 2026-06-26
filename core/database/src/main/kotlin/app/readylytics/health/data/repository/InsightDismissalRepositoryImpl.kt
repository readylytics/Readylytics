package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.InsightDismissalDao
import app.readylytics.health.data.local.entity.InsightDismissalEntity
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.repository.InsightDismissalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InsightDismissalRepositoryImpl
    @Inject
    constructor(
        private val dao: InsightDismissalDao,
    ) : InsightDismissalRepository {
        override suspend fun dismiss(
            dateMidnightMs: Long,
            type: InsightType,
        ) {
            dao.dismiss(InsightDismissalEntity(dateMidnightMs, type.name))
        }

        override suspend fun restoreAllForDate(dateMidnightMs: Long) {
            dao.restoreAllForDate(dateMidnightMs)
        }

        override fun observeForDate(dateMidnightMs: Long): Flow<Set<InsightType>> =
            dao
                .observeForDate(dateMidnightMs)
                .map { entities ->
                    entities
                        .mapNotNull { entity ->
                            runCatching { InsightType.valueOf(entity.type) }.getOrNull()
                        }.toSet()
                }.distinctUntilChanged()
    }
