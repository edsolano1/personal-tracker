package com.edsolano.tracker.data

data class PlanItem(
    val id: Long,
    val name: String,          // title with the "[Plan]" prefix removed
    val startMillis: Long,
    val endMillis: Long,
    val calories: Int,
    val protein: Int,
    val isWorkout: Boolean,
)

data class Charge(
    val label: String,
    val amount: Double,
    val isRestaurant: Boolean,
    val isPending: Boolean,
) {
    /** 20% tip estimate, only while a restaurant charge is still pending. */
    val tipEstimate: Double get() = if (isRestaurant && isPending) amount * Targets.TIP_RATE else 0.0
}

data class MoneyDay(
    val spendingLeft: Double,  // the number Claude Duet writes in the title
    val charges: List<Charge>,
) {
    val tipEstimates: Double get() = charges.sumOf { it.tipEstimate }
    /** What's really left once pending restaurant tips are counted. */
    val adjustedLeft: Double get() = spendingLeft - tipEstimates
}

data class DaySnapshot(
    val plan: List<PlanItem>,
    val money: MoneyDay?,
    val nowMillis: Long,
) {
    val isLiftDay: Boolean get() = plan.any { it.isWorkout }
    val calorieTarget: Int get() = if (isLiftDay) Targets.CAL_LIFT else Targets.CAL_REST
    val proteinTarget: Int get() = Targets.PROTEIN

    /** Items whose start time has passed count as eaten. */
    private val done get() = plan.filter { it.startMillis <= nowMillis }
    val caloriesSoFar: Int get() = done.sumOf { it.calories }
    val proteinSoFar: Int get() = done.sumOf { it.protein }
    val caloriesPlanned: Int get() = plan.sumOf { it.calories }
    val proteinPlanned: Int get() = plan.sumOf { it.protein }

    fun upcoming(limit: Int = 4): List<PlanItem> =
        plan.filter { it.endMillis > nowMillis }.sortedBy { it.startMillis }.take(limit)
}

object Targets {
    const val PROTEIN = 165
    const val CAL_REST = 1900
    const val CAL_LIFT = 2200
    const val TIP_RATE = 0.20
}
