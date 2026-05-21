package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.domain.repository.WorkoutData
import com.gregor.lauritz.healthdashboard.domain.repository.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutRepositoryImpl
    @Inject
    constructor(
        private val dao: WorkoutDao,
    ) : WorkoutRepository {
        override suspend fun getById(id: String): WorkoutData? = dao.getById(id)?.let { mapToDomain(it) }

        override suspend fun getEarliestWorkoutTimestamp(): Long? = dao.getEarliestWorkoutTimestamp()

        override fun observeSince(fromMs: Long): Flow<List<WorkoutData>> =
            dao.observeSince(fromMs).map { list ->
                list.map { mapToDomain(it) }
            }

        private fun mapToDomain(entity: WorkoutRecordEntity): WorkoutData =
            WorkoutData(
                id = entity.id,
                startTime = entity.startTime,
                endTime = entity.endTime,
                exerciseType = entity.exerciseType,
                durationMinutes = entity.durationMinutes,
                zone1Minutes = entity.zone1Minutes,
                zone2Minutes = entity.zone2Minutes,
                zone3Minutes = entity.zone3Minutes,
                zone4Minutes = entity.zone4Minutes,
                zone5Minutes = entity.zone5Minutes,
                trimp = entity.trimp,
                avgHr = entity.avgHr,
                deviceName = entity.deviceName,
            )
    }
