package com.dpad.mgr.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dpad.mgr.core.Store
import com.dpad.mgr.svc.DpadService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContent {
            MaterialTheme {
                Surface { App() }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching { DpadService.ensureStarted(ctx) }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Status", "Apps", "Profiles")
    val icons = listOf(Icons.Default.Info, Icons.Default.List, Icons.Default.Settings)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.width(72.dp).fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    titles.indices.forEach { i ->
                        NavigationRailItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icons[i], null) },
                            label = { Text(titles[i], style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                    val m = Modifier.fillMaxSize()
                    when (tab) {
                        0 -> StatusScreen(m, onOpenApps = { tab = 1 })
                        1 -> AppsScreen(m)
                        else -> ProfilesScreen(m)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = tab, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    titles.indices.forEach { i ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(titles[i]) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val m = Modifier.fillMaxSize()
                    when (tab) {
                        0 -> StatusScreen(m, onOpenApps = { tab = 1 })
                        1 -> AppsScreen(m)
                        else -> ProfilesScreen(m)
                    }
                }
            }
        }
    }
}
