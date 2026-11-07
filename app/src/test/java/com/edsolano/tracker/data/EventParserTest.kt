package com.edsolano.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EventParserTest {
    private val today = LocalDate.of(2026, 11, 7)

    private fun plan(title: String, desc: String, start: Long = 0, end: Long = 1, id: Long = 1) =
        EventParser.parsePlan(id, title, desc, start, end)

    @Test fun workoutPlanItem() {
        val p = plan("[Plan] Back + Chest", "type: workout\nslot: Evening\nmins: 60\ncal: 0\nprotein: 0")
        assertEquals("Back + Chest", p.name)
        assertEquals(PlanType.WORKOUT, p.type)
        assertEquals("Evening", p.slot)
        assertEquals(60, p.minutes)
        assertTrue(p.isWorkout)
        assertFalse(p.isFood)
        assertEquals(Targets.CAL_LIFT, DaySnapshot(today, listOf(p), null, 0).calorieTarget)
    }

    @Test fun mealWithSwapsAndUnknownKeys() {
        val p = plan("[Plan] Huel", "type: meal\nslot: Breakfast\ncal: 450\nprotein: 30\nfoo: bar\nswap: Huel | 400 | 40\nswap: bad line")
        assertEquals(PlanType.MEAL, p.type)
        assertEquals(450, p.calories)
        assertEquals(30, p.protein)
        assertEquals(listOf(Swap("Huel", 400, 40)), p.swaps)
        assertTrue(p.isFood)
    }

    @Test fun restDayWithoutWorkout() {
        val p = plan("[Plan] Walk", "type: walk\nmins: 30")
        assertEquals(Targets.CAL_REST, DaySnapshot(today, listOf(p), null, 0).calorieTarget)
    }

    @Test fun moneyTitleAndCharges() {
        val m = EventParser.parseMoney(today, "[Money] Spending left: $123.45",
            "pending | Chipotle | 17.40 | tip\npending | Target | 23.10\nposted | Starbucks | 6.25\nsomething else")!!
        assertEquals(123.45, m.spendingLeft, 0.001)
        assertEquals(2, m.pending.size)
        assertEquals(1, m.posted.size)
        assertTrue(m.pending[0].includesTip)
        assertFalse(m.pending[1].includesTip)
        assertEquals(17.40, m.pending[0].amount, 0.001)   // shown as written, no math
        assertEquals("Starbucks", m.posted[0].label)
    }

    @Test fun moneyAmountFormats() {
        assertEquals(-12.5, EventParser.parseMoney(today, "[Money] Spending left: -$12.50", null)!!.spendingLeft, 0.001)
        assertEquals(-12.5, EventParser.parseMoney(today, "[Money] Spending left: $-12.50", null)!!.spendingLeft, 0.001)
        assertEquals(1234.56, EventParser.parseMoney(today, "[Money] Spending left: $1,234.56", null)!!.spendingLeft, 0.001)
        assertNull(EventParser.parseMoney(today, "[Money] Spending left: ?", null))
    }

    @Test fun staleMoneyFlag() {
        val y = EventParser.parseMoney(today.minusDays(1), "[Money] Spending left: $5", null)
        assertTrue(DaySnapshot(today, emptyList(), y, 0).moneyIsStale)
    }

    @Test fun foodTotalsCountStartedMealsOnly() {
        val a = plan("[Plan] A", "type: meal\ncal: 400\nprotein: 40", 0, 10, 1)
        val b = plan("[Plan] B", "type: meal\ncal: 600\nprotein: 50", 100, 110, 2)
        val w = plan("[Plan] Lift", "type: workout\ncal: 0", 20, 30, 3)
        val s = DaySnapshot(today, listOf(a, w, b), null, 50)
        assertEquals(400, s.caloriesSoFar)
        assertEquals(40, s.proteinSoFar)
        assertEquals(listOf("B"), s.upcoming().map { it.name })
        assertEquals(2, s.meals.size)
    }

    @Test fun fitsWhatsLeftRespectsCalories() {
        val big = plan("[Plan] Dinner", "type: meal\ncal: 1800\nprotein: 100\nswap: Tofu bowl | 500 | 35", 0, 1)
        val s = DaySnapshot(today, listOf(big), null, 10)   // 100 cal left on a rest day
        assertTrue(s.fitsWhatsLeft().all { it.calories <= 100 })
        val fresh = DaySnapshot(today, listOf(plan("[Plan] Later", "type: meal\ncal: 500\nswap: Tofu bowl | 500 | 35", 100, 110)), null, 10)
        assertTrue(fresh.fitsWhatsLeft(20).any { it.name == "Tofu bowl" })
    }
}
