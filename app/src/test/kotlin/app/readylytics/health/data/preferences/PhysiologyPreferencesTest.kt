package app.readylytics.health.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class PhysiologyPreferencesTest {
    private lateinit var dataStore: DataStore<UserPreferencesProto>
    private lateinit var physiologyPreferences: PhysiologyPreferences
    private val fixedClock =
        Clock.fixed(
            Instant.parse("2026-07-08T12:00:00Z"),
            ZoneId.systemDefault(),
        )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "physiology_prefs_${System.nanoTime()}.pb"
        dataStore =
            DataStoreFactory.create(
                serializer = UserPreferencesSerializer,
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.dataStoreFile(fileName) },
            )
        physiologyPreferences = PhysiologyPreferences(dataStore, fixedClock)
    }

    @Test
    fun `updateBirthday persists date, age, and sets isBirthdayConfigured to true`() =
        runTest {
            val birthDate = LocalDate.of(1990, 6, 15)
            // Initially, isBirthdayConfigured should be false
            var proto = dataStore.data.first()
            assertEquals(false, proto.isBirthdayConfigured)

            physiologyPreferences.updateBirthday(birthDate)

            proto = dataStore.data.first()
            assertEquals(15, proto.birthDay)
            assertEquals(6, proto.birthMonth)
            assertEquals(1990, proto.birthYear)
            assertEquals(36, proto.age) // 2026 - 1990 = 36 years (since 2026-07-08 is after 1990-06-15)
            assertEquals(true, proto.isBirthdayConfigured)
        }

    @Test
    fun `migrateTrimpDefaultsIfNeeded normalizes unset 0_0 to 1_0 and sets flag`() =
        runTest {
            // Unset proto default rasCalibration is 0.0f
            physiologyPreferences.migrateTrimpDefaultsIfNeeded()

            val proto = dataStore.data.first()
            assertEquals(1.0f, proto.rasCalibration, 0f)
            assertEquals(true, proto.trimpNormalizationMigrated)
        }

    @Test
    fun `migrateTrimpDefaultsIfNeeded preserves stored nonzero multiplier 1_35 and sets flag`() =
        runTest {
            physiologyPreferences.updateBanisterMultiplier(1.35f)

            physiologyPreferences.migrateTrimpDefaultsIfNeeded()

            val proto = dataStore.data.first()
            assertEquals(1.35f, proto.rasCalibration, 0f)
            assertEquals(true, proto.trimpNormalizationMigrated)
        }

    @Test
    fun `migrateTrimpDefaultsIfNeeded preserves a customized multiplier`() =
        runTest {
            physiologyPreferences.updateBanisterMultiplier(1.50f)

            physiologyPreferences.migrateTrimpDefaultsIfNeeded()

            val proto = dataStore.data.first()
            assertEquals(1.50f, proto.rasCalibration, 0f)
            assertEquals(true, proto.trimpNormalizationMigrated)
        }

    @Test
    fun `migrateTrimpDefaultsIfNeeded does not re-run once flagged`() =
        runTest {
            physiologyPreferences.updateBanisterMultiplier(1.35f)
            physiologyPreferences.migrateTrimpDefaultsIfNeeded()
            // Simulate a post-migration user change back to an old default value.
            physiologyPreferences.updateBanisterMultiplier(1.35f)

            physiologyPreferences.migrateTrimpDefaultsIfNeeded()

            val proto = dataStore.data.first()
            assertEquals(1.35f, proto.rasCalibration, 0f)
            assertEquals(true, proto.trimpNormalizationMigrated)
        }

    @Test
    fun `migrateTrimpDefaultsIfNeeded leaves cheng beta and itrimp b unchanged while preserving banister`() =
        runTest {
            physiologyPreferences.updateChengBeta(0.09f)
            physiologyPreferences.updateItrimB(2.1f)
            physiologyPreferences.updateBanisterMultiplier(1.75f)

            physiologyPreferences.migrateTrimpDefaultsIfNeeded()

            val proto = dataStore.data.first()
            assertEquals(0.09f, proto.chengBeta, 0f)
            assertEquals(2.1f, proto.itrimpB, 0f)
            assertEquals(1.75f, proto.rasCalibration, 0f)
            assertEquals(true, proto.trimpNormalizationMigrated)
        }

    @Test
    fun `updatePhysiologyProfile sets banisterMultiplier to 1_0 for all profile types`() =
        runTest {
            for (profile in PhysiologyProfile.entries) {
                assertEquals(1.0f, profile.banisterMultiplier, 0f)
                physiologyPreferences.updatePhysiologyProfile(profile)
                val proto = dataStore.data.first()
                assertEquals(1.0f, proto.rasCalibration, 0f)
                assertEquals(profile.defaultChengBeta, proto.chengBeta, 0f)
                assertEquals(profile.defaultItrimB, proto.itrimpB, 0f)
            }
        }

    @Test
    fun `updateResidualFatigueEnabled persists the toggle`() =
        runTest {
            physiologyPreferences.updateResidualFatigueEnabled(false)
            var proto = dataStore.data.first()
            assertEquals(false, proto.residualFatigueEnabled)

            physiologyPreferences.updateResidualFatigueEnabled(true)
            proto = dataStore.data.first()
            assertEquals(true, proto.residualFatigueEnabled)
        }

    @Test
    fun `updateResidualFatigueHalfLifeHours persists in-range values and clamps out-of-range`() =
        runTest {
            physiologyPreferences.updateResidualFatigueHalfLifeHours(48f)
            var proto = dataStore.data.first()
            assertEquals(48f, proto.residualFatigueHalfLifeHours, 0f)

            physiologyPreferences.updateResidualFatigueHalfLifeHours(2f)
            proto = dataStore.data.first()
            assertEquals(6f, proto.residualFatigueHalfLifeHours, 0f)

            physiologyPreferences.updateResidualFatigueHalfLifeHours(120f)
            proto = dataStore.data.first()
            assertEquals(96f, proto.residualFatigueHalfLifeHours, 0f)
        }

    @Test
    fun `updateResidualFatigueGain persists in-range values and clamps out-of-range`() =
        runTest {
            physiologyPreferences.updateResidualFatigueGain(2.5f)
            var proto = dataStore.data.first()
            assertEquals(2.5f, proto.residualFatigueGain, 0f)

            physiologyPreferences.updateResidualFatigueGain(0.05f)
            proto = dataStore.data.first()
            assertEquals(0.1f, proto.residualFatigueGain, 0f)

            physiologyPreferences.updateResidualFatigueGain(10f)
            proto = dataStore.data.first()
            assertEquals(5.0f, proto.residualFatigueGain, 0f)
        }
}
