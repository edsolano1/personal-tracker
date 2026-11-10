package com.edsolano.tracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.edsolano.tracker.data.Food
import com.edsolano.tracker.data.FoodEdit
import com.edsolano.tracker.data.Nutrition
import com.edsolano.tracker.data.PlanItem
import com.edsolano.tracker.data.Portion
import com.edsolano.tracker.data.PortionUnit
import com.edsolano.tracker.data.Portions
import com.edsolano.tracker.data.Swap
import com.edsolano.tracker.data.Targets

/**
 * Change a meal: a different food, his own portion, or numbers typed straight in.
 *
 * Calories and protein follow the portion while he leaves them alone. The moment he
 * types one himself it stops recalculating, because a number he typed is a number he
 * means. "Back to the plan" throws the whole edit away.
 */
@Composable
fun MealEditor(
    item: PlanItem,
    myFoods: List<Food>,
    onSave: (FoodEdit, Boolean) -> Unit,
    onBackToPlan: () -> Unit,
    onForgetFood: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Set when this is not an edit of a planned meal: adding a meal, or a food for the list. */
    title: String? = null,
    saveLabel: String = "Save",
    /** False when saving already means "keep this food", so the tick box would say nothing. */
    showKeep: Boolean = true,
) {
    val mineNames = remember(myFoods) { myFoods.map { it.name.trim().lowercase() }.toSet() }
    val choices = remember(item, myFoods) { choicesFor(item, myFoods) }

    var base by remember { mutableStateOf(item.asFood) }
    var name by remember { mutableStateOf(item.name) }
    var amountText by remember { mutableStateOf(Portions.number(item.portion?.amount ?: 1.0)) }
    var unit by remember { mutableStateOf(item.portion?.unit ?: PortionUnit.SERVING) }
    var manual by remember { mutableStateOf(false) }
    var calText by remember { mutableStateOf(item.calories.toString()) }
    var proteinText by remember { mutableStateOf(item.protein.toString()) }
    var keep by remember { mutableStateOf(false) }

    val wanted = Portions.amount(amountText)?.let { Portion(it, unit) }
    val scaled = wanted?.let { Nutrition.scale(base, it) }
    val calShown = if (manual) calText else scaled?.calories?.toString().orEmpty()
    val proteinShown = if (manual) proteinText else scaled?.protein?.toString().orEmpty()
    val cal = calShown.toIntOrNull()
    val protein = proteinShown.toIntOrNull()
    val canSave = name.isNotBlank() && cal != null && protein != null
    val shownName = name.ifBlank { "this food" }.take(28)

    fun pick(food: Food) {
        base = food
        name = food.name
        amountText = Portions.number(food.per.amount)
        unit = food.per.unit
        manual = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: if (item.edited) "Edit meal" else "Change this meal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Label("Food")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("What you actually had") },
                )

                Spacer(Modifier.height(12.dp))
                Label("Portion")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it; manual = false },
                        modifier = Modifier.width(110.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Spacer(Modifier.width(10.dp))
                    Muted(
                        wanted?.let { "of $shownName" }
                            ?: "Type an amount — 2, 1.5 or 1 1/2",
                        small = true,
                    )
                }
                Spacer(Modifier.height(8.dp))
                UnitPicker(unit) { unit = it; manual = false }
                Spacer(Modifier.height(8.dp))
                Muted(
                    when {
                        wanted == null -> "No amount yet."
                        base.calories == 0 && base.protein == 0 ->
                            "Pick a food below, or type the calories and protein."
                        scaled != null -> "${base.calories} cal and ${base.protein}g per " +
                            "${base.per.label} → ${scaled.calories} cal · ${scaled.protein}g"
                        else -> "Can't turn ${unit.words(2.0)} into ${base.per.unit.words(2.0)} " +
                            "for this food, so type the calories and protein yourself."
                    },
                    small = true,
                )

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(if (manual) "Calories and protein · typed in" else "Calories and protein")
                    Spacer(Modifier.width(8.dp))
                    if (manual) TextButton(onClick = { manual = false }) { Text("Use the portion") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = calShown,
                        onValueChange = { manual = true; calText = it },
                        modifier = Modifier.width(120.dp),
                        singleLine = true,
                        label = { Text("cal") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = proteinShown,
                        onValueChange = { manual = true; proteinText = it },
                        modifier = Modifier.width(120.dp),
                        singleLine = true,
                        label = { Text("protein g") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                Spacer(Modifier.height(4.dp))
                if (showKeep) Row(
                    Modifier.fillMaxWidth().clickable { keep = !keep },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = keep, onCheckedChange = { keep = it },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                    )
                    Muted("Save this food and portion so I can pick it again", small = true)
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Label("Or pick one")
                choices.forEach { food ->
                    FoodRow(
                        food = food,
                        mine = food.name.trim().lowercase() in mineNames,
                        onPick = { pick(food) },
                        onForget = { onForgetFood(food.name) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                // The portion is recorded whether or not the numbers came from it: an
                // amount he typed is worth showing, and it makes a saved food scale right.
                onClick = { onSave(FoodEdit(name.trim(), cal ?: 0, protein ?: 0, wanted), keep) },
            ) { Text(saveLabel) }
        },
        dismissButton = {
            Row {
                if (item.edited) TextButton(onClick = onBackToPlan) { Text("Back to plan") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Label(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = 6.dp),
)

/** Two rows of two, so the four units always fit however narrow the screen is. */
@Composable
private fun UnitPicker(selected: PortionUnit, onPick: (PortionUnit) -> Unit) {
    val units = PortionUnit.entries
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        units.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { u ->
                    val pad = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    if (u == selected) {
                        Button(onClick = { onPick(u) }, contentPadding = pad) { Text(u.choice) }
                    } else {
                        OutlinedButton(onClick = { onPick(u) }, contentPadding = pad) { Text(u.choice) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodRow(food: Food, mine: Boolean, onPick: () -> Unit, onForget: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(Modifier.weight(1f)) {
        Text(food.name, fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal)
        Muted("${food.calories} cal · ${food.protein}g per ${food.per.label}", small = true)
    }
    if (mine) TextButton(onClick = onForget) { Text("Forget") }
}

/**
 * What he can pick from: the plan's own food first, then the foods he has saved, then
 * the swaps Claude offered for this meal, then the vegetarian staples.
 */
private fun choicesFor(item: PlanItem, mine: List<Food>): List<Food> {
    val planned = item.planned ?: Swap(item.name, item.calories, item.protein)
    return (
        listOf(Food(planned.name, planned.calories, planned.protein)) +
            mine +
            item.swaps.map { Food(it.name, it.calories, it.protein) } +
            Targets.VEG_IDEAS.map { Food(it.name, it.calories, it.protein) }
        ).filter { it.name.isNotBlank() }.distinctBy { it.name.trim().lowercase() }
}
