package app.readylytics.health.core.database.testutil

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Deterministic wall-clock for tests that construct classes taking an injected [Clock]. */
val FIXED_TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-12-31T12:00:00Z"), ZoneOffset.UTC)
