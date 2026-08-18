package app.readylytics.health.data.backup

import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.AppThemeProto
import app.readylytics.health.data.preferences.BackupSchedule
import app.readylytics.health.data.preferences.BackupScheduleProto
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.PhysiologyProfileProto
import app.readylytics.health.data.preferences.SyncPreference
import app.readylytics.health.data.preferences.SyncPreferenceProto
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `LocalBackupManager` writes the **domain** enum name (`prefs.syncPreference.name` → `"BY_TIME"`)
 * while `LocalRestoreManager` parsed it with `SyncPreferenceProto.valueOf(...)`, which expects the
 * prefixed proto name (`"SYNC_BY_TIME"`). Every value of all four enums therefore threw, the
 * surrounding catch logged a warning, and the preference silently reverted to its default —
 * including `physiologyProfile`, which feeds `snapshotProfile`/`hrvSigmaPrior` in the scoring
 * engine, so a restored user's readiness scores changed with no visible cause.
 *
 * Found on-device during the Step 05b verification; no unit test asserted that preference *values*
 * survive a backup→restore round trip, only that restore completed.
 *
 * These tests pin the exact strings the backup writer emits. If a proto enum gains a value or a
 * prefix changes, the "every domain value resolves" cases fail rather than silently regressing to
 * defaults again. These call the production `resolveProtoEnum` directly — not a copy of it.
 */
class RestorePreferenceEnumRoundTripTest {
    @Test
    fun everySyncPreferenceValueSurvivesTheRoundTrip() {
        SyncPreference.entries.forEach { domain ->
            val resolved = resolveProtoEnum(domain.name, "SYNC_", SyncPreferenceProto::valueOf)
            assertNotNull(resolved, "backup writes '${domain.name}'; restore must resolve it")
            assertEquals("SYNC_${domain.name}", resolved.name)
        }
    }

    @Test
    fun everyAppThemeValueSurvivesTheRoundTrip() {
        AppTheme.entries.forEach { domain ->
            val resolved = resolveProtoEnum(domain.name, "THEME_", AppThemeProto::valueOf)
            assertNotNull(resolved, "backup writes '${domain.name}'; restore must resolve it")
            assertEquals("THEME_${domain.name}", resolved.name)
        }
    }

    @Test
    fun everyBackupScheduleValueSurvivesTheRoundTrip() {
        BackupSchedule.entries.forEach { domain ->
            val resolved = resolveProtoEnum(domain.name, "BACKUP_", BackupScheduleProto::valueOf)
            assertNotNull(resolved, "backup writes '${domain.name}'; restore must resolve it")
            assertEquals("BACKUP_${domain.name}", resolved.name)
        }
    }

    /** The one that changes scoring output, so it gets its own explicit assertion. */
    @Test
    fun everyPhysiologyProfileValueSurvivesTheRoundTrip() {
        PhysiologyProfile.entries.forEach { domain ->
            val resolved = resolveProtoEnum(domain.name, "PROFILE_", PhysiologyProfileProto::valueOf)
            assertNotNull(
                resolved,
                "backup writes '${domain.name}'; losing it silently changes the user's scores",
            )
            assertEquals("PROFILE_${domain.name}", resolved.name)
        }
    }

    /** Already-prefixed input must still resolve, so the reader tolerates either encoding. */
    @Test
    fun alreadyPrefixedProtoNamesAlsoResolve() {
        assertEquals(
            SyncPreferenceProto.SYNC_ALWAYS,
            resolveProtoEnum("SYNC_ALWAYS", "SYNC_", SyncPreferenceProto::valueOf),
        )
        assertEquals(
            PhysiologyProfileProto.PROFILE_ATHLETE,
            resolveProtoEnum("PROFILE_ATHLETE", "PROFILE_", PhysiologyProfileProto::valueOf),
        )
    }

    /** Genuinely unknown values must still yield null so the caller can log and skip. */
    @Test
    fun unknownValuesResolveToNull() {
        assertNull(resolveProtoEnum("NOT_A_MODE", "SYNC_", SyncPreferenceProto::valueOf))
        assertNull(resolveProtoEnum("", "PROFILE_", PhysiologyProfileProto::valueOf))
    }
}
