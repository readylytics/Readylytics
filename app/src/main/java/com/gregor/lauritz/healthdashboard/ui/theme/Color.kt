package com.gregor.lauritz.healthdashboard.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

// Custom success semantic colors (Material 3 has no built-in success role)
val SuccessGreenDark = Color(0xFF1E8B4A)
val SuccessGreenContainerDark = Color(0xFF0A3D20)
val OnSuccessGreenDark = Color(0xFFFFFFFF)
val OnSuccessGreenContainerDark = Color(0xFFB8F5C8)

val SuccessGreenLight = Color(0xFF1A7A41)
val SuccessGreenContainerLight = Color(0xFFB8F5C8)
val OnSuccessGreenLight = Color(0xFFFFFFFF)
val OnSuccessGreenContainerLight = Color(0xFF003917)

// Brighter purple for dark mode readability
val OnPrimaryContainerDark = Color(0xFFEADDFF)
val PrimaryContainerDark = Color(0xFF4F378B)

// Darker purple for light mode readability
val OnPrimaryContainerLight = Color(0xFF21005D)
val PrimaryContainerLight = Color(0xFFEADDFF)

// Warning orange semantic colors
val WarningOrangeDark = Color(0xFFFFB74D) // Lighter orange for dark mode
val WarningOrangeContainerDark = Color(0xFFB36B00)
val OnWarningOrangeDark = Color(0xFF4D2C00)
val OnWarningOrangeContainerDark = Color(0xFFFFDDB3)

val WarningOrangeLight = Color(0xFFF57C00) // Darker orange for light mode
val WarningOrangeContainerLight = Color(0xFFFB8C00)
val OnWarningOrangeLight = Color(0xFFFFFFFF)
val OnWarningOrangeContainerLight = Color(0xFF4D2C00)

fun Color.toHexCode(): String = String.format("#%06X", (0xFFFFFF and this.toArgb()))
