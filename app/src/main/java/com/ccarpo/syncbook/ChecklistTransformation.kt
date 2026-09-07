package com.ccarpo.syncbook

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

internal data class ChecklistGlyph(
    val transformedStart: Int,
    val originalLineStart: Int,
    val transformedLength: Int,
    val checked: Boolean,
)

internal data class ChecklistTransform(
    val transformed: String,
    val offsetMapping: OffsetMapping,
    val checkedRanges: List<TextRange>,
    val glyphs: List<ChecklistGlyph>,
)

private data class ChecklistLine(
    val originalStart: Int,
    val originalEnd: Int,
    val transformedStart: Int,
    val prefixLength: Int,
    val replacement: String,
    val transformedPrefixLength: Int,
    val checked: Boolean,
)

private data class ParsedPrefix(
    val length: Int,
    val replacement: String,
    val checked: Boolean,
)

private fun parsePrefix(line: String): ParsedPrefix? =
    when {
        line.startsWith("- [ ] ") -> ParsedPrefix(6, "☐ ", false)
        line.startsWith("- [x] ") || line.startsWith("- [X] ") -> ParsedPrefix(6, "☑ ", true)
        line == "- [ ]" -> ParsedPrefix(5, "☐", false)
        line == "- [x]" || line == "- [X]" -> ParsedPrefix(5, "☑", true)
        else -> null
    }

internal fun transformChecklistText(text: String): ChecklistTransform {
    val lines = mutableListOf<ChecklistLine>()
    val transformed = StringBuilder()
    var originalStart = 0
    var transformedStart = 0

    while (true) {
        val originalEnd = text.indexOf('\n', originalStart).let { if (it < 0) text.length else it }
        val line = text.substring(originalStart, originalEnd)
        val prefix = parsePrefix(line)
        val replacement = if (prefix == null) line else {
            prefix.replacement + line.substring(prefix.length)
        }
        transformed.append(replacement)
        lines += ChecklistLine(
            originalStart = originalStart,
            originalEnd = originalEnd,
            transformedStart = transformedStart,
            prefixLength = prefix?.length ?: 0,
            replacement = replacement,
            transformedPrefixLength = prefix?.replacement?.length ?: 0,
            checked = prefix?.checked == true,
        )
        transformedStart += replacement.length
        if (originalEnd == text.length) break
        transformed.append('\n')
        transformedStart++
        originalStart = originalEnd + 1
    }

    val originalLength = text.length
    val transformedLength = transformed.length
    val mapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int {
            val clamped = offset.coerceIn(0, originalLength)
            val line = lines.lastOrNull {
                clamped >= it.originalStart && clamped <= it.originalEnd
            } ?: return clamped.coerceIn(0, transformedLength)
            if (line.prefixLength == 0) {
                return (line.transformedStart + clamped - line.originalStart)
                    .coerceIn(0, transformedLength)
            }
            val prefixEnd = line.originalStart + line.prefixLength
            return if (clamped <= line.originalStart) {
                line.transformedStart
            } else if (clamped < prefixEnd) {
                line.transformedStart + line.transformedPrefixLength
            } else {
                line.transformedStart + line.transformedPrefixLength + clamped - prefixEnd
            }.coerceIn(0, transformedLength)
        }

        override fun transformedToOriginal(offset: Int): Int {
            val clamped = offset.coerceIn(0, transformedLength)
            val line = lines.lastOrNull {
                clamped >= it.transformedStart &&
                    clamped <= it.transformedStart + it.replacement.length
            } ?: return clamped.coerceIn(0, originalLength)
            if (line.prefixLength == 0) {
                return (line.originalStart + clamped - line.transformedStart)
                    .coerceIn(0, originalLength)
            }
            val replacementEnd = line.transformedStart + line.transformedPrefixLength
            return if (clamped < replacementEnd) {
                line.originalStart
            } else {
                line.originalStart + line.prefixLength + clamped - replacementEnd
            }.coerceIn(0, originalLength)
        }
    }

    val checkedRanges = lines.filter { it.checked }.mapNotNull { line ->
        val textStart = line.transformedStart + line.transformedPrefixLength
        val textEnd = line.transformedStart + line.transformedPrefixLength +
            (line.originalEnd - line.originalStart - line.prefixLength)
        if (textStart < textEnd) TextRange(textStart, textEnd) else null
    }
    val glyphs = lines.filter { it.prefixLength > 0 }.map { line ->
        ChecklistGlyph(
            transformedStart = line.transformedStart,
            originalLineStart = line.originalStart,
            transformedLength = line.transformedPrefixLength,
            checked = line.checked,
        )
    }
    return ChecklistTransform(
        transformed = transformed.toString(),
        offsetMapping = mapping,
        checkedRanges = checkedRanges,
        glyphs = glyphs,
    )
}

internal fun toggleCheckedLine(text: String, cursor: Int): String? {
    val lineStart = lineStartAt(text, cursor)
    val line = lineAt(text, cursor)
    val replacement = when {
        line.startsWith("- [ ] ") -> "- [x] "
        line.startsWith("- [x] ") -> "- [ ] "
        line.startsWith("- [X] ") -> "- [ ] "
        line == "- [ ]" -> "- [x]"
        line == "- [x]" || line == "- [X]" -> "- [ ]"
        else -> return null
    }
    return text.substring(0, lineStart) + replacement + text.substring(lineStart + replacement.length)
}

internal fun lineAt(text: String, offset: Int): String {
    val lineStart = lineStartAt(text, offset)
    val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
    return text.substring(lineStart, lineEnd)
}

internal fun isCheckedLine(line: String): Boolean =
    line.startsWith("- [x] ") ||
        line.startsWith("- [X] ") ||
        line == "- [x]" ||
        line == "- [X]"

private fun lineStartAt(text: String, offset: Int): Int {
    val position = offset.coerceIn(0, text.length)
    return text.lastIndexOf('\n', (position - 1).coerceAtLeast(0)) + 1
}

internal fun checkboxAtTransformedOffset(
    transform: ChecklistTransform,
    offset: Int,
): ChecklistGlyph? =
    transform.glyphs.firstOrNull { glyph ->
        offset >= glyph.transformedStart &&
            offset <= glyph.transformedStart + glyph.transformedLength
    }

class ChecklistVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val transform = transformChecklistText(text.text)
        val builder = AnnotatedString.Builder(transform.transformed)
        transform.checkedRanges.forEach { range ->
            builder.addStyle(
                SpanStyle(
                    color = Color.Gray,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                ),
                range.start,
                range.end,
            )
        }
        return TransformedText(builder.toAnnotatedString(), transform.offsetMapping)
    }
}
