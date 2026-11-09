package com.edsolano.tracker

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.edsolano.tracker.data.CalendarRepository
import com.edsolano.tracker.data.DaySnapshot
import com.edsolano.tracker.data.DoneStore
import com.edsolano.tracker.data.EatenWriter
import com.edsolano.tracker.data.Food
import com.edsolano.tracker.data.FoodEdit
import com.edsolano.tracker.data.FoodStore
import com.edsolano.tracker.data.MealAnswer
import com.edsolano.tracker.data.MessageLog
import com.edsolano.tracker.data.MessageWriter
import com.edsolano.tracker.data.PlanItem
import com.edsolano.tracker.data.Portion
import com.edsolano.tracker.data.PortionUnit
import com.edsolano.tracker.ui.DebugScreen
import com.edsolano.tracker.ui.FoodScreen
import com.edsolano.tracker.ui.MealEditor
import com.edsolano.tracker.ui.MoneyScreen
import com.edsolano.tracker.ui.TodayScreen
import com.edsolano.tracker.ui.TrackerTheme
import com.edsolano.tracker.work.RefreshScheduler
import com.edsolano.tracker.work.refreshEverything
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val repo by lazy { CalendarRepository(this) }
    private val doneStore by lazy { DoneStore(this) }
    private val messages by lazy { MessageWriter(this) }
    private val messageLog by lazy { MessageLog(this) }
    private val foodStore by lazy { FoodStore(this) }
    private val eatenWriter by lazy { EatenWriter(this) }
    private var snapshot by mutableStateOf(DaySnapshot.empty())
    private var showDebug by mutableStateOf(false)
    private var hasCalendar by mutableStateOf(false)
    private var sendStatus by mutableStateOf<String?>(null)
    private var sent by mutableStateOf(emptyList<String>())
    private var editing by mutableStateOf<PlanItem?>(null)
    private var myFoods by mutableStateOf(emptyList<Food>())

    private val askPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!repo.hasPermission()) requestPerms()
        setContent { TrackerTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun requestPerms() = askPermissions.launch(
        arrayOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    )

    /**
     * Records an answer and folds it straight into the snapshot so the bars move at once,
     * then puts the same answer on the calendar event so Claude sees it too.
     */
    private fun answer(item: PlanItem, value: MealAnswer) {
        doneStore.mark(item.doneKey, value)
        snapshot = snapshot.copy(done = doneStore.done(), skipped = doneStore.skipped())
        // Only meals carry an "eaten" line; a tick on a workout stays on the phone.
        if (!item.isFood) return
        if (!eatenWriter.hasPermission()) requestPerms()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                eatenWriter.write(item.id, value)
                refreshEverything(this@MainActivity)
            }
        }
    }

    /**
     * The timeline checkbox. For a meal, unticking means he did not eat it, which is a
     * real answer and goes to the calendar; for anything else it is just a done check.
     */
    private fun toggle(item: PlanItem, checked: Boolean) {
        if (item.isFood) {
            answer(item, if (checked) MealAnswer.ATE else MealAnswer.SKIPPED)
        } else {
            doneStore.mark(item.doneKey, if (checked) MealAnswer.ATE else MealAnswer.UNANSWERED)
            snapshot = snapshot.copy(done = doneStore.done(), skipped = doneStore.skipped())
        }
    }

    /** Saves the change and, if he asked, keeps the food and portion for next time. */
    private fun saveEdit(item: PlanItem, edit: FoodEdit, keepFood: Boolean) {
        foodStore.save(item.doneKey, edit)
        if (keepFood) {
            foodStore.remember(
                Food(edit.name, edit.calories, edit.protein, edit.portion ?: Portion(1.0, PortionUnit.SERVING))
            )
        }
        editing = null
        reload()
    }

    private fun backToPlan(item: PlanItem) {
        foodStore.clear(item.doneKey)
        editing = null
        reload()
    }

    private fun send(text: String) {
        if (!messages.hasPermission()) {
            sendStatus = "Needs calendar write access. Allow it, then tap Send again."
            requestPerms()
            return
        }
        lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) { messages.send(text) }
            if (where == null) {
                sendStatus = "Couldn't write it to the calendar."
            } else {
                val stamp = java.time.LocalTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("h:mm a")
                )
                withContext(Dispatchers.IO) { messageLog.add("$stamp  ${text.trim()}") }
                sent = messageLog.recent()
                sendStatus = "Sent to $where."
            }
        }
    }

    private fun reload() {
        hasCalendar = repo.hasPermission()
        sent = messageLog.recent()
        myFoods = foodStore.myFoods()
        lifecycleScope.launch {
            snapshot = withContext(Dispatchers.IO) { repo.load() }
            if (hasCalendar) {
                RefreshScheduler.ensureScheduled(this@MainActivity)
                withContext(Dispatchers.IO) { refreshEverything(this@MainActivity) }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        BackHandler(enabled = showDebug) { showDebug = false }
        editing?.let { item ->
            MealEditor(
                item = item,
                myFoods = myFoods,
                onSave = { edit, keep -> saveEdit(item, edit, keep) },
                onBackToPlan = { backToPlan(item) },
                onForgetFood = { foodStore.forget(it); myFoods = foodStore.myFoods() },
                onDismiss = { editing = null },
            )
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(
                        "Today" to Icons.Filled.DateRange,
                        "Food" to Icons.Filled.Favorite,
                        "Money" to Icons.Filled.ShoppingCart,
                    ).forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i && !showDebug, onClick = { tab = i; showDebug = false },
                            icon = { Icon(icon, contentDescription = label) }, label = { Text(label) },
                        )
                    }
                }
            },
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(inner)) {
                if (!hasCalendar) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Personal Tracker reads your calendar to show your plan and money.")
                        Button(onClick = { requestPerms() }, Modifier.padding(top = 16.dp)) {
                            Text("Allow calendar access")
                        }
                    }
                } else if (showDebug) {
                    DebugScreen(snapshot, onBack = { showDebug = false })
                } else when (tab) {
                    0 -> TodayScreen(snapshot, onToggle = ::toggle, onDebug = { showDebug = true })
                    1 -> FoodScreen(
                        snapshot,
                        onAnswer = ::answer,
                        onEdit = { editing = it },
                        onSend = ::send,
                        sendStatus = sendStatus,
                        sent = sent,
                    )
                    else -> MoneyScreen(snapshot)
                }
            }
        }
    }
}
