@file:OptIn(ExperimentalMaterial3Api::class)

package com.sophia.signal.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sophia.signal.model.Radio
import com.sophia.signal.scan.Perms

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SophiaTheme { Root() } }
    }
}

private val TABS = listOf("COMMAND", "RADAR", "WI-FI", "BLE", "PRIORITY", "HISTORY", "DEBRIEF", "SETTINGS")

@Composable
fun Root() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detailKey by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.invoke(); pending = null
    }
    val gate: (() -> Unit) -> Unit = { action ->
        val missing = Perms.required().filterNot { Perms.granted(ctx, it) }
        if (missing.isEmpty()) action() else { pending = action; permLauncher.launch(missing.toTypedArray()) }
    }
    val open: (String) -> Unit = { detailKey = it }

    CompositionLocalProvider(LocalPermGate provides gate) {
        Scaffold(
            containerColor = C.Bg,
            topBar = {
                Column(Modifier.background(C.Bg).statusBarsPadding()) {
                    Header()
                    ScrollableTabRow(
                        selectedTabIndex = tab, containerColor = C.Bg, contentColor = C.Green, edgePadding = 8.dp,
                        divider = { HorizontalDivider(color = C.Line) },
                    ) {
                        TABS.forEachIndexed { i, t ->
                            Tab(selected = tab == i, onClick = { tab = i }, text = {
                                Text(t, fontSize = 12.sp, letterSpacing = 1.5.sp, fontFamily = FontFamily.Monospace, color = if (tab == i) C.Green else C.Dim)
                            })
                        }
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().navigationBarsPadding()) {
                when (tab) {
                    0 -> CommandScreen(open) { tab = it }
                    1 -> RadarScreen(open)
                    2 -> ObservationScreen(Radio.WIFI, open)
                    3 -> ObservationScreen(Radio.BLE, open)
                    4 -> PriorityScreen(open)
                    5 -> HistoryScreen()
                    6 -> DebriefScreen()
                    7 -> SettingsScreen()
                }
            }
        }
        detailKey?.let { DetailDialog(it) { detailKey = null } }
    }
}

@Composable
private fun Header() {
    val app = sophia()
    val wifi by app.scanner.wifiState.collectAsStateWithLifecycle()
    val ble by app.scanner.bleState.collectAsStateWithLifecycle()
    val active = wifi.running || ble.running
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("SØPHIA", color = C.Green, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        Text("SIGNAL AWARENESS", color = C.Dim, fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) C.Green else C.Red))
        Spacer(Modifier.width(6.dp))
        Text(if (active) "LIVE" else "STANDBY", color = if (active) C.Green else C.Red, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
