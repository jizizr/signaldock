package com.jizizr.signaldock

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal data class IslandShareTemplateToken(
    val value: String,
    val example: String,
)

internal val islandShareTemplateTokens = listOf(
    IslandShareTemplateToken("{类型}", "取餐码"),
    IslandShareTemplateToken("{号码}", "A123"),
    IslandShareTemplateToken("{商品}", "示例饮品"),
    IslandShareTemplateToken("{规格}", "中杯 · 少冰"),
    IslandShareTemplateToken("{商品规格}", "示例饮品 · 中杯"),
    IslandShareTemplateToken("{商家}", "示例餐厅"),
)

internal fun insertIslandShareToken(
    value: TextFieldValue,
    token: String,
): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)
    val updated = value.text.replaceRange(start, end, token)
    return TextFieldValue(updated, TextRange(start + token.length))
}

/** Prevents partial edits from corrupting a template variable. */
internal fun applyAtomicIslandShareTokenEdit(
    previous: TextFieldValue,
    next: TextFieldValue,
): TextFieldValue {
    if (previous.text == next.text) return next

    val prefix = previous.text.commonPrefixWith(next.text).length
    val maxSuffix = minOf(previous.text.length - prefix, next.text.length - prefix)
    var suffix = 0
    while (
        suffix < maxSuffix &&
        previous.text[previous.text.lastIndex - suffix] == next.text[next.text.lastIndex - suffix]
    ) {
        suffix++
    }
    var oldStart = prefix
    var oldEnd = previous.text.length - suffix
    val newEnd = next.text.length - suffix
    val tokenRanges = buildList {
        islandShareTemplateTokens.forEach { token ->
            var index = previous.text.indexOf(token.value)
            while (index >= 0) {
                add(index until index + token.value.length)
                index = previous.text.indexOf(token.value, index + token.value.length)
            }
        }
    }

    if (oldStart == oldEnd) {
        val insertedInsideToken = tokenRanges.any { oldStart > it.first && oldStart <= it.last }
        return if (insertedInsideToken) previous else next
    }
    tokenRanges.forEach { range ->
        if (oldStart < range.last + 1 && oldEnd > range.first) {
            oldStart = minOf(oldStart, range.first)
            oldEnd = maxOf(oldEnd, range.last + 1)
        }
    }
    if (oldStart == prefix && oldEnd == previous.text.length - suffix) return next

    val inserted = next.text.substring(prefix, newEnd)
    val updated = previous.text.replaceRange(oldStart, oldEnd, inserted)
    return TextFieldValue(updated, TextRange(oldStart + inserted.length))
}
