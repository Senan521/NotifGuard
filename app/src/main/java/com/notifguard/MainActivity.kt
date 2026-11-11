package com.notifguard

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Screen() } }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

data class Found(val pkg: String, val app: String, val uid: Int, val ch: Ch, val on: Boolean = true)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Screen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(Rules.mode(ctx)) }
    var filterOn by remember { mutableStateOf(Rules.filter(ctx)) }
    var access by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var blockT by remember { mutableStateOf(Rules.block(ctx)) }
    var allowT by remember { mutableStateOf(Rules.allow(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val found = remember { mutableStateListOf<Found>() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        count = Rules.count(ctx)
    }

    Scaffold(topBar = { LargeTopAppBar(title = { Text("Bildirim Bekçisi") }) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1) Filtre
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Anlık filtre", style = MaterialTheme.typography.titleLarge)
                        Text("Gelen bildirimin başlık/metnine bakıp gereksizse siler. Engellenen: $count")
                        if (!access) {
                            Button(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }) { Text("Bildirim erişimini ver") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Filtre açık", Modifier.weight(1f))
                            Switch(checked = filterOn, onCheckedChange = { filterOn = it; Rules.setFilter(ctx, it) })
                        }
                    }
                }
            }
            // 2) Mod
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Kanal kapatma yöntemi", style = MaterialTheme.typography.titleLarge)
                        val labels = listOf("Yalnız filtre", "Shizuku", "Root")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            labels.forEachIndexed { i, l ->
                                SegmentedButton(
                                    selected = mode == i,
                                    onClick = { mode = i; Rules.setMode(ctx, i) },
                                    shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                                    label = { Text(l) }
                                )
                            }
                        }
                        if (mode == 1) {
                            OutlinedButton(onClick = {
                                runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(1001) else msg = "Shizuku çalışmıyor" }
                            }) { Text("Shizuku izni iste") }
                        }
                    }
                }
            }
            // 3) Kurallar
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Kurallar", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(blockT, { blockT = it }, Modifier.fillMaxWidth(),
                            label = { Text("Engellenecek kelimeler (virgülle)") })
                        OutlinedTextField(allowT, { allowT = it }, Modifier.fillMaxWidth(),
                            label = { Text("Her zaman izinli kelimeler") })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { Rules.save(ctx, blockT, allowT); msg = "Kaydedildi" }) { Text("Kaydet") }
                            OutlinedButton(onClick = { blockT = Rules.DEF_BLOCK; allowT = Rules.DEF_ALLOW }) { Text("Varsayılan") }
                        }
                    }
                }
            }
            // 4) Tarama
            item {
                Button(
                    onClick = {
                        Rules.save(ctx, blockT, allowT)
                        scope.launch {
                            busy = true; found.clear(); msg = ""
                            val be = Backends.get(ctx, mode)
                            if (be == null) {
                                msg = when (mode) {
                                    0 -> "Tarama için Shizuku veya Root seç."
                                    1 -> "Shizuku çalışmıyor ya da izin verilmedi."
                                    else -> "Root alınamadı."
                                }
                            } else {
                                val apps = withContext(Dispatchers.IO) {
                                    ctx.packageManager.getInstalledApplications(0).filter {
                                        it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName
                                    }
                                }
                                for (ai in apps) {
                                    val label = ctx.packageManager.getApplicationLabel(ai).toString()
                                    be.channels(ai.packageName, ai.uid)
                                        .filter { it.importance != 0 && Rules.isJunk(ctx, it.id + " " + it.name) }
                                        .forEach { found.add(Found(ai.packageName, label, ai.uid, it)) }
                                }
                                msg = "${found.size} gereksiz kanal bulundu"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Uygulamaları tara") }
            }
            if (busy) item { Box(Modifier.fillMaxWidth(), Alignment.Center) { LoadingIndicator() } }
            if (msg.isNotEmpty()) item { Text(msg, style = MaterialTheme.typography.bodyLarge) }

            items(found.toList(), key = { it.pkg + "/" + it.ch.id }) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = f.on, onCheckedChange = { v ->
                            val i = found.indexOfFirst { it.pkg == f.pkg && it.ch.id == f.ch.id }
                            if (i >= 0) found[i] = f.copy(on = v)
                        })
                        Column {
                            Text(f.app, style = MaterialTheme.typography.titleMedium)
                            Text(f.ch.name.ifBlank { f.ch.id }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (found.isNotEmpty()) item {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val be = Backends.get(ctx, mode)
                            val sel = found.filter { it.on }
                            val done = mutableListOf<Found>()
                            if (be != null) for (f in sel) if (be.block(f.pkg, f.uid, f.ch.id)) done.add(f)
                            found.removeAll { d -> done.any { it.pkg == d.pkg && it.ch.id == d.ch.id } }
                            msg = "${done.size}/${sel.size} kanal kapatıldı" +
                                if (done.size < sel.size) " (bazıları reddedildi; bu Android sürümünde Root dene)" else ""
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Seçilenleri kapat") }
            }
        }
    }
}
