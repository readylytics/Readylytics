package app.readylytics.health.data.preferences

enum class FallbackThemeColor(
    val primaryColor: Long,
    val secondaryColor: Long,
    val tertiaryColor: Long,
) {
    GREEN_PERFORMANCE(0xFF2ECC71L, 0xFF3498DBL, 0xFFF1C40FL),
    BLUE_TRUST(0xFF4A90E2L, 0xFFF5A623L, 0xFF50E3C2L),
    PURPLE_INSIGHT(0xFF8E44ADL, 0xFFF39C12L, 0xFFE91E63L),
    ICON_SIGNATURE(0xFF9D6FFFL, 0xFF409FFFL, 0xFFC1A2F5L),
    ICON_ELEMENTS(0xFF409FFFL, 0xFF9D6FFFL, 0xFFFFB74DL),
    ;

    val seedColor: Long get() = primaryColor
}
