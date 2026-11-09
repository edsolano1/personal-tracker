package com.edsolano.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodEditTest {

    // ---- reading the amount he typed ----

    @Test fun amountsHeMightType() {
        assertEquals(2.0, Portions.amount("2")!!, 0.0001)
        assertEquals(1.5, Portions.amount(" 1.5 ")!!, 0.0001)
        assertEquals(1.5, Portions.amount("1,5")!!, 0.0001)
        assertEquals(0.75, Portions.amount("3/4")!!, 0.0001)
        assertEquals(1.5, Portions.amount("1 1/2")!!, 0.0001)
        assertNull(Portions.amount(""))
        assertNull(Portions.amount("a lot"))
        assertNull(Portions.amount("1/0"))
    }

    @Test fun numbersReadLikeNumbers() {
        assertEquals("2", Portions.number(2.0))
        assertEquals("1.5", Portions.number(1.5))
        assertEquals("180", Portions.number(180.0))
        assertEquals("1 serving", Portion(1.0, PortionUnit.SERVING).label)
        assertEquals("2 servings", Portion(2.0, PortionUnit.SERVING).label)
        assertEquals("180 g", Portion(180.0, PortionUnit.GRAM).label)
        assertEquals("1.5 cups", Portion(1.5, PortionUnit.CUP).label)
    }

    // ---- the portion arithmetic ----

    @Test fun servingsScaleStraightUp() {
        val huel = Food("Huel", 400, 40)
        val two = Nutrition.scale(huel, Portion(2.0, PortionUnit.SERVING))!!
        assertEquals(800, two.calories)
        assertEquals(80, two.protein)
        val half = Nutrition.scale(huel, Portion(0.5, PortionUnit.SERVING))!!
        assertEquals(200, half.calories)
        assertEquals(20, half.protein)
    }

    @Test fun gramsAndOuncesConvertBothWays() {
        // 100 g of tofu at 144 cal; 8 oz is 226.8 g, so about 2.27x.
        val tofu = Food("Tofu", 144, 17, Portion(100.0, PortionUnit.GRAM))
        val eightOz = Nutrition.scale(tofu, Portion(8.0, PortionUnit.OUNCE))!!
        assertEquals(327, eightOz.calories)
        assertEquals(39, eightOz.protein)

        val perOunce = Food("Seitan", 140, 28, Portion(4.0, PortionUnit.OUNCE))
        val inGrams = Nutrition.scale(perOunce, Portion(113.4, PortionUnit.GRAM))!!
        assertEquals(140, inGrams.calories)
        assertEquals(28, inGrams.protein)
    }

    @Test fun unitsThatCannotBeComparedRefuseToGuess() {
        val rice = Food("Rice", 200, 4, Portion(1.0, PortionUnit.CUP))
        assertNull(Nutrition.factor(rice.per, Portion(150.0, PortionUnit.GRAM)))
        assertNull(Nutrition.scale(rice, Portion(150.0, PortionUnit.GRAM)))
        // Same unit still works, so he can still say "two cups".
        assertEquals(400, Nutrition.scale(rice, Portion(2.0, PortionUnit.CUP))!!.calories)
    }

    @Test fun aZeroPortionIsZeroNotNothing() {
        val huel = Food("Huel", 400, 40)
        val none = Nutrition.scale(huel, Portion(0.0, PortionUnit.SERVING))!!
        assertEquals(0, none.calories)
        assertEquals(0, none.protein)
    }

    // ---- what gets stored ----

    @Test fun foodsSurviveARoundTrip() {
        val foods = listOf(
            Food("Huel", 400, 40),
            Food("Tofu scramble", 310, 26, Portion(1.5, PortionUnit.CUP)),
            Food("Seitan", 140, 28, Portion(113.0, PortionUnit.GRAM)),
        )
        val blob = foods.joinToString("\n") { FoodCodec.encode(it) }
        assertEquals(foods, FoodCodec.foods(blob))
    }

    @Test fun editsSurviveARoundTripIncludingOneWithNoPortion() {
        val typedIn = FoodEdit("Leftover pizza", 620, 24, null)
        val weighed = FoodEdit("Tofu", 327, 39, Portion(8.0, PortionUnit.OUNCE))
        val blob = listOf("12@100" to typedIn, "13@200" to weighed)
            .joinToString("\n") { FoodCodec.encode(it.first, it.second) }
        val back = FoodCodec.edits(blob)
        assertEquals(typedIn, back["12@100"])
        assertEquals(weighed, back["13@200"])
        assertNull(back["12@100"]!!.portion)
    }

    @Test fun rubbishLinesAreDroppedRatherThanCrashing() {
        assertTrue(FoodCodec.foods("\n\nnot a food\n").isEmpty())
        assertTrue(FoodCodec.edits("rubbish").isEmpty())
        assertNotNull(FoodCodec.food(FoodCodec.encode(Food("Has a | pipe", 100, 10))))
    }

    // ---- an edit replaces what the plan said ----

    @Test fun anEditedMealKeepsWhatThePlanSaid() {
        val planned = EventParser.parsePlan(7, "[Plan] Huel", "type: meal\ncal: 400\nprotein: 40", 0, 10)
        val edited = planned.copy(
            name = "Tofu scramble", calories = 620, protein = 48,
            portion = Portion(2.0, PortionUnit.SERVING),
            planned = Swap(planned.name, planned.calories, planned.protein),
        )
        assertTrue(edited.edited)
        assertEquals("Huel", edited.planned!!.name)
        assertEquals(400, edited.planned!!.calories)
        // The edited numbers are the ones that count, once he says he ate it.
        val day = DaySnapshot(
            java.time.LocalDate.of(2026, 11, 9), listOf(edited), null, 50,
            done = setOf(edited.doneKey),
        )
        assertEquals(620, day.caloriesSoFar)
        assertEquals(48, day.proteinSoFar)
    }

    // ---- the line written back to the calendar ----

    @Test fun theEatenLineIsAddedReplacedAndRemoved() {
        val desc = "type: meal\nslot: Dinner\ncal: 600\nprotein: 45"
        val yes = EatenLine.rewrite(desc, "yes")
        assertEquals("$desc\neaten: yes", yes)
        assertEquals("$desc\neaten: no", EatenLine.rewrite(yes, "no"))
        assertEquals(desc, EatenLine.rewrite(yes, null))
        // An existing line is replaced, not duplicated, wherever it sits.
        val middle = "type: meal\neaten: yes\ncal: 600"
        assertEquals("type: meal\ncal: 600\neaten: no", EatenLine.rewrite(middle, "no"))
        assertEquals("eaten: yes", EatenLine.rewrite("", "yes"))
        assertEquals("eaten: yes", EatenLine.rewrite("eaten: no", "yes"))
    }
}
