package com.gregor.lauritz.healthdashboard.data.preferences

enum class Gender(
    val displayName: String,
) {
    MALE("Male"),
    FEMALE("Female"),
    OTHER("Other"),
    PREFER_NOT_TO_SAY("Prefer not to say"),
    ;

    val isMale: Boolean get() = this == MALE

    fun toDisplayString(): String = displayName

    companion object {
        fun fromString(value: String?): Gender? =
            entries.firstOrNull { it.displayName.equals(value, ignoreCase = true) }

        fun fromStringOrNull(value: String?): Gender? =
            when {
                value == null -> null
                else -> fromString(value)
            }
    }
}
