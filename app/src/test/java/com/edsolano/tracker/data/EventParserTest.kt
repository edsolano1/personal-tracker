package com.edsolano.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventParserTest {
    @Test fun planItemReadsCaloriesAndProtein() {
        val p = EventParser.parsePlan(1, "[Plan] Huel", "cal: 450\nprotein: 30", 0, 1)
        assertEquals("Huel", p.name)
        assertEquals(450, p.calories)
        assertEquals(30, p.protein)
        assertFalse(p.isWorkout)
    }

    @Test fun workoutTagMakesLiftDay() {
        val p = EventParser.parsePlan(2, "[Plan] Back + Chest", "tag: workout", 0, 1)
        assertTrue(p.isWorkout)
        val s = DaySnapshot(listOf(p), null, 0)
        assertEquals(Targets.CAL_LIFT, s.calorieTarget)
        assertEquals(Targets.CAL_REST, DaySnapshot(emptyList(), null, 0).calorieTarget)
    }

    @Test fun hashtagWorkoutAlsoCounts() {
        assertTrue(EventParser.parsePlan(3, "[Plan] Legs", "#workout", 0, 1).isWorkout)
    }

    @Test fun moneyTitleParses() {
        val m = EventParser.parseMoney("[Money] Spending left: $123", null)
        assertNotNull(m); assertEquals(123.0, m!!.spendingLeft, 0.001)
        assertEquals(-12.5, EventParser.parseMoney("[Money] Spending left: -$12.50", null)!!.spendingLeft, 0.001)
        assertEquals(1234.56, EventParser.parseMoney("[Money] Spending left: $1,234.56", null)!!.spendingLeft, 0.001)
    }

    @Test fun pendingRestaurantGetsTipEstimate() {
        val m = EventParser.parseMoney(
            "[Money] Spending left: $100",
            "Pending:\n- Chipotle $20.00 (restaurant, pending)\n- Target $15.00 (pending)\n- Olive Garden $40 (restaurant)",
        )!!
        assertEquals(3, m.charges.size)
        assertEquals(4.0, m.tipEstimates, 0.001)      // only the pending restaurant one
        assertEquals(96.0, m.adjustedLeft, 0.001)
        assertEquals("Chipotle", m.charges[0].label)
    }

    @Test fun snapshotCountsOnlyStartedItems() {
        val a = EventParser.parsePlan(1, "[Plan] A", "cal: 400\nprotein: 40", 0, 10)
        val b = EventParser.parsePlan(2, "[Plan] B", "cal: 600\nprotein: 50", 100, 110)
        val s = DaySnapshot(listOf(a, b), null, 50)
        assertEquals(400, s.caloriesSoFar)
        assertEquals(1000, s.caloriesPlanned)
        assertEquals(listOf("B"), s.upcoming().map { it.name })
    }
}
