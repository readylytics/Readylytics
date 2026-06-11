package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.InsightDismissalDao
import com.gregor.lauritz.healthdashboard.data.local.entity.InsightDismissalEntity
import com.gregor.lauritz.healthdashboard.domain.model.InsightType
import com.gregor.lauritz.healthdashboard.domain.repository.InsightDismissalRepository
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
