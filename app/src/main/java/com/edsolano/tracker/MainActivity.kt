package com.edsolano.tracker

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import com.edsolano.tracker.ui.FoodScreen
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
    private var snapshot by mutableStateOf(DaySnapshot(emptyList(), null, System.currentTimeMillis()))
    private var hasCalendar by mutableStateOf(false)

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
        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.POST_NOTIFICATIONS)
    )

    private fun reload() {
        hasCalendar = repo.hasPermission()
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
                            selected = tab == i, onClick = { tab = i },
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
                } else when (tab) {
                    0 -> TodayScreen(snapshot)
                    1 -> FoodScreen(snapshot)
                    else -> MoneyScreen(snapshot)
                }
            }
        }
    }
}
