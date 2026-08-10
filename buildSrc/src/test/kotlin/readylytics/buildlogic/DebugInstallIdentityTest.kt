package readylytics.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals

class DebugInstallIdentityTest {

    @Test
    fun `strips non-alphanumerics and lowercases`() {
        assertEquals("gregorsmacbookpro", DebugInstallIdentity.sanitizeMachineId("Gregors-MacBook-Pro"))
    }

    @Test
    fun `prefixes m when segment starts with a digit`() {
        assertEquals("m123", DebugInstallIdentity.sanitizeMachineId("123"))
    }

    @Test
    fun `prefixes m once when digit follows leading letters`() {
        assertEquals("m1laptop", DebugInstallIdentity.sanitizeMachineId("1-Laptop"))
    }

    @Test
    fun `does not double the m prefix`() {
        assertEquals("m1laptop", DebugInstallIdentity.sanitizeMachineId("m1laptop"))
    }

    @Test
    fun `empty hostname falls back to device`() {
        assertEquals("device", DebugInstallIdentity.sanitizeMachineId(""))
    }

    @Test
    fun `all-non-alphanumeric hostname falls back to device`() {
        assertEquals("device", DebugInstallIdentity.sanitizeMachineId("  !@#  "))
    }

    @Test
    fun `truncates to twenty characters`() {
        assertEquals("abcdefghijklmnopqrst", DebugInstallIdentity.sanitizeMachineId("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `strips mdns local suffix`() {
        assertEquals("Gregors-MacBook-Pro", DebugInstallIdentity.stripMdnsSuffix("Gregors-MacBook-Pro.local"))
    }

    @Test
    fun `keeps hostname without local suffix unchanged`() {
        assertEquals("myhost", DebugInstallIdentity.stripMdnsSuffix("myhost"))
    }

    @Test
    fun `sanitizes hostname after stripping mdns suffix`() {
        val raw = DebugInstallIdentity.stripMdnsSuffix("Gregors-MacBook-Pro.local")
        assertEquals("gregorsmacbookpro", DebugInstallIdentity.sanitizeMachineId(raw))
    }
}
