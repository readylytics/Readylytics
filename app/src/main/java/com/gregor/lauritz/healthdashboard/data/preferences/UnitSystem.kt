package com.gregor.lauritz.healthdashboard.data.preferences

enum class UnitSystem(
    val displayName: String,
) {
    METRIC("Metric (cm, kg)"),
    IMPERIAL("Imperial (ft/in, lbs)"),
    ;

    fun toDisplayString(): String = displayName

    companion object {
        fun fromString(value: String?): UnitSystem? =
            entries.firstOrNull { it.displayName.equals(value, ignoreCase = true) }

        fun fromStringOrNull(value: String?): UnitSystem? =
            when {
                value == null -> null
                else -> fromString(value)
            }
    }
}
