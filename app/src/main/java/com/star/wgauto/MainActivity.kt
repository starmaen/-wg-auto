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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class Actions(
    val toggle: () -> Unit,
    val importFiles: () -> Unit,
    val batteryExempt: () -> Unit,
    val testAll: () -> Unit,
    val testOne: (String) -> Unit,
    val syncService: () -> Unit,
    val restoreSystem: () -> Unit
)

class MainActivity : ComponentActivity() {
    private val app get() = application as WgApp
    private var pendingAfterPerm: (() -> Unit)? = null

    private val vpnPermLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val action = pendingAfterPerm
            pendingAfterPerm = null
            if (res.resultCode == RESULT_OK) action?.invoke() else toast("لم يتم منح إذن VPN")
        }
    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            var ok = 0
            var fail = 0
            uris.forEach {
                val (o, f) = importUri(it)
                ok += o
                fail += f
            }
            if (ok > 0 || fail > 0) {
                toast("تم استيراد $ok كونفيج" + (if (fail > 0) " • تعذّر $fail" else "") + " — تُفحص عند تشغيل الـ VPN")
            }
        }

    private val lightScheme = lightColorScheme(
        primary = Color(0xFF1565C0), onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E4FF), onPrimaryContainer = Color(0xFF0B2A5B),
        secondary = Color(0xFF3F6FB5)
    )
    private val darkScheme = darkColorScheme(
        primary = Color(0xFF90CAF9), onPrimary = Color(0xFF0D2B57),
        primaryContainer = Color(0xFF1B3F73), onPrimaryContainer = Color(0xFFD6E4FF),
        secondary = Color(0xFF9DB8E6)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val actions = Actions(
            toggle = { onToggle() },
            importFiles = { importLauncher.launch(arrayOf("*/*")) },
            batteryExempt = { batteryExempt() },
            testAll = { withVpnPermission { app.engine.testAll() } },
            testOne = { id -> withVpnPermission { app.engine.testConfigs(listOf(id)) } },
            syncService = { syncService() },
            restoreSystem = { app.engine.restoreSystemDns() }
        )
        syncService()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkScheme else lightScheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Root(app, actions)
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun withVpnPermission(action: () -> Unit) {
        val i = VpnService.prepare(this)
        if (i == null) {
            action()
        } else {
            pendingAfterPerm = action
            vpnPermLauncher.launch(i)
        }
    }

    /** الخدمة تعمل إذا كان الـ VPN يعمل أو كان الفحص الخلفي مفعّلاً. */
    private fun syncService() {
        val need = app.engine.status.value.running || app.store.settings.value.backgroundScan
        val i = Intent(this, AutoService::class.java)
        if (need) ContextCompat.startForegroundService(this, i) else stopService(i)
    }

    private fun onToggle() {
        if (app.engine.status.value.running) {
            app.engine.stop()
            if (!app.store.settings.value.backgroundScan) stopService(Intent(this, AutoService::class.java))
        } else {
            if (app.store.configs.value.none { it.enabled }) {
                toast("أضف كونفيجاً واحداً على الأقل وفعّله")
                return
            }
            withVpnPermission { startVpn() }
        }
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(this, Intent(this, AutoService::class.java))
        app.engine.start()
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: "config"

    /** يستورد ملف .conf أو ملف .zip يحوي عدة كونفيجات. يعيد (نجح, فشل). */
    private fun importUri(uri: Uri): Pair<Int, Int> {
        var ok = 0
        var fail = 0
        try {
            val name = displayName(uri)
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val isZip = name.endsWith(".zip", true) ||
                (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
            if (isZip) {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val base = e.name.substringAfterLast('/').substringAfterLast('\\')
                            if (!base.startsWith(".") && !e.name.startsWith("__MACOSX")) {
                                val data = zin.readBytes()
                                if (data.size < 200_000) {
                                    val text = data.toString(Charsets.UTF_8)
                                    if (text.contains("[Interface]", true) && text.contains("[Peer]", true)) {
                                        val n = base.substringBeforeLast('.')
                                        if (app.engine.addConfig(n, text) == null) ok++ else fail++
                                    }
                                }
                            }
                        }
                        zin.closeEntry()
                        e = zin.nextEntry
                    }
                }
            } else {
                val text = bytes.toString(Charsets.UTF_8)
                if (app.engine.addConfig(name.removeSuffix(".conf"), text) == null) ok++ else fail++
            }
        } catch (e: Exception) {
            fail++
        }
        return ok to fail
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

/** ضوء أحمر ناعم نابض يدل على العنصر الذي اختاره التطبيق. */
@Composable
fun GlowDot(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "glow")
    val a by t.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(18.dp).background(Color(0xFFE53935).copy(alpha = a * 0.35f), CircleShape))
        Box(Modifier.size(9.dp).background(Color(0xFFE53935).copy(alpha = 0.55f + a * 0.4f), CircleShape))
    }
}

@Composable
fun MarkerSlot(chosen: Boolean) {
    if (chosen) GlowDot() else Spacer(Modifier.width(18.dp))
    Spacer(Modifier.width(8.dp))
}

private fun rowLine(r: ConfigRow): String = when (r.state) {
    "pending" -> "بانتظار الفحص"
    "testing" -> "جارٍ الفحص…"
    "fail" -> "✗ ${r.note}"
    else -> buildString {
        append("${r.latencyMs} ms")
        r.mbps?.let { append(" • %.1f Mbps".format(it)) }
        if (r.mtu > 0) append(" • MTU ${r.mtu}")
        if (r.dnsName.isNotEmpty()) append(" • ${r.dnsName}")
    }
}

@Composable
fun ResultsCard(rep: Report, st: Status) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("نتيجة الفحص", fontWeight = FontWeight.Bold)
            if (rep.progress.isNotEmpty()) Text(rep.progress, fontSize = 12.sp)
            val rows = rep.rows.sortedWith(
                compareBy<ConfigRow>(
                    { when (it.state) { "ok" -> 0; "testing" -> 1; "pending" -> 2; else -> 3 } },
                    { -it.score },
                    { if (it.latencyMs < 0) Int.MAX_VALUE else it.latencyMs }
                )
            )
            rows.forEach { r ->
                val chosen = st.connected && r.id == rep.chosenConfigId
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarkerSlot(chosen)
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            rowLine(r), fontSize = 12.sp,
                            color = if (r.state == "fail") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (rep.dnsRows.isNotEmpty()) {
                HorizontalDivider()
                Text("DNS (الأسرع أولاً)", fontWeight = FontWeight.Bold)
                rep.dnsRows.take(6).forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MarkerSlot(st.connected && d.id == rep.chosenDnsId)
                        Text(
                            "${d.name}: " + if (d.ms < 0) "لا استجابة" else "${d.ms} ms",
                            fontSize = 13.sp
                        )
                    }
                }
            }
            if (rep.chosenMtu > 0) {
                HorizontalDivider()
                Text("MTU المختار: ${rep.chosenMtu}", fontWeight = FontWeight.Bold)
                Text(
                    if (rep.chosenPathMtu > 0)
                        "أقصى حزمة تصل للخادم ${rep.chosenPathMtu} − ${rep.chosenPathMtu - rep.chosenMtu} (رأس WireGuard) = ${rep.chosenMtu}"
                    else "قيمة افتراضية/يدوية (لم يُقَس مسار الخادم)",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun HomeTab(app: WgApp, a: Actions) {
    val st by app.engine.status.collectAsState()
    val logs by app.engine.logs.collectAsState()
    val ni by app.engine.netInfo.collectAsState()
    val rep by app.engine.report.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("WG", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Column {
                Text("WG Auto", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("اختيار تلقائي للكونفيج وDNS وMTU", fontSize = 12.sp)
            }
        }
        Button(
            onClick = a.toggle,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (st.running) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                when {
                    st.running && st.busy -> "جارٍ الفحص… (إيقاف)"
                    st.running -> "إيقاف"
                    else -> "تشغيل"
                },
                fontSize = 22.sp, fontWeight = FontWeight.Bold
            )
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(st.message.ifEmpty { "متوقف" }, fontWeight = FontWeight.Bold)
                if (st.connected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MarkerSlot(true)
                        Text("الكونفيج: ${st.activeConfig}", fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MarkerSlot(true)
                        Text("DNS: ${st.activeDns}", fontWeight = FontWeight.Bold)
                    }
                    Text("MTU: ${st.activeMtu}")
                    Text("التأخر: ${st.latencyMs} ms")
                    Text("السرعة: ${st.mbps?.let { "%.1f Mbps".format(it) } ?: "—"}")
                }
            }
        }
        if (rep.rows.isNotEmpty() || rep.progress.isNotEmpty()) ResultsCard(rep, st)
        if (st.running && !st.busy) {
            OutlinedButton(
                onClick = { app.engine.reselect("يدوي") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("فحص وإعادة اختيار الكونفيج الآن") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("فحص الشبكة (مستقل عن الـ VPN)", fontWeight = FontWeight.Bold)
                val d = ni.direct
                if (d == null) {
                    Text("لم يُفحص بعد")
                } else {
                    SideView("الشبكة الأصلية", d, false)
                    ni.vpn?.let { v ->
                        HorizontalDivider()
                        SideView("عبر VPN (${ni.vpnOwner})", v, true)
                    }
                    if (ni.time.isNotEmpty()) Text("آخر فحص: ${ni.time}", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { app.engine.scanNetwork("يدوي") },
                    enabled = !st.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("فحص الشبكة الآن") }
            }
        }
        Text("السجل", fontWeight = FontWeight.Bold)
        logs.reversed().take(60).forEach { Text(it, fontSize = 12.sp) }
    }
}

@Composable
fun SideView(title: String, sd: SideInfo, vpnSide: Boolean) {
    Text(title, fontWeight = FontWeight.Bold)
    Text(sd.label + if (sd.iface.isNotEmpty()) " (${sd.iface})" else "")
    if (sd.dnsMs >= 0) Text("أسرع DNS: ${sd.bestDns} (${sd.dnsMs} ms)") else Text("أسرع DNS: —")
    if (sd.latencyMs >= 0) Text("التأخر: ${sd.latencyMs} ms")
    if (sd.pathMtu > 0) {
        if (vpnSide) {
            Text("أقصى حزمة تمر: ${sd.pathMtu}" + if (sd.ifaceMtu > 0) " • MTU الواجهة: ${sd.ifaceMtu}" else "")
        } else {
            Text("أقصى حزمة للمسار: ${sd.pathMtu} • MTU مقترح: ${sd.suggestedMtu}")
        }
    } else {
        Text(if (vpnSide) "MTU: تعذّر الفحص" else "MTU: يحتاج ping أو روت أثناء وجود VPN")
    }
}

@Composable
fun ConfigsTab(app: WgApp, a: Actions) {
    val list by app.store.configs.collectAsState()
    val s by app.store.settings.collectAsState()
    val results by app.engine.configResults.collectAsState()
    val st by app.engine.status.collectAsState()
    var showPaste by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = a.importFiles, modifier = Modifier.weight(1f)) { Text("استيراد", maxLines = 1) }
            OutlinedButton(onClick = { showPaste = true }, modifier = Modifier.weight(1f)) { Text("لصق", maxLines = 1) }
            OutlinedButton(onClick = a.testAll, modifier = Modifier.weight(1f)) { Text("فحص الكل", maxLines = 1) }
        }
        Text(
            "يدعم ملفات .conf وملف .zip يحوي عدة كونفيجات. تُفحص الكونفيجات تلقائياً عند تشغيل الـ VPN فقط.",
            fontSize = 12.sp
        )
        if (list.isEmpty()) Text("لا توجد كونفيجات بعد.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!s.autoConfig) {
                                RadioButton(
                                    selected = s.selConfigId == c.id,
                                    onClick = { app.store.updateSettings { it.copy(selConfigId = c.id) } }
                                )
                            }
                            MarkerSlot(st.connected && st.activeConfigId == c.id)
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
                        }
                        results[c.id]?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
                        Row {
                            TextButton(onClick = { a.testOne(c.id) }) { Text("فحص") }
                            TextButton(onClick = { app.store.updateConfigs { l -> l.filter { it.id != c.id } } }) {
                                Text("حذف", color = MaterialTheme.colorScheme.error)
                            }
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
                    if (e == null) {
                        showPaste = false
                    } else {
                        err = e
                    }
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
    val st by app.engine.status.collectAsState()
    val ni by app.engine.netInfo.collectAsState()
    val chosenDns = if (st.connected) st.activeDnsId else ni.direct?.bestDnsId
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
                        MarkerSlot(!chosenDns.isNullOrEmpty() && chosenDns == d.id)
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.Bold)
                            Text(d.ips().joinToString("  "), fontSize = 12.sp)
                            if (d.dot.isNotEmpty()) Text("DoT: ${d.dot}", fontSize = 11.sp)
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
        var p3 by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("إضافة خادم DNS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true)
                    OutlinedTextField(p1, { p1 = it }, label = { Text("العنوان الأساسي") }, singleLine = true)
                    OutlinedTextField(p2, { p2 = it }, label = { Text("العنوان الثانوي (اختياري)") }, singleLine = true)
                    OutlinedTextField(p3, { p3 = it }, label = { Text("مضيف DoT (اختياري، للتثبيت بالروت)") }, singleLine = true)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!validIp(p1) || (p2.isNotBlank() && !validIp(p2))) {
                        err = "عنوان IP غير صالح"
                    } else {
                        app.store.updateDns { it + DnsServer(name = name.ifBlank { p1.trim() }, primary = p1.trim(), secondary = p2.trim(), dot = p3.trim()) }
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

        Text("الفحص في الخلفية", fontWeight = FontWeight.Bold)
        SwitchRow("فحص DNS وMTU تلقائياً بدون تشغيل VPN", s.backgroundScan) { v ->
            upd { it.copy(backgroundScan = v) }
            a.syncService()
        }
        Text("يعمل عند فتح التطبيق وعند تبدّل الشبكة، ويعرض أفضل DNS وMTU للشبكة الحالية.", fontSize = 12.sp)

        Text("إعادة الاختيار", fontWeight = FontWeight.Bold)
        SwitchRow("عند تبدّل الشبكة أو تغيّر IP", s.reselectOnNetwork) { v -> upd { it.copy(reselectOnNetwork = v) } }
        Picker(
            "إعادة الفحص الكاملة دورياً", if (s.periodMin == 0) "إيقاف" else "كل ${s.periodMin} دقيقة",
            listOf(0 to "إيقاف", 15 to "كل 15 دقيقة", 30 to "كل 30 دقيقة", 60 to "كل 60 دقيقة", 120 to "كل 120 دقيقة")
        ) { v -> upd { it.copy(periodMin = v) } }

        Text("متقدم", fontWeight = FontWeight.Bold)
        SwitchRow("استخدام الروت لفحص MTU (su)", s.useRoot) { v -> upd { it.copy(useRoot = v) } }
        SwitchRow("تثبيت القيم المثلى بالروت", s.applyRoot) { v ->
            upd { it.copy(applyRoot = v) }
            if (!v) a.restoreSystem()
        }
        Text(
            "يخفض MTU واجهة أي VPN خارجي إلى أكبر حزمة تمر فعلاً، ويضبط DNS النظام (Private DNS) على أسرع خادم يدعم DoT. " +
                "عند الإيقاف يُعاد DNS إلى وضعه الأصلي.",
            fontSize = 12.sp
        )
        OutlinedButton(onClick = a.batteryExempt, modifier = Modifier.fillMaxWidth()) {
            Text("استثناء التطبيق من توفير الطاقة")
        }
        Text(
            "MTU النفق = أقصى حزمة تصل للخادم − 60 بايت (IPv4) أو − 80 بايت (IPv6).",
            fontSize = 12.sp
        )

        val ctx = LocalContext.current
        val ver = remember {
            try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
            } catch (e: Exception) {
                ""
            }
        }
        HorizontalDivider()
        Text("حول التطبيق", fontWeight = FontWeight.Bold)
        Text("الإصدار: $ver")
        Text("Star Syria")
        TextButton(onClick = {
            try {
                ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:starsyria2500@gmail.com")))
            } catch (e: Exception) {
                // لا يوجد تطبيق بريد
            }
        }) { Text("starsyria2500@gmail.com") }
    }
}
