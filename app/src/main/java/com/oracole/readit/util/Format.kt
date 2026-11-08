package com.oracole.readit.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatWords(n: Int): String = when {
    n >= 10000 -> String.format(Locale.US, "%.1f 万字", n / 10000.0)
    n >= 1000 -> String.format(Locale.US, "%.1f 千词", n / 1000.0)
    else -> "$n 词"
}

fun formatRelative(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val min = 60_000L; val hour = 60 * min; val day = 24 * hour
    return when {
        diff < min -> "刚刚"
        diff < hour -> "${diff / min} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 2 * day -> "昨天"
        diff < 7 * day -> "${diff / day} 天前"
        diff < 365 * day -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(ts))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(ts))
    }
}
