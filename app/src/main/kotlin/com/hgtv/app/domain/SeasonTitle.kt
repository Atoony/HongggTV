package com.hgtv.app.domain

import java.text.Normalizer

/**
 * 标题季语义（CONTEXT.md 季）：从短剧标题解析"第 N 季/部"。
 * 票14 从 net/HongguoWeb 迁入 domain——标题→季是领域规则，UI（搜索结果季号角标）
 * 与数据源层共用；net 旧链在票15 删除前继续 import 本文件。
 */

private val SEASON_SUFFIX = Regex("第\\s*([0-9零〇一二两兩三四五六七八九十百]+)\\s*([季部])[\\p{P}\\s]*$")

data class SeasonInfo(val base: String, val season: Int, val unit: String, val label: String)

/** 标题季号解析：(base, season, unit, label)；无季返回 ("",0,"","")。 */
fun seasonOf(title: String): SeasonInfo {
    val t = Normalizer.normalize(title.trim(), Normalizer.Form.NFKC)
    val match = SEASON_SUFFIX.find(t) ?: return SeasonInfo("", 0, "", "")
    val base = t.substring(0, match.range.first).trimEnd { it.isWhitespace() || !it.isLetterOrDigit() }
    val label = match.groupValues[1]
    val unit = match.groupValues[2]
    val number = parseCnNumber(label) ?: return SeasonInfo("", 0, "", "")
    if (base.isEmpty() || number < 1 || number > 200) return SeasonInfo("", 0, "", "")
    return SeasonInfo(base, number, unit, label)
}

internal fun parseCnNumber(label: String): Int? {
    label.toIntOrNull()?.let { return it }
    val digits = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '兩' to 2,
        '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    if (!label.any { it == '十' || it == '百' }) {
        var n = 0
        for (c in label) {
            val v = digits[c] ?: return null
            n = n * 10 + v
            if (n > 200) return null
        }
        return n
    }
    var digit = 0
    var previous = 1000
    var number = 0
    for (c in label) {
        val v = digits[c]
        if (v != null) {
            digit = digit * 10 + v
            continue
        }
        val unitVal = when (c) {
            '十' -> 10
            '百' -> 100
            else -> return null
        }
        if (unitVal >= previous || digit > 9) return null
        number += maxOf(digit, 1) * unitVal
        digit = 0
        previous = unitVal
    }
    return number + digit
}

fun chineseSeason(number: Int): String {
    val digits = "零一二三四五六七八九"
    // 千位兜底退化数据（seasonOf 上限 200，但 mapAlbum 的 index 来自 SDK 不设限）：
    // digits[n/100] 千位越界直接闪退，改回阿拉伯数字不炸组合期
    if (number > 999 || number < 0) return number.toString()
    if (number < 10) return digits[number].toString()
    if (number < 100) {
        var prefix = ""
        if (number >= 20) prefix = digits[number / 10].toString()
        prefix += "十"
        if (number % 10 != 0) prefix += digits[number % 10]
        return prefix
    }
    var prefix = digits[number / 100].toString() + "百"
    val rest = number % 100
    if (rest == 0) return prefix
    prefix += when {
        rest < 10 -> "零"
        rest < 20 -> "一"
        else -> ""
    }
    return prefix + chineseSeason(rest)
}
