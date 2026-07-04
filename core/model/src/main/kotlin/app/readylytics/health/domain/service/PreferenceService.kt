package app.readylytics.health.domain.service

import app.readylytics.health.domain.model.Result

/**
 * Thin abstraction over a key/value preference store.
 *
 * Production: backed by DataStore via an adapter; tests can pass
 * an in-memory implementation.
 */
interface PreferenceStore {
    fun read(key: String): Any?

    fun write(
        key: String,
        value: Any?,
    )

    fun remove(key: String)

    fun keys(): Set<String>
}

/** Simple in-memory [PreferenceStore] for tests and as a default. */
class InMemoryPreferenceStore(
    initial: Map<String, Any?> = emptyMap(),
) : PreferenceStore {
    private val data: MutableMap<String, Any?> = initial.toMutableMap()

    override fun read(key: String): Any? = data[key]

    override fun write(
        key: String,
        value: Any?,
    ) {
        data[key] = value
    }

    override fun remove(key: String) {
        data.remove(key)
    }

    override fun keys(): Set<String> = data.keys.toSet()
}

/**
 * Pure-Kotlin service for typed preference access.
 *
 * Constructor-injectable. Never throws for missing keys or type
 * mismatches — returns a typed [Result] instead, with stable failure codes
 * defined in [Codes].
 */
class PreferenceService(
    private val store: PreferenceStore,
) {
    fun getString(key: String): Result<String> = typedGet(key, String::class.java, "String")

    fun getInt(key: String): Result<Int> = typedGet(key, Int::class.javaObjectType, "Int")

    fun getLong(key: String): Result<Long> = typedGet(key, Long::class.javaObjectType, "Long")

    fun getFloat(key: String): Result<Float> = typedGet(key, Float::class.javaObjectType, "Float")

    fun getBoolean(key: String): Result<Boolean> = typedGet(key, Boolean::class.javaObjectType, "Boolean")

    fun getStringOrDefault(
        key: String,
        default: String,
    ): String = (getString(key) as? Result.Success)?.data ?: default

    fun getIntOrDefault(
        key: String,
        default: Int,
    ): Int = (getInt(key) as? Result.Success)?.data ?: default

    fun getLongOrDefault(
        key: String,
        default: Long,
    ): Long = (getLong(key) as? Result.Success)?.data ?: default

    fun getFloatOrDefault(
        key: String,
        default: Float,
    ): Float = (getFloat(key) as? Result.Success)?.data ?: default

    fun getBooleanOrDefault(
        key: String,
        default: Boolean,
    ): Boolean = (getBoolean(key) as? Result.Success)?.data ?: default

    fun putString(
        key: String,
        value: String,
    ) {
        store.write(key, value)
    }

    fun putInt(
        key: String,
        value: Int,
    ) {
        store.write(key, value)
    }

    fun putLong(
        key: String,
        value: Long,
    ) {
        store.write(key, value)
    }

    fun putFloat(
        key: String,
        value: Float,
    ) {
        store.write(key, value)
    }

    fun putBoolean(
        key: String,
        value: Boolean,
    ) {
        store.write(key, value)
    }

    fun contains(key: String): Boolean = store.read(key) != null

    fun remove(key: String) {
        store.remove(key)
    }

    fun allKeys(): Set<String> = store.keys()

    @Suppress("UNCHECKED_CAST")
    private fun <T> typedGet(
        key: String,
        type: Class<T>,
        expectedTypeName: String,
    ): Result<T> {
        val raw =
            store.read(key)
                ?: return Result.Failure(
                    reason = "Key '$key' is missing",
                    code = Codes.MISSING,
                )
        return if (type.isInstance(raw)) {
            Result.Success(raw as T)
        } else {
            val actualType = raw::class.java.simpleName
            Result.Failure(
                reason = "Type mismatch for key '$key': expected $expectedTypeName but got $actualType",
                code = Codes.TYPE_MISMATCH,
            )
        }
    }

    /** Stable [Result.Failure.code] values produced by this service. */
    object Codes {
        const val MISSING: String = "MISSING"
        const val TYPE_MISMATCH: String = "TYPE_MISMATCH"
    }
}
