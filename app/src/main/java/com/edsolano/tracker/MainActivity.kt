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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import com.edsolano.tracker.data.IcsFeed
import com.edsolano.tracker.data.MealAnswer
import com.edsolano.tracker.data.MessageLog
import com.edsolano.tracker.data.MessageWriter
import com.edsolano.tracker.data.PlanItem
import com.edsolano.tracker.data.PlanType
import com.edsolano.tracker.data.PlanWriter
import com.edsolano.tracker.data.Portion
import com.edsolano.tracker.data.PortionUnit
import com.edsolano.tracker.ui.DebugScreen
import com.edsolano.tracker.ui.FeedLinkDialog
import com.edsolano.tracker.ui.Format
import com.edsolano.tracker.ui.FoodScreen
import com.edsolano.tracker.ui.MealEditor
import com.edsolano.tracker.ui.MoneyScreen
import com.edsolano.tracker.ui.TodayScreen
import com.edsolano.tracker.ui.HomeScreen
import com.edsolano.tracker.ui.sectionTransition
import com.edsolano.tracker.ui.TrackerTheme
import com.edsolano.tracker.work.RefreshScheduler
import com.edsolano.tracker.work.refreshEverything
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the food editor is open for. */
private enum class EditMode { EDIT, ADD, LIBRARY }

/** The web app holding Home, Today and Money. */
private const val WEB_APP = "https://edsolano1.github.io/personal-tracker/"

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
    private val planWriter by lazy { PlanWriter(this) }
    private var editMode by mutableStateOf(EditMode.EDIT)
    private var addStatus by mutableStateOf<String?>(null)
    private var refreshing by mutableStateOf(false)
    private var lastSyncAsk = 0L
    private var lastUpdateCheck = 0L
    private val feed by lazy { IcsFeed(this) }
    private var linkSet by mutableStateOf(false)
    private var freshness by mutableStateOf("")
    private var showLink by mutableStateOf(false)
    private val walletWeb by lazy {
        com.edsolano.tracker.wallet.Wallet.newWebView(this).also {
            it.loadUrl(com.edsolano.tracker.wallet.Wallet.url(this@MainActivity))
        }
    }
    private var openTab by mutableIntStateOf(-1)

    /**
     * Home, Today and Money are web pages (edsolano1.github.io/personal-tracker), so a
     * change to them reaches the phone without an install. The page reads the phone's
     * calendar and acts on it through the `App` bridge below. Food is still native.
     */
    private val appWeb by lazy {
        android.webkit.WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            setBackgroundColor(android.graphics.Color.parseColor("#0E1411"))
            addJavascriptInterface(AppBridge(), "App")
            webViewClient = android.webkit.WebViewClient()
            webChromeClient = android.webkit.WebChromeClient()
            loadUrl(WEB_APP)
        }
    }

    private inner class AppBridge {
        @android.webkit.JavascriptInterface
        fun snapshot(): String = snapshotJson()

        @android.webkit.JavascriptInterface
        fun walletUrl(): String = com.edsolano.tracker.wallet.Wallet.url(this@MainActivity)

        @android.webkit.JavascriptInterface
        fun version(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

        @android.webkit.JavascriptInterface
        fun toggle(key: String, checked: Boolean) = runOnUiThread {
            snapshot.plan.firstOrNull { it.doneKey == key }?.let { toggle(it, checked) }
        }

        @android.webkit.JavascriptInterface
        fun refresh() = runOnUiThread { refresh() }

        @android.webkit.JavascriptInterface
        fun open(what: String) = runOnUiThread {
            when (what) {
                "food" -> openTab = 1
                "raw" -> showDebug = true
                "link" -> showLink = true
            }
        }
    }

    private fun snapshotJson(): String {
        val s = snapshot
        val w = getSharedPreferences("wallet", MODE_PRIVATE)
        fun ev(e: com.edsolano.tracker.data.CalEvent) = org.json.JSONObject()
            .put("title", e.title).put("begin", e.begin).put("end", e.end)
            .put("allDay", e.allDay).put("shared", e.calendar.contains("shared", true))
        fun item(p: PlanItem) = org.json.JSONObject()
            .put("key", p.doneKey).put("name", p.name).put("begin", p.startMillis).put("end", p.endMillis)
            .put("type", p.type.name.lowercase()).put("food", p.isFood)
            .put("calories", p.calories).put("protein", p.protein)
            .put("done", s.answerFor(p) == MealAnswer.ATE)
        return org.json.JSONObject()
            .put("date", s.date.toString()).put("now", System.currentTimeMillis())
            .put("calendar", hasCalendar)
            .put("events", org.json.JSONArray(s.events.map(::ev)))
            .put("plan", org.json.JSONArray(s.plan.map(::item)))
            .put("food", org.json.JSONObject()
                .put("calories", s.caloriesSoFar).put("calorieTarget", s.calorieTarget)
                .put("protein", s.proteinSoFar).put("proteinTarget", s.proteinTarget)
                .put("liftDay", s.isLiftDay))
            .put("wallet", org.json.JSONObject()
                .put("big", w.getString("big", "")).put("eyebrow", w.getString("eyebrow", ""))
                .put("pill", w.getString("pill", "")))
            .put("refreshing", refreshing).put("freshness", freshness)
            .toString()
    }

    private val askPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!repo.hasPermission()) requestPerms()
        openTab = intent?.getIntExtra("tab", -1) ?: -1
        setContent { TrackerTheme { Root() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val t = intent.getIntExtra("tab", -1)
        if (t >= 0) openTab = t
    }

    /** Notifications and widgets ask for a tab: Food is native, the rest are pages in the web app. */
    private fun route(t: Int): Int {
        if (t == 0 || t == 2) appWeb.evaluateJavascript("window.go && go('${if (t == 0) "today" else "money"}')", null)
        return if (t == 1) 1 else -1
    }

    override fun onResume() {
        super.onResume()
        // Look for a new version every time the app comes forward, not only on a cold start
        // (Android keeps the app alive in the background, so onCreate rarely runs).
        if (System.currentTimeMillis() - lastUpdateCheck > 5 * 60_000) {
            lastUpdateCheck = System.currentTimeMillis()
            lifecycleScope.launch { com.edsolano.tracker.update.Updater.check(this@MainActivity) }
        }
        reload()
        // Google syncs this calendar by itself about once a day, so nudge it when the
        // app comes forward, but not more than once every ten minutes.
        if (System.currentTimeMillis() - lastSyncAsk > 2 * 60 * 1000) refresh(showBusy = false)
    }

    /** Asks the calendar to sync, then re-reads it a couple of times as the sync lands. */
    private fun refresh(showBusy: Boolean = true) {
        if (!repo.hasPermission()) return
        lastSyncAsk = System.currentTimeMillis()
        if (showBusy) refreshing = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                planWriter.requestSync()
                feed.fetch()
            }
            reload()
            refreshing = false
            if (feed.url == null) {
                // Without the link the only hope is the phone's own sync landing.
                delay(8_000)
                reload()
            }
        }
    }

    /** Opens the editor on a meal that isn't on the plan, optionally starting from a food. */
    private fun startAdd(food: Food?, mode: EditMode = EditMode.ADD) {
        val now = System.currentTimeMillis()
        editMode = mode
        editing = PlanItem(
            id = -1, name = food?.name.orEmpty(), startMillis = now, endMillis = now,
            type = PlanType.MEAL, slot = null, minutes = null,
            calories = food?.calories ?: 0, protein = food?.protein ?: 0, swaps = emptyList(),
            portion = food?.per,
        )
    }

    private fun asFood(edit: FoodEdit) =
        Food(edit.name, edit.calories, edit.protein, edit.portion ?: Portion(1.0, PortionUnit.SERVING))

    /** Writes an off-plan meal onto the calendar, already marked eaten. */
    private fun saveAdded(edit: FoodEdit, keepFood: Boolean) {
        editing = null
        if (keepFood) foodStore.remember(asFood(edit))
        if (!planWriter.hasPermission()) {
            addStatus = "Needs calendar write access. Allow it, then add it again."
            requestPerms()
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                planWriter.addMeal(edit).also { if (it) planWriter.requestSync() }
            }
            addStatus = if (ok) "Added ${edit.name}." else "Couldn't write it to the calendar."
            reload()
        }
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
        if (keepFood) foodStore.remember(asFood(edit))
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
        linkSet = feed.url != null
        freshness = when {
            !linkSet -> "Phone calendar read at ${Format.time(System.currentTimeMillis())}"
            feed.lastError != null -> "Couldn't reach Google: ${feed.lastError}"
            feed.fetchedAt > 0 -> "Google read at ${Format.time(feed.fetchedAt)}"
            else -> "Reading Google…"
        }
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
        var tab by rememberSaveable { mutableIntStateOf(-1) }  // -1 = the web app, 1 = Food
        BackHandler(enabled = showDebug) { showDebug = false }
        if (openTab >= 0) { tab = route(openTab); openTab = -1 }
        // Food goes back to the web app; in the web app, back walks its page history, then leaves.
        BackHandler(enabled = tab >= 0 && !showDebug) { tab = -1 }
        BackHandler(enabled = tab < 0 && !showDebug) {
            if (appWeb.canGoBack()) appWeb.goBack() else finish()
        }
        if (showLink) {
            FeedLinkDialog(
                isSet = linkSet,
                onSave = { feed.url = it; showLink = false; refresh() },
                onDismiss = { showLink = false },
            )
        }
        editing?.let { item ->
            MealEditor(
                item = item,
                myFoods = myFoods,
                onSave = { edit, keep ->
                    when (editMode) {
                        EditMode.EDIT -> saveEdit(item, edit, keep)
                        EditMode.ADD -> saveAdded(edit, keep)
                        EditMode.LIBRARY -> {
                            foodStore.remember(asFood(edit))
                            editing = null
                            myFoods = foodStore.myFoods()
                        }
                    }
                },
                onBackToPlan = { backToPlan(item) },
                onForgetFood = { foodStore.forget(it); myFoods = foodStore.myFoods() },
                onDismiss = { editing = null },
                title = when (editMode) {
                    EditMode.EDIT -> null
                    EditMode.ADD -> "Add a meal"
                    EditMode.LIBRARY -> "New food"
                },
                saveLabel = if (editMode == EditMode.ADD) "Add" else "Save",
                showKeep = editMode != EditMode.LIBRARY,
            )
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
        ) { inner ->
            val go: (Int) -> Unit = { tab = it }
            androidx.compose.runtime.LaunchedEffect(Unit) { walletWeb }  // keeps the home screen's wallet number fresh
            androidx.compose.animation.AnimatedContent(
                targetState = tab, transitionSpec = { sectionTransition() }, label = "section",
                modifier = Modifier.fillMaxSize().padding(inner),
            ) { tab ->
            Column(Modifier.fillMaxSize()) {
                if (showDebug) {
                    DebugScreen(snapshot, onBack = { showDebug = false })
                } else if (tab < 0) {
                    com.edsolano.tracker.ui.WalletScreen(appWeb)
                } else if (!hasCalendar) {
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
                    0 -> TodayScreen(
                        snapshot, onToggle = ::toggle, onDebug = { showDebug = true },
                        onRefresh = { refresh() }, refreshing = refreshing,
                        freshness = freshness, linkSet = linkSet, onLink = { showLink = true },
                    )
                    1 -> FoodScreen(
                        snapshot,
                        onAnswer = ::answer,
                        onEdit = { editMode = EditMode.EDIT; editing = it },
                        onSend = ::send,
                        sendStatus = sendStatus,
                        sent = sent,
                        myFoods = myFoods,
                        onAdd = { startAdd(it) },
                        onNewFood = { startAdd(null, EditMode.LIBRARY) },
                        onForgetFood = { foodStore.forget(it); myFoods = foodStore.myFoods() },
                        addStatus = addStatus,
                    )
                    else -> com.edsolano.tracker.ui.WalletScreen(walletWeb)
                }
            }
            }
        }
    }
}
