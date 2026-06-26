package app.readylytics.health.domain.validation

import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertTrue

// ─── IntRangeRule ──────────────────────────────────────────────────────────────

class IntRangeRuleTest {
    private val rule = IntRangeRule(min = 1, max = 100, errorMessage = "Must be 1-100")

    @Test
    fun `empty string returns Valid`() {
        assertTrue(rule.validate("") is ValidationResult.Valid)
    }

    @Test
    fun `valid integer within range returns Valid`() {
        assertTrue(rule.validate("50") is ValidationResult.Valid)
    }

    @Test
    fun `minimum boundary returns Valid`() {
        assertTrue(rule.validate("1") is ValidationResult.Valid)
    }

    @Test
    fun `maximum boundary returns Valid`() {
        assertTrue(rule.validate("100") is ValidationResult.Valid)
    }

    @Test
    fun `below minimum returns Invalid`() {
        assertTrue(rule.validate("0") is ValidationResult.Invalid)
    }

    @Test
    fun `above maximum returns Invalid`() {
        assertTrue(rule.validate("101") is ValidationResult.Invalid)
    }

    @Test
    fun `non-integer string returns Invalid`() {
        assertTrue(rule.validate("abc") is ValidationResult.Invalid)
    }

    @Test
    fun `decimal string returns Invalid`() {
        assertTrue(rule.validate("1.5") is ValidationResult.Invalid)
    }
}

// ─── FloatRangeRule ────────────────────────────────────────────────────────────

class FloatRangeRuleTest {
    private val rule = RasScalingFactorRule()

    @Test
    fun `empty string returns Valid`() {
        assertTrue(rule.validate("") is ValidationResult.Valid)
    }

    @Test
    fun `valid float within range returns Valid`() {
        assertTrue(rule.validate("0.2") is ValidationResult.Valid)
    }

    @Test
    fun `minimum boundary 0_1 returns Valid`() {
        assertTrue(rule.validate("0.1") is ValidationResult.Valid)
    }

    @Test
    fun `maximum boundary 0_3 returns Valid`() {
        assertTrue(rule.validate("0.3") is ValidationResult.Valid)
    }

    @Test
    fun `below minimum returns Invalid`() {
        assertTrue(rule.validate("0.05") is ValidationResult.Invalid)
    }

    @Test
    fun `above maximum returns Invalid`() {
        assertTrue(rule.validate("0.4") is ValidationResult.Invalid)
    }

    @Test
    fun `non-float string returns Invalid`() {
        assertTrue(rule.validate("abc") is ValidationResult.Invalid)
    }

    @Test
    fun `direct float validate within range returns Valid`() {
        assertTrue(rule.validate(0.2f) is ValidationResult.Valid)
    }

    @Test
    fun `direct float validate outside range returns Invalid`() {
        assertTrue(rule.validate(0.5f) is ValidationResult.Invalid)
    }
}

// ─── BirthdayDateRule ─────────────────────────────────────────────────────────

class BirthdayDateRuleTest {
    private val fixedNow = LocalDate.of(2026, 6, 26)
    private val fixedClock = Clock.fixed(
        fixedNow.atStartOfDay(ZoneId.of("UTC")).toInstant(),
        ZoneId.of("UTC"),
    )

    @Test
    fun `past date is Valid`() {
        val rule = BirthdayDateRule(fixedClock)
        assertTrue(rule.validate(LocalDate.of(1990, 5, 15)) is ValidationResult.Valid)
    }

    @Test
    fun `today is Valid`() {
        val rule = BirthdayDateRule(fixedClock)
        assertTrue(rule.validate(fixedNow) is ValidationResult.Valid)
    }

    @Test
    fun `tomorrow is Invalid`() {
        val rule = BirthdayDateRule(fixedClock)
        assertTrue(rule.validate(fixedNow.plusDays(1)) is ValidationResult.Invalid)
    }

    @Test
    fun `date before 1900 is Invalid`() {
        val rule = BirthdayDateRule(fixedClock)
        assertTrue(rule.validate(LocalDate.of(1899, 12, 31)) is ValidationResult.Invalid)
    }

    @Test
    fun `year 1900 is Valid`() {
        val rule = BirthdayDateRule(fixedClock)
        assertTrue(rule.validate(LocalDate.of(1900, 1, 1)) is ValidationResult.Valid)
    }

    @Test
    fun `errorMessage is set`() {
        val rule = BirthdayDateRule(fixedClock)
        assertTrue(rule.errorMessage.isNotEmpty())
    }
}

// ─── RetentionDaysRule ────────────────────────────────────────────────────────

class RetentionDaysRuleTest {
    private val rule = RetentionDaysRule()

    @Test
    fun `valid days in range returns Valid`() {
        assertTrue(rule.validate("30") is ValidationResult.Valid)
    }

    @Test
    fun `minimum 1 day returns Valid`() {
        assertTrue(rule.validate("1") is ValidationResult.Valid)
    }

    @Test
    fun `maximum 3650 days returns Valid`() {
        assertTrue(rule.validate("3650") is ValidationResult.Valid)
    }

    @Test
    fun `zero days returns Invalid`() {
        assertTrue(rule.validate("0") is ValidationResult.Invalid)
    }

    @Test
    fun `above max 3651 returns Invalid`() {
        assertTrue(rule.validate("3651") is ValidationResult.Invalid)
    }

    @Test
    fun `empty string returns Valid`() {
        assertTrue(rule.validate("") is ValidationResult.Valid)
    }
}

// ─── StepGoalRule ─────────────────────────────────────────────────────────────

class StepGoalRuleTest {
    private val rule = StepGoalRule()

    @Test
    fun `valid step count returns Valid`() {
        assertTrue(rule.validate("10000") is ValidationResult.Valid)
    }

    @Test
    fun `zero steps returns Valid`() {
        assertTrue(rule.validate("0") is ValidationResult.Valid)
    }

    @Test
    fun `maximum 100000 steps returns Valid`() {
        assertTrue(rule.validate("100000") is ValidationResult.Valid)
    }

    @Test
    fun `negative steps returns Invalid`() {
        assertTrue(rule.validate("-1") is ValidationResult.Invalid)
    }

    @Test
    fun `above maximum returns Invalid`() {
        assertTrue(rule.validate("100001") is ValidationResult.Invalid)
    }

    @Test
    fun `empty string returns Valid`() {
        assertTrue(rule.validate("") is ValidationResult.Valid)
    }
}

// ─── SyncIntervalHoursRule ────────────────────────────────────────────────────

class SyncIntervalHoursRuleTest {
    private val rule = SyncIntervalHoursRule()

    @Test
    fun `valid hours in range returns Valid`() {
        assertTrue(rule.validate("6") is ValidationResult.Valid)
    }

    @Test
    fun `minimum 1 hour returns Valid`() {
        assertTrue(rule.validate("1") is ValidationResult.Valid)
    }

    @Test
    fun `maximum 24 hours returns Valid`() {
        assertTrue(rule.validate("24") is ValidationResult.Valid)
    }

    @Test
    fun `zero hours returns Invalid`() {
        assertTrue(rule.validate("0") is ValidationResult.Invalid)
    }

    @Test
    fun `above max 25 hours returns Invalid`() {
        assertTrue(rule.validate("25") is ValidationResult.Invalid)
    }

    @Test
    fun `empty string returns Valid`() {
        assertTrue(rule.validate("") is ValidationResult.Valid)
    }
}

// ─── TrimpParameterRule ───────────────────────────────────────────────────────

class TrimpParameterRuleTest {
    private val rule = TrimpParameterRule(min = 0.5f, max = 2.0f, errorMessage = "Must be 0.5-2.0")

    @Test
    fun `valid float string within range returns Valid`() {
        assertTrue(rule.validate("1.0") is ValidationResult.Valid)
    }

    @Test
    fun `minimum boundary returns Valid`() {
        assertTrue(rule.validate("0.5") is ValidationResult.Valid)
    }

    @Test
    fun `maximum boundary returns Valid`() {
        assertTrue(rule.validate("2.0") is ValidationResult.Valid)
    }

    @Test
    fun `below minimum returns Invalid`() {
        assertTrue(rule.validate("0.4") is ValidationResult.Invalid)
    }

    @Test
    fun `above maximum returns Invalid`() {
        assertTrue(rule.validate("2.1") is ValidationResult.Invalid)
    }

    @Test
    fun `empty string returns Valid`() {
        assertTrue(rule.validate("") is ValidationResult.Valid)
    }

    @Test
    fun `direct float validate within range returns Valid`() {
        assertTrue(rule.validate(1.5f) is ValidationResult.Valid)
    }

    @Test
    fun `direct float validate outside range returns Invalid`() {
        assertTrue(rule.validate(3.0f) is ValidationResult.Invalid)
    }
}

// ─── RasScalingFactorRule ─────────────────────────────────────────────────────

class RasScalingFactorRuleTest {
    private val rule = RasScalingFactorRule()

    @Test
    fun `midpoint value 0_2 returns Valid`() {
        assertTrue(rule.validate(0.2f) is ValidationResult.Valid)
    }

    @Test
    fun `minimum boundary 0_1 returns Valid`() {
        assertTrue(rule.validate(0.1f) is ValidationResult.Valid)
    }

    @Test
    fun `maximum boundary 0_3 returns Valid`() {
        assertTrue(rule.validate(0.3f) is ValidationResult.Valid)
    }

    @Test
    fun `below minimum returns Invalid`() {
        assertTrue(rule.validate(0.05f) is ValidationResult.Invalid)
    }

    @Test
    fun `above maximum returns Invalid`() {
        assertTrue(rule.validate(0.35f) is ValidationResult.Invalid)
    }

    @Test
    fun `default errorMessage mentions RAS`() {
        assertTrue(rule.errorMessage.contains("RAS"))
    }
}
