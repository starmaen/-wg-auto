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
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.widget.Toast
import androidx.compose.ui.viewinterop.AndroidView
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

class Actions(
    val toggle: () -> Unit,
    val importFiles: () -> Unit,
    val batteryExempt: () -> Unit,
    val testAll: () -> Unit,
    val testOne: (String) -> Unit,
    val syncService: () -> Unit,
    val restoreSystem: () -> Unit,
    val shutdownAll: () -> Unit,
    val openVpnSettings: () -> Unit,
    val activateConfig: (String) -> Unit,
    val startOvpn: (OvpnServer) -> Unit
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
            testAll = { app.ovpn.stop(); withVpnPermission { app.engine.testAll() } },
            testOne = { id -> app.ovpn.stop(); withVpnPermission { app.engine.testConfigs(listOf(id)) } },
            syncService = { syncService() },
            restoreSystem = { app.engine.restoreSystemDns() },
            shutdownAll = {
                app.engine.shutdownAll {
                    stopService(Intent(this, AutoService::class.java))
                    toast("أُغلق التطبيق بالكامل — لن يعمل شيء حتى تشغّله يدوياً")
                }
            },
            openVpnSettings = {
                try {
                    startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS))
                } catch (e: Exception) {
                    toast("تعذّر فتح إعدادات VPN في هذا الجهاز")
                }
            },
            activateConfig = { id ->
                app.store.updateSettings { it.copy(autoConfig = false, selConfigId = id) }
                app.ovpn.stop()
                withVpnPermission {
                    ContextCompat.startForegroundService(this, Intent(this, AutoService::class.java))
                    app.engine.connectManual(id)
                }
            },
            // إذن VPN من النظام (مرة واحدة) ثم تشغيل OpenVPN الداخلي — بدونه يفشل establish بصمت
            startOvpn = { s ->
                withVpnPermission {
                    toast(startOvpnInternal(app, s, app.store.settings.value.ovpnProto))
                }
            }
        )
        syncService()
        if (app.store.settings.value.autoPurgeExpired) {
            val n = app.store.purgeExpiredConfigs()
            if (n > 0) toast("حُذفت $n كونفيجات منتهية الصلاحية تلقائياً — ولّد بديلاً من VPNBook")
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkScheme else lightScheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    LicenseGate(app) { Root(app, actions) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // فحص خفيف عند فتح التطبيق: لا يُسقط النفق العامل، فقط يحدّث الأرقام المعروضة
        val st = app.engine.status.value
        if (st.running && st.connected) app.engine.healthCheck()
        else if (!st.running && app.store.settings.value.backgroundScan) app.engine.scanNetwork("فتح التطبيق")
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
        app.ovpn.stop() // نفق واحد فقط: فصل OpenVPN الداخلي قبل تشغيل WireGuard
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
                // تنظيف اسم ملفات المتصفح (.conf.txt المزدوجة): لا حاجة لتغيير الامتداد يدوياً
                var cleanName = name
                fun strip(s: String, suf: String): String =
                    if (s.endsWith(suf, ignoreCase = true)) s.substring(0, s.length - suf.length) else s
                repeat(3) {
                    cleanName = strip(strip(strip(strip(cleanName, ".txt"), ".conf"), ".ovpn"), ".cfg").trim()
                }
                if (app.engine.addConfig(cleanName.ifEmpty { name }, text) == null) ok++ else fail++
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

/** بوابة الترخيص: تعرض شاشة تفعيل حتى يكون الجهاز مرخَّصاً، ثم تعرض محتوى التطبيق. */
@Composable
fun LicenseGate(app: WgApp, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val deviceId = remember { License.deviceId(ctx) }
    var status by remember { mutableStateOf(License.status(app.store)) }

    if (status.valid) {
        content()
        return
    }

    var tapCount by remember { mutableIntStateOf(0) }
    var showOwner by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var ownerPhrase by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                    .clickable {
                        tapCount++
                        if (tapCount >= 7) {
                            showOwner = true
                            tapCount = 0
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("WG", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Text("تفعيل WG Auto", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(
                "أرسل معرّف جهازك أدناه إلى مطوّر التطبيق للحصول على كود التفعيل، ثم الصقه في الحقل تحته.",
                fontSize = 14.sp
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("معرّف جهازك", fontWeight = FontWeight.Bold)
                    Text(License.formatId(deviceId), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("device id", deviceId))
                            msg = "نُسخ معرّف الجهاز"
                        }, modifier = Modifier.weight(1f)) { Text("نسخ") }
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "معرّف جهازي لتفعيل WG Auto: ${License.formatId(deviceId)}")
                            }
                            ctx.startActivity(Intent.createChooser(send, null))
                        }, modifier = Modifier.weight(1f)) { Text("إرسال") }
                    }
                }
            }
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text("كود التفعيل") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                when (val r = License.activate(app.store, deviceId, code)) {
                    is License.Attempt.Ok -> { status = License.status(app.store); msg = "" }
                    is License.Attempt.Wrong -> msg = "كود غير صحيح لهذا الجهاز"
                    is License.Attempt.Locked -> msg = "محاولات كثيرة — انتظر ${r.secondsLeft} ثانية"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("تفعيل") }
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)

            if (showOwner) {
                HorizontalDivider()
                Text("دخول المالك", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = ownerPhrase, onValueChange = { ownerPhrase = it },
                    label = { Text("العبارة السرّية") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    when (val r = License.ownerLogin(app.store, ownerPhrase)) {
                        is License.Attempt.Ok -> status = License.status(app.store)
                        is License.Attempt.Wrong -> msg = "عبارة غير صحيحة"
                        is License.Attempt.Locked -> msg = "محاولات كثيرة — انتظر ${r.secondsLeft} ثانية"
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("دخول") }
            }
        }
    }
}

@Composable
fun Root(app: WgApp, a: Actions) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("الرئيسية", "الكونفيجات", "VPN 🌍", "DNS", "الإعدادات")
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
                2 -> OvpnTab(app, a)
                3 -> DnsTab(app)
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

/** عمر آخر فحص، بلون يدل على مدى حداثته: أخضر حديث، أصفر متوسط، أحمر قديم. */
@Composable
fun ScanAgeLine(lastScanAt: Long) {
    if (lastScanAt <= 0L) {
        Text("آخر فحص: لم يُجرَ بعد", fontSize = 12.sp)
        return
    }
    val ageMs = System.currentTimeMillis() - lastScanAt
    val text = when {
        ageMs < 60_000 -> "آخر فحص: قبل ${(ageMs / 1000).coerceAtLeast(1)} ثانية"
        ageMs < 3_600_000 -> "آخر فحص: قبل ${ageMs / 60_000} دقيقة"
        else -> "آخر فحص: قبل ${ageMs / 3_600_000} ساعة"
    }
    val color = when {
        ageMs < 2 * 60_000 -> Color(0xFF2E7D32)
        ageMs < 20 * 60_000 -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.error
    }
    Text(text, fontSize = 12.sp, color = color)
}

private fun rowLine(r: ConfigRow): String = when {
    r.sameCountry -> "⚠ نفس دولتك ${Country.label(r.exitLoc)} — لا يفيد لتجاوز الحجب، مستبعَد تلقائياً"
    r.state == "pending" -> "بانتظار الفحص"
    r.state == "testing" -> "جارٍ الفحص…"
    r.state == "fail" -> "✗ ${r.note}"
    else -> buildString {
        append("${r.latencyMs} ms")
        if (r.jitter > 0) append(" ±${r.jitter}")
        if (r.score > 0) append(" • فقد ${r.loss}%")
        r.mbps?.let { append(" • %.1f Mbps".format(it)) }
        if (r.mtu > 0) append(" • MTU ${r.mtu}")
        if (r.exitIp.isNotEmpty()) append(" • ${Country.ipLine(r.exitIp, r.exitLoc)}")
    }
}

@Composable
fun ResultsCard(rep: Report, st: Status) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("نتيجة الفحص — امتحان عادل للجميع", fontWeight = FontWeight.Bold)
            Text(Exam.FORMULA, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rep.progress.isNotEmpty()) Text(rep.progress, fontSize = 12.sp)
            val rows = rep.rows.sortedWith(
                compareBy<ConfigRow>(
                    { when (it.state) { "ok" -> 0; "testing" -> 1; "pending" -> 2; else -> 3 } },
                    { -it.score },
                    { if (it.latencyMs < 0) Int.MAX_VALUE else it.latencyMs }
                )
            )
            var rank = 0
            rows.forEach { r ->
                val chosen = st.connected && r.id == rep.chosenConfigId
                val badge = if (r.state == "ok") { rank++; Exam.rankMedal(rank - 1) } else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarkerSlot(chosen)
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (badge != null) "$badge • " else "") + r.name,
                            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            rowLine(r), fontSize = 12.sp,
                            color = when {
                                r.sameCountry -> Color(0xFFF9A825)
                                r.state == "fail" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
            if (rep.dnsRows.isNotEmpty()) {
                HorizontalDivider()
                Text("DNS عبر النفق (الأفضل جودةً أولاً)", fontWeight = FontWeight.Bold)
                rep.dnsRows.take(6).forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MarkerSlot(st.connected && d.id == rep.chosenDnsId)
                        Text(
                            "${d.name}: " + if (d.ms < 0) "لا استجابة (0%)"
                            else "${d.ms} ms ±${d.jitter} • فقد ${d.loss}%",
                            fontSize = 13.sp
                        )
                    }
                }
            }
            if (rep.chosenMtu > 0) {
                HorizontalDivider()
                Text("MTU المختار: ${rep.chosenMtu}", fontWeight = FontWeight.Bold)
                Text(
                    "اختير بالتجربة الفعلية عبر النفق: أعلى MTU يمرّ فيه HTTPS بزمن مقارب للمرجع " +
                        "(لا نعتمد على ICMP لأنه يعطي أرقاماً أقل من الحقيقة في كثير من الشبكات).",
                    fontSize = 12.sp
                )
                if (rep.mtuRows.isNotEmpty()) {
                    Text("نتائج التجربة (سرعة عبر النفق لكل MTU، «فشل» = لا يمر HTTPS):", fontSize = 12.sp)
                    rep.mtuRows.forEach { m ->
                        Text(
                            "  MTU ${m.mtu}: " + (m.mbps?.let { "%.1f Mbps".format(it) } ?: "فشل") +
                                if (m.mtu == rep.chosenMtu) "  ← المختار" else "",
                            fontSize = 12.sp
                        )
                    }
                }
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
    val cfgs by app.store.configs.collectAsState()
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
                ScanAgeLine(st.lastScanAt)
                if (st.killSwitchOn) {
                    Text("🔒 قاطع الطوارئ مفعّل — لا إنترنت خارج النفق", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
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
                    Text("التأخر عبر النفق: ${st.latencyMs} ms")
                    if (st.mbps != null) Text("السرعة: ${"%.1f Mbps".format(st.mbps)}")
                    HorizontalDivider()
                    Text("دليل أن النفق حقيقي", fontWeight = FontWeight.Bold)
                    if (st.diag.isNotEmpty()) Text(st.diag, fontSize = 13.sp)
                    if (st.vpnIface.isNotEmpty()) Text("واجهة VPN: ${st.vpnIface}", fontSize = 13.sp)
                    if (st.exitIp.isNotEmpty()) Text("IP عبر النفق: ${Country.ipLine(st.exitIp, st.exitLoc)}", fontSize = 13.sp)
                    if (st.directIp.isNotEmpty()) Text("IP المباشر: ${Country.ipLine(st.directIp, st.directLoc)}", fontSize = 13.sp)
                    Text(
                        if (st.verified) "✔ تغيّر الـ IP: الحركة تمر فعلاً عبر النفق"
                        else "⚠ لم يُتحقق من تغيّر الـ IP (تحقق بنفسك من موقع فحص IP)",
                        fontSize = 13.sp,
                        color = if (st.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        if (rep.rows.isNotEmpty() || rep.progress.isNotEmpty()) ResultsCard(rep, st)
        // زر الفحص الشامل دائم الظهور — حتى بدون تشغيل: يفحص الجميع ويعرض العامل فقط
        if (!st.running && !st.busy) {
            val n = cfgs.count { it.enabled }
            OutlinedButton(
                onClick = { a.testAll() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("🔍 فحص شامل للجميع ($n) — بدون اتصال") }
            Text(
                "يفحص كل الكونفيجات المفعّلة واحداً واحداً ويعرض بجانب كل واحد: العلم والدولة وIP والزمن — ثم تختار أو تشغّل.",
                fontSize = 12.sp
            )
        }
        if (st.running && !st.busy) {
            var showFullScanConfirm by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = {
                    if (st.connected) app.engine.opportunisticCheck("يدوي") else app.engine.reselect("يدوي")
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (st.connected) "بحث عن كونفيج أفضل (بلا قطع الاتصال)" else "فحص وإعادة اختيار الكونفيج الآن") }
            if (st.connected) {
                TextButton(
                    onClick = { showFullScanConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("بحث كامل شامل — قد يقطع الاتصال مؤقتاً") }
            }
            if (showFullScanConfirm) {
                AlertDialog(
                    onDismissRequest = { showFullScanConfirm = false },
                    title = { Text("بحث كامل شامل") },
                    text = { Text("سيُعاد فحص كل الكونفيجات من الصفر، وقد ينقطع الاتصال الحالي مؤقتاً أثناء ذلك (قد يستغرق دقائق). متابعة؟") },
                    confirmButton = {
                        TextButton(onClick = { showFullScanConfirm = false; app.engine.reselect("بحث كامل يدوي") }) {
                            Text("متابعة", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { showFullScanConfirm = false }) { Text("إلغاء") } }
                )
            }
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
        var showShutdown by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showShutdown = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) { Text("⛔ إغلاق نهائي شامل") }
        if (showShutdown) {
            AlertDialog(
                onDismissRequest = { showShutdown = false },
                title = { Text("إغلاق نهائي شامل") },
                text = {
                    Text(
                        "سيوقف النفق وقاطع الطوارئ والفحص التلقائي في الخلفية، ويستعيد إعداد DNS الأصلي للنظام. " +
                            "لن يعمل شيء بعدها حتى تفتح التطبيق وتضغط تشغيل يدوياً."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showShutdown = false; a.shutdownAll() }) {
                        Text("إغلاق", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { showShutdown = false }) { Text("إلغاء") } }
            )
        }
        var showLog by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showLog = !showLog },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (showLog) "▲ إخفاء سجل الفحص" else "▼ سجل الفحص التفصيلي (يُحذف تلقائياً بعد يوم)") }
        if (showLog) {
            Text("الأحدث أولاً — للمراجعة عند الحاجة فقط", fontSize = 11.sp)
            logs.reversed().take(100).forEach { Text(it, fontSize = 12.sp) }
        }
    }
}

@Composable
fun SideView(title: String, sd: SideInfo, vpnSide: Boolean) {
    Text(title, fontWeight = FontWeight.Bold)
    Text(sd.label + if (sd.iface.isNotEmpty()) " (${sd.iface})" else "")
    if (sd.dnsMs >= 0) {
        Text("أسرع DNS: ${sd.bestDns} — ${sd.dnsMs} ms (${sd.dnsProto}، فقد ${sd.dnsLoss}%)")
    } else {
        Text("DNS: لا استجابة من أي خادم (المنفذ 53 قد يكون محجوباً من مزودك)")
    }
    if (sd.sysDnsMs >= 0) Text("DNS الحالي للنظام: ${sd.sysDnsMs} ms", fontSize = 13.sp)
    if (sd.latencyMs >= 0) Text("التأخر: ${sd.latencyMs} ms")
    if (sd.pathMtu > 0) {
        if (vpnSide) {
            Text("أقصى حزمة تمر: ${sd.pathMtu}" + if (sd.ifaceMtu > 0) " • MTU الواجهة: ${sd.ifaceMtu}" else "")
        } else {
            Text("أقصى حزمة للمسار (ICMP): ${sd.pathMtu} • مقترح مبدئي: ${sd.suggestedMtu}")
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
    var webServer by remember { mutableStateOf<VpnBook.Server?>(null) }
    var webProton by remember { mutableStateOf(false) }

    // شاشة المولد الداخلي تحتل التبويب كاملاً (إنجاز: توليد وتنزيل دون مغادرة التطبيق)
    if (webServer != null) {
        val ws = webServer!!
        VpnBookWebView(
            app = app,
            hint = "اختر في الصفحة: ${Country.label(ws.country)} ${ws.label} — المنفذ 443",
            onClose = { webServer = null }
        )
        return
    }
    if (webProton) {
        VpnBookWebView(
            app = app,
            title = "تحميل Proton داخل التطبيق",
            url = "https://account.protonvpn.com/downloads",
            namePrefix = "proton",
            hint = "سجّل الدخول بحسابك المجاني ← Downloads ← اختر WireGuard لأخف سيرفر — الملف يُستورد تلقائياً لقائمتك",
            onClose = { webProton = false }
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = a.importFiles, modifier = Modifier.weight(1f)) { Text("استيراد", maxLines = 1) }
            OutlinedButton(onClick = { showPaste = true }, modifier = Modifier.weight(1f)) { Text("لصق", maxLines = 1) }
            OutlinedButton(onClick = a.testAll, modifier = Modifier.weight(1f)) { Text("فحص الكل", maxLines = 1) }
        }
        val warpStatus by app.engine.warpStatus.collectAsState()
        OutlinedButton(
            onClick = { app.engine.generateWarp() },
            enabled = warpStatus != "جارٍ التسجيل في WARP…",
            modifier = Modifier.fillMaxWidth()
        ) { Text("توليد كونفيج Cloudflare WARP مجاني") }
        if (warpStatus.isNotEmpty()) {
            Text(
                warpStatus, fontSize = 12.sp,
                color = if (warpStatus.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        var showProton by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showProton = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("🇨🇭 خوادم Proton المجانية (خروج أجنبي)") }
        if (showProton) ProtonDialog(
            app,
            onClose = { showProton = false },
            onOpenInApp = { showProton = false; webProton = true }
        )
        var showBook by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showBook = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("📖 VPNBook مجاني — أمريكا/ألمانيا/فرنسا (7 أيام)") }
        if (showBook) VpnBookDialog(
            onClose = { showBook = false },
            onImport = { a.importFiles() },
            onOpenInApp = { srv -> showBook = false; webServer = srv }
        )
        var showAI by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = { showAI = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("🧠 مستشار الذكاء: اختر لي أفضل كونفيج وDNS وMTU وسيرفر") }
        if (showAI) AIAdvisorDialog(app, onClose = { showAI = false })
        var repairMsg by remember { mutableStateOf("") }
        OutlinedButton(
            onClick = {
                val (ok, fail) = app.engine.repairAllConfigs()
                repairMsg = "عُولج $ok كونفيج • فشل $fail — لا حاجة لحذف DNS يدوياً بعد الآن"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("🔧 إصلاح الكونفيجات المستوردة تلقائياً") }
        if (repairMsg.isNotEmpty()) Text(repairMsg, fontSize = 12.sp)
        Text(
            "يدعم ملفات .conf وملف .zip يحوي عدة كونفيجات. تُفحص الكونفيجات تلقائياً عند تشغيل الـ VPN فقط.",
            fontSize = 12.sp
        )
        if (list.isEmpty()) Text("لا توجد كونفيجات بعد.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // السطر 1: الاسم + العلم (جدول منظم)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!s.autoConfig) {
                                RadioButton(
                                    selected = s.selConfigId == c.id,
                                    onClick = { app.store.updateSettings { it.copy(selConfigId = c.id) } }
                                )
                            }
                            MarkerSlot(st.connected && st.activeConfigId == c.id)
                            Text(
                                c.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            if (c.lastCountry.isNotEmpty()) {
                                Text(Country.label(c.lastCountry), fontSize = 13.sp, maxLines = 1)
                            }
                            Switch(
                                checked = c.enabled,
                                onCheckedChange = { v ->
                                    app.store.updateConfigs { l -> l.map { if (it.id == c.id) it.copy(enabled = v) else it } }
                                }
                            )
                        }
                        // السطر 2: الخادم + IP الخروج (إن عُرف)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val ep = Probes.parseEndpoint(c.text)
                            Text(
                                ep?.let { "🖥 ${it.first}:${it.second}" } ?: "—",
                                fontSize = 12.sp, modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // سطر الانتهاء (VPNBook): تحذير أحمر عند القرب
                        VpnBook.expiryText(c.expiresAt)?.let { ex ->
                            val urgent = VpnBook.expiringSoon(c.expiresAt) || VpnBook.expired(c.expiresAt)
                            Text(
                                ex, fontSize = 12.sp,
                                color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // السطر 3: نتيجة الفحص فقط (بلا سلسلة كتابات)
                        results[c.id]?.let {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isTargeted = !s.autoConfig && s.selConfigId == c.id
                            val isLiveConnected = st.connected && st.activeConfigId == c.id
                            TextButton(onClick = {
                                if (isTargeted) {
                                    // إيقاف هذا الكونفيج فقط — لا يُغيَّر إعداد الاختيار التلقائي
                                    // ولا يبدأ أي فحص شامل؛ العودة للتلقائي فعل منفصل ومقصود.
                                    if (st.running) app.engine.stop()
                                } else {
                                    a.activateConfig(c.id)
                                }
                            }) {
                                Text(
                                    when {
                                        isLiveConnected -> "✔ متصل فعلياً"
                                        isTargeted && st.busy -> "⏳ جارٍ الاتصال…"
                                        isTargeted -> "⏸ إيقاف (لم يتصل بعد)"
                                        else -> "▶ تفعيل"
                                    },
                                    color = when {
                                        isLiveConnected -> Color(0xFF2E7D32)
                                        isTargeted && st.busy -> Color(0xFFF9A825)
                                        isTargeted -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            TextButton(onClick = { a.testOne(c.id) }) { Text("فحص") }
                            TextButton(onClick = { app.store.updateConfigs { l -> l.filter { it.id != c.id } } }) {
                                Text("حذف", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        if (!s.autoConfig) {
            OutlinedButton(
                onClick = {
                    app.store.updateSettings { it.copy(autoConfig = true) }
                    if (st.running) app.engine.reselect("العودة للاختيار التلقائي")
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("العودة للاختيار التلقائي") }
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

@Composable
fun ProtonDialog(app: WgApp, onClose: () -> Unit, onOpenInApp: () -> Unit = {}) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(ProtonServers.cache) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    // جلب أول مرة تلقائياً
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (list.isEmpty()) {
            loading = true
            list = ProtonServers.refresh()
            err = ProtonServers.lastError
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Proton Free — خروج أجنبي") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "حساب Proton المجاني يعطي خروجاً أجنبياً حقيقياً (هولندا/أمريكا/اليابان) بلا دفع. التوليد التلقائي الكامل يحتاج تسجيل دخول، لذلك نعرض أخف الخوادم المجانية لتختارها في موقع Proton ثم تستورد ملف .conf هنا.",
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            loading = true
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                list = ProtonServers.refresh()
                                err = ProtonServers.lastError
                                loading = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (loading) "جارٍ التحديث…" else "تحديث القائمة") }
                    OutlinedButton(
                        onClick = {
                            try {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://account.protonvpn.com/downloads"))
                                )
                            } catch (e: Exception) { }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("فتح خارجي", maxLines = 1) }
                }
                Button(
                    onClick = { onOpenInApp() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("فتح تحميل Proton داخل التطبيق") }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (list.isEmpty() && !loading) Text("لا توجد بيانات بعد — اضغط تحديث القائمة", fontSize = 12.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list.take(30), key = { it.domain + it.entryIp }) { s ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp)) {
                                Text("${Country.label(s.country)} • ${s.name}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${s.entryIp} • حمل ${s.load}%", fontSize = 12.sp)
                                Text(s.domain, fontSize = 11.sp)
                            }
                        }
                    }
                }
                Text("بعد التحميل من الموقع: عُد هنا واضغط استيراد واختر ملف .conf — سيظهر العلم تلقائياً.", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
        dismissButton = null
    )
}

@Composable
fun VpnBookDialog(onClose: () -> Unit, onImport: () -> Unit, onOpenInApp: (VpnBook.Server) -> Unit) {
    val ctx = LocalContext.current
    var pings by remember { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    var pinging by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<VpnBook.Server?>(null) }

    fun refresh() {
        if (pinging) return
        pinging = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val res = VpnBook.servers.map { s ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    s.host to Probes.pingOnce(s.host)
                }
            }.map { it.await() }.toMap()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                pings = res
                pinging = false
                // اختيار تلقائي لأسرع مستجيب عند أول قياس
                if (selected == null) {
                    res.entries.filter { it.value != null }.minByOrNull { it.value!! }?.let { best ->
                        selected = VpnBook.servers.firstOrNull { it.host == best.key }
                    }
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    val sorted = VpnBook.servers.sortedWith(
        compareBy<VpnBook.Server> { pings[it.host] ?: Int.MAX_VALUE }.thenBy { it.country }
    )
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("VPNBook مجاني — خروج أجنبي حقيقي") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "اختر سيرفراً (يُقاس تلقائياً ويُختار الأسرع)، ثم افتح المولد داخل التطبيق " +
                        "واختر نفس السيرفر والمنفذ 443 — الملف المولّد يُستورد مباشرة لقائمتك.",
                    fontSize = 12.sp
                )
                if (selected != null) {
                    Text(
                        "المختار: ${Country.label(selected!!.country)} ${selected!!.label} — افتح المولد واختره هناك",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { onOpenInApp(selected ?: sorted.firstOrNull() ?: VpnBook.servers.first()) },
                        modifier = Modifier.weight(1f)
                    ) { Text("فتح المولد داخل التطبيق", maxLines = 1) }
                    OutlinedButton(onClick = { onImport() }, modifier = Modifier.weight(1f)) { Text("استيراد .conf", maxLines = 1) }
                }
                TextButton(
                    onClick = {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VpnBook.GEN_URL)))
                        } catch (e: Exception) {
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("أو فتح في المتصفح الخارجي", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { refresh() },
                    enabled = !pinging,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (pinging) "جارٍ قياس السرعة…" else "إعادة قياس سرعة السيرفرات") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sorted, key = { it.host }) { s ->
                        val rtt = pings[s.host]
                        val isSel = selected?.host == s.host
                        Card(
                            Modifier.fillMaxWidth().clickable { selected = s },
                            colors = if (isSel) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else CardDefaults.cardColors()
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(
                                    (if (isSel) "✔ " else "") + "${Country.label(s.country)} • ${s.label}",
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                                )
                                Text(
                                    s.host + " • " + if (pings.isEmpty() || (pinging && rtt == null)) "…"
                                    else rtt?.let { "$it ms" } ?: "لا يرد ping",
                                    fontSize = 12.sp
                                )
                                Text("المنفذ المقترح: 443 (ثم 123 ثم 80)", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
        dismissButton = null
    )
}

@Composable
fun VpnBookWebView(app: WgApp, hint: String, onClose: () -> Unit) {
    VpnBookWebView(
        app = app, title = "مولد VPNBook داخل التطبيق", url = VpnBook.GEN_URL,
        namePrefix = "vpnbook", hint = hint, onClose = onClose
    )
}

@Composable
fun VpnBookWebView(app: WgApp, title: String, url: String, namePrefix: String, hint: String, onClose: () -> Unit) {
    var statusMsg by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val ctx = LocalContext.current

    // يستقبل نص الكونفيج من JS (blob أو التقاط ظاهر) ويدخله للقائمة — يعمل على أي خيط
    fun importFromJs(raw: String, fnameHint: String) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var msg: String
            try {
                // نص evaluateJavascript قد يصل JSON-mquoted — فكّه إن لزم
                var text = raw
                if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
                    text = text.substring(1, text.length - 1)
                        .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                        .replace("\\\"", "\"").replace("\\\\", "\\")
                }
                // فك ترميز \uXXXX إن وجد (مفاتيح WireGuard عادة ASCII لكن احتياط)
                if (text.contains("\\u")) {
                    try {
                        val sb = StringBuilder()
                        var i = 0
                        while (i < text.length) {
                            if (text[i] == '\\' && i + 5 < text.length && text[i + 1] == 'u') {
                                val hex = text.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16)
                                if (code != null) {
                                    sb.append(code.toChar())
                                    i += 6
                                    continue
                                }
                            }
                            sb.append(text[i])
                            i++
                        }
                        text = sb.toString()
                    } catch (_e: Exception) { }
                }
                if (!text.contains("[Interface]") || !text.contains("[Peer]")) {
                    msg = "لم أجد كونفيج في الصفحة بعد — انتظر ظهوره ثم اضغط التقاط مجدداً"
                } else {
                    val cleanName = fnameHint.substringAfterLast('/').substringAfterLast('\\')
                        .substringBeforeLast('.').ifBlank { "$namePrefix-${System.currentTimeMillis()}" }
                    val err = app.engine.addConfig(cleanName, text)
                    msg = if (err == null) "✔ تم إدخال $cleanName في قائمة الكونفيجات (ارجع وافحصه)"
                    else "✗ $err"
                }
            } catch (e: Exception) {
                msg = "فشل الاستيراد: ${e.message?.take(100)}"
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                downloading = false
                statusMsg = msg
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onClose) { Text("رجوع ✕") }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(hint, fontSize = 12.sp, maxLines = 2)
            }
        }
        // زر التقاط احتياطي — لا يعطّل التنزيل التلقائي، فقط بديل عند فشل blob
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val wv = webRef
                    if (wv == null) {
                        statusMsg = "المتصفح لم يجهز بعد"
                        return@OutlinedButton
                    }
                    downloading = true
                    statusMsg = ""
                    wv.evaluateJavascript(
                        "(function(){try{" +
                            "var t=(document.body?document.body.innerText:'');" +
                            "var i=t.indexOf('[Interface]');" +
                            "if(i<0){AndroidBlob.onError('NO_CONFIG');return;}" +
                            "AndroidBlob.onBlob(t.substring(i,i+9000));" +
                            "}catch(e){AndroidBlob.onError(''+e)}})();",
                        null
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("📋 التقاط الكونفيج الظاهر", maxLines = 1) }
            OutlinedButton(
                onClick = {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) { }
                },
                modifier = Modifier.weight(1f)
            ) { Text("فتح خارجي", maxLines = 1) }
            OutlinedButton(
                onClick = {
                    val wv = webRef
                    if (wv == null) statusMsg = "المتصفح لم يجهز بعد"
                    else {
                        downloading = true
                        statusMsg = ""
                        wv.reload()
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("↻ تحميل", maxLines = 1) }
        }
        if (statusMsg.isNotEmpty() || downloading) {
            Text(
                if (downloading) "⏳ جارٍ تنزيل الملف وإدخاله للقائمة…" else statusMsg,
                fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // بعض المواقع (ومنها بروتون) تعطي صفحة بيضاء عند كشف WebView
                    // عبر علامة "; wv" — نحذفها ليظهر المتصفح ككروم عادي.
                    try {
                        settings.userAgentString =
                            settings.userAgentString.replace("; wv", "")
                    } catch (_: Exception) { }
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    val wv = this
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            wv.post {
                                if (downloading) downloading = false
                            }
                        }
                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                wv.post {
                                    downloading = false
                                    statusMsg = "تعذّر تحميل الصفحة (${error?.description ?: "خطأ شبكة"}) — تحقق من الإنترنت ثم ↻ تحميل، أو افتح خارجياً"
                                }
                            }
                        }
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onBlob(text: String) {
                            importFromJs(text, "$namePrefix-${System.currentTimeMillis()}.conf")
                        }
                        @JavascriptInterface
                        fun onError(err: String) {
                            wv.post {
                                downloading = false
                                statusMsg = if (err == "NO_CONFIG")
                                    "لا كونفيج ظاهر بعد — اضغط Generate في الصفحة أولاً ثم التقاط"
                                else "تعذّر قراءة الملف من الصفحة ($err) — جرّب التقاط مجدداً"
                            }
                        }
                    }, "AndroidBlob")
                    webRef = this
                    setDownloadListener { url, userAgent, contentDisposition, _, _ ->
                        downloading = true
                        statusMsg = ""
                        if (url.startsWith("blob:", true)) {
                            // ملفات VPNBook تُولَّد كـ blob داخل الصفحة — HttpURLConnection لا يفهمها،
                            // فنقرأها عبر fetch داخل الـ WebView نفسه ثم نستوردها.
                            val safe = url.replace("'", "")
                            wv.evaluateJavascript(
                                "(function(){fetch('$safe').then(function(r){return r.text();})" +
                                    ".then(function(t){AndroidBlob.onBlob(t);})" +
                                    ".catch(function(e){AndroidBlob.onError(''+e);});})();",
                                null
                            )
                            return@setDownloadListener
                        }
                        if (url.startsWith("data:", true)) {
                            // data:text/plain;base64,... — فك مباشر بلا شبكة
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                var msg: String
                                try {
                                    val comma = url.indexOf(',')
                                    val meta = url.substring(5, if (comma >= 0) comma else url.length)
                                    var data = if (comma >= 0) url.substring(comma + 1) else ""
                                    val text = if (meta.contains(";base64", true)) {
                                        String(
                                            android.util.Base64.decode(data, android.util.Base64.DEFAULT),
                                            Charsets.UTF_8
                                        )
                                    } else {
                                        java.net.URLDecoder.decode(data, "UTF-8")
                                    }
                                    if (text.contains("[Interface]") && text.contains("[Peer]")) {
                                        val err = app.engine.addConfig(
                                            "$namePrefix-${System.currentTimeMillis()}", text
                                        )
                                        msg = if (err == null) "✔ تم إدخال الكونفيج في القائمة (ارجع وافحصه)"
                                        else "✗ $err"
                                    } else {
                                        msg = "الملف المحمّل ليس كونفيج WireGuard صالحاً"
                                    }
                                } catch (e: Exception) {
                                    msg = "فشل التنزيل: ${e.message?.take(100)}"
                                }
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    downloading = false
                                    statusMsg = msg
                                }
                            }
                            return@setDownloadListener
                        }
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            var msg: String
                            try {
                                val cookie = CookieManager.getInstance().getCookie(url)
                                val conn = URL(url).openConnection() as HttpURLConnection
                                if (cookie != null) conn.setRequestProperty("Cookie", cookie)
                                conn.setRequestProperty("User-Agent", userAgent)
                                conn.connectTimeout = 15000
                                conn.readTimeout = 15000
                                val code = conn.responseCode
                                val text = conn.inputStream.bufferedReader().use { it.readText() }
                                conn.disconnect()
                                if (code in 200..299 && text.contains("[Interface]") && text.contains("[Peer]")) {
                                    var fname = Regex("filename=\"?([^\";]+)\"?").find(contentDisposition ?: "")
                                        ?.groupValues?.getOrNull(1) ?: "$namePrefix-${System.currentTimeMillis()}.conf"
                                    fname = fname.substringAfterLast('/').substringAfterLast('\\')
                                    val err = app.engine.addConfig(fname.substringBeforeLast('.'), text)
                                    msg = if (err == null) "✔ تم إدخال $fname في قائمة الكونفيجات (ارجع وافحصه)"
                                    else "✗ $err"
                                } else {
                                    msg = "الملف المحمّل ليس كونفيج WireGuard صالحاً (HTTP $code)"
                                }
                            } catch (e: Exception) {
                                msg = "فشل التنزيل: ${e.message?.take(100)}"
                            }
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                downloading = false
                                statusMsg = msg
                            }
                        }
                    }
                    loadUrl(url)
                }
            },
            onRelease = { webRef = null; it.destroy() },
            modifier = Modifier.fillMaxSize().weight(1f)
        )
    }
}

private fun statText(name: String, st: Stat?): String =
    if (st == null) "$name: —" else "$name: ${st.median} ms ±${st.jitter} فقد ${st.loss}%"

private fun dnsMeasureLine(m: DnsMeasure): String {
    val parts = ArrayList<String>()
    parts += if (m.udp == null) "UDP: لا استجابة" else statText("UDP", m.udp)
    m.dot?.let { parts += statText("DoT", it) }
    m.doh?.let { parts += statText("DoH", it) }
    return parts.joinToString(" • ")
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
                            if (d.filtered) {
                                Text(
                                    "مُرشِّح (يحجب مواقع) — لا يُختار تلقائياً إلا بإذن من الإعدادات",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.error
                                )
                            }
                            res[d.id]?.let { m -> Text(dnsMeasureLine(m), fontSize = 12.sp) }
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
        SwitchRow("اختيار أفضل DNS تلقائياً", s.autoDns) { v -> upd { it.copy(autoDns = v) } }
        SwitchRow("السماح باختيار DNS المُرشِّحة (تحجب مواقع)", s.allowFilteredDns) { v ->
            upd { it.copy(allowFilteredDns = v) }
        }
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

        Text("إعادة الاختيار (الوضع التلقائي فقط — اليدوي يشغّل ويفصل بيدك دائماً)", fontWeight = FontWeight.Bold)
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
            "يخفض MTU واجهة أي VPN خارجي إلى أكبر حزمة تمر فعلاً. ولا يغيّر DNS النظام (Private DNS) " +
                "إلا لخادم DoT مقاس بفقد 0% وأفضل بوضوح من الحالي، ثم يتحقق من الحل بعد التطبيق ويتراجع تلقائياً إن ساء.",
            fontSize = 12.sp
        )
        OutlinedButton(onClick = a.restoreSystem, modifier = Modifier.fillMaxWidth()) {
            Text("استعادة إعداد DNS الخاص الأصلي للنظام")
        }
        OutlinedButton(onClick = a.batteryExempt, modifier = Modifier.fillMaxWidth()) {
            Text("استثناء التطبيق من توفير الطاقة")
        }
        SwitchRow("حذف الكونفيجات المنتهية تلقائياً عند الفتح", s.autoPurgeExpired) { v ->
            upd { it.copy(autoPurgeExpired = v) }
        }
        Text(
            "كونفيجات VPNBook تنتهي بعد 7 أيام — تُحذف وحدها عند فتح التطبيق (ولّد بديلاً من زر VPNBook).",
            fontSize = 12.sp
        )
        Text(
            "MTU النفق = أقصى حزمة تصل للخادم − 60 بايت (IPv4) أو − 80 بايت (IPv6).",
            fontSize = 12.sp
        )

        HorizontalDivider()
        Text("قاطع الطوارئ (Kill Switch)", fontWeight = FontWeight.Bold)
        SwitchRow("منع أي إنترنت خارج النفق (يتطلب روت)", s.killSwitch) { v -> upd { it.copy(killSwitch = v) } }
        Text(
            "يحجب كل حركة الشبكة إلا عبر واجهة النفق (tun+) طالما التطبيق يعمل. إن سقط النفق يبقى الإنترنت مقطوعاً " +
                "بدل تسريب IP الحقيقي، حتى يعود النفق أو تضغط إيقاف. لا يعمل بلا روت.",
            fontSize = 12.sp
        )
        OutlinedButton(onClick = a.openVpnSettings, modifier = Modifier.fillMaxWidth()) {
            Text("فتح إعدادات Always-on VPN بالنظام")
        }
        Text(
            "إضافياً، تفعيل \"Always-on VPN\" و\"Block connections without VPN\" لهذا التطبيق من إعدادات أندرويد " +
                "يمنع أي تسريب حتى بلا روت، ويبقى فعّالاً حتى لو أُغلق التطبيق تماماً.",
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
        var verTaps by remember { mutableIntStateOf(0) }
        var showPrompt by remember { mutableStateOf(false) }
        var toolUnlocked by remember { mutableStateOf(false) }
        Text(
            "حول التطبيق", fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
                verTaps++
                if (verTaps >= 7) {
                    showPrompt = true
                    verTaps = 0
                }
            }
        )
        Text("الإصدار: $ver")
        Text("Star Syria")
        TextButton(onClick = {
            try {
                ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:starsyria2500@gmail.com")))
            } catch (e: Exception) {
                // لا يوجد تطبيق بريد
            }
        }) { Text("📧 starsyria2500@gmail.com") }
        TextButton(onClick = {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/963938466549")))
            } catch (e: Exception) {
                // لا يوجد واتساب مثبَّت
            }
        }) { Text("💬 واتساب: 963938466549+") }
        // TODO: أضف رابط قناة تلغرام هنا فور تزويدي به، بنفس نمط الزرّين أعلاه:
        // TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/USERNAME"))) }) { Text("📣 قناة تلغرام") }

        val lic = License.status(app.store)
        Text(
            when {
                lic.isOwner -> "الترخيص: دخول مالك"
                lic.expiryAt > 0 ->
                    "الترخيص: ساري حتى " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(lic.expiryAt))
                else -> "الترخيص: غير مفعّل"
            },
            fontSize = 12.sp
        )
        if (showPrompt) {
            if (toolUnlocked) {
                OwnerToolPanel(app)
                OutlinedButton(onClick = {
                    toolUnlocked = false
                    showPrompt = false
                }, modifier = Modifier.fillMaxWidth()) { Text("خروج من الأداة") }
            } else {
                var phrase by remember { mutableStateOf("") }
                var lm by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = phrase, onValueChange = { phrase = it },
                    label = { Text("عبارة دخول المالك") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    lm = when (val r = License.verifyOwnerAttempt(app.store, phrase)) {
                        is License.Attempt.Ok -> { toolUnlocked = true; "" }
                        is License.Attempt.Wrong -> "عبارة غير صحيحة"
                        is License.Attempt.Locked -> "محاولات كثيرة — انتظر ${r.secondsLeft} ثانية"
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("دخول") }
                if (lm.isNotEmpty()) Text(lm, fontSize = 12.sp)
            }
        }
    }
}

/** أداة توليد أكواد التفعيل داخل التطبيق — تظهر فقط بعد دخول المالك، بلا أي أوامر يدوية خارجية. */
@Composable
fun OwnerToolPanel(app: WgApp) {
    var name by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("365") }
    var permanent by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }
    var err by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(License.historyList(app.store)) }
    val ctx = LocalContext.current
    val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)

    fun copy(text: String) {
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("code", text))
    }

    HorizontalDivider()
    Text("أداة توليد أكواد التفعيل", fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = name, onValueChange = { name = it },
        label = { Text("اسم المستخدم (للسجل فقط)") }, singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = device, onValueChange = { device = it; err = "" },
        label = { Text("معرّف جهاز المستخدم") }, singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = days, onValueChange = { days = it.filter { c -> c.isDigit() } },
            label = { Text("عدد الأيام") }, singleLine = true, enabled = !permanent,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text("دائم", fontSize = 13.sp)
        Switch(checked = permanent, onCheckedChange = { permanent = it })
    }
    Button(onClick = {
        val d = device.trim()
        if (d.isEmpty()) {
            err = "أدخل معرّف الجهاز أولاً"
            return@Button
        }
        val daysNum = if (permanent) 0 else (days.toIntOrNull() ?: 0)
        val (code, expiry) = License.generateCodeForOwner(d, daysNum)
        result = code to expiry
        License.historyAdd(
            app.store,
            License.IssuedCode(
                name.trim(), d.replace("-", "").uppercase(), code, expiry,
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            )
        )
        history = License.historyList(app.store)
        copy(code)
    }, modifier = Modifier.fillMaxWidth()) { Text("توليد الكود") }
    if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)

    result?.let { (code, expiry) ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("الكود (نُسخ تلقائياً)", fontSize = 12.sp)
                Text(code, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("صالح حتى: $expiry", fontSize = 12.sp)
                OutlinedButton(onClick = { copy(code) }, modifier = Modifier.fillMaxWidth()) { Text("نسخ مرة أخرى") }
            }
        }
    }

    if (history.isNotEmpty()) {
        Text("السجل (الأحدث أولاً)", fontWeight = FontWeight.Bold)
        history.asReversed().take(30).forEach { h ->
            Card(Modifier.fillMaxWidth().clickable { copy(h.code) }) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        (if (h.name.isNotEmpty()) "${h.name} — " else "") + h.deviceId,
                        fontWeight = FontWeight.Bold, fontSize = 13.sp
                    )
                    Text("${h.code}  •  حتى ${h.expiry}  •  صدر ${h.issuedAt}", fontSize = 12.sp)
                }
            }
        }
    }
}

// =====================================================================
// تبويب VPN 🌍 — سيرفرات OpenVPN مجانية من VPNGate تتحدث تلقائياً.
// الفحص بنفس معادلة الامتحان الموحدة (الزمن 50% + الفقد 30% + التذبذب 20%)،
// والاتصال يتم عبر تطبيق OpenVPN خارجي بعد إيقاف نفق WireGuard (نفق واحد فقط
// مسموح في أندرويد). DNS وMTU يُقترحان من فحص شبكتك، وكونفيج VPNGate يدفع
// DNS الخاص به عند الاتصال.
// =====================================================================

/** يجلب القائمة من VPNGate ويدمجها مع الكاش (يحتفظ بسيرفرات اختفت منذ أقل من 48 ساعة
 *  لتنويع الدول في الأيام التي تطغى فيها دولة واحدة). يعيد رسالة عرض. */
suspend fun refreshOvpnServers(app: WgApp): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
    val csv = VpnGate.fetchCsv()
        ?: return@withContext "تعذّر الجلب — تحقق من الإنترنت (الكاش القديم ما زال يعمل)"
    val parsed = VpnGate.parse(csv)
    if (parsed.isEmpty()) return@withContext "رد فارغ من VPNGate — حاول لاحقاً"
    app.store.saveVpnGateCsv(csv)
    val now = System.currentTimeMillis()
    val old = app.store.ovpn.value.associateBy { it.key }
    val freshKeys = parsed.map { it.key }.toSet()
    // القدامى الغائبون منذ أقل من 48 ساعة يبقون (موسومون ضمنياً بقدم lastSeen)
    val kept = old.values.filter { it.key !in freshKeys && now - it.lastSeen < 48L * 3600 * 1000 }
    app.store.updateOvpn {
        parsed.map { s ->
            val o = old[s.key]
            OvpnServer(
                s.key, s.host, s.ip, s.country, s.sessions, s.speedMbps, s.pingAdvertised,
                lat = o?.lat ?: -1, jitter = o?.jitter ?: 0, loss = o?.loss ?: 100,
                via = o?.via ?: "", testedAt = o?.testedAt ?: 0L, lastSeen = now
            )
        } + kept
    }
    app.store.setOvpnUpdatedAt(now)
    val nCountries = (parsed.map { it.country } + kept.map { it.country }).distinct().size
    "تم التحديث: ${parsed.size + kept.size} سيرفراً في $nCountries دولة"
}

/** يفحص أفضل المرشحين ويحفظ النتائج تدريجياً. يعيد المرتبين (الأفضل أولاً). */
suspend fun examOvpnBest(
    app: WgApp,
    prefer: String,
    onProgress: (String) -> Unit
): List<OvpnServer> {
    val csv = app.store.loadVpnGateCsv() ?: return emptyList()
    val cands = VpnGate.candidates(VpnGate.parse(csv), prefer)
    if (cands.isEmpty()) return emptyList()
    onProgress("فحص ${cands.size} مرشحاً…")
    val now = System.currentTimeMillis()
    VpnGate.examMany(cands, prefer) { key, r ->
        if (r != null) {
            app.store.updateOvpn { l ->
                l.map {
                    if (it.key == key) it.copy(lat = r.lat, jitter = r.jitter, loss = r.loss, via = r.via, testedAt = now)
                    else it
                }
            }
        }
    }
    onProgress("")
    return app.store.ovpn.value.filter { it.tested && it.loss < 100 }.sortedBy { it.score }
}

/**
 * يتصل بسيرفر OpenVPN داخل التطبيق: يوقف WireGuard أولاً (نفق واحد فقط)،
 * ثم يشغّل الكونفيج المفلتر حسب تفضيل البروتوكول عبر المحرك المدمج. يعيد رسالة عرض.
 */
fun startOvpnInternal(app: WgApp, server: OvpnServer, prefer: String): String {
    if (app.engine.status.value.running) app.engine.stop()
    if (app.ovpn.isUp()) app.ovpn.stop()
    val b64 = app.store.ovpnConfigB64(server.key)
        ?: return "هذا السيرفر غاب عن آخر تحديث — حدّث القائمة أو اختر سيرفراً آخر"
    val cfg = VpnGate.decodeConfig(b64) ?: return "كونفيج تالف — حدّث القائمة"
    val filtered = VpnGate.filterConfig(cfg, prefer)
    return app.ovpn.start(server, filtered)
}

@Composable
fun OvpnTab(app: WgApp, a: Actions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by app.store.ovpn.collectAsState()
    val updatedAt by app.store.ovpnUpdatedAt.collectAsState()
    val settings by app.store.settings.collectAsState()
    val ni by app.engine.netInfo.collectAsState()
    val wgSt by app.engine.status.collectAsState()
    val ovSt by app.ovpn.state.collectAsState()
    var busy by remember { mutableStateOf("") }
    var countryFilter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }

    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
    val prefer = settings.ovpnProto

    // تحميل أولي من الكاش + تحديث تلقائي إن قَدُم (أكثر من 6 ساعات)
    LaunchedEffect(Unit) {
        if (servers.isEmpty()) {
            app.store.loadVpnGateCsv()?.let { csv ->
                val list = VpnGate.parse(csv)
                if (list.isNotEmpty()) {
                    app.store.updateOvpn { _ ->
                        list.map { s ->
                            OvpnServer(s.key, s.host, s.ip, s.country, s.sessions, s.speedMbps, s.pingAdvertised)
                        }
                    }
                }
            }
        }
        if (System.currentTimeMillis() - app.store.ovpnUpdatedAt.value > VpnGate.STALE_MS) {
            busy = "تحديث تلقائي للقائمة…"
            val msg = refreshOvpnServers(app)
            busy = ""
            if (app.store.ovpn.value.isNotEmpty()) toast(msg)
        }
    }

    val ageText = if (updatedAt <= 0L) "لم تُحدَّث بعد" else {
        val age = System.currentTimeMillis() - updatedAt
        when {
            age < 60_000 -> "حُدّثت قبل لحظات"
            age < 3_600_000 -> "حُدّثت قبل ${age / 60_000} دقيقة"
            else -> "حُدّثت قبل ${age / 3_600_000} ساعة"
        }
    }
    val tested = servers.filter { it.tested && it.loss < 100 }.sortedBy { it.score }
    val best = tested.firstOrNull()
    val topList = tested.take(3)
    val topKeys = topList.map { it.key }.toSet()
    fun rankOf(key: String) = topList.indexOfFirst { it.key == key }
    val countries = servers.map { it.country }.distinct().sorted()
    val shown = servers.filter {
        (countryFilter == "ALL" || it.country == countryFilter) &&
            (query.isBlank() || it.host.contains(query.trim(), true) || it.ip.contains(query.trim()))
    }.sortedWith(compareBy({ val r = rankOf(it.key); if (r >= 0) r else 99 }, { it.score }))

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val wgUp = wgSt.running && wgSt.connected
        // ---- ترويسة مميزة للتبويب ----
        item {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B3F73))
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🌍", fontSize = 30.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "سيرفرات OpenVPN المجانية",
                        fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = Color.White
                    )
                    Text(
                        "تتحدث تلقائياً من VPNGate • بجانب WireGuard وليس بديلاً عنه",
                        fontSize = 12.sp, color = Color(0xFFD6E4FF)
                    )
                }
            }
        }
        }
        // ---- شريط الحالة: هل يوجد اتصال؟ وهل يمكن فصله؟ ----
        item {
        when {
            ovSt.active -> Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🟢 متصل عبر OpenVPN داخل التطبيق", fontWeight = FontWeight.Bold)
                    if (ovSt.serverLabel.isNotEmpty()) Text(ovSt.serverLabel, fontSize = 13.sp)
                    Text("النفق يعمل الآن — افصله من هنا بضغطة واحدة", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { app.engine.scanNetwork("فحص يدوي من تبويب VPN") },
                            modifier = Modifier.weight(1f)
                        ) { Text("فحص", maxLines = 1) }
                        Button(
                            onClick = { toast(app.ovpn.stop()) },
                            modifier = Modifier.weight(1f)
                        ) { Text("فصل ⏻", maxLines = 1) }
                    }
                }
            }
            ovSt.connecting -> Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🟡 ${ovSt.statusText.ifEmpty { "جارٍ الاتصال…" }}", fontWeight = FontWeight.Bold)
                    if (ovSt.serverLabel.isNotEmpty()) Text(ovSt.serverLabel, fontSize = 13.sp)
                    OutlinedButton(
                        onClick = { toast(app.ovpn.stop()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("إلغاء") }
                }
            }
            wgUp -> Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🔵 متصل عبر WireGuard (${wgSt.activeConfig ?: ""})", fontWeight = FontWeight.Bold)
                    Text("أوقف WireGuard أولاً قبل تشغيل OpenVPN (نفق واحد فقط مسموح)", fontSize = 12.sp)
                    OutlinedButton(
                        onClick = { app.engine.stop(); toast("أُوقف WireGuard — اختر سيرفر OpenVPN الآن") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("إيقاف WireGuard") }
                }
            }
            ni.vpn != null -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("🟡 يوجد نفق — يُفحص الآن…", fontSize = 13.sp)
                }
            }
            else -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("⚪ لا يوجد اتصال VPN حالياً", fontSize = 13.sp)
                }
            }
        }
        }

        // ---- القائمة والتحديث ----
        item {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        busy = "جلب القائمة…"
                        toast(refreshOvpnServers(app))
                        busy = ""
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("تحديث السيرفرات", maxLines = 1) }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = "فحص المرشحين…"
                        val res = examOvpnBest(app, prefer) { p -> busy = p }
                        busy = ""
                        toast(if (res.isEmpty()) "لا استجابة من المرشحين — جرّب TCP" else "الأفضل: ${res.first().host} (${res.first().lat}ms)")
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("فحص السرعة", maxLines = 1) }
        }
        }
        item {
        Text(
            "$ageText • ${servers.size} سيرفراً في ${countries.size} دولة • المصدر: VPNGate مجاني",
            fontSize = 11.sp
        )
        }

        // ---- تفضيل البروتوكول: شرائح واضحة (يدوي أو تلقائي) ----
        item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("البروتوكول", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val opts = listOf(
                        "auto" to "⚡ تلقائي",
                        "udp" to "🚀 UDP أسرع",
                        "tcp" to "🧱 TCP للحجب"
                    )
                    opts.forEach { (v, t) ->
                        FilterChip(
                            selected = prefer == v,
                            onClick = { app.store.updateSettings { it.copy(ovpnProto = v) } },
                            label = { Text(t, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    when (prefer) {
                        "udp" -> "UDP: سرعة أعلى وزمن أقل — الأفضل للتصفح والمكالمات."
                        "tcp" -> "TCP: يعبر معظم الجدران والشبكات المقيدة (443/80) — أبطأ قليلاً."
                        else -> "تلقائي: يفحص TCP أولاً ثم Ping، ويختار أعلى درجة بالمعادلة الموحدة."
                    },
                    fontSize = 11.sp
                )
            }
        }
        }

        // ---- ثبات الاتصال: البطارية + إحياء تلقائي ----
        item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🛡 ثبات الاتصال", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "أندرويد يقتل التطبيقات في الخلفية لتوفير البطارية/الذاكرة. خطوتان تمنعان الفصل بعد دقائق:",
                    fontSize = 11.sp
                )
                OutlinedButton(
                    onClick = { a.batteryExempt() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("استثناء التطبيق من توفير البطارية") }
                SwitchRow("إعادة الوصل تلقائياً إن أسقطه النظام", settings.ovpnAutoReconnect) { v ->
                    app.store.updateSettings { it.copy(ovpnAutoReconnect = v) }
                }
                Text(
                    "نصيحة للجهاز: اقفل التطبيق في شاشة التطبيقات الأخيرة (القفل 🔒) وفعّل التشغيل التلقائي من إعدادات البطارية.",
                    fontSize = 11.sp
                )
            }
        }
        }

        // ---- الاختيار التلقائي: الأفضل بضغطة ----
        if (best != null) {
            item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🥇", fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${Country.flag(best.country)} ${best.host}",
                            fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        Text(
                            "${Country.name(best.country)} • ${best.ip} • ${best.lat}ms • فقد ${best.loss}% • ${VpnGate.speedText(best.speedMbps)}",
                            fontSize = 12.sp
                        )
                    }
                    Button(onClick = { a.startOvpn(best) }) { Text("اتصال") }
                }
            }
            }
        }

        // ---- فلترة يدوية: شرائح الدول + بحث ----
        item { Text("الدولة", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        item {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = countryFilter == "ALL",
                onClick = { countryFilter = "ALL" },
                label = { Text("🌐 الكل (${servers.size})", maxLines = 1) }
            )
            countries.forEach { cc ->
                val n = servers.count { it.country == cc }
                FilterChip(
                    selected = countryFilter == cc,
                    onClick = { countryFilter = cc },
                    label = { Text("${Country.flag(cc)} ${Country.name(cc)} ($n)", maxLines = 1) }
                )
            }
        }
        }
        item {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("بحث باسم السيرفر أو IP") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        }

        // ---- سجل OpenVPN الحي: تشخيص الاتصال داخل التطبيق ----
        item {
        val ovpnLog by app.ovpn.logLines.collectAsState()
        if (ovSt.connecting || ovSt.active || ovpnLog.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("سجل الاتصال المباشر", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    val tail = ovpnLog.takeLast(6)
                    if (tail.isEmpty()) Text("بانتظار بدء الاتصال…", fontSize = 12.sp)
                    else tail.forEach { line -> Text(line, fontSize = 11.sp) }
                }
            }
        }
        }

        // ---- DNS وMTU المقترحان من فحص شبكتك ----
        item {
        ni.direct?.let { d ->
            if (d.bestDns.isNotEmpty() || d.suggestedMtu > 0) {
                Text(
                    "مقترح شبكتك: DNS ${d.bestDns.ifEmpty { "—" }}${if (d.dnsMs >= 0) " (${d.dnsMs}ms)" else ""} • MTU ${if (d.suggestedMtu > 0) d.suggestedMtu else "—"} — كونفيج VPNGate يدفع DNS الخاص به عند الاتصال",
                    fontSize = 11.sp
                )
            }
        }
        }

        if (busy.isNotEmpty()) item { Text("⏳ $busy", fontSize = 12.sp, fontWeight = FontWeight.Bold) }

        // ---- القائمة اليدوية: علم + حالة + فحص + اتصال ----
        if (shown.isEmpty()) {
            item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("لا توجد سيرفرات بعد — اضغط «تحديث السيرفرات» لجلب القائمة المجانية", fontSize = 13.sp)
                }
            }
            }
        }
        items(shown, key = { it.key }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${Country.flag(s.country)}", fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                val rank = rankOf(s.key)
                                Text(
                                    (if (rank >= 0) "${Exam.rankMedal(rank)} " else "") + s.host,
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                                )
                                Text(
                                    "${Country.name(s.country)} • ${s.ip} • ${s.sessions} جلسة • ${VpnGate.speedText(s.speedMbps)}",
                                    fontSize = 12.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (!s.tested) "⚪" else if (s.loss >= 100) "🔴" else "🟢",
                                    fontSize = 18.sp
                                )
                                Text(
                                    if (!s.tested) "لم يُفحص" else if (s.loss >= 100) "لا يعمل" else "يعمل",
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Text(
                            if (s.tested) VpnGate.examText(VpnGate.ExamResult(s.lat, s.jitter, s.loss, s.via))
                            else if (s.pingAdvertised >= 0) "معلن: ${s.pingAdvertised}ms — لم يُفحص من جهازك"
                            else "لم يُفحص بعد",
                            fontSize = 12.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        busy = "فحص ${s.host}…"
                                        val csv = app.store.loadVpnGateCsv()
                                        val full = csv?.let { VpnGate.parse(it).firstOrNull { p -> p.key == s.key } }
                                        val r = full?.let { VpnGate.exam(it, prefer) }
                                        if (r != null) {
                                            val now = System.currentTimeMillis()
                                            app.store.updateOvpn { l ->
                                                l.map {
                                                    if (it.key == s.key) it.copy(
                                                        lat = r.lat, jitter = r.jitter,
                                                        loss = r.loss, via = r.via, testedAt = now
                                                    ) else it
                                                }
                                            }
                                            toast("${s.host}: ${r.lat}ms • فقد ${r.loss}%")
                                        } else {
                                            toast("${s.host}: لا استجابة — جرّب سيرفراً آخر")
                                        }
                                        busy = ""
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("فحص", maxLines = 1) }
                            Button(
                                onClick = { a.startOvpn(s) },
                                modifier = Modifier.weight(1f)
                            ) { Text("اتصال", maxLines = 1) }
                        }
                    }
                }
        }
    }
}
