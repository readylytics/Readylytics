package app.readylytics.health.data.local

import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_10_11
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_11_12
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_12_13
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_13_14
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_14_15
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_15_16
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_16_17
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_17_18
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_18_19
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_19_20
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_1_2
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_20_21
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_21_22
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_22_23
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_23_24
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_24_25
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_25_24
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_25_26
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_26_27
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_27_28
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_28_29
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_29_30
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_2_3
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_3_4
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_4_5
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_5_6
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_6_7
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_7_8
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_8_9
import app.readylytics.health.data.local.DatabaseMigrations.MIGRATION_9_10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationTest {
    @Test
    fun `all migrations are registered in sequential order`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        assertEquals("Expected 29 migrations (1..30)", 29, migrations.size)

        val expectedPairs = (1..29).map { it to it + 1 }
        val actualPairs = migrations.map { it.startVersion to it.endVersion }
        assertEquals(expectedPairs, actualPairs)
    }

    @Test
    fun `no gaps in migration chain`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        for (i in 0 until migrations.size - 1) {
            val current = migrations[i]
            val next = migrations[i + 1]
            assertEquals(
                "Gap between migration ${current.endVersion} and ${next.startVersion}",
                current.endVersion,
                next.startVersion,
            )
        }
    }

    @Test
    fun `migration chain starts at version 1`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        assertEquals(1, migrations.first().startVersion)
    }

    @Test
    fun `migration chain ends at DATABASE_VERSION`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        assertEquals(HealthDatabase.DATABASE_VERSION, migrations.last().endVersion)
    }

    @Test
    fun `database version matches latest migration end version`() {
        val migrations = DatabaseMigrations.all.filter { it.startVersion < it.endVersion }
        val latestEndVersion = migrations.last().endVersion
        assertEquals(
            "HealthDatabase.DATABASE_VERSION must match the latest migration end version",
            latestEndVersion,
            HealthDatabase.DATABASE_VERSION,
        )
    }

    @Test
    fun `MIGRATION_22_23 adds baseline_calculated_at_date column`() {
        assertEquals(22, MIGRATION_22_23.startVersion)
        assertEquals(23, MIGRATION_22_23.endVersion)
    }

    @Test
    fun `MIGRATION_22_23 is registered in all array`() {
        val found = DatabaseMigrations.all.any { it === MIGRATION_22_23 }
        assertTrue("MIGRATION_22_23 must be registered in DatabaseMigrations.all", found)
    }

    @Test
    fun `MIGRATION_1_2 version range is correct`() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
    }

    @Test
    fun `MIGRATION_2_3 version range is correct`() {
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }

    @Test
    fun `MIGRATION_3_4 version range is correct`() {
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
    }

    @Test
    fun `MIGRATION_4_5 version range is correct`() {
        assertEquals(4, MIGRATION_4_5.startVersion)
        assertEquals(5, MIGRATION_4_5.endVersion)
    }

    @Test
    fun `MIGRATION_5_6 version range is correct`() {
        assertEquals(5, MIGRATION_5_6.startVersion)
        assertEquals(6, MIGRATION_5_6.endVersion)
    }

    @Test
    fun `MIGRATION_6_7 version range is correct`() {
        assertEquals(6, MIGRATION_6_7.startVersion)
        assertEquals(7, MIGRATION_6_7.endVersion)
    }

    @Test
    fun `MIGRATION_7_8 version range is correct`() {
        assertEquals(7, MIGRATION_7_8.startVersion)
        assertEquals(8, MIGRATION_7_8.endVersion)
    }

    @Test
    fun `MIGRATION_8_9 version range is correct`() {
        assertEquals(8, MIGRATION_8_9.startVersion)
        assertEquals(9, MIGRATION_8_9.endVersion)
    }

    @Test
    fun `MIGRATION_9_10 version range is correct`() {
        assertEquals(9, MIGRATION_9_10.startVersion)
        assertEquals(10, MIGRATION_9_10.endVersion)
    }

    @Test
    fun `MIGRATION_10_11 version range is correct`() {
        assertEquals(10, MIGRATION_10_11.startVersion)
        assertEquals(11, MIGRATION_10_11.endVersion)
    }

    @Test
    fun `MIGRATION_11_12 version range is correct`() {
        assertEquals(11, MIGRATION_11_12.startVersion)
        assertEquals(12, MIGRATION_11_12.endVersion)
    }

    @Test
    fun `MIGRATION_12_13 version range is correct`() {
        assertEquals(12, MIGRATION_12_13.startVersion)
        assertEquals(13, MIGRATION_12_13.endVersion)
    }

    @Test
    fun `MIGRATION_13_14 version range is correct`() {
        assertEquals(13, MIGRATION_13_14.startVersion)
        assertEquals(14, MIGRATION_13_14.endVersion)
    }

    @Test
    fun `MIGRATION_14_15 version range is correct`() {
        assertEquals(14, MIGRATION_14_15.startVersion)
        assertEquals(15, MIGRATION_14_15.endVersion)
    }

    @Test
    fun `MIGRATION_15_16 version range is correct`() {
        assertEquals(15, MIGRATION_15_16.startVersion)
        assertEquals(16, MIGRATION_15_16.endVersion)
    }

    @Test
    fun `MIGRATION_16_17 version range is correct`() {
        assertEquals(16, MIGRATION_16_17.startVersion)
        assertEquals(17, MIGRATION_16_17.endVersion)
    }

    @Test
    fun `MIGRATION_17_18 version range is correct`() {
        assertEquals(17, MIGRATION_17_18.startVersion)
        assertEquals(18, MIGRATION_17_18.endVersion)
    }

    @Test
    fun `MIGRATION_18_19 version range is correct`() {
        assertEquals(18, MIGRATION_18_19.startVersion)
        assertEquals(19, MIGRATION_18_19.endVersion)
    }

    @Test
    fun `MIGRATION_19_20 version range is correct`() {
        assertEquals(19, MIGRATION_19_20.startVersion)
        assertEquals(20, MIGRATION_19_20.endVersion)
    }

    @Test
    fun `MIGRATION_20_21 version range is correct`() {
        assertEquals(20, MIGRATION_20_21.startVersion)
        assertEquals(21, MIGRATION_20_21.endVersion)
    }

    @Test
    fun `MIGRATION_21_22 version range is correct`() {
        assertEquals(21, MIGRATION_21_22.startVersion)
        assertEquals(22, MIGRATION_21_22.endVersion)
    }

    @Test
    fun `MIGRATION_23_24 version range is correct`() {
        assertEquals(23, MIGRATION_23_24.startVersion)
        assertEquals(24, MIGRATION_23_24.endVersion)
    }

    @Test
    fun `MIGRATION_23_24 is registered in all array`() {
        val found = DatabaseMigrations.all.any { it === MIGRATION_23_24 }
        assertTrue("MIGRATION_23_24 must be registered in DatabaseMigrations.all", found)
    }

    @Test
    fun `MIGRATION_24_25 version range is correct`() {
        assertEquals(24, MIGRATION_24_25.startVersion)
        assertEquals(25, MIGRATION_24_25.endVersion)
    }

    @Test
    fun `MIGRATION_24_25 is registered in all array`() {
        val found = DatabaseMigrations.all.any { it === MIGRATION_24_25 }
        assertTrue("MIGRATION_24_25 must be registered in DatabaseMigrations.all", found)
    }

    @Test
    fun `MIGRATION_25_24 version range is correct`() {
        assertEquals(25, MIGRATION_25_24.startVersion)
        assertEquals(24, MIGRATION_25_24.endVersion)
    }

    @Test
    fun `MIGRATION_25_24 is registered in all array`() {
        val found = DatabaseMigrations.all.any { it === MIGRATION_25_24 }
        assertTrue("MIGRATION_25_24 must be registered in DatabaseMigrations.all", found)
    }

    @Test
    fun `MIGRATION_25_26 version range is correct`() {
        assertEquals(25, MIGRATION_25_26.startVersion)
        assertEquals(26, MIGRATION_25_26.endVersion)
    }

    @Test
    fun `MIGRATION_25_26 is registered in all array`() {
        val found = DatabaseMigrations.all.any { it === MIGRATION_25_26 }
        assertTrue("MIGRATION_25_26 must be registered in DatabaseMigrations.all", found)
    }

    @Test
    fun `MIGRATION_26_27 version range is correct`() {
        assertEquals(26, MIGRATION_26_27.startVersion)
        assertEquals(27, MIGRATION_26_27.endVersion)
    }

    @Test
    fun `MIGRATION_26_27 is registered in all array`() {
        val found = DatabaseMigrations.all.any { it === MIGRATION_26_27 }
        assertTrue("MIGRATION_26_27 must be registered in DatabaseMigrations.all", found)
    }

    @Test
    fun `MIGRATION_27_28 version range is correct`() {
        assertEquals(27, MIGRATION_27_28.startVersion)
        assertEquals(28, MIGRATION_27_28.endVersion)
    }

    @Test
    fun `MIGRATION_27_28 is registered in all array`() {
        val found = DatabaseMigrations.all.any { it === MIGRATION_27_28 }
        assertTrue("MIGRATION_27_28 must be registered in DatabaseMigrations.all", found)
    }

    @Test
    fun `MIGRATION_28_29 version range is correct`() {
        assertEquals(28, MIGRATION_28_29.startVersion)
        assertEquals(29, MIGRATION_28_29.endVersion)
    }

    @Test
    fun `MIGRATION_28_29 is registered in all array`() {
        val found = DatabaseMigrations.all.any { it === MIGRATION_28_29 }
        assertTrue("MIGRATION_28_29 must be registered in DatabaseMigrations.all", found)
    }

    @Test
    fun `MIGRATION_29_30 version range is correct`() {
        assertEquals(29, MIGRATION_29_30.startVersion)
        assertEquals(30, MIGRATION_29_30.endVersion)
    }

    @Test
    fun `MIGRATION_29_30 is registered in all array`() {
        val found = DatabaseMigrations.all.any { it === MIGRATION_29_30 }
        assertTrue("MIGRATION_29_30 must be registered in DatabaseMigrations.all", found)
    }
}
