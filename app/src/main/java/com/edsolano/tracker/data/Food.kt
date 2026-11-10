package com.edsolano.tracker.data

import kotlin.math.roundToInt

/**
 * Foods, portions, and the arithmetic that turns a portion into calories and
 * protein. Pure Kotlin with no Android imports, so the unit tests cover it.
 */
enum class PortionUnit(val choice: String, private val one: String, private val many: String) {
    SERVING("Servings", "serving", "servings"),
    GRAM("Grams", "g", "g"),
    OUNCE("Ounces", "oz", "oz"),
    CUP("Cups", "cup", "cups");

    fun words(amount: Double) = if (amount == 1.0) one else many

    companion object {
        /** Lenient on purpose: this reads whatever he typed or whatever was stored. */
        fun from(text: String?): PortionUnit {
            val t = text?.trim()?.lowercase().orEmpty()
            entries.firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { return it }
            return when {
                t.startsWith("serv") || t == "srv" || t == "x" -> SERVING
                t.startsWith("gram") || t == "g" -> GRAM
                t.startsWith("oz") || t.startsWith("ounce") -> OUNCE
                t.startsWith("cup") -> CUP
                else -> SERVING
            }
        }
    }
}

data class Portion(val amount: Double, val unit: PortionUnit) {
    /** "2 servings", "180 g", "1.5 cups" */
    val label get() = "${Portions.number(amount)} ${unit.words(amount)}"

    companion object {
        /** Reads a label back: "180 g", "2 servings", "1 1/2 cups". Null when there is no amount. */
        fun parse(text: String): Portion? {
            val t = text.trim()
            val cut = t.lastIndexOf(' ')
            if (cut <= 0) return null
            val amount = Portions.amount(t.substring(0, cut)) ?: return null
            return if (amount > 0.0) Portion(amount, PortionUnit.from(t.substring(cut + 1))) else null
        }
    }
}

/** A food and what it is worth at one reference portion. */
data class Food(
    val name: String,
    val calories: Int,
    val protein: Int,
    val per: Portion = Portion(1.0, PortionUnit.SERVING),
)

/** What Eddie changed a meal to. Held on the phone; the plan on the calendar is left alone. */
data class FoodEdit(
    val name: String,
    val calories: Int,
    val protein: Int,
    val portion: Portion?,
)

object Portions {
    /** Drops a trailing ".0" so a whole number reads as one. Always a dot, never a comma,
     *  because this text is also what gets stored and read back. */
    fun number(v: Double): String {
        if (v == v.toLong().toDouble()) return v.toLong().toString()
        return String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    /**
     * Reads an amount the way he would write it: "2", "1.5", "1,5", "3/4", "1 1/2".
     * Null when it isn't a usable number, which keeps the editor from inventing one.
     */
    fun amount(text: String): Double? {
        val cleaned = text.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val total = cleaned.split(' ', '\t').filter { it.isNotBlank() }
            .map { part -> fraction(part) ?: return null }
            .sum()
        return total.takeIf { it >= 0.0 }
    }

    private fun fraction(part: String): Double? {
        if ('/' !in part) return part.toDoubleOrNull()
        val halves = part.split('/')
        if (halves.size != 2) return null
        val top = halves[0].trim().toDoubleOrNull() ?: return null
        val bottom = halves[1].trim().toDoubleOrNull() ?: return null
        return if (bottom == 0.0) null else top / bottom
    }
}

object Nutrition {
    private const val GRAMS_PER_OUNCE = 28.349523125

    private fun grams(p: Portion): Double? = when (p.unit) {
        PortionUnit.GRAM -> p.amount
        PortionUnit.OUNCE -> p.amount * GRAMS_PER_OUNCE
        else -> null
    }

    /**
     * How many of [per] the portion [want] is, or null when the two can't be compared.
     * Grams and ounces convert; cups of rice are not grams of rice without knowing the
     * density, and a guess would put wrong numbers in his day — so the editor asks him
     * for the calories instead of pretending.
     */
    fun factor(per: Portion, want: Portion): Double? {
        if (per.amount <= 0.0 || want.amount < 0.0) return null
        if (per.unit == want.unit) return want.amount / per.amount
        val a = grams(per) ?: return null
        val b = grams(want) ?: return null
        return if (a > 0.0) b / a else null
    }

    /** The same food at a different portion, with the numbers moved with it. */
    fun scale(food: Food, want: Portion): Food? {
        val f = factor(food.per, want) ?: return null
        return food.copy(
            calories = (food.calories * f).roundToInt(),
            protein = (food.protein * f).roundToInt(),
            per = want,
        )
    }
}

/**
 * Stores foods and meal edits as lines of separated fields. Plain text rather than
 * JSON so it needs no library and stays readable in the prefs file.
 */
internal object FoodCodec {
    private const val SEP = "\u001F"

    private fun clean(s: String) = s.replace(SEP, " ").replace('\n', ' ').trim()

    fun encode(food: Food) = listOf(
        clean(food.name), food.calories.toString(), food.protein.toString(),
        Portions.number(food.per.amount), food.per.unit.name,
    ).joinToString(SEP)

    fun food(line: String): Food? {
        val p = line.split(SEP)
        if (p.size < 5 || p[0].isBlank()) return null
        val cal = p[1].toIntOrNull() ?: return null
        val protein = p[2].toIntOrNull() ?: return null
        val amount = p[3].toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
        return Food(p[0], cal, protein, Portion(amount, PortionUnit.from(p[4])))
    }

    fun encode(key: String, edit: FoodEdit) = listOf(
        clean(key), clean(edit.name), edit.calories.toString(), edit.protein.toString(),
        edit.portion?.let { Portions.number(it.amount) }.orEmpty(),
        edit.portion?.unit?.name.orEmpty(),
    ).joinToString(SEP)

    fun edit(line: String): Pair<String, FoodEdit>? {
        val p = line.split(SEP)
        if (p.size < 6 || p[0].isBlank() || p[1].isBlank()) return null
        val cal = p[2].toIntOrNull() ?: return null
        val protein = p[3].toIntOrNull() ?: return null
        val amount = p[4].toDoubleOrNull()?.takeIf { it > 0.0 }
        val portion = amount?.let { Portion(it, PortionUnit.from(p[5])) }
        return p[0] to FoodEdit(p[1], cal, protein, portion)
    }

    fun foods(blob: String?): List<Food> = blob.orEmpty().lines().mapNotNull { food(it) }

    fun edits(blob: String?): Map<String, FoodEdit> =
        blob.orEmpty().lines().mapNotNull { edit(it) }.toMap()
}
