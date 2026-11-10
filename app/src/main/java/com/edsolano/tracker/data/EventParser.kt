package com.edsolano.tracker.data

import java.time.LocalDate

/**
 * Turns the calendar events Claude Duet writes into app data.
 * Pure Kotlin with no Android imports, so it is covered by unit tests.
 */
object EventParser {
    private const val PLAN_PREFIX = "[Plan]"
    private const val MONEY_PREFIX = "[Money]"
    private val keyValue = Regex("""^\s*([A-Za-z_ ]+?)\s*:\s*(.*?)\s*$""")

    fun isPlan(title: String?) = title?.trimStart()?.startsWith(PLAN_PREFIX, ignoreCase = true) == true
    fun isMoney(title: String?) = title?.trimStart()?.startsWith(MONEY_PREFIX, ignoreCase = true) == true

    /**
     * Description is "key: value" lines: type, slot, mins, cal, protein, swap, eaten, portion.
     * Unknown keys are ignored.
     *
     * "eaten" is the only line that says a meal actually happened. Without it, and
     * without a tap on the phone, the meal stays planned however long ago it was.
     */
    fun parsePlan(id: Long, title: String, description: String?, start: Long, end: Long): PlanItem {
        val name = title.trimStart().substring(PLAN_PREFIX.length).trim()
        var type: String? = null
        var slot: String? = null
        var mins: Int? = null
        var cal = 0
        var protein = 0
        var eaten: Boolean? = null
        var portion: Portion? = null
        val swaps = mutableListOf<Swap>()
        description.orEmpty().lines().forEach { line ->
            val m = keyValue.find(line) ?: return@forEach
            val value = m.groupValues[2]
            when (m.groupValues[1].trim().lowercase()) {
                "type" -> type = value
                "slot" -> slot = value.ifBlank { null }
                "mins" -> mins = int(value)
                "cal" -> cal = int(value) ?: 0
                "protein" -> protein = int(value) ?: 0
                "swap" -> parseSwap(value)?.let { swaps += it }
                "eaten" -> eaten = yesNo(value)
                "portion" -> portion = Portion.parse(value)
            }
        }
        return PlanItem(
            id, name, start, end, PlanType.from(type), slot, mins, cal, protein, swaps,
            eatenOnCalendar = eaten, portion = portion,
        )
    }

    /** Null for anything that isn't a clear yes or no, so a typo never counts a meal. */
    internal fun yesNo(value: String): Boolean? = when (value.trim().lowercase()) {
        "yes", "y", "true", "ate", "eaten", "1" -> true
        "no", "n", "false", "skipped", "skip", "0" -> false
        else -> null
    }

    /** "Huel | 400 | 40" → name | cal | protein */
    internal fun parseSwap(value: String): Swap? {
        val parts = value.split('|').map { it.trim() }
        if (parts.size < 3 || parts[0].isEmpty()) return null
        return Swap(parts[0], int(parts[1]) ?: return null, int(parts[2]) ?: return null)
    }

    /** Title "[Money] Spending left: $123.45"; description lines "pending | Chipotle | 17.40 | tip". */
    fun parseMoney(date: LocalDate, title: String, description: String?): MoneyDay? {
        val after = title.trimStart().substring(MONEY_PREFIX.length)
        val left = parseAmount(after.substringAfter(':', after)) ?: return null
        return MoneyDay(date, left, description.orEmpty().lines().mapNotNull { parseCharge(it) })
    }

    internal fun parseCharge(line: String): Charge? {
        val parts = line.split('|').map { it.trim() }
        if (parts.size < 3) return null
        val status = when (parts[0].lowercase()) {
            "pending" -> ChargeStatus.PENDING
            "posted" -> ChargeStatus.POSTED
            else -> return null
        }
        val amount = parseAmount(parts[2]) ?: return null
        val tip = parts.drop(3).any { it.equals("tip", ignoreCase = true) }
        return Charge(status, parts[1], amount, tip)
    }

    internal fun parseAmount(text: String): Double? {
        val m = Regex("""(-)?\s*\$?\s*(-)?(\d[\d,]*(?:\.\d+)?)""").find(text) ?: return null
        val v = m.groupValues[3].replace(",", "").toDoubleOrNull() ?: return null
        return if (m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty()) -v else v
    }

    private fun int(s: String) = Regex("""-?\d+""").find(s)?.value?.toIntOrNull()
}
