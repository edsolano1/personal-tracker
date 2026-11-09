package com.edsolano.tracker.data

import java.time.LocalDate

enum class PlanType { MEAL, WORKOUT, WALK, TASK, EVENT, OTHER;
    companion object {
        fun from(s: String?) = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: OTHER
    }
}

data class Swap(val name: String, val calories: Int, val protein: Int)

data class PlanItem(
    val id: Long,
    val name: String,          // title with the "[Plan]" prefix removed
    val startMillis: Long,
    val endMillis: Long,
    val type: PlanType,
    val slot: String?,
    val minutes: Int?,
    val calories: Int,
    val protein: Int,
    val swaps: List<Swap>,
) {
    val isWorkout get() = type == PlanType.WORKOUT
    val isFood get() = type == PlanType.MEAL || (type == PlanType.OTHER && (calories > 0 || protein > 0))
    /** Stable key for the local "done" check. */
    val doneKey get() = "$id@$startMillis"
}

/** Whether a meal on the plan actually happened. Nothing is assumed from the clock. */
enum class MealAnswer { UNANSWERED, ATE, SKIPPED }

enum class ChargeStatus { PENDING, POSTED }

/** One charge line, shown as written. Duet's amounts are final; the app does no math on them. */
data class Charge(val status: ChargeStatus, val label: String, val amount: Double, val includesTip: Boolean)

data class MoneyDay(
    val date: LocalDate,
    val spendingLeft: Double,  // final number from the title: carryover, pending and tips included
    val charges: List<Charge>,
) {
    val pending get() = charges.filter { it.status == ChargeStatus.PENDING }
    val posted get() = charges.filter { it.status == ChargeStatus.POSTED }
}

/** What the debug screen shows for every calendar event the app looked at. */
data class RawEvent(
    val calendar: String,
    val title: String,
    val description: String,
    val begin: Long,
    val allDay: Boolean,
    val parsedAs: String,
)

data class DaySnapshot(
    val date: LocalDate,
    val plan: List<PlanItem>,
    val money: MoneyDay?,
    val nowMillis: Long,
    val calendarFound: Boolean = false,
    val raw: List<RawEvent> = emptyList(),
    val done: Set<String> = emptySet(),
    val skipped: Set<String> = emptySet(),
) {
    val isLiftDay get() = plan.any { it.isWorkout }
    val calorieTarget get() = if (isLiftDay) Targets.CAL_LIFT else Targets.CAL_REST
    val proteinTarget get() = Targets.PROTEIN

    val meals get() = plan.filter { it.isFood }

    fun answerFor(item: PlanItem) = when (item.doneKey) {
        in done -> MealAnswer.ATE
        in skipped -> MealAnswer.SKIPPED
        else -> MealAnswer.UNANSWERED
    }

    /** Only meals you confirmed count. The clock passing a meal proves nothing. */
    private val eaten get() = meals.filter { it.doneKey in done }

    /** Meals whose time has come and gone that you haven't answered yet. */
    val mealsToAnswer get() = meals
        .filter { it.startMillis <= nowMillis && answerFor(it) == MealAnswer.UNANSWERED }
        .sortedBy { it.startMillis }

    val caloriesSoFar get() = eaten.sumOf { it.calories }
    val proteinSoFar get() = eaten.sumOf { it.protein }
    val caloriesLeft get() = (calorieTarget - caloriesSoFar).coerceAtLeast(0)
    val proteinLeft get() = (proteinTarget - proteinSoFar).coerceAtLeast(0)

    val moneyIsStale get() = money != null && money.date != date

    fun upcoming(limit: Int = 4): List<PlanItem> =
        plan.filter { it.endMillis > nowMillis }.sortedBy { it.startMillis }.take(limit)

    /**
     * "Fits what's left": swap options from today's meals plus vegetarian staples
     * that fit the remaining calories, best protein per calorie first.
     */
    fun fitsWhatsLeft(limit: Int = 5): List<Swap> {
        if (caloriesLeft <= 0) return emptyList()
        val options = (plan.flatMap { it.swaps } + Targets.VEG_IDEAS).distinctBy { it.name.lowercase() }
        return options.filter { it.calories in 1..caloriesLeft }
            .sortedByDescending { it.protein.toDouble() / it.calories }
            .take(limit)
    }

    companion object { fun empty(date: LocalDate = LocalDate.now()) = DaySnapshot(date, emptyList(), null, System.currentTimeMillis()) }
}

object Targets {
    const val PROTEIN = 165
    const val CAL_REST = 1900
    const val CAL_LIFT = 2200
    const val CALENDAR_NAME = "Personal Tracker"

    /** Vegetarian fallback ideas (name, cal, protein) used when no swaps fit. */
    val VEG_IDEAS = listOf(
        Swap("Huel", 400, 40),
        Swap("Greek yogurt (2 cups, nonfat)", 260, 46),
        Swap("Protein shake (whey, water)", 120, 25),
        Swap("Cottage cheese (1 cup)", 180, 25),
        Swap("Seitan (4 oz)", 140, 28),
        Swap("Tofu, extra firm (half block)", 190, 22),
        Swap("Egg whites (1 cup)", 125, 26),
        Swap("Edamame (1 cup)", 190, 18),
        Swap("Lentil soup (2 cups)", 320, 20),
        Swap("Tempeh (4 oz)", 220, 21),
    )
}
