package app.readylytics.health.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenderTest {
    @Test
    fun fromString_withValidMaleVariants() {
        assertEquals(Gender.MALE, Gender.fromString("male"))
        assertEquals(Gender.MALE, Gender.fromString("MALE"))
        assertEquals(Gender.MALE, Gender.fromString("Male"))
    }

    @Test
    fun fromString_withValidFemaleVariants() {
        assertEquals(Gender.FEMALE, Gender.fromString("female"))
        assertEquals(Gender.FEMALE, Gender.fromString("FEMALE"))
        assertEquals(Gender.FEMALE, Gender.fromString("Female"))
    }

    @Test
    fun fromString_withNullOrInvalid() {
        assertNull(Gender.fromString(null))
        assertNull(Gender.fromString(""))
        assertNull(Gender.fromString("unknown"))
        assertNull(Gender.fromString("invalid"))
    }

    @Test
    fun displayName_returnsCorrectValues() {
        assertEquals("Male", Gender.MALE.displayName)
        assertEquals("Female", Gender.FEMALE.displayName)
    }

    @Test
    fun isMale_returnsCorrectValues() {
        assertEquals(true, Gender.MALE.isMale)
        assertEquals(false, Gender.FEMALE.isMale)
    }

    @Test
    fun toDisplayString_returnsConsistentWithDisplayName() {
        assertEquals(Gender.MALE.displayName, Gender.MALE.toDisplayString())
        assertEquals(Gender.FEMALE.displayName, Gender.FEMALE.toDisplayString())
    }
}
