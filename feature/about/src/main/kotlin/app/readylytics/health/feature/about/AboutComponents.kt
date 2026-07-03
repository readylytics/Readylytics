package app.readylytics.health.feature.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import app.readylytics.health.core.designsystem.spacing

@Composable
fun SectionHeader(text: String) {
    val cleanText = text.removePrefix("# ").trim()
    Text(
        text = cleanText,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.pageSectionGapSmall,
                ),
    )
}

@Composable
fun SubHeader(text: String) {
    val cleanText = text.removePrefix("## ").trim()
    Text(
        text = cleanText,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.extraSmall,
                ),
    )
}

@Composable
fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    fontStyle: FontStyle? = null,
) {
    Text(
        text = parseMarkdown(text),
        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = fontStyle ?: FontStyle.Normal),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.extraSmall,
                ),
    )
}

@Composable
fun BulletItem(text: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal, vertical = MaterialTheme.spacing.hairline),
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = MaterialTheme.spacing.pageSectionGapSmall),
        )
        Text(
            text = parseMarkdown(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun HighlightBox(content: @Composable () -> Unit) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.pageSectionGapSmall,
                ),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(vertical = MaterialTheme.spacing.pageSectionGapSmall)) { content() }
    }
}

@Composable
fun SectionDivider() {
    HorizontalDivider(
        modifier =
            Modifier.padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.pageSectionGapSmall,
            ),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

fun parseMarkdown(text: String): AnnotatedString =
    buildAnnotatedString {
        var currentText = text

        val parts = currentText.split("(?=\\*\\*)|(?<=\\*\\*)".toRegex())
        var isBold = false

        parts.forEach { part ->
            if (part == "**") {
                isBold = !isBold
            } else {
                val subParts = part.split("(?=\\*)|(?<=\\*)".toRegex())
                var isItalic = false
                subParts.forEach { subPart ->
                    if (subPart == "*") {
                        isItalic = !isItalic
                    } else {
                        val style =
                            when {
                                isBold && isItalic ->
                                    SpanStyle(
                                        fontWeight = FontWeight.Bold,
                                        fontStyle = FontStyle.Italic,
                                    )
                                isBold -> SpanStyle(fontWeight = FontWeight.Bold)
                                isItalic -> SpanStyle(fontStyle = FontStyle.Italic)
                                else -> SpanStyle()
                            }
                        withStyle(style) {
                            append(subPart)
                        }
                    }
                }
            }
        }
    }
