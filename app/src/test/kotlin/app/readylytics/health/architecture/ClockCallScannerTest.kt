package app.readylytics.health.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockCallScannerTest {
    @Test
    fun `LocalDate now with no args is flagged as violation`() {
        val source = "val today = LocalDate.now()"
        val violations = ClockCallScanner.violations(source)
        assertEquals(listOf("LocalDate.now()"), violations)
    }

    @Test
    fun `LocalDate now with zoneId is flagged as violation`() {
        val source = "val today = LocalDate.now(zoneId)"
        val violations = ClockCallScanner.violations(source)
        assertEquals(listOf("LocalDate.now(zoneId)"), violations)
    }

    @Test
    fun `aliased import resolves to normalized type identity`() {
        val source =
            """
            import java.time.LocalDate as LD
            fun test() {
                val today = LD.now(zoneId)
            }
            """.trimIndent()
        val violations = ClockCallScanner.violations(source)
        assertEquals(listOf("LocalDate.now(zoneId)"), violations)
    }

    @Test
    fun `fully qualified LocalDate now with zoneId is normalized`() {
        val source = "val today = java.time.LocalDate.now(zoneId)"
        val violations = ClockCallScanner.violations(source)
        assertEquals(listOf("LocalDate.now(zoneId)"), violations)
    }

    @Test
    fun `LocalDate now with explicit clock is nonviolation`() {
        val source =
            """
            val today1 = LocalDate.now(clock)
            val today2 = LocalDate.now(clock.withZone(zoneId))
            """.trimIndent()
        val violations = ClockCallScanner.violations(source)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `calls inside comments are ignored`() {
        val source =
            """
            // val today = LocalDate.now(zoneId)
            /*
             * val x = LocalDate.now()
             * val y = System.currentTimeMillis()
             */
            """.trimIndent()
        val violations = ClockCallScanner.violations(source)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `calls inside string literals are ignored`() {
        val source =
            """
            val s1 = "LocalDate.now(zoneId)"
            val s2 = ""${'"'}
                Instant.now()
                System.currentTimeMillis()
            ""${'"'}
            """.trimIndent()
        val violations = ClockCallScanner.violations(source)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `Instant now and System currentTimeMillis are flagged`() {
        val source =
            """
            val inst = Instant.now()
            val millis = System.currentTimeMillis()
            """.trimIndent()
        val violations = ClockCallScanner.violations(source)
        assertEquals(listOf("Instant.now()", "System.currentTimeMillis()"), violations)
    }

    @Test
    fun `clock instant and clock millis pass`() {
        val source =
            """
            val inst = clock.instant()
            val millis = clock.millis()
            """.trimIndent()
        val violations = ClockCallScanner.violations(source)
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `operational Clock system construction and constructor defaults are flagged`() {
        val source =
            """
            class MyService(
                private val clock: Clock = Clock.systemDefaultZone(),
            ) {
                fun bad() {
                    val c = Clock.systemDefaultZone()
                }
            }
            """.trimIndent()
        val violations = ClockCallScanner.violations(source)
        assertEquals(listOf("Clock.systemDefaultZone()", "Clock.systemDefaultZone()"), violations)
    }

    @Test
    fun `aliased clock construction and field defaults are violations`() {
        val source = "import java.time.Clock as TimeSource\nval time: TimeSource = TimeSource.systemUTC()"
        assertEquals(listOf("Clock.systemUTC()"), ClockCallScanner.violations(source))
    }

    @Test
    fun `typed clock with another name passes but a zone containing clock does not`() {
        assertTrue(ClockCallScanner.violations("fun day(time: Clock) = LocalDate.now(time.withZone(zoneId))").isEmpty())
        assertEquals(listOf("LocalDate.now(zoneId)"), ClockCallScanner.violations("LocalDate.now(clockZone)"))
    }

    @Test
    fun `commented imports do not create aliases and nested comments are ignored`() {
        val source =
            "// import java.time.LocalDate as Date\nval x = Date.now()\n" +
                "/* outer /* LocalTime.now() */ ZonedDateTime.now(zoneId) */"
        assertTrue(ClockCallScanner.violations(source).isEmpty())
    }

    @Test
    fun `all time types and clock system forms are normalized`() {
        val source =
            "LocalTime.now(zoneId)\nZonedDateTime.now()\n" +
                "java.time.LocalDateTime.now()\nOffsetDateTime.now(zoneId)\n" +
                "Clock.system(zoneId)\nClock.systemUTC()"
        assertEquals(
            listOf(
                "LocalTime.now(zoneId)",
                "ZonedDateTime.now()",
                "LocalDateTime.now()",
                "OffsetDateTime.now(zoneId)",
                "Clock.system(zoneId)",
                "Clock.systemUTC()",
            ),
            ClockCallScanner.violations(source),
        )
    }

    @Test
    fun `character literals do not hide following calls`() {
        val source = "val quote = '\"'\nval bad = LocalDate.now()"
        assertEquals(listOf("LocalDate.now()"), ClockCallScanner.violations(source))
    }

    @Test
    fun `clock-derived zones still use ambient now overloads`() {
        val source =
            """
            import java.time.Clock as TimeSource
            fun days(time: TimeSource) {
                LocalDate.now(clock.zone)
                LocalTime.now(time.zone)
                ZonedDateTime.now(resolveZone(time))
                LocalDate.now(clock.withZone(zoneId).zone)
            }
            """.trimIndent()
        assertEquals(
            listOf(
                "LocalDate.now(zoneId)",
                "LocalTime.now(zoneId)",
                "ZonedDateTime.now(zoneId)",
                "LocalDate.now(zoneId)",
            ),
            ClockCallScanner.violations(source),
        )
    }

    @Test
    fun `direct clocks and clock-returning expressions pass`() {
        val source =
            """
            import java.time.Clock as TimeSource
            fun days(time: TimeSource) {
                LocalDate.now(time)
                LocalTime.now(this.clock.withZone(resolveZone(time)))
                ZonedDateTime.now(TimeSource.fixed(instant, zoneId))
                LocalDate.now(Clock.offset(time, duration).withZone(zoneId))
                LocalDate.now(Clock.tick(time, duration))
            }
            """.trimIndent()
        assertTrue(ClockCallScanner.violations(source).isEmpty())
    }

    @Test
    fun `explicit non-clock declaration named clock selects ambient overload`() {
        for (zoneType in listOf("ZoneId", "java.time.ZoneId", "ScoringZone")) {
            val source =
                "import java.time.ZoneId as ScoringZone\n" +
                    "fun day(clock: $zoneType) = LocalDate.now(clock)"
            assertEquals(listOf("LocalDate.now(zoneId)"), ClockCallScanner.violations(source))
        }
    }

    @Test
    fun `explicit clock declarations still pass with direct qualified and aliased types`() {
        for (clockType in listOf("Clock", "java.time.Clock", "TimeSource")) {
            val source =
                "import java.time.Clock as TimeSource\n" +
                    "fun day(clock: $clockType) = LocalDate.now(clock.withZone(zoneId))"
            assertTrue(ClockCallScanner.violations(source).isEmpty())
        }
        assertTrue(ClockCallScanner.violations("LocalDate.now(clock)").isEmpty())
    }

    @Test
    fun `ambient tick construction is rejected standalone and inside now with aliases`() {
        for (factory in listOf("tickSeconds", "tickMinutes", "tickMillis")) {
            for (clockType in listOf("Clock", "java.time.Clock", "TimeSource")) {
                val call = "$clockType.$factory(zoneId)"
                val imports = "import java.time.Clock as TimeSource\n"
                assertEquals(
                    listOf("Clock.$factory(zoneId)"),
                    ClockCallScanner.violations(imports + "val time = $call"),
                )
                assertEquals(
                    listOf("LocalDate.now(zoneId)", "Clock.$factory(zoneId)"),
                    ClockCallScanner.violations(imports + "LocalDate.now($call)"),
                )
            }
        }
    }

    @Test
    fun `explicit-source clock factories remain positive in every type form`() {
        for (clockType in listOf("Clock", "java.time.Clock", "TimeSource")) {
            val source =
                "import java.time.Clock as TimeSource\n" +
                    "LocalDate.now($clockType.fixed(instant, zoneId))\n" +
                    "LocalTime.now($clockType.offset(clock, duration))\n" +
                    "ZonedDateTime.now($clockType.tick(clock, duration))\n" +
                    "val time = $clockType.tick(clock, duration)"
            assertTrue(ClockCallScanner.violations(source).isEmpty())
        }
    }
}
