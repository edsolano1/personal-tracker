package com.edsolano.tracker.data

import android.content.Context

/**
 * The food Eddie has changed, and the foods he wants to keep.
 *
 * Edits live on the phone, keyed by the meal's [PlanItem.doneKey]. The plan on the
 * calendar is Claude's and is left exactly as written, so "back to the plan" is
 * always one tap away and nothing he changes here can corrupt the plan itself.
 */
class FoodStore(context: Context) {
    private val prefs = context.getSharedPreferences("food", Context.MODE_PRIVATE)

    // ---- edits to a particular meal ----

    fun edits(): Map<String, FoodEdit> = FoodCodec.edits(prefs.getString(EDITS, ""))

    fun save(key: String, edit: FoodEdit) = writeEdits(edits() + (key to edit))

    /** Back to whatever the calendar says. */
    fun clear(key: String) = writeEdits(edits() - key)

    private fun writeEdits(map: Map<String, FoodEdit>) {
        // Keep the newest 200; a day has a handful, so this only ever trims old days.
        val text = map.entries.toList().takeLast(200)
            .joinToString("\n") { FoodCodec.encode(it.key, it.value) }
        prefs.edit().putString(EDITS, text).apply()
    }

    // ---- his own foods, for reuse ----

    fun myFoods(): List<Food> = FoodCodec.foods(prefs.getString(FOODS, ""))

    /** Keeps a food at the portion he used, newest first; the same name replaces itself. */
    fun remember(food: Food) {
        if (food.name.isBlank()) return
        val next = (listOf(food) + myFoods().filterNot { it.name.equals(food.name, ignoreCase = true) })
            .take(40)
        prefs.edit().putString(FOODS, next.joinToString("\n") { FoodCodec.encode(it) }).apply()
    }

    fun forget(name: String) {
        val next = myFoods().filterNot { it.name.equals(name, ignoreCase = true) }
        prefs.edit().putString(FOODS, next.joinToString("\n") { FoodCodec.encode(it) }).apply()
    }

    private companion object {
        const val EDITS = "edits"
        const val FOODS = "foods"
    }
}
