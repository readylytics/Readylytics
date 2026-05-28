package com.gregor.lauritz.healthdashboard.domain.service

import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.model.getOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreferenceServiceTest {
    private lateinit var store: InMemoryPreferenceStore
    private lateinit var service: PreferenceService

    @Before
    fun setUp() {
        store = InMemoryPreferenceStore()
        service = PreferenceService(store)
    }

    // ─── String ──────────────────────────────────────────────────────────────
    @Test
    fun getString_missingKey_isFailureMissing() {
        val r = service.getString("absent")
        assertTrue(r is Result.Failure)
        assertEquals(PreferenceService.Codes.MISSING, (r as Result.Failure).code)
    }

    @Test
    fun putString_thenGetString_returnsSuccess() {
        service.putString("k", "hello")
        val r = service.getString("k") as Result.Success
        assertEquals("hello", r.data)
    }

    @Test
    fun getString_wrongType_returnsTypeMismatch() {
        store.write("n", 42)
        val r = service.getString("n") as Result.Failure
        assertEquals(PreferenceService.Codes.TYPE_MISMATCH, r.code)
        assertTrue(r.reason.contains("String"))
    }

    @Test
    fun getStringOrDefault_missing_usesDefault() {
        assertEquals("fallback", service.getStringOrDefault("absent", "fallback"))
    }

    @Test
    fun getStringOrDefault_present_returnsStored() {
        service.putString("k", "v")
        assertEquals("v", service.getStringOrDefault("k", "fallback"))
    }

    @Test
    fun getStringOrDefault_typeMismatch_usesDefault() {
        store.write("k", 5)
        assertEquals("fallback", service.getStringOrDefault("k", "fallback"))
    }

    // ─── Int ─────────────────────────────────────────────────────────────────
    @Test
    fun getInt_missingKey_isFailure() = assertTrue(service.getInt("absent") is Result.Failure)

    @Test
    fun putInt_thenGetInt_returnsSuccess() {
        service.putInt("k", 100)
        val r = service.getInt("k") as Result.Success
        assertEquals(100, r.data)
    }

    @Test
    fun getInt_wrongType_returnsTypeMismatch() {
        store.write("k", "not-an-int")
        val r = service.getInt("k") as Result.Failure
        assertEquals(PreferenceService.Codes.TYPE_MISMATCH, r.code)
    }

    @Test
    fun getIntOrDefault_missing_usesDefault() = assertEquals(42, service.getIntOrDefault("absent", 42))

    @Test
    fun getIntOrDefault_present_returnsStored() {
        service.putInt("k", 7)
        assertEquals(7, service.getIntOrDefault("k", 42))
    }

    @Test
    fun getInt_negativeStored_returnsNegative() {
        service.putInt("k", -100)
        val r = service.getInt("k") as Result.Success
        assertEquals(-100, r.data)
    }

    @Test
    fun getInt_zero_returnsZero() {
        service.putInt("k", 0)
        val r = service.getInt("k") as Result.Success
        assertEquals(0, r.data)
    }

    @Test
    fun getInt_maxValue_returnsMax() {
        service.putInt("k", Int.MAX_VALUE)
        val r = service.getInt("k") as Result.Success
        assertEquals(Int.MAX_VALUE, r.data)
    }

    @Test
    fun getInt_minValue_returnsMin() {
        service.putInt("k", Int.MIN_VALUE)
        val r = service.getInt("k") as Result.Success
        assertEquals(Int.MIN_VALUE, r.data)
    }

    // ─── Long ────────────────────────────────────────────────────────────────
    @Test
    fun getLong_missingKey_isFailure() = assertTrue(service.getLong("absent") is Result.Failure)

    @Test
    fun putLong_thenGetLong_returnsSuccess() {
        service.putLong("k", 9_999_999_999L)
        val r = service.getLong("k") as Result.Success
        assertEquals(9_999_999_999L, r.data)
    }

    @Test
    fun getLongOrDefault_missing_usesDefault() = assertEquals(100L, service.getLongOrDefault("absent", 100L))

    @Test
    fun getLongOrDefault_present_returnsStored() {
        service.putLong("k", 50L)
        assertEquals(50L, service.getLongOrDefault("k", 100L))
    }

    @Test
    fun getLong_wrongType_returnsTypeMismatch() {
        store.write("k", "abc")
        val r = service.getLong("k") as Result.Failure
        assertEquals(PreferenceService.Codes.TYPE_MISMATCH, r.code)
    }

    @Test
    fun getLong_maxValue_returnsMax() {
        service.putLong("k", Long.MAX_VALUE)
        val r = service.getLong("k") as Result.Success
        assertEquals(Long.MAX_VALUE, r.data)
    }

    // ─── Float ───────────────────────────────────────────────────────────────
    @Test
    fun getFloat_missingKey_isFailure() = assertTrue(service.getFloat("absent") is Result.Failure)

    @Test
    fun putFloat_thenGetFloat_returnsSuccess() {
        service.putFloat("k", 3.14f)
        val r = service.getFloat("k") as Result.Success
        assertEquals(3.14f, r.data, 0.001f)
    }

    @Test
    fun getFloatOrDefault_missing_usesDefault() = assertEquals(1.5f, service.getFloatOrDefault("absent", 1.5f), 0f)

    @Test
    fun getFloatOrDefault_present_returnsStored() {
        service.putFloat("k", 2.71f)
        assertEquals(2.71f, service.getFloatOrDefault("k", 1.5f), 0.001f)
    }

    @Test
    fun getFloat_wrongType_returnsTypeMismatch() {
        store.write("k", 5)
        val r = service.getFloat("k") as Result.Failure
        assertEquals(PreferenceService.Codes.TYPE_MISMATCH, r.code)
    }

    @Test
    fun getFloat_negative_returnsNegative() {
        service.putFloat("k", -10.5f)
        val r = service.getFloat("k") as Result.Success
        assertEquals(-10.5f, r.data, 0.001f)
    }

    // ─── Boolean ─────────────────────────────────────────────────────────────
    @Test
    fun getBoolean_missingKey_isFailure() = assertTrue(service.getBoolean("absent") is Result.Failure)

    @Test
    fun putBoolean_true_returnsSuccess() {
        service.putBoolean("k", true)
        val r = service.getBoolean("k") as Result.Success
        assertTrue(r.data)
    }

    @Test
    fun putBoolean_false_returnsSuccess() {
        service.putBoolean("k", false)
        val r = service.getBoolean("k") as Result.Success
        assertFalse(r.data)
    }

    @Test
    fun getBooleanOrDefault_missing_usesDefault() = assertTrue(service.getBooleanOrDefault("absent", true))

    @Test
    fun getBooleanOrDefault_present_returnsStored() {
        service.putBoolean("k", false)
        assertFalse(service.getBooleanOrDefault("k", true))
    }

    @Test
    fun getBoolean_wrongType_returnsTypeMismatch() {
        store.write("k", 1)
        val r = service.getBoolean("k") as Result.Failure
        assertEquals(PreferenceService.Codes.TYPE_MISMATCH, r.code)
    }

    // ─── contains / remove / allKeys ─────────────────────────────────────────
    @Test
    fun contains_missingKey_isFalse() = assertFalse(service.contains("absent"))

    @Test
    fun contains_presentKey_isTrue() {
        service.putString("k", "v")
        assertTrue(service.contains("k"))
    }

    @Test
    fun remove_makesKeyAbsent() {
        service.putString("k", "v")
        service.remove("k")
        assertFalse(service.contains("k"))
    }

    @Test
    fun remove_missingKey_isNoOp() {
        service.remove("never-set")
        assertFalse(service.contains("never-set"))
    }

    @Test
    fun allKeys_isEmpty_initially() = assertTrue(service.allKeys().isEmpty())

    @Test
    fun allKeys_afterTwoPuts_hasTwoKeys() {
        service.putInt("a", 1)
        service.putInt("b", 2)
        assertEquals(setOf("a", "b"), service.allKeys())
    }

    @Test
    fun allKeys_afterRemove_shrinks() {
        service.putInt("a", 1)
        service.putInt("b", 2)
        service.remove("a")
        assertEquals(setOf("b"), service.allKeys())
    }

    // ─── overwrite semantics ─────────────────────────────────────────────────
    @Test
    fun overwriteSameKey_storesLatest() {
        service.putInt("k", 1)
        service.putInt("k", 2)
        val r = service.getInt("k") as Result.Success
        assertEquals(2, r.data)
    }

    @Test
    fun overwriteWithDifferentType_replacesValue() {
        service.putInt("k", 1)
        service.putString("k", "now-a-string")
        assertTrue(service.getString("k") is Result.Success)
    }

    @Test
    fun overwriteWithDifferentType_oldTypeReadFails() {
        service.putInt("k", 1)
        service.putString("k", "now-a-string")
        val r = service.getInt("k") as Result.Failure
        assertEquals(PreferenceService.Codes.TYPE_MISMATCH, r.code)
    }

    // ─── Result helpers ──────────────────────────────────────────────────────
    @Test
    fun result_getOrNull_success_returnsValue() =
        assertEquals("v", (Result.Success("v") as Result<String>).getOrNull())

    @Test
    fun result_getOrNull_missing_returnsNull() =
        assertNull((Result.Failure("missing", PreferenceService.Codes.MISSING) as Result<String>).getOrNull())

    @Test
    fun result_getOrNull_typeMismatch_returnsNull() {
        val r: Result<String> = Result.Failure("mismatch", PreferenceService.Codes.TYPE_MISMATCH)
        assertNull(r.getOrNull())
    }

    // ─── store contract ──────────────────────────────────────────────────────
    @Test
    fun inMemoryStore_initial_isUsed() {
        val seeded = InMemoryPreferenceStore(mapOf("seed" to "value"))
        val svc = PreferenceService(seeded)
        assertEquals("value", svc.getStringOrDefault("seed", "fallback"))
    }

    @Test
    fun inMemoryStore_writeReadRemove_roundtrip() {
        store.write("a", 1)
        assertEquals(1, store.read("a"))
        store.remove("a")
        assertNull(store.read("a"))
    }

    @Test
    fun inMemoryStore_keys_returnsSnapshot() {
        store.write("a", 1)
        val keys = store.keys()
        store.write("b", 2)
        assertEquals(setOf("a"), keys)
    }

    @Test
    fun service_typeMismatch_carriesActualTypeInReason() {
        store.write("k", 42)
        val r = service.getString("k") as Result.Failure
        assertTrue(r.reason.contains("Integer"))
    }

    @Test
    fun service_putInt_storesInteger() {
        service.putInt("k", 5)
        assertNotNull(store.read("k"))
    }

    @Test
    fun service_putLong_storesLong() {
        service.putLong("k", 10L)
        val raw = store.read("k")
        assertTrue(raw is Long)
    }

    @Test
    fun service_putFloat_storesFloat() {
        service.putFloat("k", 1.5f)
        val raw = store.read("k")
        assertTrue(raw is Float)
    }

    @Test
    fun service_putBoolean_storesBoolean() {
        service.putBoolean("k", true)
        val raw = store.read("k")
        assertTrue(raw is Boolean)
    }

    @Test
    fun service_putString_storesString() {
        service.putString("k", "v")
        val raw = store.read("k")
        assertTrue(raw is String)
    }

    @Test
    fun service_multipleKeys_areIndependent() {
        service.putInt("a", 1)
        service.putString("b", "two")
        val a = service.getInt("a") as Result.Success
        val b = service.getString("b") as Result.Success
        assertEquals(1, a.data)
        assertEquals("two", b.data)
    }

    @Test
    fun service_constructorAcceptsCustomStore() {
        val custom =
            object : PreferenceStore {
                private val m = mutableMapOf<String, Any?>("custom" to "ok")

                override fun read(key: String) = m[key]

                override fun write(
                    key: String,
                    value: Any?,
                ) {
                    m[key] = value
                }

                override fun remove(key: String) {
                    m.remove(key)
                }

                override fun keys() = m.keys.toSet()
            }
        val svc = PreferenceService(custom)
        assertEquals("ok", svc.getStringOrDefault("custom", "fallback"))
    }

    @Test
    fun service_emptyKey_isUsable() {
        service.putString("", "empty-key-value")
        assertEquals("empty-key-value", service.getStringOrDefault("", "fallback"))
    }
}
