package com.star.wgauto

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class Actions(
    val toggle: () -> Unit,
    val importFiles: () -> Unit,
    val batteryExempt: () -> Unit
)

class MainActivity : ComponentActivity() {
    private val app get() = application as WgApp

    private val vpnPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) startVpn() else toast("لم يتم منح إذن VPN")
        }
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { importUri(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val actions = Actions(
            toggle = { onToggle() },
            importFiles = { importLauncher.launch(arrayOf("*/*")) },
            batteryExempt = { batteryExempt() }
        )
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Root(app, actions)
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun onToggle() {
        if (app.engine.status.value.running) {
            app.engine.stop()
            stopService(Intent(this, AutoService::class.java))
        } else {
            if (app.store.configs.value.none { it.enabled }) {
                toast("أضف كونفيجاً واحداً على الأقل وفعّله")
                return
            }
            val i = VpnService.prepare(this)
            if (i != null) vpnPermLauncher.launch(i) else startVpn()
        }
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(this, Intent(this, AutoService::class.java))
        app.engine.start()
    }

    private fun importUri(uri: Uri) {
        try {
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            } ?: "config"
            val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val err = app.engine.addConfig(name.removeSuffix(".conf"), text)
            toast(if (err == null) "تم استيراد $name" else "$name: $err")
        } catch (e: Exception) {
            toast("فشل الاستيراد: ${e.message}")
        }
    }

    private fun batteryExempt() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("التطبيق مستثنى بالفعل من توفير الطاقة")
        } else {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }
}

// ============================================================ UI

@Composable
fun Root(app: WgApp, a: Actions) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("الرئيسية", "الكونفيجات", "DNS", "الإعدادات")
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
                }
            }
            when (tab) {
                0 -> HomeTab(app, a)
                1 -> ConfigsTab(app, a)
                2 -> DnsTab(app)
                else -> SettingsTab(app, a)
            }
        }
    }
}

@Composable
fun HomeTab(app: WgApp, a: Actions) {
    val st by app.engine.status.collectAsState()
    val logs by app.engine.logs.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = a.toggle,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (st.running) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (st.running) "إيقاف" else "تشغيل", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(st.message.ifEmpty { "متوقف" }, fontWeight = FontWeight.Bold)
                if (st.connected) {
                    Text("الكونفيج: ${st.activeConfig}")
                    Text("DNS: ${st.activeDns}")
                    Text("MTU: ${st.activeMtu}")
                    Text("التأخر: ${st.latencyMs} ms")
                    Text("السرعة: ${st.mbps?.let { "%.1f Mbps".format(it) } ?: "—"}")
                }
            }
        }
        OutlinedButton(
            onClick = { app.engine.reselect("يدوي") },
            enabled = st.running && !st.busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("فحص وإعادة الاختيار الآن") }
        Text("السجل", fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f)) {
            items(logs.reversed()) { Text(it, fontSize = 12.sp) }
        }
    }
}

@Composable
fun ConfigsTab(app: WgApp, a: Actions) {
    val list by app.store.configs.collectAsState()
    val s by app.store.settings.collectAsState()
    var showPaste by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = a.importFiles, modifier = Modifier.weight(1f)) { Text("استيراد ملفات", maxLines = 1) }
            OutlinedButton(onClick = { showPaste = true }, modifier = Modifier.weight(1f)) { Text("لصق كونفيج", maxLines = 1) }
        }
        if (list.isEmpty()) Text("لا توجد كونفيجات. استورد ملف .conf أو الصق النص.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!s.autoConfig) {
                            RadioButton(
                                selected = s.selConfigId == c.id,
                                onClick = { app.store.updateSettings { it.copy(selConfigId = c.id) } }
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.Bold)
                            val ep = Probes.parseEndpoint(c.text)
                            Text(ep?.let { "${it.first}:${it.second}" } ?: "—", fontSize = 12.sp)
                        }
                        Switch(
                            checked = c.enabled,
                            onCheckedChange = { v ->
                                app.store.updateConfigs { l -> l.map { if (it.id == c.id) it.copy(enabled = v) else it } }
                            }
                        )
                        TextButton(onClick = { app.store.updateConfigs { l -> l.filter { it.id != c.id } } }) {
                            Text("حذف", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showPaste) {
        var name by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("إضافة كونفيج") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true)
                    OutlinedTextField(text, { text = it }, label = { Text("نص الكونفيج") }, minLines = 6, maxLines = 10)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val e = app.engine.addConfig(name, text)
                    if (e == null) showPaste = false else err = e
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("إلغاء") } }
        )
    }
}

private fun validIp(v: String): Boolean {
    val x = v.trim()
    if (x.isEmpty()) return false
    val p = x.split(".")
    if (p.size == 4 && p.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) return true
    return x.contains(":") && x.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
}

@Composable
fun DnsTab(app: WgApp) {
    val list by app.store.dns.collectAsState()
    val s by app.store.settings.collectAsState()
    val res by app.engine.dnsResults.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) { Text("إضافة", maxLines = 1) }
            OutlinedButton(onClick = { app.engine.testDnsNow() }, modifier = Modifier.weight(1f)) { Text("اختبار", maxLines = 1) }
            OutlinedButton(onClick = { app.store.restoreDefaultDns() }, modifier = Modifier.weight(1f)) { Text("الافتراضية", maxLines = 1) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!s.autoDns) {
                            RadioButton(
                                selected = s.selDnsId == d.id,
                                onClick = { app.store.updateSettings { it.copy(selDnsId = d.id) } }
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.Bold)
                            Text(d.ips().joinToString("  "), fontSize = 12.sp)
                            res[d.id]?.let { Text(if (it < 0) "لا استجابة" else "$it ms", fontSize = 12.sp) }
                        }
                        Switch(
                            checked = d.enabled,
                            onCheckedChange = { v ->
                                app.store.updateDns { l -> l.map { if (it.id == d.id) it.copy(enabled = v) else it } }
                            }
                        )
                        TextButton(onClick = { app.store.updateDns { l -> l.filter { it.id != d.id } } }) {
                            Text("حذف", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("إضافة خادم DNS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true)
                    OutlinedTextField(p1, { p1 = it }, label = { Text("العنوان الأساسي") }, singleLine = true)
                    OutlinedTextField(p2, { p2 = it }, label = { Text("العنوان الثانوي (اختياري)") }, singleLine = true)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!validIp(p1) || (p2.isNotBlank() && !validIp(p2))) {
                        err = "عنوان IP غير صالح"
                    } else {
                        app.store.updateDns { it + DnsServer(name = name.ifBlank { p1.trim() }, primary = p1.trim(), secondary = p2.trim()) }
                        showAdd = false
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> Picker(label: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $current", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, t) ->
                DropdownMenuItem(text = { Text(t) }, onClick = { onPick(v); open = false })
            }
        }
    }
}

@Composable
fun SettingsTab(app: WgApp, a: Actions) {
    val s by app.store.settings.collectAsState()
    fun upd(f: (AppSettings) -> AppSettings) = app.store.updateSettings(f)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("الاختيار التلقائي", fontWeight = FontWeight.Bold)
        SwitchRow("اختيار أفضل كونفيج تلقائياً", s.autoConfig) { v -> upd { it.copy(autoConfig = v) } }
        SwitchRow("اختيار أسرع DNS تلقائياً", s.autoDns) { v -> upd { it.copy(autoDns = v) } }
        SwitchRow("فحص MTU المسار تلقائياً", s.autoMtu) { v -> upd { it.copy(autoMtu = v) } }
        Text(
            "عند إيقاف الاختيار التلقائي لكونفيج أو DNS يظهر زر اختيار بجانب كل عنصر في تبويبه.",
            fontSize = 12.sp
        )
        if (!s.autoMtu) {
            Picker(
                "MTU", s.selMtu.toString(),
                MtuCatalog.values.map { it to MtuCatalog.label(it) }
            ) { v -> upd { it.copy(selMtu = v) } }
        }

        Text("إعادة الاختيار", fontWeight = FontWeight.Bold)
        SwitchRow("عند تبدّل الشبكة أو تغيّر IP", s.reselectOnNetwork) { v -> upd { it.copy(reselectOnNetwork = v) } }
        Picker(
            "فحص دوري", if (s.periodMin == 0) "إيقاف" else "كل ${s.periodMin} دقيقة",
            listOf(0 to "إيقاف", 15 to "كل 15 دقيقة", 30 to "كل 30 دقيقة", 60 to "كل 60 دقيقة", 120 to "كل 120 دقيقة")
        ) { v -> upd { it.copy(periodMin = v) } }

        Text("متقدم", fontWeight = FontWeight.Bold)
        SwitchRow("استخدام الروت لفحص MTU (su)", s.useRoot) { v -> upd { it.copy(useRoot = v) } }
        OutlinedButton(onClick = a.batteryExempt, modifier = Modifier.fillMaxWidth()) {
            Text("استثناء التطبيق من توفير الطاقة")
        }
        Text(
            "MTU النفق = أقصى حزمة تصل للخادم − 60 بايت (IPv4) أو − 80 بايت (IPv6).",
            fontSize = 12.sp
        )
    }
}
