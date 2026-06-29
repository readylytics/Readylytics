package app.readylytics.health.core.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

sealed class UiText {
    data class StringRes(
        @androidx.annotation.StringRes val id: Int,
    ) : UiText()

    data class StringResWithArgs(
        @androidx.annotation.StringRes val id: Int,
        val args: List<Any>,
    ) : UiText()

    data class RawString(
        val value: String,
    ) : UiText()

    data class Compound(
        val parts: List<UiText>,
    ) : UiText()
}

@Composable
fun UiText.resolveString(): String =
    when (this) {
        is UiText.StringRes -> stringResource(id)
        is UiText.StringResWithArgs -> stringResource(id, *args.toTypedArray())
        is UiText.RawString -> value
        is UiText.Compound -> {
            val builder = StringBuilder()
            parts.forEach { builder.append(it.resolveString()) }
            builder.toString()
        }
    }

@Composable
fun UiText?.resolveOrNull(): String? = this?.resolveString()
