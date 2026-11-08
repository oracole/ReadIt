package com.oracole.readit.data.ai

/**
 * 把一组义项压成一行释义
 *
 * 同一个词性只出现一次：AI 常把同一个 n. 拆成两三条返回
 * 直接拼会得到n. 甲 ；n. 乙，读起来像两个人写的
 * 这里按词性归并：一个词性一行，组内义项用分号连
 *
 * 词性为空的不加前缀，按首次出现的位置排列
 */
fun senseSummary(senses: List<SenseLine>): String {
    if (senses.isEmpty()) return ""

    // LinkedHashMap 保序：按词性首次出现的顺序排列
    val groups = LinkedHashMap<String, MutableList<String>>()
    for (s in senses) {
        val m = s.meaning.trim()
        if (m.isEmpty()) continue
        groups.getOrPut(s.pos.trim()) { mutableListOf() }.add(m)
    }
    if (groups.isEmpty()) return ""

    // 一个词性一行，同一词性内的多个义项用分号连
    // 依据《英汉百科翻译大词典》的体例：同一义项内的近义译法用逗号
    // 不同义项之间用分号。SenseLine 每条就是一个义项，所以这里该用分号
    return groups.entries.joinToString("\n") { (pos, meanings) ->
        if (pos.isBlank()) meanings.joinToString("；")
        else "$pos ${meanings.joinToString("；")}"
    }
}
