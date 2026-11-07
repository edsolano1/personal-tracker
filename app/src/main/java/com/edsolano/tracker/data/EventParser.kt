package com.edsolano.tracker.data

/**
 * Turns the calendar events Claude Duet writes into app data.
 * Pure Kotlin with no Android imports, so it is covered by unit tests.
 */
object EventParser {
    private const val PLAN_PREFIX = "[Plan]"
    private const val MONEY_PREFIX = "[Money]"
    private val amountRegex = Regex("""-?\$?\s*(-?\d[\d,]*(?:\.\d+)?)""")
    private val keyValue = Regex("""^\s*[-*•]?\s*([A-Za-z ]+?)\s*[:=]\s*(.+?)\s*$""")
    private val restaurantWords = listOf("restaurant", "dining", "dine", "food & drink", "bar", "cafe", "café")

    fun isPlan(title: String?) = title?.trimStart()?.startsWith(PLAN_PREFIX, ignoreCase = true) == true
    fun isMoney(title: String?) = title?.trimStart()?.startsWith(MONEY_PREFIX, ignoreCase = true) == true

    fun parsePlan(id: Long, title: String, description: String?, start: Long, end: Long): PlanItem {
        val name = title.trimStart().substring(PLAN_PREFIX.length).trim()
        var cal = 0
        var protein = 0
        var workout = false
        val desc = description.orEmpty()
        desc.lines().forEach { line ->
            val m = keyValue.find(line) ?: return@forEach
            val key = m.groupValues[1].trim().lowercase()
            val value = m.groupValues[2]
            when (key) {
                "cal", "cals", "calories", "kcal" -> cal = firstInt(value) ?: cal
                "protein", "prot" -> protein = firstInt(value) ?: protein
                "tag", "tags", "type", "kind", "category" ->
                    if (value.contains("workout", ignoreCase = true)) workout = true
            }
        }
        if (Regex("""#workout\b""", RegexOption.IGNORE_CASE).containsMatchIn(desc)) workout = true
        return PlanItem(id, name, start, end, cal, protein, workout)
    }

    /** "[Money] Spending left: $123" plus an optional list of charges in the description. */
    fun parseMoney(title: String, description: String?): MoneyDay? {
        val afterPrefix = title.trimStart().substring(MONEY_PREFIX.length)
        val left = parseAmount(afterPrefix.substringAfter(":", afterPrefix)) ?: return null
        val charges = description.orEmpty().lines().mapNotNull { parseCharge(it) }
        return MoneyDay(left, charges)
    }

    /**
     * One charge per line, e.g. "- Chipotle $14.50 (restaurant, pending)".
     * A line counts as a charge if it has a dollar amount; "pending" and
     * restaurant words are picked up anywhere in the line.
     */
    internal fun parseCharge(raw: String): Charge? {
        val line = raw.trim().trimStart('-', '*', '•').trim()
        if (line.isEmpty() || !line.contains('$')) return null
        val amount = parseAmount(line.substring(line.indexOf('$'))) ?: return null
        val lower = line.lowercase()
        val label = line.substringBefore('$').trim().trimEnd(':', '-', '–').trim().ifEmpty { "Charge" }
        return Charge(
            label = label,
            amount = kotlin.math.abs(amount),
            isRestaurant = restaurantWords.any { lower.contains(it) },
            isPending = lower.contains("pending"),
        )
    }

    internal fun parseAmount(text: String): Double? {
        val m = amountRegex.find(text) ?: return null
        val neg = text.substring(0, m.range.first + 1).contains('-') || m.groupValues[1].startsWith("-")
        val v = m.groupValues[1].replace(",", "").trimStart('-').toDoubleOrNull() ?: return null
        return if (neg) -v else v
    }

    private fun firstInt(s: String) = Regex("""\d+""").find(s)?.value?.toIntOrNull()
}
