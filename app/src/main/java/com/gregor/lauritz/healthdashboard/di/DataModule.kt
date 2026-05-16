package com.gregor.lauritz.healthdashboard.di

import com.gregor.lauritz.healthdashboard.data.device.HealthDeviceRepository
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideHealthDeviceRepository(
        sleepSessionDao: SleepSessionDao,
        heartRateDao: HeartRateDao,
        hrvDao: HrvDao,
        workoutDao: WorkoutDao,
        healthConnectRepository: HealthConnectRepository,
    ): HealthDeviceRepository =
        HealthDeviceRepository(
            sleepSessionDao,
            heartRateDao,
            hrvDao,
            workoutDao,
            healthConnectRepository,
        )
}
